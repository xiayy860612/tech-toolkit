package tech.toolkit.auth.client;

/** What the client side needs from the OAuth side: end every authorization of one client. */
public interface ClientAuthorizations {

  /** Deletes every code and token issued to the client, for all users. */
  int revokeByClientId(String clientId);
}
