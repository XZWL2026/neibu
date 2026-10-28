package com.xzwl.cloud.architecture.fixtures.interfacesjpaviolation.interfaces;

import jakarta.persistence.EntityManager;

public final class EntityManagerAdapter {

    private final EntityManager entityManager;

    public EntityManagerAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }
}
