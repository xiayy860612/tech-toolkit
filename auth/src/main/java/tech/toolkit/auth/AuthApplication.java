package tech.toolkit.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Process entry point of the auth service (OAuth 2.0 / OIDC authorization server). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuthApplication.class, args);
  }
}
