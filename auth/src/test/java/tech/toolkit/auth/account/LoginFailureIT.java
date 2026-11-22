package tech.toolkit.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import tech.toolkit.auth.support.OAuthTestBase;

class LoginFailureIT extends OAuthTestBase {

  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @MockitoSpyBean PasswordEncoder passwordEncoder;

  private MvcResult failedLogin(String username, String password) throws Exception {
    return signIn(new Browser(), username, password);
  }

  private String loginPage(String query) throws Exception {
    return mvc.perform(get("/login" + query)).andReturn().getResponse().getContentAsString();
  }

  @Test
  void wrongPasswordUnknownUserAndDisabledAccountAreIndistinguishable() throws Exception {
    long id = users.insert("bob", encoder.encode("Another-Long-Password-1"), false, false);
    ownerJdbc().update("update auth_user set status = 'DISABLED' where id = ?", id);

    MvcResult wrongPassword = failedLogin(USERNAME, "not-the-password");
    MvcResult unknownUser = failedLogin("nobody", "whatever-password-1");
    MvcResult disabled = failedLogin("bob", "Another-Long-Password-1");

    for (MvcResult result : new MvcResult[] {wrongPassword, unknownUser, disabled}) {
      assertThat(result.getResponse().getStatus()).isEqualTo(302);
      assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
    }
    assertThat(loginPage("?error")).contains(ERROR_TEXT);
  }

  @Test
  void theLoginPageAlwaysCarriesTheLockoutHint() throws Exception {
    String fresh = loginPage("");
    String afterFailure = loginPage("?error");

    assertThat(fresh).contains(LOCK_HINT).doesNotContain(ERROR_TEXT);
    assertThat(afterFailure).contains(LOCK_HINT).contains(ERROR_TEXT);
  }

  @Test
  void anUnknownUserStillCostsAPasswordHashComparison() throws Exception {
    clearInvocations(passwordEncoder);

    failedLogin("nobody", "whatever-password-1");

    // the work done for a missing user must look like the work done for a wrong password
    verify(passwordEncoder, atLeastOnce()).matches(any(), any());
  }

  @Test
  void aDisabledAccountWithTheRightPasswordCannotSignIn() throws Exception {
    ownerJdbc().update("update auth_user set status = 'DISABLED' where username = ?", USERNAME);

    MvcResult result = failedLogin(USERNAME, PASSWORD);

    assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
  }

  @Test
  void anAccountDisabledAfterTheLoginPageWasShownCannotCompleteTheLogin() throws Exception {
    Browser browser = new Browser();
    browser.get("/login");
    ownerJdbc().update("update auth_user set status = 'DISABLED' where username = ?", USERNAME);

    MvcResult result =
        browser.postForm("/login", Map.of("username", USERNAME, "password", PASSWORD));

    assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
  }
}
