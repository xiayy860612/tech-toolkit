package tech.toolkit.tenantcore.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ClocksTest {

  @Test
  void truncatesANanosecondClockToMicroseconds() {
    // Linux JVMs report nanoseconds; PostgreSQL timestamptz keeps microseconds.
    Clock nanos = Clock.fixed(Instant.parse("2026-11-07T13:07:12.460401627Z"), ZoneOffset.UTC);
    assertThat(Clocks.microsecondPrecision(nanos).instant())
        .isEqualTo(Instant.parse("2026-11-07T13:07:12.460401Z"));
  }

  @Test
  void keepsAnInstantThatIsAlreadyMicrosecondPrecise() {
    Clock micros = Clock.fixed(Instant.parse("2026-11-07T13:07:12.460401Z"), ZoneOffset.UTC);
    assertThat(Clocks.microsecondPrecision(micros).instant())
        .isEqualTo(Instant.parse("2026-11-07T13:07:12.460401Z"));
  }

  @Test
  void keepsTheZoneOfTheWrappedClock() {
    assertThat(Clocks.microsecondPrecision(Clock.systemUTC()).getZone()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void systemUtcNeverReportsSubMicrosecondDigits() {
    for (int i = 0; i < 1000; i += 1) {
      assertThat(Clocks.systemUtc().instant().getNano() % 1000).isZero();
    }
  }
}
