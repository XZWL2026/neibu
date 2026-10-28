package com.xzwl.cloud.architecture.fixtures.interfacesspringdataviolation.interfaces;

import org.springframework.data.repository.Repository;

public final class RepositoryAdapter {

    private final Repository repository;

    public RepositoryAdapter(Repository repository) {
        this.repository = repository;
    }
}
