package tech.toolkit.auth.client;

import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The back-channel logout endpoint of the dev example clients: it only logs the {@code sid} of the
 * notice it receives (never the token), so the flow can be seen in a local run. Dev profile only.
 */
@RestController
@Profile("dev")
public class DevBackChannelReceiver {

  private static final Logger log = LoggerFactory.getLogger(DevBackChannelReceiver.class);
  private static final Pattern SID = Pattern.compile("[A-Za-z0-9_-]{43}");

  @PostMapping("/dev/backchannel-logout")
  ResponseEntity<Void> receive(@RequestParam("logout_token") String logoutToken) {
    try {
      String sid = SignedJWT.parse(logoutToken).getJWTClaimsSet().getStringClaim("sid");
      log.info(
          "dev back-channel logout received for sid {}",
          sid != null && SID.matcher(sid).matches() ? sid : "?");
      return ResponseEntity.noContent().build();
    } catch (ParseException e) {
      return ResponseEntity.badRequest().build();
    }
  }

  @Configuration
  @Profile("dev")
  static class Security {

    /** A machine endpoint: no login and no CSRF token, like a real client's receiver. */
    @Bean
    @Order(0)
    SecurityFilterChain devBackChannelChain(HttpSecurity http) throws Exception {
      http.securityMatcher("/dev/**")
          .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
          .csrf(AbstractHttpConfigurer::disable);
      return http.build();
    }
  }
}
