package tech.toolkit.tenantcore.platform;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/** 系统生成的 16 位临时密码，保证包含四类字符；只在响应中返回一次（FR-011a）。 */
@Component
public class TemporaryPasswordGenerator {

  private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
  private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
  private static final String DIGIT = "23456789";
  private static final String SYMBOL = "!@#$%^&*-_=+";
  private static final String ALL = UPPER + LOWER + DIGIT + SYMBOL;
  private static final int LENGTH = 16;

  private final SecureRandom random = new SecureRandom();

  public String generate() {
    List<Character> chars = new ArrayList<>(LENGTH);
    for (String set : new String[] {UPPER, LOWER, DIGIT, SYMBOL}) {
      chars.add(pick(set));
    }
    while (chars.size() < LENGTH) {
      chars.add(pick(ALL));
    }
    Collections.shuffle(chars, random);
    StringBuilder sb = new StringBuilder(LENGTH);
    chars.forEach(sb::append);
    return sb.toString();
  }

  private char pick(String set) {
    return set.charAt(random.nextInt(set.length()));
  }
}
