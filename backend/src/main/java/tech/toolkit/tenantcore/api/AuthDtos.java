package tech.toolkit.tenantcore.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import tech.toolkit.tenantcore.identity.Role;

/** /auth/* 的请求与响应（与 contracts/openapi.yaml 一致）。 */
public final class AuthDtos {

  private AuthDtos() {}

  public record LoginRequest(
      @NotBlank @Size(max = 64) String username,
      @NotBlank @Size(max = 128) String password,
      @Size(max = 512) String userAgent) {
    @Override
    public String toString() {
      return "LoginRequest[redacted]";
    }
  }

  public record Me(
      Long userId, long tenantId, String displayName, Role role, boolean mustChangePassword) {}

  public record LoginResult(Me me, String sessionToken, Instant expiresAt) {
    @Override
    public String toString() {
      return "LoginResult[redacted]";
    }
  }

  public record SessionResolveRequest(@NotBlank @Size(min = 16, max = 256) String sessionToken) {
    @Override
    public String toString() {
      return "SessionResolveRequest[redacted]";
    }
  }

  public record SessionResolveResult(
      UUID sessionId,
      Long userId,
      long tenantId,
      String displayName,
      boolean mustChangePassword,
      Instant expiresAt) {}

  public record ChangePasswordRequest(
      @NotBlank @Size(max = 128) String currentPassword,
      @NotBlank @Size(max = 128) String newPassword) {
    @Override
    public String toString() {
      return "ChangePasswordRequest[redacted]";
    }
  }
}
