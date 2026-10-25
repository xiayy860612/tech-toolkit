package com.s2u2m.uam.api.dto;

/**
 * {@code POST /v1/auth/sessions/verify} 的请求体（契约 {@code VerifyRequest}）。
 *
 * <p>不做结构校验：缺失、空白、过长的会话标识都由用例统一判为 {@code session_invalid}（401），客户端只需区分「会话无效」与「服务不可用」。
 */
public record VerifyRequest(String sessionId) {

  /** 不输出会话标识，避免误入日志。 */
  @Override
  public String toString() {
    return "VerifyRequest[]";
  }
}
