package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.application.AccountProvisioningService;
import com.s2u2m.uam.domain.policy.PasswordPolicy;
import com.s2u2m.uam.support.ApiClient;
import com.s2u2m.uam.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** 密码策略接受的最长密码（128 个码点，全是 emoji 时 256 个 UTF-16 字符）一定能用来登录。 */
class PasswordLengthIT extends IntegrationTestBase {

  @Value("${local.server.port}")
  int port;

  @Autowired AccountProvisioningService provisioning;

  @Test
  void theLongestPasswordThePolicyAcceptsCanLogIn() {
    String widest = "😀".repeat(PasswordPolicy.MAX_LENGTH - 2) + "a1";
    provisioning.createUserWithAccount("长密码", "long-password", widest);

    ApiClient api = new ApiClient(port, TEST_SERVICE_KEY);

    assertThat(api.login("long-password", widest, "203.0.113.7").status()).isEqualTo(200);
  }
}
