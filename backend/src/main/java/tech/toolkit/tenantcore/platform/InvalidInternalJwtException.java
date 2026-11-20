package tech.toolkit.tenantcore.platform;

/** 内部 JWT 缺失、格式错误、签名无效、已过期或缺少必需声明。 */
public class InvalidInternalJwtException extends RuntimeException {

  public InvalidInternalJwtException(String message) {
    super(message);
  }

  public InvalidInternalJwtException(String message, Throwable cause) {
    super(message, cause);
  }
}
