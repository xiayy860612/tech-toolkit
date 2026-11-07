package tech.toolkit.tenantcore.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import tech.toolkit.tenantcore.architecture.SecretToStringRule;

/** Guards the HTTP layer: it talks to services only, never to Repositories or the CLI. */
@AnalyzeClasses(
    packages = "tech.toolkit.tenantcore.api",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ApiArchitectureTest {

  @ArchTest
  static final ArchRule apiNeverTouchesRepositories =
      noClasses()
          .that()
          .resideInAPackage("tech.toolkit.tenantcore.api..")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith("Repository");

  @ArchTest
  static final ArchRule apiDoesNotDependOnTheCli =
      noClasses()
          .that()
          .resideInAPackage("tech.toolkit.tenantcore.api..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("tech.toolkit.tenantcore.cli..", "picocli..");

  @ArchTest
  static final ArchRule controllersLiveInApiPackages =
      classes()
          .that()
          .haveSimpleNameEndingWith("Controller")
          .should()
          .resideInAPackage("tech.toolkit.tenantcore.api..");

  @ArchTest
  static final ArchRule noStandardStreams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule noFieldInjection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

  @ArchTest static final ArchRule secretCarryingDtosHaveASafeToString = SecretToStringRule.RULE;
}
