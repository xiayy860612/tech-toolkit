package com.s2u2m.uam.domain.port;

import java.time.Instant;

/** 时间端口：业务代码经它取当前时间，便于测试注入固定时间。 */
public interface Clock {

  Instant now();
}
