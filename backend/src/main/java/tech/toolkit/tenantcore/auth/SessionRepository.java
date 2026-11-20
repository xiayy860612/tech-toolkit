package tech.toolkit.tenantcore.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, UUID> {

  Optional<Session> findByTokenHash(byte[] tokenHash);

  @Modifying
  @Query("update Session s set s.lastSeenAt = :now where s.id = :id")
  int touch(@Param("id") UUID id, @Param("now") Instant now);

  @Modifying
  @Query(
      "update Session s set s.revokedAt = :now, s.revokeReason = :reason"
          + " where s.id = :id and s.revokedAt is null")
  int revokeById(
      @Param("id") UUID id, @Param("now") Instant now, @Param("reason") RevokeReason reason);

  @Modifying
  @Query(
      "update Session s set s.revokedAt = :now, s.revokeReason = :reason"
          + " where s.accountId = :accountId and s.revokedAt is null")
  int revokeByAccount(
      @Param("accountId") UUID accountId,
      @Param("now") Instant now,
      @Param("reason") RevokeReason reason);

  @Modifying
  @Query(
      "update Session s set s.revokedAt = :now, s.revokeReason = :reason"
          + " where s.accountId = :accountId and s.revokedAt is null and s.id <> :exceptId")
  int revokeByAccountExcept(
      @Param("accountId") UUID accountId,
      @Param("exceptId") UUID exceptId,
      @Param("now") Instant now,
      @Param("reason") RevokeReason reason);

  @Modifying
  @Query(
      "update Session s set s.revokedAt = :now, s.revokeReason = :reason"
          + " where s.userId = :userId and s.revokedAt is null")
  int revokeByUser(
      @Param("userId") UUID userId,
      @Param("now") Instant now,
      @Param("reason") RevokeReason reason);
}
