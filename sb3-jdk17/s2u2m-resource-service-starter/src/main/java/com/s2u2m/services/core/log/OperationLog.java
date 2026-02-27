package com.s2u2m.services.core.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.logging.LogLevel;

/**
 * 业务操作日志注解
 * 用于标记需要记录业务日志的方法
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

  /**
   * SpEL 表达式列表，用于从方法参数中提取要记录的值
   * 示例：{"#user.username", "#user.email", "#role", "#id"}
   */
  String[] args() default {};

  /**
   * 是否记录返回值
   */
  boolean logResult() default false;

  /**
   * 日志级别
   */
  LogLevel level() default LogLevel.INFO;

}
