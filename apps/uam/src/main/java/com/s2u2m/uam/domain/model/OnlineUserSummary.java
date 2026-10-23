package com.s2u2m.uam.domain.model;

import java.time.Instant;
import java.util.UUID;

/** 在线用户的聚合：至少有一个有效会话的用户、其有效会话数，以及这些会话里最近的活动时间。 */
public record OnlineUserSummary(UUID userId, int sessionCount, Instant lastActiveAt) {}
