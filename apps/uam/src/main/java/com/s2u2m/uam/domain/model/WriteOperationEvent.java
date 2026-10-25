package com.s2u2m.uam.domain.model;

/** 一次写操作的审计事件（research R16）；被拒绝的操作也要记录。 */
public record WriteOperationEvent(
    OperatorContext operator,
    String clientId,
    Action action,
    String targetType,
    String targetId,
    Outcome outcome,
    String reason) {

  /** 写操作种类，{@link #value()} 是日志里的稳定名称。 */
  public enum Action {
    USER_CREATE("user.create"),
    USER_DISABLE("user.disable"),
    USER_ENABLE("user.enable"),
    SESSION_TERMINATE("session.terminate"),
    ACCOUNT_PASSWORD_CHANGE("account.password.change");

    private final String value;

    Action(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  /** 结果：成功、被规则拒绝、执行失败。 */
  public enum Outcome {
    SUCCESS("success"),
    REJECTED("rejected"),
    FAILED("failed");

    private final String value;

    Outcome(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }
}
