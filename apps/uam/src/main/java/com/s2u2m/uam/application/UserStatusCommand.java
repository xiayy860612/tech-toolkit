package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.model.OperatorContext;
import java.util.UUID;

/** 停用 / 启用用户的输入：操作人与客户端用于自我保护规则与写操作日志。 */
public record UserStatusCommand(OperatorContext operator, String clientId, UUID userId) {}
