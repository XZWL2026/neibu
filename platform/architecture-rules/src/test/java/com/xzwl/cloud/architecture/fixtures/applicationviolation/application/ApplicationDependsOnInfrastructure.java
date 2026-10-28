package com.xzwl.cloud.architecture.fixtures.applicationviolation.application;

import com.xzwl.cloud.architecture.fixtures.applicationviolation.infrastructure.PersistenceAdapter;

public final class ApplicationDependsOnInfrastructure {

    private final PersistenceAdapter adapter;

    public ApplicationDependsOnInfrastructure(PersistenceAdapter adapter) {
        this.adapter = adapter;
    }
}
