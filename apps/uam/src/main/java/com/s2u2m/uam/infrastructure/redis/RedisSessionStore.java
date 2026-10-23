package com.s2u2m.uam.infrastructure.redis;

import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.SessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 会话存储（data-model §3，research R7 ~ R9）。
 *
 * <p>键：{@code uam:sess:{sha}}（HASH，{@code sha} = SHA-256(会话标识)，明文标识从不入库）、{@code
 * uam:acct:{accountId}:sess}（当前会话的 {@code sha}，一对一）、{@code uam:sref:{ref}}（会话引用 → {@code
 * sha}）、{@code uam:online}（ZSET，成员 {@code {userId}|{ref}}，分数为到期 epoch 秒）。 登录、续期、终止各用一个 Lua
 * 脚本原子完成；三个会话键的 TTL 都取「空闲剩余」与「绝对剩余」的较小值（即会话的有效到期）。
 *
 * <p>HASH 里除设计表中的字段外，还快照了 {@code userName}、{@code loginName}、{@code kind}、{@code expiresAt}，使校验只读
 * Redis。
 */
@Component
@Profile("!cli")
public class RedisSessionStore implements SessionStore {

  private static final String SESSION = "uam:sess:";
  private static final String ACCOUNT_PREFIX = "uam:acct:";
  private static final String ACCOUNT_SUFFIX = ":sess";
  private static final String REF = "uam:sref:";
  private static final String ONLINE = "uam:online";

  private final StringRedisTemplate redis;
  private final Clock clock;
  private final DefaultRedisScript<String> loginScript = script("login.lua", String.class);
  private final DefaultRedisScript<Long> touchScript = script("touch.lua", Long.class);
  private final DefaultRedisScript<Long> terminateScript = script("terminate.lua", Long.class);

  public RedisSessionStore(StringRedisTemplate redis, Clock clock) {
    this.redis = redis;
    this.clock = clock;
  }

  private static <T> DefaultRedisScript<T> script(String name, Class<T> resultType) {
    DefaultRedisScript<T> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("redis/" + name));
    script.setResultType(resultType);
    return script;
  }

  @Override
  public Optional<SessionRef> create(Session session, String tokenHash) {
    String replaced =
        redis.execute(
            loginScript,
            List.of(
                accountKey(session.accountId()), SESSION + tokenHash, REF + session.ref(), ONLINE),
            tokenHash,
            session.ref().toString(),
            session.accountId().toString(),
            session.userId().toString(),
            session.userName(),
            session.loginName(),
            session.kind().name(),
            millis(session.loginAt()),
            millis(session.lastActiveAt()),
            millis(session.absoluteExpiresAt()),
            millis(session.expiresAt()),
            Long.toString(ttlMillis(session)),
            member(session),
            score(session));
    return Optional.ofNullable(replaced).map(SessionRef::parse);
  }

  @Override
  public Optional<Session> find(String tokenHash) {
    Map<Object, Object> hash = redis.opsForHash().entries(SESSION + tokenHash);
    if (hash.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Session(
            SessionRef.parse(field(hash, "ref")),
            UUID.fromString(field(hash, "userId")),
            field(hash, "userName"),
            UUID.fromString(field(hash, "accountId")),
            field(hash, "loginName"),
            AccountKind.valueOf(field(hash, "kind")),
            instant(hash, "loginAt"),
            instant(hash, "lastActiveAt"),
            instant(hash, "absExpiresAt"),
            instant(hash, "expiresAt")));
  }

  @Override
  public Optional<Session> touch(String tokenHash, Session touched) {
    Long result =
        redis.execute(
            touchScript,
            List.of(
                SESSION + tokenHash, accountKey(touched.accountId()), REF + touched.ref(), ONLINE),
            tokenHash,
            millis(touched.lastActiveAt()),
            millis(touched.expiresAt()),
            Long.toString(ttlMillis(touched)),
            member(touched),
            score(touched));
    return result == null || result == 0 ? Optional.empty() : Optional.of(touched);
  }

  @Override
  public boolean terminate(SessionRef ref) {
    Long result = redis.execute(terminateScript, List.of(REF + ref, ONLINE), ref.toString());
    return result != null && result == 1;
  }

  private long ttlMillis(Session session) {
    return Math.max(1, Duration.between(clock.now(), session.expiresAt()).toMillis());
  }

  private static String accountKey(UUID accountId) {
    return ACCOUNT_PREFIX + accountId + ACCOUNT_SUFFIX;
  }

  private static String member(Session session) {
    return session.userId() + "|" + session.ref();
  }

  private static String score(Session session) {
    return Long.toString(session.expiresAt().getEpochSecond());
  }

  private static String millis(Instant instant) {
    return Long.toString(instant.toEpochMilli());
  }

  private static String field(Map<Object, Object> hash, String name) {
    return (String) hash.get(name);
  }

  private static Instant instant(Map<Object, Object> hash, String name) {
    return Instant.ofEpochMilli(Long.parseLong(field(hash, name)));
  }
}
