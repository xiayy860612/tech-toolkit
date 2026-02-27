package com.s2u2m.services.core.log;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 日志模块配置
 * 自动装配请求追踪和业务日志功能
 */
@Configuration
@Import({
    RequestTrackingFilter.class,
    OperationLogAspect.class
})
@EnableConfigurationProperties(OperationLogProperties.class)
public class LogConfig {
}
