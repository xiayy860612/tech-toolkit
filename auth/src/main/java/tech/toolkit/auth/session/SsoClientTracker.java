package tech.toolkit.auth.session;

import org.springframework.stereotype.Component;

/** Notes every authorization that is issued during a login, so the login knows its clients. */
@Component
public class SsoClientTracker {

  private final SsoClientRepository clients;

  public SsoClientTracker(SsoClientRepository clients) {
    this.clients = clients;
  }

  public void track(String sid, String registeredClientId) {
    if (sid != null && registeredClientId != null) {
      clients.record(sid, registeredClientId);
    }
  }
}
