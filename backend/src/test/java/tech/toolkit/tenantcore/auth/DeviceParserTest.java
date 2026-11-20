package tech.toolkit.tenantcore.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeviceParserTest {

  @Test
  void parsesCommonBrowsersAndSystems() {
    assertThat(
            DeviceParser.parse(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/130.0.0.0 Safari/537.36"))
        .isEqualTo("Chrome 130 / macOS");
    assertThat(
            DeviceParser.parse(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0"
                    + " Safari/537.36 Edg/130.0.0.0"))
        .isEqualTo("Edge 130 / Windows");
    assertThat(
            DeviceParser.parse(
                "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0"))
        .isEqualTo("Firefox 131 / Linux");
    assertThat(
            DeviceParser.parse(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15"
                    + " (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"))
        .isEqualTo("Safari 17 / iOS");
  }

  @Test
  void unknownOrMissingUserAgentIsLabelledUnknown() {
    assertThat(DeviceParser.parse(null)).isEqualTo("未知设备");
    assertThat(DeviceParser.parse("  ")).isEqualTo("未知设备");
    assertThat(DeviceParser.parse("curl/8.0")).isEqualTo("未知设备");
  }
}
