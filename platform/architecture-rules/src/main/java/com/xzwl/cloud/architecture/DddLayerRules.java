package com.xzwl.cloud.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

public final class DddLayerRules {

    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern API_CONTRACT_NAME = Pattern.compile(
            "^(.+)(Command|Query|DTO|Dto|Event)$");
    private static final Pattern CAMEL_OR_ACRONYM_BOUNDARY = Pattern.compile(
            "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Set<String> FORBIDDEN_API_ROLE_TOKENS = Set.of(
            "entity", "row", "sdk");

    private DddLayerRules() {
    }

    public static ArchRule all(String basePackage) {
        String base = requireBasePackage(basePackage);
        return CompositeArchRule.of(allSixLayersMustBePresent(base))
                .and(
                        ForbiddenTechnologyRules.domainMustNotDependOnForbiddenTechnologies(base))
                .and(domainMayOnlyDependOnAllowedPackages(base))
                .and(applicationMayOnlyDependOnAllowedLayers(base))
                .and(infrastructureMayOnlyDependOnAllowedServiceLayers(base))
                .and(interfacesMayOnlyDependOnAllowedServiceLayers(base))
                .and(domainAndApplicationMustNotDependOnInfrastructure(base))
                .and(interfacesMustNotDependOnPersistence(base))
                .and(bootstrapMustBeOnlyAssemblyRoot(base))
                .and(apiMustContainOnlyImmutableContracts(base))
                .as("DDD layer and forbidden technology rules for " + base);
    }

    public static ArchRule allSixLayersMustBePresent(String basePackage) {
        String base = requireBasePackage(basePackage);
        return CompositeArchRule.of(layerMustBePresent(base, "domain"))
                .and(layerMustBePresent(base, "application"))
                .and(layerMustBePresent(base, "infrastructure"))
                .and(layerMustBePresent(base, "interfaces"))
                .and(layerMustBePresent(base, "bootstrap"))
                .and(layerMustBePresent(base, "api"))
                .as("all six DDD layers must be present in the imported classes for " + base);
    }

    public static ArchRule applicationMayOnlyDependOnAllowedLayers(String basePackage) {
        String base = requireBasePackage(basePackage);
        return classes()
                .that().resideInAPackage(base + ".application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        base + ".application..",
                        base + ".domain..",
                        base + ".api..")
                .allowEmptyShould(false)
                .as("application may depend only on JDK, application ports, domain, and api");
    }

    public static ArchRule domainMayOnlyDependOnAllowedPackages(String basePackage) {
        String base = requireBasePackage(basePackage);
        return classes()
                .that().resideInAPackage(base + ".domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "com.xzwl.cloud.kernel..",
                        base + ".domain..")
                .allowEmptyShould(false)
                .as("domain may depend only on JDK, platform-kernel, and its own domain");
    }

    public static ArchRule infrastructureMayOnlyDependOnAllowedServiceLayers(String basePackage) {
        String base = requireBasePackage(basePackage);
        return classes()
                .that().resideInAPackage(base + ".infrastructure..")
                .should(onlyDependOnAllowedLocalPackages(
                        base,
                        base + ".infrastructure",
                        base + ".application.port",
                        base + ".domain",
                        base + ".api"))
                .allowEmptyShould(false)
                .as("infrastructure may depend locally only on infrastructure, application.port, domain, and api");
    }

    public static ArchRule interfacesMayOnlyDependOnAllowedServiceLayers(String basePackage) {
        String base = requireBasePackage(basePackage);
        return classes()
                .that().resideInAPackage(base + ".interfaces..")
                .should(onlyDependOnAllowedLocalPackages(
                        base,
                        base + ".interfaces",
                        base + ".application",
                        base + ".api"))
                .allowEmptyShould(false)
                .as("interfaces may depend locally only on interfaces, application, and api");
    }

    public static ArchRule domainAndApplicationMustNotDependOnInfrastructure(String basePackage) {
        String base = requireBasePackage(basePackage);
        return noClasses()
                .that().resideInAnyPackage(base + ".domain..", base + ".application..")
                .should().dependOnClassesThat().resideInAPackage(base + ".infrastructure..")
                .allowEmptyShould(false)
                .as("domain and application must not depend on infrastructure");
    }

    public static ArchRule interfacesMustNotDependOnPersistence(String basePackage) {
        String base = requireBasePackage(basePackage);
        return classes()
                .that().resideInAPackage(base + ".interfaces..")
                .should(notDependOnPersistenceDetails(base))
                .allowEmptyShould(false)
                .as("interfaces must adapt protocols without depending on Mapper or persistence details");
    }

    public static ArchRule bootstrapMustBeOnlyAssemblyRoot(String basePackage) {
        String base = requireBasePackage(basePackage);
        return classes()
                .that().resideInAPackage(base + "..")
                .and().resideOutsideOfPackages(base + ".bootstrap..", base + ".infrastructure..")
                .should(notDependOnPackage(base + ".infrastructure"))
                .allowEmptyShould(false)
                .as("bootstrap must be the only layer outside infrastructure that wires infrastructure");
    }

    public static ArchRule apiMustContainOnlyImmutableContracts(String basePackage) {
        String base = requireBasePackage(basePackage);
        ArchRule dependencyBoundary = classes()
                .that().resideInAPackage(base + ".api..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        base + ".api..")
                .allowEmptyShould(false)
                .as("api may depend only on JDK and api value types");
        ArchRule structure = classes()
                .that().resideInAPackage(base + ".api..")
                .should(beImmutableApiContracts())
                .allowEmptyShould(false)
                .as("api must contain only immutable Command, Query, DTO, and Event contracts");
        return CompositeArchRule.of(dependencyBoundary)
                .and(structure)
                .as("api dependency, naming, and structural immutability boundary");
    }

    static String requireBasePackage(String basePackage) {
        if (basePackage == null || !JAVA_PACKAGE.matcher(basePackage).matches()) {
            throw new IllegalArgumentException("basePackage must be a non-blank Java package");
        }
        return basePackage;
    }

    private static ArchCondition<JavaClass> notDependOnPersistenceDetails(String basePackage) {
        return new ArchCondition<>("not depend on Mapper or persistence details") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String packageName = target.getPackageName().toLowerCase(Locale.ROOT);
                    String simpleName = target.getSimpleName().toLowerCase(Locale.ROOT);
                    String servicePersistence =
                            (basePackage + ".infrastructure.persistence").toLowerCase(Locale.ROOT);
                    boolean persistencePackage = packageName.equals(servicePersistence)
                            || packageName.startsWith(servicePersistence + ".")
                            || packageName.contains(".persistence.")
                            || packageName.endsWith(".persistence")
                            || packageName.contains(".mapper.")
                            || packageName.endsWith(".mapper")
                            || packageName.contains(".repository.")
                            || packageName.endsWith(".repository")
                            || isPersistenceTechnologyPackage(packageName);
                    boolean persistenceType = simpleName.endsWith("mapper")
                            || simpleName.endsWith("repository");
                    if (persistencePackage || persistenceType) {
                        events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> onlyDependOnAllowedLocalPackages(
            String basePackage, String... allowedPackagePrefixes) {
        return new ArchCondition<>("depend only on allowed packages inside " + basePackage) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    if (!isSameOrSubpackage(targetPackage, basePackage)) {
                        continue;
                    }
                    boolean allowed = false;
                    for (String allowedPrefix : allowedPackagePrefixes) {
                        if (isSameOrSubpackage(targetPackage, allowedPrefix)) {
                            allowed = true;
                            break;
                        }
                    }
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static boolean isSameOrSubpackage(String packageName, String packagePrefix) {
        return packageName.equals(packagePrefix) || packageName.startsWith(packagePrefix + ".");
    }

    private static boolean isPersistenceTechnologyPackage(String packageName) {
        return packageName.equals("org.apache.ibatis")
                || packageName.startsWith("org.apache.ibatis.")
                || packageName.equals("org.mybatis")
                || packageName.startsWith("org.mybatis.")
                || packageName.equals("jakarta.persistence")
                || packageName.startsWith("jakarta.persistence.")
                || packageName.equals("javax.persistence")
                || packageName.startsWith("javax.persistence.")
                || packageName.equals("org.springframework.data")
                || packageName.startsWith("org.springframework.data.")
                || packageName.equals("org.hibernate")
                || packageName.startsWith("org.hibernate.");
    }

    private static ArchRule layerMustBePresent(String basePackage, String layer) {
        return classes()
                .that().resideInAPackage(basePackage + "." + layer + "..")
                .should(new ArchCondition<>("be included in the imported classes") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        events.add(SimpleConditionEvent.satisfied(
                                item, item.getName() + " is included as " + layer));
                    }
                })
                .allowEmptyShould(false)
                .as(layer + " layer must be present in the imported classes");
    }

    private static ArchCondition<JavaClass> notDependOnPackage(String packagePrefix) {
        return new ArchCondition<>("not depend on " + packagePrefix) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    if (targetPackage.equals(packagePrefix) || targetPackage.startsWith(packagePrefix + ".")) {
                        events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> beImmutableApiContracts() {
        return new ArchCondition<>("be an immutable Command, Query, DTO, or Event") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String simpleName = item.getSimpleName();
                Matcher contractName = API_CONTRACT_NAME.matcher(simpleName);
                String contractStem = simpleName;
                if (!contractName.matches()) {
                    events.add(SimpleConditionEvent.violated(
                            item, item.getName() + " is not named Command, Query, DTO, or Event"));
                } else {
                    contractStem = contractName.group(1);
                    if (!isCanonicalApiStem(contractStem)) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                item.getName()
                                        + " does not use a canonical UpperCamel/acronym contract stem"));
                    }
                }
                if (containsForbiddenApiRole(contractStem)) {
                    events.add(SimpleConditionEvent.violated(
                            item, item.getName() + " uses forbidden Entity, Row, or SDK role naming"));
                }
                if (!item.getModifiers().contains(JavaModifier.FINAL)) {
                    events.add(SimpleConditionEvent.violated(
                            item, item.getName() + " is not final"));
                }
                item.getRawSuperclass().ifPresent(superclass -> {
                    String superclassName = superclass.getName();
                    if (!superclassName.equals("java.lang.Object")
                            && !superclassName.equals("java.lang.Record")) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                item.getName() + " extends unsupported mutable-capable superclass "
                                        + superclassName));
                    }
                });
                for (JavaField field : item.getFields()) {
                    if (!field.getModifiers().contains(JavaModifier.FINAL)) {
                        events.add(SimpleConditionEvent.violated(
                                item, item.getName() + " has non-final field " + field.getName()));
                    }
                    if (!field.getModifiers().contains(JavaModifier.STATIC)
                            && !field.getModifiers().contains(JavaModifier.PRIVATE)) {
                        events.add(SimpleConditionEvent.violated(
                                item, item.getName() + " has non-private instance field " + field.getName()));
                    }
                }
            }
        };
    }

    static boolean isCanonicalApiStem(String stem) {
        if (stem == null || stem.isEmpty() || !isAsciiUppercase(stem.charAt(0))) {
            return false;
        }
        for (int index = 1; index < stem.length(); index++) {
            char character = stem.charAt(index);
            if (!isAsciiUppercase(character)
                    && !isAsciiLowercase(character)
                    && !isAsciiDigit(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiUppercase(char character) {
        return character >= 'A' && character <= 'Z';
    }

    private static boolean isAsciiLowercase(char character) {
        return character >= 'a' && character <= 'z';
    }

    private static boolean isAsciiDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean containsForbiddenApiRole(String contractStem) {
        String foldedStem = contractStem.toLowerCase(Locale.ROOT);
        for (String reservedRole : FORBIDDEN_API_ROLE_TOKENS) {
            if (foldedStem.endsWith(reservedRole)) {
                return true;
            }
        }
        for (String token : CAMEL_OR_ACRONYM_BOUNDARY.split(contractStem)) {
            String foldedToken = token.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_API_ROLE_TOKENS.contains(foldedToken)) {
                return true;
            }
        }
        return false;
    }
}
