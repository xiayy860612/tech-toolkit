package tech.toolkit.tenantcore.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ApiException.FieldError;
import tech.toolkit.tenantcore.platform.ErrorCode;
import tech.toolkit.tenantcore.platform.PasswordHasher;
import tech.toolkit.tenantcore.platform.PasswordPolicy;
import tech.toolkit.tenantcore.platform.TemporaryPasswordGenerator;

/** 创建用户名密码 Account 与凭证（创建 User 与新增 Account 共用）：校验、唯一性、临时密码；须在调用方事务内使用。 */
@Component
class AccountProvisioner {

  /** {@code generatedPassword} 仅在系统生成时非空，只返回一次。 */
  record Provisioned(Account account, boolean mustChangePassword, String generatedPassword) {}

  private final AccountRepository accounts;
  private final PasswordCredentialRepository credentials;
  private final PasswordHasher hasher;
  private final PasswordPolicy policy;
  private final TemporaryPasswordGenerator generator;

  AccountProvisioner(
      AccountRepository accounts,
      PasswordCredentialRepository credentials,
      PasswordHasher hasher,
      PasswordPolicy policy,
      TemporaryPasswordGenerator generator) {
    this.accounts = accounts;
    this.credentials = credentials;
    this.hasher = hasher;
    this.policy = policy;
    this.generator = generator;
  }

  /** 校验用户名与（若给定）临时密码，返回字段错误；不抛出。 */
  List<FieldError> validate(
      String usernameField, String username, String passwordField, String password) {
    List<FieldError> errors = new ArrayList<>();
    FieldError bad = IdentityRules.usernameField(usernameField, username);
    if (bad != null) {
      errors.add(bad);
    }
    if (password != null) {
      policy.violations(password, username, Set.of()).stream()
          .map(m -> new FieldError(passwordField, m))
          .forEach(errors::add);
    }
    return errors;
  }

  Provisioned provision(UUID userId, String username, String suppliedPassword) {
    if (accounts
        .findByTypeAndIdentifierNormalized(
            AccountType.USERNAME_PASSWORD, Account.normalize(username))
        .isPresent()) {
      throw new ApiException(ErrorCode.USERNAME_TAKEN);
    }
    String password = suppliedPassword == null ? generator.generate() : suppliedPassword;
    try {
      Account account =
          accounts.saveAndFlush(new Account(userId, AccountType.USERNAME_PASSWORD, username));
      credentials.save(new PasswordCredential(account.getId(), hasher.hash(password), true));
      return new Provisioned(account, true, suppliedPassword == null ? password : null);
    } catch (DataIntegrityViolationException e) {
      throw new ApiException(ErrorCode.USERNAME_TAKEN);
    }
  }
}
