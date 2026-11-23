package tech.toolkit.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.OAuthTestBase;
import tools.jackson.databind.JsonNode;

/** FR-029: a health endpoint for the platform, and nothing else of the actuator. */
class HealthEndpointIT extends OAuthTestBase {

  @Test
  void healthIsReachableWithoutLoginAndSaysOnlyUp() throws Exception {
    MvcResult result = mvc.perform(get("/actuator/health")).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("status").asString()).isEqualTo("UP");
    // no components, no details: nothing about the database, the disk or the configuration
    assertThat(body.has("components")).isFalse();
    assertThat(body.has("details")).isFalse();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("postgres", "diskSpace");
  }

  @Test
  void otherActuatorEndpointsAreNotExposed() throws Exception {
    for (String path : new String[] {"env", "beans", "metrics", "configprops", "heapdump"}) {
      MvcResult result = mvc.perform(get("/actuator/" + path)).andReturn();

      assertThat(result.getResponse().getStatus()).as(path).isNotEqualTo(200);
      assertThat(result.getResponse().getContentAsString())
          .as(path)
          .doesNotContain("propertySources");
    }
  }
}
