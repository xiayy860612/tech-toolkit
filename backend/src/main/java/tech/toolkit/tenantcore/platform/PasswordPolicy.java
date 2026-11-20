package tech.toolkit.tenantcore.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 密码复杂度（FR-011）：长度 ≥ 10；大写/小写/数字/符号至少 3 类；不含用户名；不得等于指定的禁用值。 */
@Component
public class PasswordPolicy {

  public static final int MIN_LENGTH = 10;

  public List<String> violations(String password, String username, Set<String> mustDifferFrom) {
    List<String> problems = new ArrayList<>();
    if (password == null || password.length() < MIN_LENGTH) {
      problems.add("密码长度至少 " + MIN_LENGTH + " 位");
      return problems;
    }
    int classes = 0;
    classes += password.chars().anyMatch(Character::isUpperCase) ? 1 : 0;
    classes += password.chars().anyMatch(Character::isLowerCase) ? 1 : 0;
    classes += password.chars().anyMatch(Character::isDigit) ? 1 : 0;
    classes += password.chars().anyMatch(c -> !Character.isLetterOrDigit(c)) ? 1 : 0;
    if (classes < 3) {
      problems.add("密码需包含大写字母、小写字母、数字、符号中的至少 3 类");
    }
    if (username != null
        && !username.isBlank()
        && password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
      problems.add("密码不得包含用户名");
    }
    if (mustDifferFrom != null && mustDifferFrom.contains(password)) {
      problems.add("新密码不得与当前密码或临时密码相同");
    }
    return problems;
  }
}
