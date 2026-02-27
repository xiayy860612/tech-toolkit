package com.s2u2m.services.core.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求跟踪过滤器
 * 为每个请求生成唯一的 traceId，并将其放入 MDC 以便在日志中追踪
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnMissingBean(RequestTrackingFilter.class)
public class RequestTrackingFilter extends OncePerRequestFilter {

  private static final String TRACE_ID_HEADER = "X-Trace-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {

    // 从请求头获取或生成 traceId
    String traceId = TraceIdUtils.getOrCreateTraceId(request.getHeader(TRACE_ID_HEADER));

    try {
      // 将 traceId 放入 MDC
      MDC.put(TraceIdUtils.getTraceIdKey(), traceId);

      // 将 traceId 添加到响应头，便于前端追踪
      response.setHeader(TRACE_ID_HEADER, traceId);

      // 记录请求开始日志
      log.info("Request started: {} {}", request.getMethod(), request.getRequestURI());

      // 继续执行过滤器链
      filterChain.doFilter(request, response);

    } finally {
      log.info("Request completed: {} {}", request.getMethod(), request.getRequestURI());
      // 请求处理完成后清理 MDC，避免内存泄漏
      MDC.remove(TraceIdUtils.getTraceIdKey());
    }
  }
}
