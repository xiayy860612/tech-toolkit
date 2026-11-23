package tech.toolkit.auth.oauth;

import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Builds the {@link SigningKeyProvider} from {@code auth.signing.keys} (PEM file paths). */
@Configuration
public class SigningKeyConfig {

  @Bean
  public SigningKeyProvider signingKeyProvider(
      Environment environment, @Value("${auth.signing.keys:}") List<String> keyFiles) {
    List<Path> paths =
        keyFiles.stream().map(String::trim).filter(s -> !s.isEmpty()).map(Path::of).toList();
    // ephemeral keys are a convenience for local runs and tests only, never for production
    boolean allowEphemeral = environment.acceptsProfiles(Profiles.of("dev", "test"));
    return new SigningKeyProvider(paths, allowEphemeral);
  }
}
