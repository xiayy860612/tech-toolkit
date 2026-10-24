package com.s2u2m.uam.application;

import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.model.SessionToken;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.policy.LoginThrottlePolicy;
import com.s2u2m.uam.domain.policy.SessionPolicy;
import com.s2u2m.uam.domain.policy.TextChars;
import com.s2u2m.uam.domain.port.AccountRepository;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.CredentialRepository;
import com.s2u2m.uam.domain.port.LoginThrottle;
import com.s2u2m.uam.domain.port.PasswordHasher;
import com.s2u2m.uam.domain.port.SessionStore;
import com.s2u2m.uam.domain.port.UserRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 登录（FR-001 ~ FR-006，research R10 / R15）。
 *
 * <p>账号不存在、密码错误、账号已停用、所属用户已停用一律 {@code
 * invalid_credentials}，且<b>都先做一次哈希比对</b>（账号不存在时比对固定的伪哈希），耗时无可区分差别。 同一「账号 +
 * 来源」的尝试在哈希比对之前先原子占用名额（并发的错误尝试不会同时通过闸门），超过阈值后即使凭据正确也拒绝（429）。成功则签发新会话并顶掉同账号旧会话；写入会话后复查用户状态，已停用则终止刚建的会话并拒绝，
 * 这样「停用与登录并发」的两种交错顺序都不会留下已停用用户的有效会话。
 *
 * <p>含 NUL 或不成对代理项的账号名不查库，与「账号不存在」同路径（同样限流、同样做一次伪哈希比对）。
 */
@Component
@Profile("!cli")
public class LoginUseCase {

  private static final String INVALID_CREDENTIALS_DETAIL = "账号名或密码错误";

  private final UserRepository users;
  private final AccountRepository accounts;
  private final CredentialRepository credentials;
  private final PasswordHasher hasher;
  private final SessionStore sessions;
  private final LoginThrottle throttle;
  private final LoginThrottlePolicy throttlePolicy;
  private final SessionPolicy sessionPolicy;
  private final Clock clock;

  public LoginUseCase(
      UserRepository users,
      AccountRepository accounts,
      CredentialRepository credentials,
      PasswordHasher hasher,
      SessionStore sessions,
      LoginThrottle throttle,
      LoginThrottlePolicy throttlePolicy,
      SessionPolicy sessionPolicy,
      Clock clock) {
    this.users = users;
    this.accounts = accounts;
    this.credentials = credentials;
    this.hasher = hasher;
    this.sessions = sessions;
    this.throttle = throttle;
    this.throttlePolicy = throttlePolicy;
    this.sessionPolicy = sessionPolicy;
    this.clock = clock;
  }

  public LoginResult login(LoginCommand command) {
    LoginName name = LoginName.of(command.accountName());
    String subject = throttlePolicy.subject(name, command.clientIp());
    OptionalLong blocked = throttle.admit(subject);
    if (blocked.isPresent()) {
      throw DomainException.throttled("登录尝试过于频繁，请稍后再试", blocked.getAsLong());
    }

    // NUL / 不成对代理项无法比较于 PostgreSQL（会变成 500），这类名字不可能存在：不查库，按「账号不存在」处理
    Optional<Account> account =
        TextChars.hasUnsafeChars(command.accountName())
            ? Optional.empty()
            : accounts.findByLoginName(name);
    Optional<User> owner = account.flatMap(a -> users.findById(a.userId()));
    Optional<String> storedHash = account.flatMap(a -> credentials.findPasswordHash(a.id()));
    // 无论账号是否存在、可否登录，都先做一次哈希比对
    boolean passwordMatches =
        hasher.matches(command.password(), storedHash.orElseGet(hasher::dummyHash));
    if (!passwordMatches
        || storedHash.isEmpty()
        || account.isEmpty()
        || owner.isEmpty()
        || !account.get().canLogin(owner.get())) {
      return reject();
    }

    if (hasher.needsUpgrade(storedHash.get())) {
      // 条件更新：校验与升级之间密码若已被改过，升级是空操作，登录照常继续
      credentials.upgradePasswordHash(
          account.get().id(), storedHash.get(), hasher.hash(command.password()));
    }
    SessionToken token = SessionToken.generate();
    Session session =
        Session.start(SessionRef.random(), owner.get(), account.get(), clock.now(), sessionPolicy);
    sessions.create(session, token.hash());

    // 写入会话之后再读一次用户状态：并发停用也不会留下有效会话
    boolean stillActive = users.findById(owner.get().id()).map(User::isActive).orElse(false);
    if (!stillActive) {
      sessions.terminateByRef(session.ref(), false);
      return reject();
    }

    throttle.reset(subject);
    long maxAge = Duration.between(clock.now(), session.absoluteExpiresAt()).toSeconds();
    return new LoginResult(token.id(), Math.max(maxAge, 0), session);
  }

  private LoginResult reject() {
    throw new DomainException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_DETAIL);
  }
}
