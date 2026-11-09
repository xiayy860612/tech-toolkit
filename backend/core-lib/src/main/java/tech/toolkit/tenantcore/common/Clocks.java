package tech.toolkit.tenantcore.common;

import java.time.Clock;
import java.time.Duration;

/**
 * Clocks for production code. PostgreSQL {@code timestamptz} keeps microseconds, but Linux JVMs
 * report nanoseconds, so a value built from the clock would differ from the same value read back
 * from the database. Truncating at the source keeps both equal on every platform.
 */
public final class Clocks {

  private static final Duration MICROSECOND = Duration.ofNanos(1_000);

  private Clocks() {}

  /** The system clock in UTC at microsecond precision. */
  public static Clock systemUtc() {
    return microsecondPrecision(Clock.systemUTC());
  }

  /** The given clock, truncated down to a whole number of microseconds. */
  public static Clock microsecondPrecision(Clock clock) {
    return Clock.tick(clock, MICROSECOND);
  }
}
