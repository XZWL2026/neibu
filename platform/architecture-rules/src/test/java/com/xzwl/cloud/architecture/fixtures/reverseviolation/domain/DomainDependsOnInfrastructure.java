package com.xzwl.cloud.architecture.fixtures.reverseviolation.domain;

import com.xzwl.cloud.architecture.fixtures.reverseviolation.infrastructure.InfrastructureDetail;

public final class DomainDependsOnInfrastructure {

    private final InfrastructureDetail detail;

    public DomainDependsOnInfrastructure(InfrastructureDetail detail) {
        this.detail = detail;
    }
}
