package com.xzwl.cloud.architecture.fixtures.assemblyviolation.interfaces;

import com.xzwl.cloud.architecture.fixtures.assemblyviolation.infrastructure.InfrastructureAdapter;

public final class NonBootstrapAssembler {

    private final InfrastructureAdapter adapter;

    public NonBootstrapAssembler(InfrastructureAdapter adapter) {
        this.adapter = adapter;
    }
}
