package tech.toolkit.tenantcore.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import tech.toolkit.tenantcore.platform.ErrorCode;

/** 过滤器层（在控制器之外）输出 problem+json。 */
public final class ProblemResponses {

  private ProblemResponses() {}

  public static void write(HttpServletResponse response, ErrorCode code) throws IOException {
    response.setStatus(code.status().value());
    response.setContentType("application/problem+json;charset=UTF-8");
    String body =
        "{\"type\":\"urn:tenant-core:error:"
            + code.name().toLowerCase().replace('_', '-')
            + "\",\"title\":\""
            + escape(code.title())
            + "\",\"status\":"
            + code.status().value()
            + ",\"code\":\""
            + code.name()
            + "\"}";
    response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
