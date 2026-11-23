package tech.toolkit.auth.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import tech.toolkit.auth.account.UserRepository;
import tech.toolkit.auth.support.PostgresTestBase;

/**
 * SC-004: 500 users log in at the same moment and 95% of them are done within 2 seconds. A login is
 * the login page plus the submitted form, over real HTTP. Not part of the normal run: {@code mvn
 * test -Dtest.excluded.groups=none -Dtest=LoginLoadIT}.
 */
@Tag("performance")
class LoginLoadIT extends PostgresTestBase {

  private static final int USERS = 500;
  private static final String PASSWORD = "Load-Test-Passw0rd-1";
  private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

  @LocalServerPort int port;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;

  private long login(String username) throws Exception {
    HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    URI login = URI.create("http://localhost:" + port + "/login");
    long started = System.nanoTime();
    HttpResponse<String> page =
        client.send(
            HttpRequest.newBuilder(login).header("Accept", "text/html").GET().build(),
            HttpResponse.BodyHandlers.ofString());
    Matcher csrf = CSRF.matcher(page.body());
    if (!csrf.find()) {
      throw new IllegalStateException("no csrf token in the login page");
    }
    String form =
        "username="
            + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)
            + "&_csrf="
            + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
    HttpResponse<String> result =
        client.send(
            HttpRequest.newBuilder(login)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
    String location = result.headers().firstValue("Location").orElse("");
    if (result.statusCode() != 302 || location.contains("error")) {
      throw new IllegalStateException(
          "login of " + username + " failed: " + result.statusCode() + " " + location);
    }
    return millis;
  }

  @Test
  void fiveHundredSimultaneousLoginsFinishWithinTwoSecondsForNinetyFivePercent() throws Exception {
    String hash = encoder.encode(PASSWORD);
    for (int i = 0; i < USERS; i++) {
      users.insert("load.user." + i, hash, false, false);
    }
    CountDownLatch gate = new CountDownLatch(1);
    AtomicInteger failures = new AtomicInteger();
    List<Long> durations = Collections.synchronizedList(new ArrayList<>());
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> running = new ArrayList<>();
      for (int i = 0; i < USERS; i++) {
        String username = "load.user." + i;
        running.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    durations.add(login(username));
                  } catch (Exception e) {
                    failures.incrementAndGet();
                  }
                  return null;
                }));
      }
      gate.countDown();
      for (Future<?> f : running) {
        f.get();
      }
    }

    List<Long> sorted = durations.stream().sorted().toList();
    long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    System.out.printf(
        "login load: n=%d failures=%d p50=%dms p95=%dms max=%dms%n",
        sorted.size(),
        failures.get(),
        sorted.get(sorted.size() / 2),
        p95,
        sorted.get(sorted.size() - 1));
    assertThat(failures.get()).isZero();
    assertThat(p95).isLessThanOrEqualTo(2000);
  }
}
