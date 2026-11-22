package tech.toolkit.auth.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.springframework.beans.factory.annotation.Autowired;

@AnalyzeClasses(
    packages = "tech.toolkit.auth",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule packagesAreFreeOfCycles =
      slices().matching("tech.toolkit.auth.(*)..").should().beFreeOfCycles().allowEmptyShould(true);

  @ArchTest
  static final ArchRule noStandardStreams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.allowEmptyShould(true);

  @ArchTest
  static final ArchRule noFieldInjection =
      noFields().should().beAnnotatedWith(Autowired.class).allowEmptyShould(true);

  /**
   * A class holding a secret-looking field must declare its own non-final toString (redacting it).
   */
  @ArchTest
  static final ArchRule secretCarryingClassesDeclareToString =
      classes()
          .that(haveSecretLookingField())
          .and()
          .areNotInterfaces()
          .should(declareNonFinalToString())
          .allowEmptyShould(true);

  private static DescribedPredicate<JavaClass> haveSecretLookingField() {
    return new DescribedPredicate<>("have a field named like a secret") {
      @Override
      public boolean test(JavaClass javaClass) {
        // instance fields only: static constants such as a SECRET_KEY pattern hold no secret
        return javaClass.getFields().stream()
            .filter(f -> !f.getModifiers().contains(JavaModifier.STATIC))
            .anyMatch(f -> f.getName().matches("(?i).*(password|secret|privatekey|hash).*"));
      }
    };
  }

  private static ArchCondition<JavaClass> declareNonFinalToString() {
    return new ArchCondition<>("declare a non-final toString()") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        boolean declared =
            javaClass.getMethods().stream()
                .anyMatch(
                    m ->
                        m.getName().equals("toString")
                            && m.getRawParameterTypes().isEmpty()
                            && !m.getModifiers().contains(JavaModifier.FINAL));
        events.add(
            new SimpleConditionEvent(
                javaClass,
                declared,
                javaClass.getName() + (declared ? " declares" : " lacks") + " toString()"));
      }
    };
  }
}
