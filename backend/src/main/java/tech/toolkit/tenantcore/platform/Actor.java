package tech.toolkit.tenantcore.platform;

import java.util.UUID;

/** 发起操作的管理员（来自已验签 JWT），用于审计与"不能对自己操作"类校验；业务层据此不必依赖 Web 层。 */
public record Actor(UUID userId, UUID accountId, String ip) {}
