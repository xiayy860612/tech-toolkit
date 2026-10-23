package com.s2u2m.uam.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 传递并记录 W3C {@code traceparent}：把 trace id 放进 MDC（结构化日志与写操作日志都带它），并在响应里回显。 缺失或不合法时开启新的 trace。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

  public static final String MDC_KEY = "traceId";
  public static final String HEADER = "traceparent";

  private static final Pattern TRACEPARENT =
      Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
  private static final String ZERO_TRACE = "0".repeat(32);
  private static final String ZERO_SPAN = "0".repeat(16);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    Matcher matcher = incoming == null ? null : TRACEPARENT.matcher(incoming.trim());
    String traceId;
    String traceparent;
    if (matcher != null
        && matcher.matches()
        && !ZERO_TRACE.equals(matcher.group(1))
        && !ZERO_SPAN.equals(matcher.group(2))) {
      traceId = matcher.group(1);
      traceparent = incoming.trim();
    } else {
      traceId = randomHex(16);
      traceparent = "00-" + traceId + "-" + randomHex(8) + "-01";
    }
    MDC.put(MDC_KEY, traceId);
    response.setHeader(HEADER, traceparent);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private static String randomHex(int bytes) {
    byte[] buffer = new byte[bytes];
    do {
      ThreadLocalRandom.current().nextBytes(buffer);
    } while (isAllZero(buffer));
    return HexFormat.of().formatHex(buffer);
  }

  private static boolean isAllZero(byte[] buffer) {
    for (byte b : buffer) {
      if (b != 0) {
        return false;
      }
    }
    return true;
  }
}
