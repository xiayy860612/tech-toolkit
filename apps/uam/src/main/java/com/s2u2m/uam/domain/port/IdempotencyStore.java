package com.s2u2m.uam.domain.port;

import java.time.Duration;

/**
 * 幂等存储（FR-022）：按「范围（调用方客户端）+ 幂等键」抢占一个位置，完成后保存响应快照，窗口内同键重放返回原响应。
 *
 * <p>快照是不透明字符串（调用方序列化，且不得含密码或哈希）；{@code fingerprint} 是请求的指纹，用来识别「同键不同请求」。
 */
public interface IdempotencyStore {

  /** 重放窗口：快照保留 10 分钟。 */
  Duration REPLAY_WINDOW = Duration.ofMinutes(10);

  /** 进行中标记的存活时间：占位方崩溃时，标记很快自行消失，不会把同键请求挡满 10 分钟。 */
  Duration IN_FLIGHT_TTL = Duration.ofSeconds(30);

  /** 抢占结果。 */
  sealed interface Claim {

    /** 抢到了：调用方应执行操作，然后 {@link #complete} 或 {@link #release}。 */
    record Acquired() implements Claim {}

    /** 别的请求正在执行同一个键。 */
    record InFlight(String fingerprint) implements Claim {}

    /** 窗口内已完成：{@code response} 是原响应快照。 */
    record Completed(String fingerprint, String response) implements Claim {}
  }

  /** 原子抢占（{@code SET NX}）；已被占用时返回当前状态。 */
  Claim claim(String scope, String key, String fingerprint);

  /** 保存响应快照，窗口为 {@link #REPLAY_WINDOW}。 */
  void complete(String scope, String key, String fingerprint, String response);

  /** 放弃占位（操作失败时），让同键的修正重试可以重新执行。 */
  void release(String scope, String key);
}
