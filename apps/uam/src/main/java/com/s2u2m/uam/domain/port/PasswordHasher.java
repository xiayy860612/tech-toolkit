package com.s2u2m.uam.domain.port;

/** 密码哈希（Argon2id，可升级）。 */
public interface PasswordHasher {

  String hash(String rawPassword);

  boolean matches(String rawPassword, String passwordHash);

  /** 哈希的算法或参数落后于当前配置，登录成功后应重新哈希。 */
  boolean needsUpgrade(String passwordHash);

  /** 固定的伪哈希：账号不存在时也做一次同成本的比对，使耗时与「密码错误」无差别。 */
  String dummyHash();
}
