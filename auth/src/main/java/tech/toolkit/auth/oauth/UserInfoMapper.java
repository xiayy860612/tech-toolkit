package tech.toolkit.auth.oauth;

import java.util.function.Function;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.stereotype.Component;
import tech.toolkit.auth.account.User;
import tech.toolkit.auth.account.UserRepository;

/** The userinfo response holds only {@code sub} and {@code preferred_username} (FR-001a). */
@Component
public class UserInfoMapper implements Function<OidcUserInfoAuthenticationContext, OidcUserInfo> {

  private final UserRepository users;

  public UserInfoMapper(UserRepository users) {
    this.users = users;
  }

  @Override
  public OidcUserInfo apply(OidcUserInfoAuthenticationContext context) {
    String subject = context.getAuthorization().getPrincipalName();
    String username = users.findById(Long.parseLong(subject)).map(User::username).orElse(null);
    OidcUserInfo.Builder info = OidcUserInfo.builder().subject(subject);
    if (username != null) {
      info.preferredUsername(username);
    }
    return info.build();
  }
}
