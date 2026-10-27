package com.s2u2m.uam.api;

import com.s2u2m.uam.api.dto.OnlineUserPage;
import com.s2u2m.uam.api.dto.SessionView;
import com.s2u2m.uam.application.ListOnlineUsersUseCase;
import com.s2u2m.uam.application.ListUserSessionsUseCase;
import com.s2u2m.uam.application.TerminateSessionCommand;
import com.s2u2m.uam.application.TerminateSessionUseCase;
import com.s2u2m.uam.domain.model.OperatorContext;
import com.s2u2m.uam.domain.model.SessionRef;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线用户与会话（契约 {@code /v1/online-users}、{@code /v1/users/{userId}/sessions}、{@code
 * /v1/sessions/{sessionRef}}）。 查询是读操作，操作人请求头可选；带了 {@code X-Operator-Session-Ref} 就用它标注 {@code
 * current}。强制下线是写操作，操作人请求头必填。 响应里只有会话引用，没有会话标识。
 */
@RestController
@Profile("!cli")
public class SessionsController {

  /** 用户会话列表的响应体（契约里是 {@code {items: [...]}}）。 */
  public record SessionList(List<SessionView> items) {}

  private final ListOnlineUsersUseCase listOnlineUsers;
  private final ListUserSessionsUseCase listUserSessions;
  private final TerminateSessionUseCase terminateSession;
  private final OperatorContextResolver operators;

  public SessionsController(
      ListOnlineUsersUseCase listOnlineUsers,
      ListUserSessionsUseCase listUserSessions,
      TerminateSessionUseCase terminateSession,
      OperatorContextResolver operators) {
    this.listOnlineUsers = listOnlineUsers;
    this.listUserSessions = listUserSessions;
    this.terminateSession = terminateSession;
    this.operators = operators;
  }

  @GetMapping("/v1/online-users")
  public OnlineUserPage onlineUsers(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "" + ListOnlineUsersUseCase.DEFAULT_SIZE) int size) {
    return OnlineUserPage.of(listOnlineUsers.list(page, size));
  }

  @GetMapping("/v1/users/{userId}/sessions")
  public SessionList userSessions(@PathVariable UUID userId, HttpServletRequest request) {
    UUID operatorSessionRef =
        operators.resolve(request).map(OperatorContext::sessionRef).orElse(null);
    return new SessionList(
        listUserSessions.list(userId, operatorSessionRef).stream().map(SessionView::of).toList());
  }

  /** 强制下线指定会话：只终止该会话；不能终止操作人自己正在使用的会话；会话已结束返回 404。 */
  @DeleteMapping("/v1/sessions/{sessionRef}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void terminate(@PathVariable UUID sessionRef, HttpServletRequest request) {
    OperatorContext operator = operators.resolve(request).orElseThrow();
    terminateSession.terminate(new TerminateSessionCommand(operator, new SessionRef(sessionRef)));
  }
}
