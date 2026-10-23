package com.s2u2m.uam.infrastructure.jdbc;

import com.s2u2m.uam.domain.port.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 基于 Spring 事务模板的 {@link TransactionRunner}：运行时异常回滚。 */
@Component
public class JdbcTransactionRunner implements TransactionRunner {

  private final TransactionTemplate template;

  public JdbcTransactionRunner(PlatformTransactionManager transactionManager) {
    this.template = new TransactionTemplate(transactionManager);
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
