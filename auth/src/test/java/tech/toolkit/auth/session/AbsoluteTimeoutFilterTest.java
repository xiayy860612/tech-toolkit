package tech.toolkit.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class AbsoluteTimeoutFilterTest {

  private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

  private AbsoluteTimeoutFilter filterWithLimit(Duration limit) {
    return new AbsoluteTimeoutFilter(Clock.fixed(NOW, ZoneOffset.UTC), limit);
  }

  private MockHttpSession sessionCreatedAgo(Duration age) {
    MockHttpSession session = new MockHttpSession();
    // MockHttpSession records its creation at construction; the clock under test is moved instead
    return session;
  }

  private boolean runs(AbsoluteTimeoutFilter filter, MockHttpSession session) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return chain.getRequest() != null;
  }

  @Test
  void endsASessionOlderThanTheLimit() throws Exception {
    MockHttpSession session = sessionCreatedAgo(Duration.ZERO);
    // the session was created "now" in real time; a clock two hours ahead makes it two hours old
    AbsoluteTimeoutFilter filter =
        new AbsoluteTimeoutFilter(
            Clock.fixed(
                Instant.ofEpochMilli(session.getCreationTime()).plus(Duration.ofHours(2)),
                ZoneOffset.UTC),
            Duration.ofHours(1));

    assertThat(runs(filter, session)).isTrue(); // the request still continues, unauthenticated
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void keepsASessionInsideTheLimit() throws Exception {
    MockHttpSession session = sessionCreatedAgo(Duration.ZERO);
    AbsoluteTimeoutFilter filter =
        new AbsoluteTimeoutFilter(
            Clock.fixed(
                Instant.ofEpochMilli(session.getCreationTime()).plus(Duration.ofMinutes(30)),
                ZoneOffset.UTC),
            Duration.ofHours(1));

    assertThat(runs(filter, session)).isTrue();
    assertThat(session.isInvalid()).isFalse();
  }

  @Test
  void aRequestWithoutASessionPassesThrough() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockFilterChain chain = new MockFilterChain();

    filterWithLimit(Duration.ofHours(8)).doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(request.getSession(false)).isNull();
  }
}
