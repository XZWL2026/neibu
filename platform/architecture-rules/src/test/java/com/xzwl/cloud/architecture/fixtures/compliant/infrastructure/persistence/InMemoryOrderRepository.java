package com.xzwl.cloud.architecture.fixtures.compliant.infrastructure.persistence;

import com.xzwl.cloud.architecture.fixtures.compliant.application.port.OrderRepositoryPort;
import com.xzwl.cloud.architecture.fixtures.compliant.domain.OrderPolicy;

import java.util.Optional;

public final class InMemoryOrderRepository implements OrderRepositoryPort {

    @Override
    public Optional<OrderPolicy> findById(String orderId) {
        return Optional.empty();
    }
}
