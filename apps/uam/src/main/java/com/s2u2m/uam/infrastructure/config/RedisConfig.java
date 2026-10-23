package com.s2u2m.uam.infrastructure.config;

import com.s2u2m.uam.infrastructure.redis.ReplicaWait;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 装配：Lettuce、单主（不使用 Cluster，research R7），连接参数来自 {@code spring.data.redis.*}。 撤销类写入后的 {@code
 * WAIT 1 <短超时>} 辅助方法见 {@link ReplicaWait}。
 */
@Configuration(proxyBeanMethods = false)
@Profile("!cli")
public class RedisConfig {

  @Bean
  ReplicaWait replicaWait(StringRedisTemplate redis) {
    return new ReplicaWait(redis);
  }
}
