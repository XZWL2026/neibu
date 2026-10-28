package com.xzwl.cloud.architecture.fixtures.interfacesviolation.interfaces.rest;

import com.xzwl.cloud.architecture.fixtures.interfacesviolation.infrastructure.persistence.AccountMapper;
import com.xzwl.cloud.architecture.fixtures.interfacesviolation.mapping.ExternalMapper;
import com.xzwl.cloud.architecture.fixtures.interfacesviolation.storage.persistence.PersistenceGateway;
import jakarta.persistence.EntityManager;
import org.apache.ibatis.session.SqlSession;
import org.springframework.data.repository.Repository;

public final class BadRestAdapter {

    private final AccountMapper mapper;
    private final ExternalMapper externalMapper;
    private final PersistenceGateway persistenceGateway;
    private final SqlSession sqlSession;
    private final EntityManager entityManager;
    private final Repository springDataRepository;

    public BadRestAdapter(
            AccountMapper mapper,
            ExternalMapper externalMapper,
            PersistenceGateway persistenceGateway,
            SqlSession sqlSession,
            EntityManager entityManager,
            Repository springDataRepository) {
        this.mapper = mapper;
        this.externalMapper = externalMapper;
        this.persistenceGateway = persistenceGateway;
        this.sqlSession = sqlSession;
        this.entityManager = entityManager;
        this.springDataRepository = springDataRepository;
    }
}
