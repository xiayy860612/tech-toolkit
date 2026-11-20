package tech.toolkit.tenantcore.platform;

import org.springframework.http.HttpStatus;

/** 稳定的机器可读错误码（与 contracts/openapi.yaml 一致）。 */
public enum ErrorCode {
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未登录或会话已失效"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问"),
  PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "请先修改密码"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "参数校验失败"),
  CURRENT_PASSWORD_INCORRECT(HttpStatus.UNPROCESSABLE_ENTITY, "当前密码不正确"),
  USERNAME_TAKEN(HttpStatus.CONFLICT, "用户名已被占用"),
  VERSION_CONFLICT(HttpStatus.CONFLICT, "数据已被他人修改，请刷新后重试"),
  LAST_ADMIN(HttpStatus.CONFLICT, "租户至少需要保留一名可用的管理员"),
  CANNOT_DISABLE_SELF(HttpStatus.CONFLICT, "不能停用自己"),
  CANNOT_CHANGE_OWN_ROLE(HttpStatus.CONFLICT, "不能修改自己的角色"),
  CANNOT_REVOKE_CURRENT_SESSION(HttpStatus.CONFLICT, "不能下线自己的当前会话，请使用退出登录"),
  CANNOT_REVOKE_OWN_SESSIONS(HttpStatus.CONFLICT, "不能下线自己的全部会话");

  private final HttpStatus status;
  private final String title;

  ErrorCode(HttpStatus status, String title) {
    this.status = status;
    this.title = title;
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }
}
