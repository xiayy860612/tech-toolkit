package tech.toolkit.auth.session;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The {@code sid} of an SSO login, the same value the OIDC framework puts into ID tokens: the
 * SHA-256 hash of the HTTP session id, base64url without padding. The session id is rotated when
 * the user signs in, but every authorization is issued after that, so the {@code sid} stays the
 * same for the whole login and ties tokens, authorizations and logout notices to it.
 */
public final class SsoSessionIds {

  private SsoSessionIds() {}

  /** The framework's algorithm (SHA-256 over the ASCII session id, base64url, no padding). */
  public static String hash(String sessionId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(sessionId.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }

  /** The sid of the login behind the request being handled, or null outside a request. */
  public static String current() {
    if (RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes) {
      return of(attributes.getRequest().getSession(false));
    }
    return null;
  }

  /** The sid of this session, or null when there is no session. */
  public static String of(HttpSession session) {
    return session == null ? null : hash(session.getId());
  }
}
