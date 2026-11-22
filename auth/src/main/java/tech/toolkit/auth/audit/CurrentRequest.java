package tech.toolkit.auth.audit;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** What the request being handled asked for, where the audit needs to tell grants apart. */
public final class CurrentRequest {

  private CurrentRequest() {}

  /** The {@code grant_type} of the token request in progress, or null outside one. */
  public static String grantType() {
    if (RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes) {
      return attributes.getRequest().getParameter("grant_type");
    }
    return null;
  }
}
