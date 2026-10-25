package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.api.TraceContextFilter;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** T022：传递并记录 W3C {@code traceparent}。 */
class TraceContextFilterTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String PARENT = "00f067aa0ba902b7";

  private final TraceContextFilter filter = new TraceContextFilter();

  private String run(MockHttpServletRequest request, MockHttpServletResponse response)
      throws Exception {
    AtomicReference<String> seen = new AtomicReference<>();
    FilterChain chain = (req, res) -> seen.set(MDC.get(TraceContextFilter.MDC_KEY));
    filter.doFilter(request, response, chain);
    return seen.get();
  }

  @Test
  void incomingTraceparentIsPutInMdcAndEchoedBack() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/users");
    request.addHeader("traceparent", "00-" + TRACE_ID + "-" + PARENT + "-01");
    MockHttpServletResponse response = new MockHttpServletResponse();

    String traceIdDuringRequest = run(request, response);

    assertThat(traceIdDuringRequest).isEqualTo(TRACE_ID);
    assertThat(response.getHeader("traceparent"))
        .isEqualTo("00-" + TRACE_ID + "-" + PARENT + "-01");
    assertThat(MDC.get(TraceContextFilter.MDC_KEY)).as("请求结束后清除").isNull();
  }

  @Test
  void missingTraceparentStartsANewTrace() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    String traceId = run(new MockHttpServletRequest("GET", "/v1/users"), response);

    assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
    assertThat(response.getHeader("traceparent")).matches("00-" + traceId + "-[0-9a-f]{16}-01");
  }

  @Test
  void malformedTraceparentStartsANewTrace() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/users");
    request.addHeader("traceparent", "garbage");
    MockHttpServletResponse response = new MockHttpServletResponse();

    String traceId = run(request, response);

    assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo("garbage");
  }

  @Test
  void allZeroTraceIdIsInvalid() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/users");
    request.addHeader("traceparent", "00-" + "0".repeat(32) + "-" + PARENT + "-01");

    String traceId = run(request, new MockHttpServletResponse());

    assertThat(traceId).isNotEqualTo("0".repeat(32));
  }
}
