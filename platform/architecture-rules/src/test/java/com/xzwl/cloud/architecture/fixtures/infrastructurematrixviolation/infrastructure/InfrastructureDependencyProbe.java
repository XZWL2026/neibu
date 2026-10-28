package com.xzwl.cloud.architecture.fixtures.infrastructurematrixviolation.infrastructure;

import com.xzwl.cloud.architecture.fixtures.infrastructurematrixviolation.api.ContractDTO;
import com.xzwl.cloud.architecture.fixtures.infrastructurematrixviolation.application.InternalUseCase;
import com.xzwl.cloud.architecture.fixtures.infrastructurematrixviolation.application.port.ApprovedPort;
import com.xzwl.cloud.architecture.fixtures.infrastructurematrixviolation.application.portadapter.AdjacentPortAdapter;
import com.xzwl.cloud.architecture.fixtures.infrastructurematrixviolation.bootstrap.AssemblyRoot;
import com.xzwl.cloud.architecture.fixtures.infrastructurematrixviolation.domain.DomainModel;
import com.xzwl.cloud.architecture.fixtures.infrastructurematrixviolation.interfaces.ProtocolAdapter;
import sample.technology.ExternalTechnologyType;

public final class InfrastructureDependencyProbe {

    private ApprovedPort approvedPort;
    private DomainModel domainModel;
    private ContractDTO contract;
    private ExternalTechnologyType externalTechnology;
    private InternalUseCase internalUseCase;
    private AdjacentPortAdapter adjacentPortAdapter;
    private ProtocolAdapter protocolAdapter;
    private AssemblyRoot assemblyRoot;
}
