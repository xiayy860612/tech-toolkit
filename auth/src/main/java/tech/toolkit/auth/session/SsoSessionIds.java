package tech.toolkit.auth.session;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;

/**
 * The {@code sid} of an SSO login: generated when the user signs in and kept as a session
 * attribute. It is deliberately not the HTTP session id, which rotates on login (session fixation
 * protection); {@code sid} stays stable and ties tokens, authorizations and logout notices to one
 * login.
 */
public final class SsoSessionIds {

  public static final String SID_ATTRIBUTE = "auth.sid";

  private SsoSessionIds() {}

  public static String assign(HttpSession session) {
    String sid = UUID.randomUUID().toString();
    session.setAttribute(SID_ATTRIBUTE, sid);
    return sid;
  }

  /** The sid of this session, or null when there is no session or nobody signed in yet. */
  public static String of(HttpSession session) {
    return session == null ? null : (String) session.getAttribute(SID_ATTRIBUTE);
  }
}
