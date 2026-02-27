package com.s2u2m.services.core.log;

import java.util.UUID;

/**
 * TraceId 工具类，用于生成和获取请求追踪ID
 */
public class TraceIdUtils {

  private static final String TRACE_ID_KEY = "traceId";

  private static final int TRACE_ID_LENGTH = 8;

  private TraceIdUtils() {}

  /**
   * 生成唯一的 traceId
   * 使用 UUID 的前8位，既保证唯一性又便于查看
   *
   * @return 8位 traceId
   */
  public static String generateTraceId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, TRACE_ID_LENGTH);
  }

  /**
   * 从请求头获取 traceId，如果不存在则生成新的
   *
   * @param traceIdFromHeader 请求头中的 traceId
   * @return traceId
   */
  public static String getOrCreateTraceId(String traceIdFromHeader) {
    if (traceIdFromHeader != null && !traceIdFromHeader.isEmpty()) {
      return traceIdFromHeader;
    }
    return generateTraceId();
  }

  /**
   * 获取 MDC key
   *
   * @return traceId 的 MDC key
   */
  public static String getTraceIdKey() {
    return TRACE_ID_KEY;
  }
}
