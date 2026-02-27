package com.s2u2m.services.core.log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.logging.LogLevel;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 业务操作日志切面
 * 拦截带有 @OperationLog 注解的方法，自动记录业务日志
 */
@Slf4j
@Aspect
@Component
@Order(1)
@ConditionalOnMissingBean(OperationLogAspect.class)
public class OperationLogAspect {

  @Resource
  private ObjectMapper objectMapper;

  @Resource
  private OperationLogProperties operationLogProperties;

  private final ExpressionParser parser = new SpelExpressionParser();
  private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

  /**
   * 定义切点：拦截所有带有 @OperationLog 注解的方法
   */
  @Pointcut("@annotation(com.s2u2m.services.core.log.OperationLog)")
  public void operationLogPointcut() {
    // Pointcut definition
  }

  /**
   * 环绕通知：拦截方法执行并记录业务日志
   */
  @Around("operationLogPointcut()")
  public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();
    OperationLog annotation = method.getAnnotation(OperationLog.class);

    if (annotation == null) {
      return joinPoint.proceed();
    }

    long startTime = System.currentTimeMillis();
    LogContext context = buildLogContext(joinPoint, method, annotation);

    // 记录方法开始日志
    logMethodStart(context);

    Object result = null;
    Throwable exception = null;

    try {
      result = joinPoint.proceed();
      return result;
    } catch (Throwable e) {
      exception = e;
      throw e;
    } finally {
      long costTime = System.currentTimeMillis() - startTime;
      logMethodEnd(context, result, exception, costTime);
    }
  }

  /**
   * 构建日志上下文
   */
  private LogContext buildLogContext(ProceedingJoinPoint joinPoint, Method method, OperationLog annotation) {
    LogContext context = new LogContext();
    context.className = joinPoint.getTarget().getClass().getSimpleName();
    context.methodName = method.getName();
    context.logResult = annotation.logResult();
    context.level = annotation.level();

    // 解析 SpEL 表达式
    String[] expresses = annotation.args();
    if (expresses.length > 0) {
      context.args = parseSpELExpressions(joinPoint, method, expresses);
    }

    return context;
  }

  /**
   * 解析 SpEL 表达式
   */
  private Map<String, Object> parseSpELExpressions(
      ProceedingJoinPoint joinPoint, Method method, String[] expressions) {
    Map<String, Object> result = new HashMap<>();

    // 获取参数名和参数值
    String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
    Object[] args = joinPoint.getArgs();

    // 创建 SpEL 评估上下文
    EvaluationContext evaluationContext = new StandardEvaluationContext();
    if (paramNames != null) {
      for (int i = 0; i < paramNames.length; i++) {
        evaluationContext.setVariable(paramNames[i], args[i]);
      }
    }

    // 解析每个表达式
    for (String expression : expressions) {
      String expr = expression.trim();
      if (expr.isEmpty()) {
        continue;
      }

      try {
        Expression exp = parser.parseExpression(expr);
        Object value = exp.getValue(evaluationContext);
        // 使用表达式作为 key（去掉 # 前缀）
        String key = expr.startsWith("#") ? expr.substring(1) : expr;

        // 检查是否为敏感字段
        if (isSensitiveField(key)) {
          result.put(key, operationLogProperties.getMask());
        } else {
          result.put(key, value);
        }
      } catch (Exception e) {
        log.warn("SpEL 表达式解析失败: {}, 错误: {}", expr, e.getMessage());
        result.put(expr, "{解析失败}");
      }
    }

    return result;
  }

  /**
   * 判断字段是否为敏感字段
   */
  private boolean isSensitiveField(String fieldName) {
    if (fieldName == null || fieldName.isEmpty()) {
      return false;
    }

    String lowerFieldName = fieldName.toLowerCase();
    return operationLogProperties.getSensitiveFields().stream()
        .anyMatch(lowerFieldName::contains);
  }

  /**
   * 记录方法开始日志
   */
  private void logMethodStart(LogContext context) {
    StringBuilder logBuilder = new StringBuilder();
    logBuilder.append("[方法开始] ");
    logBuilder.append(context.className).append(".").append(context.methodName);
    if (context.args != null && !context.args.isEmpty()) {
      logBuilder.append(" | 参数: ").append(serializeArgs(context.args));
    }

    logWithLevel(context.level, logBuilder.toString());
  }

  /**
   * 记录方法结束日志
   */
  private void logMethodEnd(LogContext context, Object result, Throwable exception, long costTime) {
    StringBuilder logBuilder = new StringBuilder();
    if (exception != null) {
      logBuilder.append("[方法异常] ");
    } else {
      logBuilder.append("[方法完成] ");
    }
    logBuilder.append(context.className).append(".").append(context.methodName);
    logBuilder.append(" | 耗时: ").append(costTime).append("ms");

    if (context.logResult && result != null && exception == null) {
      logBuilder.append(" | 结果: ").append(serializeResult(result));
    }

    if (exception != null) {
      logBuilder.append(" | 异常: ").append(exception.getMessage());
    }

    LogLevel logLevel = exception != null ? LogLevel.ERROR : context.level;
    logWithLevel(logLevel, logBuilder.toString());
  }

  /**
   * 序列化参数
   */
  private String serializeArgs(Map<String, Object> args) {
    if (args == null || args.isEmpty()) {
      return "{}";
    }

    try {
      return objectMapper.writeValueAsString(args);
    } catch (JsonProcessingException e) {
      return "{序列化失败: " + e.getMessage() + "}";
    }
  }

  /**
   * 序列化返回值
   */
  private String serializeResult(Object result) {
    try {
      return objectMapper.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      return "{序列化失败: " + e.getMessage() + "}";
    }
  }

  /**
   * 根据指定级别记录日志
   */
  private void logWithLevel(LogLevel level, String message) {
    switch (level) {
      case DEBUG:
        if (log.isDebugEnabled()) {
          log.debug(message);
        }
        break;
      case WARN:
        log.warn(message);
        break;
      case ERROR:
        log.error(message);
        break;
      default:
        log.info(message);
        break;
    }
  }

  /**
   * 日志上下文内部类
   */
  private static class LogContext {
    String className;
    String methodName;
    boolean logResult;
    LogLevel level;
    Map<String, Object> args;
  }
}
