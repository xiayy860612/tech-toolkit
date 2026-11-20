package tech.toolkit.tenantcore.support;

import java.net.InetAddress;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.toolkit.tenantcore.auth.LoginType;
import tech.toolkit.tenantcore.auth.SessionService;
import tech.toolkit.tenantcore.platform.TenantContext;

/** 测试夹具：为夹具用户直接开一个会话，并签发相应的用户内部 JWT（相当于 BFF 解析会话后签发的令牌）。 */
@Component
public class TestSessions {

  /** {@code jwt} 即 BFF 会为该会话签发的 typ=user 令牌。 */
  public record Open(String jwt, UUID sessionId, Long userId, long tenantId) {

    public String bearer() {
      return "Bearer " + jwt;
    }
  }

  private final SessionService sessions;
  private final TransactionTemplate tx;

  TestSessions(SessionService sessions, TransactionTemplate tx) {
    this.sessions = sessions;
    this.tx = tx;
  }

  public Open open(long tenant, TestAccounts.Created account) {
    return open(tenant, account, "Mozilla/5.0 (Macintosh) Chrome/130.0.0.0");
  }

  public Open open(long tenant, TestAccounts.Created account, String userAgent) {
    return TenantContext.callAs(
        tenant,
        () ->
            tx.execute(
                s -> {
                  try {
                    var created =
                        sessions.create(
                            account.user().getId(),
                            LoginType.USERNAME_PASSWORD,
                            InetAddress.getByName("203.0.113.5"),
                            userAgent);
                    UUID sid = created.session().getId();
                    String jwt = TestJwt.user(account.user().getId(), sid, tenant).sign();
                    return new Open(jwt, sid, account.user().getId(), tenant);
                  } catch (java.net.UnknownHostException e) {
                    throw new IllegalStateException(e);
                  }
                }));
  }
}
