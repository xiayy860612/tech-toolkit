package com.s2u2m.uam.domain.port;

import java.util.function.Supplier;

/** 事务边界：{@code work} 在同一个数据库事务里执行，抛出运行时异常则整体回滚。 */
public interface TransactionRunner {

  <T> T inTransaction(Supplier<T> work);
}
