package com.xzwl.cloud.architecture.fixtures.interfacesmatrixviolation.interfaces;

import com.xzwl.cloud.architecture.fixtures.interfacesmatrixviolation.api.ContractDTO;
import com.xzwl.cloud.architecture.fixtures.interfacesmatrixviolation.application.ApplicationService;
import com.xzwl.cloud.architecture.fixtures.interfacesmatrixviolation.bootstrap.AssemblyRoot;
import com.xzwl.cloud.architecture.fixtures.interfacesmatrixviolation.domain.DomainModel;
import com.xzwl.cloud.architecture.fixtures.interfacesmatrixviolation.infrastructure.InfrastructureAdapter;
import sample.protocol.ExternalProtocolType;

public final class InterfaceDependencyProbe {

    private ApplicationService applicationService;
    private ContractDTO contract;
    private ExternalProtocolType externalProtocol;
    private DomainModel domainModel;
    private InfrastructureAdapter infrastructureAdapter;
    private AssemblyRoot assemblyRoot;
}
