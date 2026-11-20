package tech.toolkit.tenantcore.identity;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tech.toolkit.tenantcore.platform.ApiException;
import tech.toolkit.tenantcore.platform.ApiException.FieldError;
import tech.toolkit.tenantcore.platform.ErrorCode;

/** User 的字段规则（与 contracts/openapi.yaml 一致）；DTO 的校验注解与服务层共用这些常量。 */
public final class IdentityRules {

  public static final String USERNAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$";
  public static final int DISPLAY_NAME_MAX = 50;

  private static final Pattern USERNAME = Pattern.compile(USERNAME_PATTERN);

  private IdentityRules() {}

  /** 用户名规范化：NFKC + 小写，租户内大小写不敏感唯一（FR-014）。 */
  public static String normalizeUsername(String username) {
    return Normalizer.normalize(username, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  public static List<FieldError> userFields(String displayName) {
    List<FieldError> errors = new ArrayList<>();
    if (displayName == null || displayName.isBlank() || displayName.length() > DISPLAY_NAME_MAX) {
      errors.add(new FieldError("displayName", "姓名必填，且不超过 " + DISPLAY_NAME_MAX + " 个字符"));
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
