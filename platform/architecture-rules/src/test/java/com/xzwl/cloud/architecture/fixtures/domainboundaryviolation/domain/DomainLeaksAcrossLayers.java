package com.xzwl.cloud.architecture.fixtures.domainboundaryviolation.domain;

import com.xzwl.cloud.architecture.fixtures.domainboundaryviolation.bootstrap.AssemblyRoot;
import com.xzwl.cloud.architecture.fixtures.domainboundaryviolation.interfaces.ProtocolAdapter;
import sample.vendor.ExternalLibraryType;

public final class DomainLeaksAcrossLayers {

    private ProtocolAdapter protocolAdapter;
    private AssemblyRoot assemblyRoot;
    private ExternalLibraryType externalLibrary;
}
