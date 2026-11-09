package tech.toolkit.tenantcore.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.regex.Pattern;

/**
 * A class that holds a password, token, JWT, PEM, hash or secret as a String must declare its own
 * non-final toString(); a record's generated toString() is final and would print the value.
 */
public final class SecretToStringRule {

  private static final Pattern SECRET_NAME =
      Pattern.compile(".*(password|token|jwt|pem|hash|secret).*", Pattern.CASE_INSENSITIVE);

  public static final ArchRule RULE =
      classes()
          .that(holdASecretString())
          .should(declareANonFinalToString())
          .because("generated toString() would leak secrets into logs");

  private SecretToStringRule() {}

  private static DescribedPredicate<JavaClass> holdASecretString() {
    return new DescribedPredicate<>("hold a secret-looking String field") {
      @Override
      public boolean test(JavaClass type) {
        return type.getFields().stream().anyMatch(SecretToStringRule::isSecretString)
            && !type.isInterface()
            && !type.isEnum();
      }
    };
  }

  private static boolean isSecretString(JavaField field) {
    return field.getRawType().isEquivalentTo(String.class)
        && !field.getModifiers().contains(JavaModifier.STATIC)
        && SECRET_NAME.matcher(field.getName()).matches();
  }

  private static ArchCondition<JavaClass> declareANonFinalToString() {
    return new ArchCondition<>("declare a non-final toString()") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        boolean ok =
            type.getMethods().stream()
                .anyMatch(
                    m ->
                        m.getName().equals("toString")
                            && m.getRawParameterTypes().isEmpty()
                            && !m.getModifiers().contains(JavaModifier.FINAL));
        if (!ok) {
          events.add(
              SimpleConditionEvent.violated(
                  type, type.getName() + " has no explicit non-final toString()"));
        }
      }
    };
  }
}
