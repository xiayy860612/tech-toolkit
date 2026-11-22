package tech.toolkit.auth.logout;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.auth.audit.AuditEvent;
import tech.toolkit.auth.audit.AuditService;
import tech.toolkit.auth.audit.AuditType;
import tech.toolkit.auth.config.AuthProperties;
import tech.toolkit.auth.logout.LogoutDeliveryRepository.Delivery;

/**
 * Delivers the queued back-channel logout notices (SC-009: within 10 seconds). A notice that fails
 * is retried after 1s, 5s, 30s, 2min and 10min, then given up and audited (data-model.md). Rows are
 * locked while they are handled, so several auth instances never send the same notice twice.
 */
@Component
public class BackChannelDeliveryWorker {

  private static final Logger log = LoggerFactory.getLogger(BackChannelDeliveryWorker.class);
  private static final int BATCH = 20;

  private final LogoutDeliveryRepository deliveries;
  private final LogoutTokenFactory tokens;
  private final AuditService audit;
  private final AuthProperties.Logout settings;
  private final TransactionTemplate transaction;
  private final HttpClient http;

  public BackChannelDeliveryWorker(
      LogoutDeliveryRepository deliveries,
      LogoutTokenFactory tokens,
      AuditService audit,
      AuthProperties properties,
      TransactionTemplate transaction) {
    this.deliveries = deliveries;
    this.tokens = tokens;
    this.audit = audit;
    this.settings = properties.logout();
    this.transaction = transaction;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(settings.httpTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Scheduled(fixedDelayString = "${auth.logout.poll-interval:1s}")
  void poll() {
    if (settings.workerEnabled()) {
      deliverDue();
    }
  }

  /** Handles every notice that is due and returns how many were looked at. */
  public int deliverDue() {
    Integer handled =
        transaction.execute(
            status -> {
              List<Delivery> due = deliveries.claimDue(BATCH);
              due.forEach(this::deliver);
              return due.size();
            });
    return handled == null ? 0 : handled;
  }

  private void deliver(Delivery delivery) {
    int attempts = delivery.attempts() + 1;
    if (send(delivery)) {
      deliveries.markDelivered(delivery.id(), attempts);
      return;
    }
    List<Duration> delays = settings.retryDelays();
    if (attempts <= delays.size()) {
      deliveries.scheduleRetry(delivery.id(), attempts, delays.get(attempts - 1));
    } else {
      deliveries.markGaveUp(delivery.id(), attempts);
      audit.record(
          AuditEvent.of(AuditType.LOGOUT, false)
              .clientId(delivery.clientId())
              .detail(Map.of("reason", "back_channel_delivery_gave_up", "attempts", attempts)));
    }
  }

  private boolean send(Delivery delivery) {
    if (delivery.uri() == null) {
      return false;
    }
    try {
      String body =
          "logout_token="
              + URLEncoder.encode(
                  tokens.create(delivery.clientId(), delivery.sid()), StandardCharsets.UTF_8);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(delivery.uri()))
              .timeout(settings.httpTimeout())
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      return status == 200 || status == 204;
    } catch (IOException | RuntimeException e) {
      log.warn(
          "back-channel logout to client {} failed: {}",
          delivery.clientId(),
          e.getClass().getSimpleName());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
