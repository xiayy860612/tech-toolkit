package com.s2u2m.services.core.log;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务日志配置属性
 */
@ConfigurationProperties(prefix = "s2u2m.operation-log")
@Setter
@Getter
public class OperationLogProperties {

  /**
   * 敏感字段列表，包含这些词的字段会被脱敏
   */
  private List<String> sensitiveFields = new ArrayList<>();

  /**
   * 脱敏掩码
   */
  private String mask = "******";

}
