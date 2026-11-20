package tech.toolkit.tenantcore;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
}
