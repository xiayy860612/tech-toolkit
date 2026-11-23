package tech.toolkit.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.config.AuthProperties;
import tech.toolkit.auth.support.OAuthTestBase;

/** FR-018: 5 failures lock the account for 15 minutes, and nothing reveals that it is locked. */
class LockoutIT extends OAuthTestBase {

  @Autowired LockoutService lockout;
  @Autowired JdbcTemplate appJdbc;

  private MvcResult wrongPassword() throws Exception {
    return signIn(new Browser(), USERNAME, "this-is-not-the-password");
  }

  private MvcResult correctPassword() throws Exception {
    return signIn(new Browser(), USERNAME, PASSWORD);
  }

  private Map<String, Object> state() {
    return ownerJdbc()
        .queryForMap(
            "select failed_attempts, locked_until,"
                + " extract(epoch from (locked_until - now())) as wait from auth_user where username = ?",
            USERNAME);
  }

  private long userId() {
    return ownerJdbc()
        .queryForObject("select id from auth_user where username = ?", Long.class, USERNAME);
  }

  private void lockTheAccount() throws Exception {
    for (int i = 0; i < 5; i++) {
      wrongPassword();
    }
  }

  @Test
  void fiveFailuresLockTheAccountForFifteenMinutesEvenForTheRightPassword() throws Exception {
    lockTheAccount();

    assertThat(state().get("failed_attempts")).isEqualTo(5);
    assertThat(((Number) state().get("wait")).doubleValue()).isBetween(14 * 60.0, 15 * 60.0 + 1);
    assertThat(correctPassword().getResponse().getRedirectedUrl()).isEqualTo("/login?error");
  }

  @Test
  void theLockIsAudited() throws Exception {
    lockTheAccount();

    assertThat(
            ownerJdbc()
                .queryForObject(
                    "select count(*) from auth_audit_event where type = 'ACCOUNT_LOCKED'",
                    Integer.class))
        .isEqualTo(1);
  }

  @Test
  void failuresDuringTheLockDoNotExtendIt() throws Exception {
    lockTheAccount();
    Object lockedUntil = state().get("locked_until");

    for (int i = 0; i < 3; i++) {
      wrongPassword();
    }

    assertThat(state().get("locked_until")).isEqualTo(lockedUntil);
    assertThat(state().get("failed_attempts")).isEqualTo(5);
  }

  @Test
  void aSuccessfulLoginClearsTheCounter() throws Exception {
    for (int i = 0; i < 3; i++) {
      wrongPassword();
    }

    assertThat(correctPassword().getResponse().getRedirectedUrl()).doesNotContain("error");

    assertThat(state().get("failed_attempts")).isEqualTo(0);
  }

  @Test
  void whenTheLockExpiresTheRightPasswordWorksAgain() throws Exception {
    lockTheAccount();
    ownerJdbc().update("update auth_user set locked_until = now() - interval '1 second'");

    assertThat(correctPassword().getResponse().getRedirectedUrl()).doesNotContain("error");

    assertThat(state().get("failed_attempts")).isEqualTo(0);
    assertThat(state().get("locked_until")).isNull();
  }

  @Test
  void afterTheLockExpiresCountingStartsOver() throws Exception {
    lockTheAccount();
    ownerJdbc().update("update auth_user set locked_until = now() - interval '1 second'");

    wrongPassword();

    assertThat(state().get("failed_attempts")).isEqualTo(1);
    assertThat(state().get("locked_until")).isNull();
  }

  @Test
  void aLockedAccountAnswersExactlyLikeAWrongPassword() throws Exception {
    lockTheAccount();

    MvcResult rightWhileLocked = correctPassword();
    MvcResult wrongWhileLocked = wrongPassword();

    assertThat(rightWhileLocked.getResponse().getStatus())
        .isEqualTo(wrongWhileLocked.getResponse().getStatus());
    assertThat(rightWhileLocked.getResponse().getRedirectedUrl())
        .isEqualTo(wrongWhileLocked.getResponse().getRedirectedUrl())
        .isEqualTo("/login?error");
    String page = mvc.perform(get("/login?error")).andReturn().getResponse().getContentAsString();
    assertThat(page).contains(ERROR_TEXT).contains(LOCK_HINT);
  }

  @Test
  void anUnknownUserNeverLocksAndNeverDiffers() throws Exception {
    for (int i = 0; i < 7; i++) {
      MvcResult result = signIn(new Browser(), "nobody", "whatever-password-1");
      assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
    }
  }

  @Test
  void thresholdAndDurationAreConfigurable() {
    LockoutService strict =
        new LockoutService(appJdbc, new AuthProperties.Lockout(2, Duration.ofMinutes(1)));

    assertThat(strict.recordFailure(userId()).locked()).isFalse();
    LockoutService.Outcome second = strict.recordFailure(userId());

    assertThat(second.locked()).isTrue();
    assertThat(((Number) state().get("wait")).doubleValue()).isBetween(50.0, 61.0);
  }

  @Test
  void concurrentFailuresAreCountedExactlyAndLockOnce() throws Exception {
    int callers = 12;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch go = new CountDownLatch(1);
    long id = userId();
    List<Future<LockoutService.Outcome>> results = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      Callable<LockoutService.Outcome> failure =
          () -> {
            go.await();
            return lockout.recordFailure(id);
          };
      results.add(pool.submit(failure));
    }
    go.countDown();
    int newlyLocked = 0;
    for (Future<LockoutService.Outcome> result : results) {
      if (result.get().newlyLocked()) {
        newlyLocked++;
      }
    }
    pool.shutdown();

    assertThat(state().get("failed_attempts")).isEqualTo(5);
    assertThat(newlyLocked).isEqualTo(1);
  }
}
