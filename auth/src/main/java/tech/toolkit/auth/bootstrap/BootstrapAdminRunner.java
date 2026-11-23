package tech.toolkit.auth.bootstrap;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the bootstrap when auth is started with the {@code bootstrap} profile, then the process ends
 * (that profile starts no web server). The initial password is typed at the prompt or piped to
 * standard input; it is never accepted as an argument, where it would end up in shell history and
 * process listings.
 */
@Component
@Profile("bootstrap")
public class BootstrapAdminRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

  private final BootstrapService bootstrap;
  private final String username;
  private final List<String> redirectUris;

  public BootstrapAdminRunner(
      BootstrapService bootstrap,
      @Value("${auth.bootstrap.admin-username:admin}") String username,
      @Value("${auth.bootstrap.admin-redirect-uris:}") List<String> redirectUris) {
    this.bootstrap = bootstrap;
    this.username = username;
    this.redirectUris = redirectUris.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  @Override
  public void run(ApplicationArguments args) {
    Console console = System.console();
    InputStream source =
        console == null
            ? System.in
            : new ByteArrayInputStream(
                (new String(console.readPassword("Initial password for %s: ", username)) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
    runWith(args, source);
  }

  /** The bootstrap with the password read from the given stream. */
  public BootstrapService.Result runWith(ApplicationArguments args, InputStream in) {
    boolean passwordArgument =
        args.getOptionNames().stream().anyMatch(name -> name.toLowerCase().contains("password"));
    if (passwordArgument) {
      throw new IllegalArgumentException(
          "the password is never accepted as a command-line argument; type it at the prompt or pipe it to standard input");
    }
    String password = readLine(in);
    if (password == null || password.isBlank()) {
      throw new IllegalArgumentException("no password was given on standard input");
    }
    BootstrapService.Result result = bootstrap.run(username, password, redirectUris);
    log.info(
        "bootstrap done: administrator {}, client {}",
        result.adminCreated() ? "created" : "already there",
        result.clientRegistered() ? "registered" : "already there");
    return result;
  }

  private static String readLine(InputStream in) {
    try {
      return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
