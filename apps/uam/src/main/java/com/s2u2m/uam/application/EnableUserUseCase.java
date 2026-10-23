package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.port.UserRepository;
import com.s2u2m.uam.domain.port.WriteOperationLog;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 启用用户（FR-024）：置为 {@code ACTIVE}，恢复其账号的登录能力；<b>不</b>恢复已结束的会话，账号自身状态不变。幂等。启用只会增加正常用户数，
 * 不需要状态变更锁。成功、被拒绝、失败都写 {@code uam.admin.write} 日志。
 */
@Component
@Profile("!cli")
public class EnableUserUseCase {

  private final UserRepository users;
  private final GetUserUseCase getUser;
  private final WriteOperationLog writeLog;

  public EnableUserUseCase(
      UserRepository users, GetUserUseCase getUser, WriteOperationLog writeLog) {
    this.users = users;
    this.getUser = getUser;
    this.writeLog = writeLog;
  }

  public GetUserUseCase.Result enable(UserStatusCommand command) {
    try {
      users
          .findById(command.userId())
          .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
      users.updateStatus(command.userId(), UserStatus.ACTIVE);
      GetUserUseCase.Result result = getUser.get(command.userId());
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

  private void record(UserStatusCommand command, Outcome outcome, String reason) {
    writeLog.record(
        new WriteOperationEvent(
            command.operator(),
            command.clientId(),
            Action.USER_ENABLE,
            "user",
            command.userId().toString(),
            outcome,
            reason));
  }
}
