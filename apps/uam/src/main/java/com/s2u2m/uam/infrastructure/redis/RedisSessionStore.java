package com.s2u2m.uam.infrastructure.redis;

import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.OnlineUserSummary;
import com.s2u2m.uam.domain.model.Session;
import com.s2u2m.uam.domain.model.SessionRef;
import com.s2u2m.uam.domain.port.Clock;
import com.s2u2m.uam.domain.port.SessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>强制下线（{@code markTerminated = true}）时 {@code terminate.lua} 在删会话的同一脚本里原子写墓碑 {@code
 * uam:term:{sha}}（值为常量、TTL = {@code uam.session.terminated-notice-ttl}），供 {@link #wasTerminated}
 * 查询； 其余终止路径不写。
 *
 * <p>停用用户时 {@link #terminateAllForUser} 经账号指针一次清掉其全部会话；强制下线时 {@link #terminateByRef} 只清掉指定引用的那一条；
 * 二者之后都 {@code WAIT 1}（失败只记警告，不改变撤销结果）。
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
  private static final String TERMINATED = "uam:term:";
  private static final String REPLICA_WAIT_FAILED = "uam.session.replica_wait_failed";

  private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);

  /** 撤销后等待副本确认的最长时间（research R7）。 */
  private static final Duration REPLICA_WAIT = Duration.ofMillis(200);

  private final StringRedisTemplate redis;
  private final Clock clock;
  private final ReplicaWait replicaWait;
  private final Duration terminatedNoticeTtl;
  private final DefaultRedisScript<String> loginScript = script("login.lua", String.class);
  private final DefaultRedisScript<Long> touchScript = script("touch.lua", Long.class);
  private final DefaultRedisScript<Long> terminateScript = script("terminate.lua", Long.class);
  private final DefaultRedisScript<Long> terminateAllScript =
      script("terminate-all.lua", Long.class);
  private final DefaultRedisScript<List> onlineScript = script("online.lua", List.class);

  public RedisSessionStore(
      StringRedisTemplate redis,
      Clock clock,
      ReplicaWait replicaWait,
      @Value("${uam.session.terminated-notice-ttl}") Duration terminatedNoticeTtl) {
    this.redis = redis;
    this.clock = clock;
    this.replicaWait = replicaWait;
    this.terminatedNoticeTtl = terminatedNoticeTtl;
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
    return read(tokenHash);
  }

  @Override
  public List<OnlineUserSummary> listOnline(Instant now) {
    List<?> flat =
        redis.execute(
            onlineScript,
            List.of(ONLINE),
            Long.toString(now.getEpochSecond()),
            Long.toString(now.toEpochMilli()));
    Map<UUID, int[]> counts = new HashMap<>();
    Map<UUID, Instant> latest = new HashMap<>();
    for (int i = 0; flat != null && i + 1 < flat.size(); i += 2) {
      String member = (String) flat.get(i);
      UUID userId = UUID.fromString(member.substring(0, member.indexOf('|')));
      Instant lastActiveAt = Instant.ofEpochMilli(Long.parseLong((String) flat.get(i + 1)));
      counts.computeIfAbsent(userId, id -> new int[1])[0]++;
      latest.merge(userId, lastActiveAt, (a, b) -> a.isAfter(b) ? a : b);
    }
    List<OnlineUserSummary> online = new ArrayList<>();
    counts.forEach(
        (userId, count) -> online.add(new OnlineUserSummary(userId, count[0], latest.get(userId))));
    online.sort(
        Comparator.comparing(OnlineUserSummary::lastActiveAt)
            .reversed()
            .thenComparing(OnlineUserSummary::userId));
    return online;
  }

  @Override
  public List<Session> sessionsOfAccounts(Collection<UUID> accountIds) {
    List<Session> found = new ArrayList<>();
    for (UUID accountId : accountIds) {
      String tokenHash = redis.opsForValue().get(accountKey(accountId));
      if (tokenHash != null) {
        read(tokenHash).ifPresent(found::add);
      }
    }
    return found;
  }

  private Optional<Session> read(String tokenHash) {
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
  public boolean wasTerminated(String tokenHash) {
    return Boolean.TRUE.equals(redis.hasKey(TERMINATED + tokenHash));
  }

  @Override
  public boolean terminateByRef(SessionRef ref, boolean markTerminated) {
    Long result =
        markTerminated
            ? redis.execute(
                terminateScript,
                List.of(REF + ref, ONLINE),
                ref.toString(),
                Long.toString(Math.max(1, terminatedNoticeTtl.toMillis())))
            : redis.execute(terminateScript, List.of(REF + ref, ONLINE), ref.toString());
    awaitReplicas();
    return result != null && result == 1;
  }

  @Override
  public int terminateAllForUser(Collection<UUID> accountIds) {
    if (accountIds.isEmpty()) {
      return 0;
    }
    Long terminated =
        redis.execute(
            terminateAllScript, List.of(ONLINE), accountIds.stream().map(UUID::toString).toArray());
    awaitReplicas();
    return terminated == null ? 0 : terminated.intValue();
  }

  /** 撤销在主节点已生效；等待副本确认失败只记一条警告（不含标识与键），不让成功的撤销变成错误。 */
  private void awaitReplicas() {
    try {
      replicaWait.await(REPLICA_WAIT);
    } catch (RuntimeException e) {
      log.atWarn().addKeyValue("exception", e.getClass().getSimpleName()).log(REPLICA_WAIT_FAILED);
    }
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
