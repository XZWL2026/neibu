package com.xzwl.cloud.architecture.fixtures.compliant.interfaces.rest;

import com.xzwl.cloud.architecture.fixtures.compliant.api.CreateOrderCommand;
import com.xzwl.cloud.architecture.fixtures.compliant.api.OrderDTO;
import com.xzwl.cloud.architecture.fixtures.compliant.application.CreateOrderUseCase;

public final class OrderControllerAdapter {

    private final CreateOrderUseCase useCase;

    public OrderControllerAdapter(CreateOrderUseCase useCase) {
        this.useCase = useCase;
    }

    public OrderDTO create(String orderId) {
        useCase.execute(new CreateOrderCommand(orderId));
        return new OrderDTO(orderId);
    }
}
