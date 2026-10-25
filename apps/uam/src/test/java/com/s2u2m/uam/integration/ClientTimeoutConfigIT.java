package com.s2u2m.uam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.support.IntegrationTestBase;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.ClientOptions;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** Redis 与连接池的客户端超时来自 {@code application.yml} 并真正生效（不发真实网络故障，只核对绑定结果）。 */
class ClientTimeoutConfigIT extends IntegrationTestBase {

  @Autowired RedisConnectionFactory redisConnectionFactory;
  @Autowired DataSource dataSource;

  @Test
  void redisCommandAndConnectTimeoutsAreOneSecond() {
    LettuceConnectionFactory lettuce = (LettuceConnectionFactory) redisConnectionFactory;

    assertThat(lettuce.getClientConfiguration().getCommandTimeout())
        .isEqualTo(Duration.ofSeconds(1));
    ClientOptions options = lettuce.getClientConfiguration().getClientOptions().orElseThrow();
    assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void databaseConnectionAcquisitionTimesOutAfterOneSecond() {
    assertThat(((HikariDataSource) dataSource).getConnectionTimeout()).isEqualTo(1_000L);
  }
}
