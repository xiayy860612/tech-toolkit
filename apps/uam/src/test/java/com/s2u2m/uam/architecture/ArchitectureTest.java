package com.s2u2m.uam.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** T015：守住分层 {@code api → application → domain ← infrastructure}。 */
@AnalyzeClasses(packages = "com.s2u2m.uam", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String API = "com.s2u2m.uam.api..";
  private static final String APPLICATION = "com.s2u2m.uam.application..";
  private static final String DOMAIN = "com.s2u2m.uam.domain..";
  private static final String INFRASTRUCTURE = "com.s2u2m.uam.infrastructure..";
  private static final String CLI = "com.s2u2m.uam.cli..";

  @ArchTest
  static final ArchRule layerDependencies =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .withOptionalLayers(true)
          .layer("Api")
          .definedBy(API)
          .layer("Application")
          .definedBy(APPLICATION)
          .layer("Domain")
          .definedBy(DOMAIN)
          .layer("Infrastructure")
          .definedBy(INFRASTRUCTURE)
          .layer("Cli")
          .definedBy(CLI)
          .whereLayer("Api")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Cli")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("Api", "Cli")
          .whereLayer("Infrastructure")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Domain")
          .mayOnlyBeAccessedByLayers("Api", "Application", "Infrastructure", "Cli");

  @ArchTest
  static final ArchRule domainDependsOnNothingButJdkAndItself =
      classes()
          .that()
          .resideInAPackage(DOMAIN)
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("java..", DOMAIN)
          .allowEmptyShould(true)
          .because("domain 不依赖 Spring 与任何框架");

  @ArchTest
  static final ArchRule domainDoesNotUseSpring =
      noClasses()
          .that()
          .resideInAPackage(DOMAIN)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "jakarta..", "tools.jackson..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule noCallerAuthenticationClasses =
      noClasses()
          .should()
          .haveSimpleNameStartingWith("Client")
          .allowEmptyShould(true)
          .because("UAM 不认证调用方（clients 功能已移除），服务身份后续另行实现");

  @ArchTest
  static final ArchRule apiDoesNotTouchInfrastructure =
      noClasses()
          .that()
          .resideInAPackage(API)
          .should()
          .dependOnClassesThat()
          .resideInAPackage(INFRASTRUCTURE)
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule cliOnlyDependsOnApplication =
      noClasses()
          .that()
          .resideInAPackage(CLI)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(API, INFRASTRUCTURE)
          .allowEmptyShould(true)
          .because("cli 只依赖 application（经 application 间接使用 domain）");
}
