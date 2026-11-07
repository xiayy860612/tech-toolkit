package tech.toolkit.tenantcore.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

/** Guards the Java package boundaries of core-lib (tenant, uam, audit, common). */
@AnalyzeClasses(
    packages = "tech.toolkit.tenantcore",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

  private static final String ROOT = "tech.toolkit.tenantcore";

  @ArchTest
  static final ArchRule packagesAreFreeOfCycles =
      slices().matching(ROOT + ".(tenant|uam|audit|common)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule tenantDoesNotDependOnUam =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".tenant..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage(ROOT + ".uam..");

  @ArchTest
  static final ArchRule auditAndCommonDoNotDependOnBusinessPackages =
      noClasses()
          .that()
          .resideInAnyPackage(ROOT + ".audit..", ROOT + ".common..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(ROOT + ".tenant..", ROOT + ".uam..");

  @ArchTest
  static final ArchRule repositoriesAreOnlyUsedInsideTheirOwnPackage =
      classes()
          .that()
          .haveSimpleNameEndingWith("Repository")
          .should()
          .onlyBeAccessed()
          .byClassesThat(
              resideInAPackage("..tenant..")
                  .or(resideInAPackage("..uam.."))
                  .or(resideInAPackage("..audit.."))
                  .or(
                      com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName(
                          "CoreComponents")))
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule tenantRepositoriesStayInsideTenant =
      classes()
          .that()
          .resideInAPackage(ROOT + ".tenant..")
          .and()
          .haveSimpleNameEndingWith("Repository")
          .should()
          .onlyBeAccessed()
          .byClassesThat(
              resideInAPackage(ROOT + ".tenant..")
                  .or(
                      com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName(
                          "CoreComponents")));

  @ArchTest
  static final ArchRule uamRepositoriesStayInsideUam =
      classes()
          .that()
          .resideInAPackage(ROOT + ".uam..")
          .and()
          .haveSimpleNameEndingWith("Repository")
          .should()
          .onlyBeAccessed()
          .byClassesThat(
              resideInAPackage(ROOT + ".uam..")
                  .or(
                      com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName(
                          "CoreComponents")));

  @ArchTest
  static final ArchRule coreLibHasNoWebOrCliDependencies =
      noClasses()
          .that()
          .resideInAPackage(ROOT + "..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.web..",
              "org.springframework.http..",
              "jakarta.servlet..",
              "picocli..",
              ROOT + ".api..",
              ROOT + ".cli..");

  @ArchTest
  static final ArchRule noStandardStreams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule noJavaUtilLogging =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  @ArchTest
  static final ArchRule noFieldInjection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

  @ArchTest
  static final ArchRule moduleNamingIsReservedForModuleSubscription =
      classes()
          .that()
          .haveSimpleNameContaining("Module")
          .should()
          .haveSimpleNameStartingWith("ModuleSubscription");

  @ArchTest
  static final ArchRule noSynonymsOfTheUbiquitousLanguage =
      noClasses()
          .should()
          .haveNameMatching(".*\\.[^.]*(Customer|Organization|Organisation|Member|Profile)[^.]*");

  @ArchTest
  static final ArchRule secretCarryingClassesDeclareASafeToString = SecretToStringRule.RULE;
}
