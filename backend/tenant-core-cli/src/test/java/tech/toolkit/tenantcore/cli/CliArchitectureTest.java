package tech.toolkit.tenantcore.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import tech.toolkit.tenantcore.architecture.SecretToStringRule;

/** Guards the Bootstrap CLI: commands use services, never Repositories or the web stack. */
@AnalyzeClasses(
    packages = "tech.toolkit.tenantcore.cli",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class CliArchitectureTest {

  @ArchTest
  static final ArchRule commandsNeverTouchRepositories =
      noClasses()
          .that()
          .resideInAPackage("tech.toolkit.tenantcore.cli..")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith("Repository");

  @ArchTest
  static final ArchRule cliDoesNotDependOnTheWebStack =
      noClasses()
          .that()
          .resideInAPackage("tech.toolkit.tenantcore.cli..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "tech.toolkit.tenantcore.api..",
              "org.springframework.web..",
              "org.springframework.http..",
              "jakarta.servlet..");

  @ArchTest
  static final ArchRule onlyTheMainClassTouchesStandardStreams =
      noClasses()
          .that()
          .doNotHaveSimpleName("TenantCoreCliMain")
          .and()
          .doNotHaveSimpleName("TenantCoreCli")
          .should(com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS)
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule secretCarryingClassesHaveASafeToString =
      SecretToStringRule.RULE.allowEmptyShould(true);
}
