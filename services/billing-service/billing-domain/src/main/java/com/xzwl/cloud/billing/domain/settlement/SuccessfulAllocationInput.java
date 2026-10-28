package com.xzwl.cloud.billing.domain.settlement;

import com.xzwl.cloud.billing.domain.money.Decimal4;

public record SuccessfulAllocationInput(
    String allocationId, Decimal4 finalSuccessfulPoints4) {}
