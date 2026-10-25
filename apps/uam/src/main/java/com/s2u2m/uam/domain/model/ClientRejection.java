package com.s2u2m.uam.domain.model;

/**
 * 一次被拒绝的客户端调用（401 / 403），写拒绝日志用。<b>刻意不含</b>服务密钥、哈希或 Authorization 头的任何部分：只有原因类别与调用的来源、目标。
 *
 * @param sourceAddress 传输层对端地址（UAM 在内网，客户端直连；不采信可被调用方伪造的转发头）
 * @param clientId 已识别的客户端；识别失败（401）时为 {@code null}
 */
public record ClientRejection(
    Reason reason, String sourceAddress, String method, String path, String clientId, int status) {

  /** 原因类别（稳定的小写蛇形取值，供日志检索与告警）。 */
  public enum Reason {
    /** 没有 Authorization 头。 */
    MISSING_KEY("missing_key"),
    /** 有 Authorization 头，但不是 {@code Bearer <密钥>} 形式。 */
    MALFORMED("malformed"),
    /** 密钥不匹配任何有效哈希（含已撤销的旧密钥，二者无法区分）。 */
    UNKNOWN_KEY("unknown_key"),
    /** 密钥有效，但客户端没有该接口所需的权限。 */
    FORBIDDEN("forbidden");

    private final String value;

    Reason(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }
}
