package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.policy.SelfProtectionRule;
import com.s2u2m.uam.domain.port.SessionStore;
import com.s2u2m.uam.domain.port.WriteOperationLog;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 强制下线某条会话（FR-016、FR-028、FR-030）。
 *
 * <p>先过自我保护（目标是操作人当前会话则拒绝，此时不触碰任何会话），再按会话引用只终止这一条；会话本来就不存在（已退出、到期、被取代、被终止）→ {@code
 * session_not_found}。强制下线会留下短期墓碑，使被下线者下一次 {@code verify} 的 401 带 {@code reason:
 * terminated}。该用户的其他会话、账号与用户状态都不变。成功、被拒绝、失败都写 {@code uam.admin.write} 日志（只记会话引用，不含会话标识）。
 */
@Component
@Profile("!cli")
public class TerminateSessionUseCase {

  private final SessionStore sessions;
  private final WriteOperationLog writeLog;

  public TerminateSessionUseCase(SessionStore sessions, WriteOperationLog writeLog) {
    this.sessions = sessions;
    this.writeLog = writeLog;
  }

  public void terminate(TerminateSessionCommand command) {
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

  private void execute(TerminateSessionCommand command) {
    SelfProtectionRule.checkTerminate(command.target(), command.operator().sessionRef());
    if (!sessions.terminateByRef(command.target(), true)) {
      throw new DomainException(ErrorCode.SESSION_NOT_FOUND, "会话不存在或已结束");
    }
  }

  private void record(TerminateSessionCommand command, Outcome outcome, String reason) {
    writeLog.record(
        new WriteOperationEvent(
            command.operator(),
            command.clientId(),
            Action.SESSION_TERMINATE,
            "session",
            command.target().toString(),
            outcome,
            reason));
  }
}
