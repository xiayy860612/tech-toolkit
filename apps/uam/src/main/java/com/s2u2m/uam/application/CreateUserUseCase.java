package com.s2u2m.uam.application;

import com.s2u2m.uam.application.AccountProvisioningService.Provisioned;
import com.s2u2m.uam.domain.DomainException;
import com.s2u2m.uam.domain.DomainException.FieldError;
import com.s2u2m.uam.domain.ErrorCode;
import com.s2u2m.uam.domain.model.Account;
import com.s2u2m.uam.domain.model.AccountKind;
import com.s2u2m.uam.domain.model.AccountStatus;
import com.s2u2m.uam.domain.model.LoginName;
import com.s2u2m.uam.domain.model.User;
import com.s2u2m.uam.domain.model.UserStatus;
import com.s2u2m.uam.domain.model.WriteOperationEvent;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Action;
import com.s2u2m.uam.domain.model.WriteOperationEvent.Outcome;
import com.s2u2m.uam.domain.port.IdempotencyStore;
import com.s2u2m.uam.domain.port.IdempotencyStore.Claim;
import com.s2u2m.uam.domain.port.WriteOperationLog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 新建用户（FR-021 ~ FR-023、FR-034）：只负责编排——校验并占用幂等键、委托 {@link AccountProvisioningService}（字段规则与单事务都在那里）、
 * 写 {@code uam.admin.write} 日志。
 *
 * <p>幂等：只缓存 201 成功。同键重放（10 分钟内）返回原响应；同键的另一个请求正在执行时短暂等待它完成（占位方失败放弃则接手）， 等不到就 {@code
 * idempotency_in_progress}（409），绝不建第二个用户；同键但请求不同（用户名 / 账号名不同）为 422 {@code Idempotency-Key reused}。
 * 被拒绝或在提交前失败的尝试会释放占位，同键的修正重试可以重新执行；提交之后的任何问题（重读、保存快照）都不会释放占位或让请求失败，快照保存不了就让进行中标记自行过期。指纹与快照都不含密码或哈希（同键只换密码的重放按原结果返回）。
 */
@Component
@Profile("!cli")
public class CreateUserUseCase {

  /** 幂等键字段名（请求头名）。 */
  public static final String KEY_FIELD = "Idempotency-Key";

  private static final Logger log = LoggerFactory.getLogger(CreateUserUseCase.class);
  private static final String READ_BACK_FAILED = "uam.user.create.read_back_failed";
  private static final String COMPLETE_FAILED = "uam.user.create.idempotency_complete_failed";

  static final int KEY_MIN_LENGTH = 16;
  static final int KEY_MAX_LENGTH = 64;
  static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(5);
  static final Duration DEFAULT_POLL = Duration.ofMillis(50);

  /** 可打印 ASCII，不含空白。 */
  private static final Pattern KEY_CHARS = Pattern.compile("[\\x21-\\x7E]+");

  /** 响应快照里的用户与账号（独立于领域类型，避免把领域方法当成属性写出）。 */
  private record Snapshot(SnapshotUser user, List<SnapshotAccount> accounts) {

    static Snapshot of(GetUserUseCase.Result result) {
      User user = result.user();
      return new Snapshot(
          new SnapshotUser(user.id(), user.name(), user.status(), user.createdAt()),
          result.accounts().stream()
              .map(
                  a ->
                      new SnapshotAccount(
                          a.id(),
                          a.userId(),
                          a.kind(),
                          a.loginName().raw(),
                          a.status(),
                          a.createdAt()))
              .toList());
    }

    GetUserUseCase.Result toResult() {
      return new GetUserUseCase.Result(
          new User(user.id(), user.name(), user.status(), user.createdAt()),
          accounts.stream()
              .map(
                  a ->
                      new Account(
                          a.id(),
                          a.userId(),
                          a.kind(),
                          LoginName.of(a.loginName()),
                          a.status(),
                          a.createdAt()))
              .toList());
    }
  }

  private record SnapshotUser(UUID id, String name, UserStatus status, Instant createdAt) {}

  private record SnapshotAccount(
      UUID id,
      UUID userId,
      AccountKind kind,
      String loginName,
      AccountStatus status,
      Instant createdAt) {}

  private final AccountProvisioningService provisioning;
  private final GetUserUseCase getUser;
  private final IdempotencyStore idempotency;
  private final WriteOperationLog writeLog;
  private final JsonMapper json;
  private final Duration maxWait;
  private final Duration poll;

  @Autowired
  public CreateUserUseCase(
      AccountProvisioningService provisioning,
      GetUserUseCase getUser,
      IdempotencyStore idempotency,
      WriteOperationLog writeLog,
      JsonMapper json) {
    this(provisioning, getUser, idempotency, writeLog, json, DEFAULT_MAX_WAIT, DEFAULT_POLL);
  }

  /** 可指定等待同键进行中请求的时长与轮询间隔（测试用）。 */
  public CreateUserUseCase(
      AccountProvisioningService provisioning,
      GetUserUseCase getUser,
      IdempotencyStore idempotency,
      WriteOperationLog writeLog,
      JsonMapper json,
      Duration maxWait,
      Duration poll) {
    this.provisioning = provisioning;
    this.getUser = getUser;
    this.idempotency = idempotency;
    this.writeLog = writeLog;
    this.json = json;
    this.maxWait = maxWait;
    this.poll = poll;
  }

  public GetUserUseCase.Result create(CreateUserCommand command) {
    try {
      return execute(command);
    } catch (DomainException e) {
      record(command, Outcome.REJECTED, null, e.errorCode().code());
      throw e;
    } catch (RuntimeException e) {
      record(command, Outcome.FAILED, null, ErrorCode.INTERNAL_ERROR.code());
      throw e;
    }
  }

  /** 请求指纹：去首尾空白的用户名 + 小写账号名的 SHA-256。不含密码（也就不会把密码的任何派生值留在 Redis）。 */
  public static String fingerprint(String userName, String accountName) {
    String normalized =
        (userName == null ? "" : userName.strip())
            + "\n"
            + (accountName == null ? "" : accountName.toLowerCase(Locale.ROOT));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(normalized.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private GetUserUseCase.Result execute(CreateUserCommand command) {
    String key = validKey(command.idempotencyKey());
    String scope = command.clientId();
    String fingerprint = fingerprint(command.userName(), command.accountName());
    long deadline = System.nanoTime() + maxWait.toNanos();
    while (true) {
      switch (idempotency.claim(scope, key, fingerprint)) {
        case Claim.Acquired acquired -> {
          return createAndRemember(command, scope, key, fingerprint);
        }
        case Claim.Completed completed -> {
          requireSameRequest(completed.fingerprint(), fingerprint);
          return json.readValue(completed.response(), Snapshot.class).toResult();
        }
        case Claim.InFlight inFlight -> {
          requireSameRequest(inFlight.fingerprint(), fingerprint);
          if (System.nanoTime() >= deadline) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, "相同的请求仍在处理中，请稍后重试");
          }
          pause();
        }
      }
    }
  }

  private GetUserUseCase.Result createAndRemember(
      CreateUserCommand command, String scope, String key, String fingerprint) {
    Provisioned created;
    try {
      created =
          provisioning.createUserWithAccount(
              command.userName(), command.accountName(), command.password());
    } catch (RuntimeException e) {
      // 只有提交之前的失败才释放占位，同键的修正重试可以重新执行
      idempotency.release(scope, key);
      throw e;
    }
    // 已提交：之后的任何问题都不能释放占位，也不能让请求失败（否则同键重试会再建一个用户）
    GetUserUseCase.Result result = resultAfterCommit(created);
    remember(scope, key, fingerprint, result);
    record(command, Outcome.SUCCESS, created.userId().toString(), null);
    return result;
  }

  /** 尽力重新读取；读取失败就用刚创建的实体拼出同样的结果。 */
  private GetUserUseCase.Result resultAfterCommit(Provisioned created) {
    try {
      return getUser.get(created.userId());
    } catch (RuntimeException e) {
      warn(READ_BACK_FAILED, e);
      return new GetUserUseCase.Result(created.user(), List.of(created.account()));
    }
  }

  /** 尽力保存响应快照；失败时保留「进行中」标记让它自然过期（30 秒），不释放。 */
  private void remember(
      String scope, String key, String fingerprint, GetUserUseCase.Result result) {
    try {
      idempotency.complete(scope, key, fingerprint, json.writeValueAsString(Snapshot.of(result)));
    } catch (RuntimeException e) {
      warn(COMPLETE_FAILED, e);
    }
  }

  /** 一条结构化警告：只含事件名与异常类型，不含标识、幂等键与密码。 */
  private static void warn(String event, RuntimeException e) {
    log.atWarn().addKeyValue("exception", e.getClass().getSimpleName()).log(event);
  }

  private static String validKey(String key) {
    String reason = null;
    if (key == null || key.isBlank()) {
      reason = "required";
    } else if (!KEY_CHARS.matcher(key).matches()) {
      reason = "invalid_chars";
    } else if (key.length() < KEY_MIN_LENGTH) {
      reason = "too_short";
    } else if (key.length() > KEY_MAX_LENGTH) {
      reason = "too_long";
    }
    if (reason != null) {
      throw DomainException.validationFailed(List.of(new FieldError(KEY_FIELD, reason)));
    }
    return key;
  }

  private static void requireSameRequest(String storedFingerprint, String fingerprint) {
    if (!storedFingerprint.equals(fingerprint)) {
      throw DomainException.validationFailed(List.of(new FieldError(KEY_FIELD, "reused")));
    }
  }

  private void pause() {
    try {
      Thread.sleep(poll);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待同键请求时被中断", e);
    }
  }

  private void record(CreateUserCommand command, Outcome outcome, String targetId, String reason) {
    writeLog.record(
        new WriteOperationEvent(
            command.operator(),
            command.clientId(),
            Action.USER_CREATE,
            "user",
            targetId,
            outcome,
            reason));
  }
}
