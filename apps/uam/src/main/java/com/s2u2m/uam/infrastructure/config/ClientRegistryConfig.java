package com.s2u2m.uam.infrastructure.config;

import com.s2u2m.uam.infrastructure.security.ClientRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** 从 {@code uam.clients}（环境变量 {@code UAM_CLIENTS}）装配客户端清单；配置不合法时启动失败。 */
@Configuration(proxyBeanMethods = false)
public class ClientRegistryConfig {

  @Bean
  ClientRegistry clientRegistry(@Value("${uam.clients}") String clientsJson, JsonMapper json) {
    return ClientRegistry.fromJson(json, clientsJson);
  }
}
