package com.xzwl.cloud.architecture.fixtures.compliant.bootstrap;

import com.xzwl.cloud.architecture.fixtures.compliant.application.CreateOrderUseCase;
import com.xzwl.cloud.architecture.fixtures.compliant.infrastructure.persistence.InMemoryOrderRepository;

public final class OrderBootstrap {

    public CreateOrderUseCase createUseCase() {
        return new CreateOrderUseCase(new InMemoryOrderRepository());
    }
}
