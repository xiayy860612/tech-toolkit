package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.policy.SelfProtectionRule;
import com.s2u2m.uam.domain.port.AccountRepository;
import com.s2u2m.uam.domain.port.SessionStore;
import com.s2u2m.uam.domain.port.TransactionRunner;
import com.s2u2m.uam.domain.port.UserRepository;
import com.s2u2m.uam.domain.port.UserStatusLock;
import com.s2u2m.uam.domain.port.WriteOperationLog;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 停用用户（FR-016、FR-024、FR-025，research R15）。
 *
 * <p>事务内先取状态变更锁，再校验自我保护并置为 {@code DISABLED}；<b>提交之后</b>才终止该用户全部账号的会话（所以终止时任何新登录的复查都已能看到
 * 「已停用」）。幂等：对已停用的用户同样成功，并再次确保其会话已终止（补偿上一次在终止前失败的情况）。账号自身状态不变。成功、被拒绝、失败都写 {@code uam.admin.write}
 * 日志。
 */
@Component
@Profile("!cli")
public class DisableUserUseCase {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final SessionStore sessions;
  private final UserStatusLock statusLock;
  private final TransactionRunner transactions;
  private final GetUserUseCase getUser;
  private final WriteOperationLog writeLog;

  public DisableUserUseCase(
      UserRepository users,
      AccountRepository accounts,
      SessionStore sessions,
      UserStatusLock statusLock,
      TransactionRunner transactions,
      GetUserUseCase getUser,
      WriteOperationLog writeLog) {
    this.users = users;
    this.accounts = accounts;
    this.sessions = sessions;
    this.statusLock = statusLock;
    this.transactions = transactions;
    this.getUser = getUser;
    this.writeLog = writeLog;
  }

  public GetUserUseCase.Result disable(UserStatusCommand command) {
    try {
      GetUserUseCase.Result result = execute(command);
      record(command, Outcome.SUCCESS, null);
      return result;
    } catch (DomainException e) {
      record(command, Outcome.REJECTED, e.errorCode().code());
      throw e;
    } catch (RuntimeException e) {
      record(command, Outcome.FAILED, ErrorCode.INTERNAL_ERROR.code());
      throw e;
    }
  }

  private GetUserUseCase.Result execute(UserStatusCommand command) {
    transactions.inTransaction(
        () -> {
          statusLock.lockStatusChanges();
          User target =
              users
                  .findById(command.userId())
                  .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
          SelfProtectionRule.checkDisable(target, command.operator().userId(), users.countActive());
          users.updateStatus(target.id(), UserStatus.DISABLED);
          return null;
        });
    // 已提交：之后的登录复查必然看到 DISABLED，此时再终止，不会留下漏网的会话
    List<UUID> accountIds =
        accounts.findByUserId(command.userId()).stream().map(Account::id).toList();
    sessions.terminateAllForUser(accountIds);
    return getUser.get(command.userId());
  }

  private void record(UserStatusCommand command, Outcome outcome, String reason) {
    writeLog.record(
        new WriteOperationEvent(
            command.operator(),
            Action.USER_DISABLE,
            "user",
            command.userId().toString(),
            outcome,
            reason));
  }
}
