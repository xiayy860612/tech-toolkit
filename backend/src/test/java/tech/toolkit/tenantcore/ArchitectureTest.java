package tech.toolkit.tenantcore;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.Parameter;
import java.util.Locale;

@AnalyzeClasses(
    packages = "tech.toolkit.tenantcore",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule business_packages_do_not_depend_on_api =
      noClasses()
          .that()
          .resideInAnyPackage(
              "tech.toolkit.tenantcore.identity..",
              "tech.toolkit.tenantcore.auth..",
              "tech.toolkit.tenantcore.audit..",
              "tech.toolkit.tenantcore.platform..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("tech.toolkit.tenantcore.api..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule bootstrap_does_not_depend_on_api =
      noClasses()
          .that()
          .resideInAPackage("tech.toolkit.tenantcore.bootstrap..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("tech.toolkit.tenantcore.api..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule api_does_not_depend_on_bootstrap =
      noClasses()
          .that()
          .resideInAPackage("tech.toolkit.tenantcore.api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("tech.toolkit.tenantcore.bootstrap..")
          .allowEmptyShould(true);

  /** FR-025：租户只来自已验签 JWT；控制器方法参数与请求体不得携带名为 tenantId 的授权依据。 */
  @ArchTest
  static final ArchRule controllers_take_no_tenant_id_input =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
          .should(notAcceptTenantIdentifiers())
          .allowEmptyShould(true);

  private static ArchCondition<JavaMethod> notAcceptTenantIdentifiers() {
    return new ArchCondition<>("not accept a tenant identifier as input") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        for (Parameter p : method.reflect().getParameters()) {
          if (isTenantName(p.getName())) {
            events.add(
                SimpleConditionEvent.violated(
                    method, method.getFullName() + " parameter " + p.getName()));
          }
          for (var a : p.getAnnotations()) {
            String text = a.toString().toLowerCase(Locale.ROOT);
            if (text.contains("tenant")) {
              events.add(
                  SimpleConditionEvent.violated(method, method.getFullName() + " binds " + text));
            }
          }
        }
        for (JavaClass type : method.getRawParameterTypes()) {
          type.getAllFields().stream()
              .filter(f -> isTenantName(f.getName()))
              .forEach(
                  f ->
                      events.add(
                          SimpleConditionEvent.violated(
                              method, type.getName() + "." + f.getName())));
        }
      }
    };
  }

  private static boolean isTenantName(String name) {
    return name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").contains("tenantid");
  }
}
