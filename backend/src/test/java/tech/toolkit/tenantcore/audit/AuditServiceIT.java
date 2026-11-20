package tech.toolkit.tenantcore.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.platform.TenantContext;
import tech.toolkit.tenantcore.support.AbstractPostgresIT;

class AuditServiceIT extends AbstractPostgresIT {

  @Autowired AuditService audit;
  @Autowired AuditLogRepository repository;
  @Autowired TransactionTemplate tx;

  private static final java.util.concurrent.atomic.AtomicLong NEXT =
      new java.util.concurrent.atomic.AtomicLong(1_000_000);

  private List<AuditLog> logsFor(long tenant, Long target) {
    return TenantContext.callAs(
        tenant,
        () ->
            tx.execute(
                s ->
                    repository.findAll().stream()
                        .filter(l -> target.equals(l.getTargetId()))
                        .toList()));
  }

  @Test
  void everyActionCanBeRecordedWithActorTargetResultAndIp() {
    Long target = NEXT.incrementAndGet();
    Long actor = NEXT.incrementAndGet();
    for (AuditAction action : AuditAction.values()) {
      TenantContext.runAs(
          0L,
          () ->
              audit.record(
                  AuditEvent.success(
                      action,
                      actor,
                      AuditEvent.TargetType.USER,
                      target,
                      "203.0.113.7",
                      Map.of("note", "ok"))));
    }
    List<AuditLog> logs = logsFor(0L, target);
    assertThat(logs).hasSize(AuditAction.values().length);
    assertThat(logs)
        .allSatisfy(
            l -> {
              assertThat(l.getResult()).isEqualTo("SUCCESS");
              assertThat(l.getActorUserId()).isEqualTo(actor);
              assertThat(l.getIp().getHostAddress()).isEqualTo("203.0.113.7");
            });
  }

  @Test
  void detailNeverContainsPasswordsTokensOrJwts() {
    Long target = NEXT.incrementAndGet();
    TenantContext.runAs(
        0L,
        () ->
            audit.record(
                AuditEvent.success(
                    AuditAction.PASSWORD_RESET,
                    null,
                    AuditEvent.TargetType.USER,
                    target,
                    null,
                    Map.of(
                        "newPassword", "Secret-Passw0rd",
                        "sessionToken", "abc",
                        "nested", Map.of("jwt", "eyJ...", "keep", "yes"),
                        "scope", "USER"))));
    Map<String, Object> detail = logsFor(0L, target).get(0).getDetail();
    assertThat(detail)
        .containsEntry("newPassword", "[REDACTED]")
        .containsEntry("sessionToken", "[REDACTED]");
    assertThat(detail.get("scope")).isEqualTo("USER");
    assertThat(detail.toString()).doesNotContain("Secret-Passw0rd").doesNotContain("eyJ");
  }

  @Test
  void recordIndependentlySurvivesRollbackOfTheOuterTransaction() {
    Long target = NEXT.incrementAndGet();
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s -> {
                  audit.recordIndependently(
                      AuditEvent.failure(
                          AuditAction.LOGIN_FAILURE,
                          null,
                          AuditEvent.TargetType.USER,
                          target,
                          "BAD_PASSWORD",
                          "198.51.100.1",
                          Map.of()));
                  s.setRollbackOnly();
                }));
    List<AuditLog> logs = logsFor(0L, target);
    assertThat(logs).hasSize(1);
    assertThat(logs.get(0).getResult()).isEqualTo("FAILURE");
    assertThat(logs.get(0).getReason()).isEqualTo("BAD_PASSWORD");
  }

  @Test
  void sameTransactionRecordRollsBackWithTheBusinessChange() {
    Long target = NEXT.incrementAndGet();
    TenantContext.runAs(
        0L,
        () ->
            tx.executeWithoutResult(
                s -> {
                  audit.record(
                      AuditEvent.success(
                          AuditAction.USER_CREATED,
                          null,
                          AuditEvent.TargetType.USER,
                          target,
                          null,
                          Map.of()));
                  s.setRollbackOnly();
                }));
    assertThat(logsFor(0L, target)).isEmpty();
  }

  @Test
  void rowsAreIsolatedByTenant() {
    Long target = NEXT.incrementAndGet();
    TenantContext.runAs(
        0L,
        () ->
            audit.record(
                AuditEvent.success(
                    AuditAction.LOGIN_SUCCESS,
                    null,
                    AuditEvent.TargetType.USER,
                    target,
                    null,
                    Map.of())));
    assertThat(logsFor(1L, target)).isEmpty();
  }
}
