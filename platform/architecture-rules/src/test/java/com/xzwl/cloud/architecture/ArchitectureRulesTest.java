package com.xzwl.cloud.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class ArchitectureRulesTest {

    private static final String FIXTURES = "com.xzwl.cloud.architecture.fixtures";

    @Test
    void deliberateDomainFixtureMustNotDependOnSpring() {
        JavaClasses classes = importFixture("deliberate");

        ForbiddenTechnologyRules.domainMustNotDependOnForbiddenTechnologies(
                        FIXTURES + ".deliberate")
                .check(classes);
    }

    @Test
    void compliantSixLayerFixturePassesEveryRule() {
        JavaClasses classes = importFixture("compliant");

        assertThatCode(() -> DddLayerRules.all(FIXTURES + ".compliant").check(classes))
                .doesNotThrowAnyException();
        assertThatCode(() -> ForbiddenTechnologyRules
                        .domainMustNotDependOnForbiddenTechnologies(FIXTURES + ".compliant")
                        .check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void domainRejectsEveryForbiddenTechnologyPrefixIncludingAnnotationMetadata() {
        JavaClasses classes = importFixture("forbidden");

        assertThatThrownBy(() -> ForbiddenTechnologyRules
                        .domainMustNotDependOnForbiddenTechnologies(FIXTURES + ".forbidden")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("org.springframework")
                .hasMessageContaining("feign")
                .hasMessageContaining("org.apache.rocketmq")
                .hasMessageContaining("jakarta.servlet")
                .hasMessageContaining("javax.servlet")
                .hasMessageContaining("com.fasterxml.jackson")
                .hasMessageContaining("org.apache.ibatis")
                .hasMessageContaining("org.mybatis")
                .hasMessageContaining("jakarta.persistence")
                .hasMessageContaining("javax.persistence");
    }

    @Test
    void applicationRejectsDependenciesOutsideApplicationDomainApiPortsAndJdk() {
        JavaClasses classes = importFixture("applicationviolation");

        assertThatThrownBy(() -> DddLayerRules
                        .applicationMayOnlyDependOnAllowedLayers(FIXTURES + ".applicationviolation")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ApplicationDependsOnInfrastructure")
                .hasMessageContaining("PersistenceAdapter");
    }

    @Test
    void infrastructureRejectsEveryDisallowedLocalLayerAndAdjacentPortPackage() {
        String basePackage = FIXTURES + ".infrastructurematrixviolation";
        JavaClasses classes = importFixture("infrastructurematrixviolation");

        assertInfrastructureMatrixViolation(catchThrowable(
                () -> DddLayerRules.infrastructureMayOnlyDependOnAllowedServiceLayers(basePackage)
                        .check(classes)));
        assertInfrastructureMatrixViolation(catchThrowable(
                () -> DddLayerRules.all(basePackage).check(classes)));
    }

    private void assertInfrastructureMatrixViolation(Throwable violation) {
        assertThat(violation)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("InfrastructureDependencyProbe")
                .hasMessageContaining("InternalUseCase")
                .hasMessageContaining("AdjacentPortAdapter")
                .hasMessageContaining("ProtocolAdapter")
                .hasMessageContaining("AssemblyRoot");
        assertThat(violation.getMessage())
                .doesNotContain("ApprovedPort")
                .doesNotContain("DomainModel")
                .doesNotContain("ContractDTO")
                .doesNotContain("ExternalTechnologyType");
    }

    @Test
    void interfacesRejectDomainInfrastructureAndBootstrapButAllowApplicationApiAndExternalProtocol() {
        String basePackage = FIXTURES + ".interfacesmatrixviolation";
        JavaClasses classes = importFixture("interfacesmatrixviolation");

        assertInterfacesMatrixViolation(catchThrowable(
                () -> DddLayerRules.interfacesMayOnlyDependOnAllowedServiceLayers(basePackage)
                        .check(classes)));
        assertInterfacesMatrixViolation(catchThrowable(
                () -> DddLayerRules.all(basePackage).check(classes)));
    }

    private void assertInterfacesMatrixViolation(Throwable violation) {
        assertThat(violation)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("InterfaceDependencyProbe")
                .hasMessageContaining("DomainModel")
                .hasMessageContaining("InfrastructureAdapter")
                .hasMessageContaining("AssemblyRoot");
        assertThat(violation.getMessage())
                .doesNotContain("ApplicationService")
                .doesNotContain("ContractDTO")
                .doesNotContain("ExternalProtocolType");
    }

    @Test
    void domainAndApplicationCannotReverseDependOnInfrastructure() {
        JavaClasses classes = importFixture("reverseviolation");

        assertThatThrownBy(() -> DddLayerRules
                        .domainAndApplicationMustNotDependOnInfrastructure(FIXTURES + ".reverseviolation")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DomainDependsOnInfrastructure")
                .hasMessageContaining("InfrastructureDetail");
    }

    @Test
    void domainRejectsInterfacesBootstrapAndUnapprovedThirdPartyDependencies() {
        JavaClasses classes = importFixture("domainboundaryviolation");

        assertThatThrownBy(() -> DddLayerRules
                        .domainMayOnlyDependOnAllowedPackages(
                                FIXTURES + ".domainboundaryviolation")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("DomainLeaksAcrossLayers")
                .hasMessageContaining("ProtocolAdapter")
                .hasMessageContaining("AssemblyRoot")
                .hasMessageContaining("ExternalLibraryType");
    }

    @Test
    void interfacesRejectMapperAndPersistenceDependencies() {
        JavaClasses classes = importFixture("interfacesviolation");

        assertThatThrownBy(() -> DddLayerRules
                        .interfacesMustNotDependOnPersistence(FIXTURES + ".interfacesviolation")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BadRestAdapter")
                .hasMessageContaining("AccountMapper")
                .hasMessageContaining("SqlSession")
                .hasMessageContaining("EntityManager")
                .hasMessageContaining("Repository");
    }

    @Test
    void interfacesRejectMyBatisJpaAndSpringDataPersistenceTypesIndependently() {
        assertThatThrownBy(() -> DddLayerRules
                        .interfacesMustNotDependOnPersistence(FIXTURES + ".interfacesmybatisviolation")
                        .check(importFixture("interfacesmybatisviolation")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SqlSessionAdapter")
                .hasMessageContaining("SqlSession");

        assertThatThrownBy(() -> DddLayerRules
                        .interfacesMustNotDependOnPersistence(FIXTURES + ".interfacesjpaviolation")
                        .check(importFixture("interfacesjpaviolation")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("EntityManagerAdapter")
                .hasMessageContaining("EntityManager");

        assertThatThrownBy(() -> DddLayerRules
                        .interfacesMustNotDependOnPersistence(FIXTURES + ".interfacesspringdataviolation")
                        .check(importFixture("interfacesspringdataviolation")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RepositoryAdapter")
                .hasMessageContaining("Repository");
    }

    @Test
    void interfacesPersistenceMatcherDoesNotCaptureAdjacentPackageNames() {
        assertThatCode(() -> DddLayerRules
                        .interfacesMustNotDependOnPersistence(FIXTURES + ".interfacesadjacentcompliant")
                        .check(importFixture("interfacesadjacentcompliant")))
                .doesNotThrowAnyException();
    }

    @Test
    void onlyBootstrapAndInfrastructureMayDependOnInfrastructure() {
        JavaClasses classes = importFixture("assemblyviolation");

        assertThatThrownBy(() -> DddLayerRules
                        .bootstrapMustBeOnlyAssemblyRoot(FIXTURES + ".assemblyviolation")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NonBootstrapAssembler")
                .hasMessageContaining("InfrastructureAdapter");
    }

    @Test
    void apiRejectsReservedTerminalRolesAcrossShortShoutingAndInterleavedCaseNames() {
        JavaClasses classes = importFixture("apiviolation");

        assertThatThrownBy(() -> DddLayerRules
                        .apiMustContainOnlyImmutableContracts(FIXTURES + ".apiviolation")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("CustomerENTITY")
                .hasMessageContaining("CustomerRow")
                .hasMessageContaining("CustomerSdk")
                .hasMessageContaining("CustomerEntityDTO")
                .hasMessageContaining("CustomerRowDTO")
                .hasMessageContaining("CustomerSDKEvent")
                .hasMessageContaining("CUSTOMERENTITYDTO")
                .hasMessageContaining("CustomerentityDTO")
                .hasMessageContaining("CUSTOMERROWDTO")
                .hasMessageContaining("CUSTOMERSDKEvent")
                .hasMessageContaining("MyentityDTO")
                .hasMessageContaining("XentityDTO")
                .hasMessageContaining("MysdkDTO")
                .hasMessageContaining("XsdkDTO")
                .hasMessageContaining("CustomerrowDTO")
                .hasMessageContaining("CustomereNtItYDTO")
                .hasMessageContaining("CustomerRoWDTO")
                .hasMessageContaining("CustomerSdKEvent")
                .hasMessageContaining("CustomerIdentityDTO")
                .hasMessageContaining("ArrowDTO")
                .hasMessageContaining("MutableCustomerDTO")
                .hasMessageContaining("MutableCollectionDTO")
                .hasMessageContaining("non-final field");
    }

    @Test
    void apiAllowsCanonicalUpperCamelAndPureAcronymContractStems() {
        JavaClasses classes = importFixture("compliant");

        assertThatCode(() -> DddLayerRules
                        .apiMustContainOnlyImmutableContracts(FIXTURES + ".compliant")
                        .check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void canonicalApiStemValidationIsStackSafeForArbitrarilyLongAsciiInput() {
        String longInvalidStem = "A".repeat(2000) + "_";

        assertThatCode(() -> assertThat(DddLayerRules.isCanonicalApiStem(longInvalidStem))
                        .isFalse())
                .doesNotThrowAnyException();
        assertThat(DddLayerRules.isCanonicalApiStem("A".repeat(2000))).isTrue();
    }

    @Test
    void apiRejectsReservedRoleTokensOutsideTheTerminalPosition() {
        JavaClasses classes = importFixture("apitokenviolation");

        assertThatThrownBy(() -> DddLayerRules
                        .apiMustContainOnlyImmutableContracts(
                                FIXTURES + ".apitokenviolation")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(
                        "CustomerEntityValueDTO uses forbidden Entity, Row, or SDK role naming")
                .hasMessageContaining(
                        "SDKPayloadDTO uses forbidden Entity, Row, or SDK role naming")
                .hasMessageContaining(
                        "RowDetailsEvent uses forbidden Entity, Row, or SDK role naming");
    }

    @Test
    void apiContractsRejectDomainInfrastructureAndExternalSdkFieldTypes() {
        JavaClasses classes = importFixture("apidependencyviolation");

        assertThatThrownBy(() -> DddLayerRules
                        .apiMustContainOnlyImmutableContracts(
                                FIXTURES + ".apidependencyviolation")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LeakyDTO")
                .hasMessageContaining("CustomerEntity")
                .hasMessageContaining("DatabaseRow")
                .hasMessageContaining("ExternalSdkType");
    }

    @Test
    void mismatchedBasePackageCannotProduceAnEmptyGreenScan() {
        JavaClasses classes = importFixture("compliant");

        assertThatThrownBy(() -> DddLayerRules
                        .all(FIXTURES + ".missing")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("domain layer must be present");
    }

    @Test
    void partialImportWithoutInfrastructureAndBootstrapCannotProduceGreen() {
        JavaClasses classes = importFixture("partial");

        assertThatThrownBy(() -> DddLayerRules
                        .all(FIXTURES + ".partial")
                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("infrastructure layer must be present");
    }

    @Test
    void blankBasePackageIsRejectedBeforeRuleConstruction() {
        assertThatThrownBy(() -> DddLayerRules.all("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ForbiddenTechnologyRules
                        .domainMustNotDependOnForbiddenTechnologies(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JavaClasses importFixture(String fixtureName) {
        return new ClassFileImporter().importPackages(FIXTURES + "." + fixtureName);
    }
}
