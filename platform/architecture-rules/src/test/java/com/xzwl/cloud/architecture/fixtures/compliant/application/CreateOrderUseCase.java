package com.xzwl.cloud.architecture.fixtures.compliant.application;

import com.xzwl.cloud.architecture.fixtures.compliant.api.CreateOrderCommand;
import com.xzwl.cloud.architecture.fixtures.compliant.application.port.OrderRepositoryPort;
import com.xzwl.cloud.architecture.fixtures.compliant.application.stereotype.UseCase;
import com.xzwl.cloud.architecture.fixtures.compliant.domain.OrderPolicy;

@UseCase
public final class CreateOrderUseCase {

    private final OrderRepositoryPort repository;

    public CreateOrderUseCase(OrderRepositoryPort repository) {
        this.repository = repository;
    }

    public OrderPolicy execute(CreateOrderCommand command) {
        return repository.findById(command.orderId()).orElseGet(() -> new OrderPolicy(command.orderId()));
    }
}
