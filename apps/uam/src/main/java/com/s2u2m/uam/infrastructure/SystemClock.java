package com.s2u2m.uam.infrastructure;

import com.s2u2m.uam.domain.port.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 基于系统时钟的 {@link Clock} 实现。 */
@Component
public class SystemClock implements Clock {

  @Override
  public Instant now() {
    return Instant.now();
  }
}
