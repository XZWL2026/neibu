package com.xzwl.cloud.architecture;

import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

public final class ForbiddenTechnologyRules {

    private static final String[] FORBIDDEN_DOMAIN_PACKAGES = {
            "org.springframework..",
            "feign..",
            "org.springframework.cloud.openfeign..",
            "org.apache.rocketmq..",
            "jakarta.servlet..",
            "javax.servlet..",
            "com.fasterxml.jackson..",
            "org.apache.ibatis..",
            "org.mybatis..",
            "jakarta.persistence..",
            "javax.persistence.."
    };

    private ForbiddenTechnologyRules() {
    }

    public static ArchRule domainMustNotDependOnForbiddenTechnologies(String basePackage) {
        String base = DddLayerRules.requireBasePackage(basePackage);
        return noClasses()
                .that().resideInAPackage(base + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_DOMAIN_PACKAGES)
                .allowEmptyShould(false)
                .as("domain classes must not depend on Spring, Feign, RocketMQ, Servlet, Jackson, MyBatis, or JPA");
    }
}
