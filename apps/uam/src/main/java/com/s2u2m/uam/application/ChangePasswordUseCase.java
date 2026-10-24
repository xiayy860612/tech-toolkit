package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.policy.PasswordPolicy;
import com.s2u2m.uam.domain.port.CredentialRepository;
import com.s2u2m.uam.domain.port.PasswordChangeThrottle;
import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.domain.port.WriteOperationLog;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 修改自己的密码（FR-026、FR-027）。
 *
 * <p>UAM 信任客户端传来的操作人，但本操作只允许改<b>操作人自己的账号</b>：路径账号不等于操作人账号一律 403 {@code client_forbidden}（不进入后续校验）。
 * 随后先查限流（来源已被封锁则直接 429，不做哈希比对），再<b>总是做一次哈希比对</b>（账号不存在或没有凭据时比对固定的伪哈希，耗时与「当前密码错误」无差别，
 * 并同样按错误计数）；当前密码正确才校验新密码强度与「不同于当前」，通过后只更新该账号的哈希。当前密码一经验证即清零计数，新密码的问题不算猜测。
 * 不触碰会话（当前会话保持有效），不影响同用户的其他账号。成功、被拒绝、失败都写 {@code uam.admin.write} 日志；密码与哈希不进日志与异常。
 */
@Component
@Profile("!cli")
public class ChangePasswordUseCase {

  private static final String NEW_PASSWORD_FIELD = "newPassword";

  private final CredentialRepository credentials;
  private final PasswordHasher hasher;
  private final PasswordChangeThrottle throttle;
  private final LoginThrottlePolicy throttlePolicy;
  private final PasswordPolicy passwordPolicy;
  private final WriteOperationLog writeLog;

  public ChangePasswordUseCase(
      CredentialRepository credentials,
      PasswordHasher hasher,
      PasswordChangeThrottle throttle,
      LoginThrottlePolicy throttlePolicy,
      PasswordPolicy passwordPolicy,
      WriteOperationLog writeLog) {
    this.credentials = credentials;
    this.hasher = hasher;
    this.throttle = throttle;
    this.throttlePolicy = throttlePolicy;
    this.passwordPolicy = passwordPolicy;
    this.writeLog = writeLog;
  }

  public void change(ChangePasswordCommand command) {
    try {
      execute(command);
      record(command, Outcome.SUCCESS, null);
    } catch (DomainException e) {
      record(command, Outcome.REJECTED, e.errorCode().code());
      throw e;
    } catch (RuntimeException e) {
      record(command, Outcome.FAILED, ErrorCode.INTERNAL_ERROR.code());
      throw e;
    }
  }

  private void execute(ChangePasswordCommand command) {
    if (!command.accountId().equals(command.operator().accountId())) {
      throw new DomainException(ErrorCode.CLIENT_FORBIDDEN, "只能修改操作人自己账号的密码");
    }
    String subject = throttlePolicy.accountSubject(command.accountId(), command.clientIp());
    OptionalLong blocked = throttle.changeBlockedFor(subject);
    if (blocked.isPresent()) {
      throw DomainException.throttled("尝试过于频繁，请稍后再试", blocked.getAsLong());
    }

    Optional<String> storedHash = credentials.findPasswordHash(command.accountId());
    boolean currentMatches =
        hasher.matches(command.currentPassword(), storedHash.orElseGet(hasher::dummyHash));
    if (!currentMatches || storedHash.isEmpty()) {
      throttle.recordChangeFailure(subject);
      throw new DomainException(ErrorCode.CURRENT_PASSWORD_INCORRECT, "当前密码不正确");
    }
    throttle.resetChange(subject);

    List<FieldError> weak = passwordPolicy.validate(NEW_PASSWORD_FIELD, command.newPassword());
    if (!weak.isEmpty()) {
      throw DomainException.withFieldErrors(ErrorCode.WEAK_PASSWORD, "新密码不满足强度要求", weak);
    }
    if (command.newPassword().equals(command.currentPassword())) {
      throw DomainException.withFieldErrors(
          ErrorCode.SAME_AS_CURRENT,
          "新密码不能与当前密码相同",
          List.of(new FieldError(NEW_PASSWORD_FIELD, "same_as_current")));
    }
    credentials.updatePasswordHash(command.accountId(), hasher.hash(command.newPassword()));
  }

  private void record(ChangePasswordCommand command, Outcome outcome, String reason) {
    writeLog.record(
        new WriteOperationEvent(
            command.operator(),
            command.clientId(),
            Action.ACCOUNT_PASSWORD_CHANGE,
            "account",
            command.accountId().toString(),
            outcome,
            reason));
  }
}
