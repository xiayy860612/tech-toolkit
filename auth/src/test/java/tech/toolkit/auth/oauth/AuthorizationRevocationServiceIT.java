package tech.toolkit.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.toolkit.auth.support.PostgresTestBase;

class AuthorizationRevocationServiceIT extends PostgresTestBase {

  @Autowired AuthorizationRevocationService revocation;

  @BeforeEach
  void fixtures() {
    insertClient("rc-1", "client1");
    insertClient("rc-2", "client2");
    // user 1 has two sessions on client1 and one on client2; user 2 has one on client1
    insertAuthorization("a1", "rc-1", "1", "sid-a");
    insertAuthorization("a2", "rc-1", "1", "sid-b");
    insertAuthorization("a3", "rc-2", "1", "sid-a");
    insertAuthorization("a4", "rc-1", "2", "sid-c");
  }

  private void insertClient(String id, String clientId) {
    ownerJdbc()
        .update(
            "insert into oauth2_registered_client (id, client_id, client_name,"
                + " client_authentication_methods, authorization_grant_types, scopes,"
                + " client_settings, token_settings) values (?, ?, ?, 'none', 'authorization_code',"
                + " 'openid', '{}', '{}')",
            id,
            clientId,
            clientId);
  }

  private void insertAuthorization(
      String id, String registeredClientId, String principal, String sid) {
    ownerJdbc()
        .update(
            "insert into oauth2_authorization (id, registered_client_id, principal_name,"
                + " authorization_grant_type, auth_session_id)"
                + " values (?, ?, ?, 'authorization_code', ?)",
            id,
            registeredClientId,
            principal,
            sid);
  }

  private List<String> remaining() {
    return ownerJdbc()
        .queryForList("select id from oauth2_authorization order by id", String.class);
  }

  @Test
  void revokesEverythingOfOneUserAndNothingElse() {
    int deleted = revocation.revokeByPrincipal("1");

    assertThat(deleted).isEqualTo(3);
    assertThat(remaining()).containsExactly("a4");
  }

  @Test
  void revokesEverythingOfOneClientAndNothingElse() {
    int deleted = revocation.revokeByClientId("client1");

    assertThat(deleted).isEqualTo(3);
    assertThat(remaining()).containsExactly("a3");
  }

  @Test
  void revokesOnlyTheAuthorizationsOfOneSession() {
    int deleted = revocation.revokeBySessionId("sid-a");

    assertThat(deleted).isEqualTo(2);
    assertThat(remaining()).containsExactly("a2", "a4");
  }

  @Test
  void deletesNothingWhenThereIsNoMatch() {
    assertThat(revocation.revokeByPrincipal("999")).isZero();
    assertThat(revocation.revokeByClientId("unknown")).isZero();
    assertThat(revocation.revokeBySessionId("sid-x")).isZero();
    assertThat(remaining()).hasSize(4);
  }

  @Test
  void rejectsBlankArguments() {
    assertThatThrownBy(() -> revocation.revokeByPrincipal(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> revocation.revokeByClientId(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> revocation.revokeBySessionId(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(remaining()).hasSize(4);
  }
}
