package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.s2u2m.uam.api.OperatorContextResolver;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.OperatorContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** T019：解析 {@code X-Operator-*} 请求头；写操作必须带，读操作可选。 */
class OperatorContextResolverTest {

  private final OperatorContextResolver resolver = new OperatorContextResolver();

  private final UUID userId = UUID.randomUUID();
  private final UUID accountId = UUID.randomUUID();
  private final UUID sessionRef = UUID.randomUUID();

  private MockHttpServletRequest request(String method, String uri) {
    return new MockHttpServletRequest(method, uri);
  }

  private MockHttpServletRequest withAllHeaders(MockHttpServletRequest request) {
    request.addHeader("X-Operator-User-Id", userId.toString());
    request.addHeader("X-Operator-Account-Id", accountId.toString());
    request.addHeader("X-Operator-Session-Ref", sessionRef.toString());
    return request;
  }

  private void assertOperatorRequired(MockHttpServletRequest request) {
    assertThatThrownBy(() -> resolver.resolve(request))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.OPERATOR_REQUIRED));
  }

  @Test
  void resolvesAllThreeHeadersOnWrites() {
    Optional<OperatorContext> context =
        resolver.resolve(withAllHeaders(request("POST", "/v1/users/" + userId + "/disable")));

    assertThat(context).contains(new OperatorContext(userId, accountId, sessionRef));
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "DELETE", "PUT", "PATCH"})
  void missingHeadersOnWritesAreRejected(String method) {
    assertOperatorRequired(request(method, "/v1/sessions/" + sessionRef));
  }

  @ParameterizedTest
  @ValueSource(strings = {"X-Operator-User-Id", "X-Operator-Account-Id", "X-Operator-Session-Ref"})
  void anySingleMissingHeaderOnWritesIsRejected(String missing) {
    MockHttpServletRequest request = request("POST", "/v1/users");
    for (String header :
        new String[] {"X-Operator-User-Id", "X-Operator-Account-Id", "X-Operator-Session-Ref"}) {
      if (!header.equals(missing)) {
        request.addHeader(header, UUID.randomUUID().toString());
      }
    }
    assertOperatorRequired(request);
  }

  @Test
  void malformedUuidOnWritesIsRejected() {
    MockHttpServletRequest request = withAllHeaders(request("POST", "/v1/users"));
    request.removeHeader("X-Operator-Account-Id");
    request.addHeader("X-Operator-Account-Id", "not-a-uuid");

    assertOperatorRequired(request);
  }

  @Test
  void blankHeaderCountsAsMissing() {
    MockHttpServletRequest request = withAllHeaders(request("POST", "/v1/users"));
    request.removeHeader("X-Operator-User-Id");
    request.addHeader("X-Operator-User-Id", "  ");

    assertOperatorRequired(request);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/v1/auth/login",
        "/v1/auth/sessions/verify",
        "/v1/auth/logout",
      })
  void loginRelatedWritesDoNotNeedAnOperator(String uri) {
    assertThat(resolver.resolve(request("POST", uri))).isEmpty();
  }

  @Test
  void loginRelatedWritesStillResolveOperatorWhenProvided() {
    assertThat(resolver.resolve(withAllHeaders(request("POST", "/v1/auth/logout"))))
        .contains(new OperatorContext(userId, accountId, sessionRef));
  }

  @Test
  void readsMayOmitAllHeaders() {
    assertThat(resolver.resolve(request("GET", "/v1/users"))).isEmpty();
    assertThat(resolver.resolve(request("HEAD", "/v1/users"))).isEmpty();
  }

  @Test
  void readsMayCarryOnlyTheSessionRef() {
    MockHttpServletRequest request = request("GET", "/v1/users/" + userId + "/sessions");
    request.addHeader("X-Operator-Session-Ref", sessionRef.toString());

    Optional<OperatorContext> context = resolver.resolve(request);

    assertThat(context).isPresent();
    assertThat(context.get().sessionRef()).isEqualTo(sessionRef);
    assertThat(context.get().userId()).isNull();
    assertThat(context.get().accountId()).isNull();
  }

  @Test
  void malformedHeaderOnReadsIsRejectedToo() {
    MockHttpServletRequest request = request("GET", "/v1/users/" + userId + "/sessions");
    request.addHeader("X-Operator-Session-Ref", "garbage");

    assertOperatorRequired(request);
  }
}
