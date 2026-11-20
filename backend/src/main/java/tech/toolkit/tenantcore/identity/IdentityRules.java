package tech.toolkit.tenantcore.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ApiException.FieldError;
import tech.toolkit.tenantcore.platform.ErrorCode;

/** User / Account 的字段规则（与 contracts/openapi.yaml 一致）；DTO 的校验注解与服务层共用这些常量。 */
public final class IdentityRules {

  public static final String USERNAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$";
  public static final String PHONE_PATTERN = "^1[3-9]\\d{9}$";
  public static final int DISPLAY_NAME_MAX = 50;

  private static final Pattern USERNAME = Pattern.compile(USERNAME_PATTERN);
  private static final Pattern PHONE = Pattern.compile(PHONE_PATTERN);
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  private IdentityRules() {}

  public static List<FieldError> userFields(String displayName, String phone, String email) {
    List<FieldError> errors = new ArrayList<>();
    if (displayName == null || displayName.isBlank() || displayName.length() > DISPLAY_NAME_MAX) {
      errors.add(new FieldError("displayName", "姓名必填，且不超过 " + DISPLAY_NAME_MAX + " 个字符"));
    }
    if (phone != null && !PHONE.matcher(phone).matches()) {
      errors.add(new FieldError("phone", "手机号格式不正确"));
    }
    if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) {
      errors.add(new FieldError("email", "邮箱格式不正确"));
    }
    return errors;
  }

  public static FieldError usernameField(String field, String username) {
    if (username == null || !USERNAME.matcher(username).matches()) {
      return new FieldError(field, "用户名为 3–32 位字母、数字、点、下划线或连字符，且以字母或数字开头");
    }
    return null;
  }

  public static void throwIfAny(List<FieldError> errors) {
    if (!errors.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.title(), errors);
    }
  }
}
