package tech.toolkit.tenantcore.platform;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

/**
 * 每个事务开始时把租户上下文写入事务级设置 {@code app.tenant_id}（{@code set_config(..., true)}，随事务
 * 结束自动失效，不会泄漏到连接池里的下一个事务）。未设置上下文时写入空串，RLS 策略此时匹配不到任何行。
 */
public class TenantScopedTransactionManager extends JpaTransactionManager {

  public TenantScopedTransactionManager(EntityManagerFactory emf) {
    super(emf);
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);
    DataSource dataSource = getDataSource();
    if (dataSource == null) {
      throw new CannotCreateTransactionException("JPA transaction has no DataSource to scope");
    }
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try (PreparedStatement ps =
        connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
      ps.setString(1, TenantContext.current().map(String::valueOf).orElse(""));
      ps.execute();
    } catch (SQLException e) {
      throw new CannotCreateTransactionException("Cannot set tenant for transaction", e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }
}
