package com.xzwl.cloud.architecture.fixtures.compliant.application.port;

import com.xzwl.cloud.architecture.fixtures.compliant.domain.OrderPolicy;

import java.util.Optional;

public interface OrderRepositoryPort {

    Optional<OrderPolicy> findById(String orderId);
}
