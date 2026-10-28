package com.xzwl.cloud.architecture.fixtures.apidependencyviolation.api;

import com.xzwl.cloud.architecture.fixtures.apidependencyviolation.domain.CustomerEntity;
import com.xzwl.cloud.architecture.fixtures.apidependencyviolation.infrastructure.DatabaseRow;
import sample.vendor.ExternalSdkType;

public record LeakyDTO(
        CustomerEntity entity,
        DatabaseRow row,
        ExternalSdkType sdk) {
}
