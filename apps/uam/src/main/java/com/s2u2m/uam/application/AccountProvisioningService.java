package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.Credential;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.policy.AccountNameRules;
import com.s2u2m.uam.domain.policy.PasswordPolicy;
import com.s2u2m.uam.domain.port.AccountRepository;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.CredentialRepository;
import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.domain.port.TransactionRunner;
import com.s2u2m.uam.domain.port.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 创建用户与账号的唯一入口：部署期命令（{@code init-admin}、{@code add-account}）与 US4 的新建用户用例共用，规则只写这一处。
 *
 * <p>用户 + 账号 + 凭据在<b>同一个事务</b>里写入，任一步失败都不留半成品；先校验、再做（较慢的）哈希、最后进事务，账号名唯一性由数据库唯一索引兜底（并发安全）。
 */
@Service
public class AccountProvisioningService {

  /** 创建结果：新账号及其所属用户。 */
  public record Provisioned(UUID userId, UUID accountId, String loginName) {}

  private static final int USER_NAME_MAX_LENGTH = 64;

  private final UserRepository users;
  private final AccountRepository accounts;
  private final CredentialRepository credentials;
  private final PasswordHasher hasher;
  private final PasswordPolicy passwordPolicy;
  private final TransactionRunner tx;
  private final Clock clock;

  public AccountProvisioningService(
      UserRepository users,
      AccountRepository accounts,
      CredentialRepository credentials,
      PasswordHasher hasher,
      PasswordPolicy passwordPolicy,
      TransactionRunner tx,
      Clock clock) {
    this.users = users;
    this.accounts = accounts;
    this.credentials = credentials;
    this.hasher = hasher;
    this.passwordPolicy = passwordPolicy;
    this.tx = tx;
    this.clock = clock;
  }

  /** 创建用户（{@code ACTIVE}）及其一个「账号名 / 密码」账号。 */
  public Provisioned createUserWithAccount(String userName, String accountName, String password) {
    String name = validate(userName, accountName, password);
    String hash = hasher.hash(password);
    return tx.inTransaction(() -> insertUserWithAccount(name, accountName, hash));
  }

  /** 仅在没有任何用户时创建首个用户（{@code init-admin}）；已有用户则什么也不做、不校验输入，返回空——幂等。 */
  public Optional<Provisioned> createInitialUserWithAccount(
      String userName, String accountName, String password) {
    if (users.count() > 0) {
      return Optional.empty();
    }
    String name = validate(userName, accountName, password);
    String hash = hasher.hash(password);
    return tx.inTransaction(
        () ->
            users.count() > 0
                ? Optional.empty()
                : Optional.of(insertUserWithAccount(name, accountName, hash)));
  }

  /** 给已有用户追加一个「账号名 / 密码」账号。 */
  public Provisioned addAccount(UUID userId, String accountName, String password) {
    validate(accountName, password);
    String hash = hasher.hash(password);
    return tx.inTransaction(
        () -> {
          User owner = users.findById(userId).orElseThrow(AccountProvisioningService::noSuchUser);
          return insertAccount(owner.id(), accountName, hash);
        });
  }

  /** 给「已有账号所属的用户」追加账号（{@code add-account --account-name-of}）。 */
  public Provisioned addAccountToUserOf(
      String existingAccountName, String accountName, String password) {
    validate(accountName, password);
    String hash = hasher.hash(password);
    return tx.inTransaction(
        () -> {
          Account existing =
              accounts
                  .findByLoginName(LoginName.of(existingAccountName))
                  .orElseThrow(AccountProvisioningService::noSuchUser);
          return insertAccount(existing.userId(), accountName, hash);
        });
  }

  private Provisioned insertUserWithAccount(String userName, String accountName, String hash) {
    User user = new User(UUID.randomUUID(), userName, UserStatus.ACTIVE, clock.now());
    users.insert(user);
    return insertAccount(user.id(), accountName, hash);
  }

  private Provisioned insertAccount(UUID userId, String accountName, String hash) {
    LoginName loginName = LoginName.of(accountName);
    if (accounts.existsByLoginName(loginName)) {
      throw taken();
    }
    Account account =
        new Account(
            UUID.randomUUID(),
            userId,
            AccountKind.PASSWORD,
            loginName,
            AccountStatus.ACTIVE,
            clock.now());
    accounts.insert(account);
    credentials.insert(new Credential(account.id(), hash));
    return new Provisioned(userId, account.id(), loginName.raw());
  }

  /** 校验并返回去首尾空白后的用户名。 */
  private String validate(String userName, String accountName, String password) {
    List<FieldError> errors = new ArrayList<>();
    String trimmed = userName == null ? "" : userName.strip();
    if (trimmed.isEmpty()) {
      errors.add(new FieldError("userName", "required"));
    } else if (trimmed.length() > USER_NAME_MAX_LENGTH) {
      errors.add(new FieldError("userName", "too_long"));
    }
    errors.addAll(AccountNameRules.validate(accountName));
    errors.addAll(passwordPolicy.validate("password", password));
    failIfAny(errors);
    return trimmed;
  }

  private void validate(String accountName, String password) {
    List<FieldError> errors = new ArrayList<>(AccountNameRules.validate(accountName));
    errors.addAll(passwordPolicy.validate("password", password));
    failIfAny(errors);
  }

  private static void failIfAny(List<FieldError> errors) {
    if (!errors.isEmpty()) {
      throw DomainException.validationFailed(errors);
    }
  }

  private static DomainException taken() {
    return new DomainException(ErrorCode.ACCOUNT_NAME_TAKEN, "账号名已被使用");
  }

  private static DomainException noSuchUser() {
    return new DomainException(ErrorCode.USER_NOT_FOUND, "用户不存在");
  }
}
