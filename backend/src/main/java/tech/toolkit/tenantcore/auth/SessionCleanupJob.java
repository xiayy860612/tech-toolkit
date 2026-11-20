package tech.toolkit.tenantcore.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时清理过期/已撤销的会话（日后可迁到 Scheduler 基础服务）。 */
@Component
class SessionCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(SessionCleanupJob.class);

  private final SessionService sessions;

  SessionCleanupJob(SessionService sessions) {
    this.sessions = sessions;
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
  void purge() {
    int removed = sessions.purge();
    if (removed > 0) {
      log.info("Purged {} expired sessions", removed);
    }
  }
}
