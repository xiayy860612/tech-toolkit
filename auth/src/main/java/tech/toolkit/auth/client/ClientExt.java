package tech.toolkit.auth.client;

/** Per-client settings the framework's client table has no room for (data-model.md). */
public record ClientExt(
    String clientId,
    boolean enabled,
    String backchannelLogoutUri,
    String postLogoutRedirectUris,
    Integer accessTokenTtlSeconds,
    Integer refreshTokenTtlSeconds) {}
