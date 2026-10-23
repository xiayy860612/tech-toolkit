package com.s2u2m.uam.domain.model;

import java.util.UUID;

/**
 * 操作人上下文：由客户端以 {@code X-Operator-*} 请求头传入，UAM 信任它，只用于自我保护规则与写操作日志（research R5）。
 * 写操作三项齐全；读操作可只带其中几项，缺省为 {@code null}。
 */
public record OperatorContext(UUID userId, UUID accountId, UUID sessionRef) {}
