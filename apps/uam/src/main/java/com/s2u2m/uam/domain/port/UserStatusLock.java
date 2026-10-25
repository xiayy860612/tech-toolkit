package com.s2u2m.uam.domain.port;

/**
 * 用户状态变更锁：串行化「会改变正常用户数」的操作（停用），使「最后一个正常用户」的校验在并发下也成立（research R15）。
 *
 * <p>单一全局锁即可；必须在 {@link TransactionRunner#inTransaction} 内调用，锁随事务提交或回滚自动释放。
 */
public interface UserStatusLock {

  /** 取得锁，别的事务持有时阻塞，直到对方提交或回滚。 */
  void lockStatusChanges();
}
