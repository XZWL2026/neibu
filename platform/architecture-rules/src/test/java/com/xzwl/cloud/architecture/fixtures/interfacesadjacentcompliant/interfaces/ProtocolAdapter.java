package com.xzwl.cloud.architecture.fixtures.interfacesadjacentcompliant.interfaces;

import com.xzwl.cloud.architecture.fixtures.interfacesadjacentcompliant.infrastructure.persistenceadapter.ProtocolTranslator;

public final class ProtocolAdapter {

    private final ProtocolTranslator translator;

    public ProtocolAdapter(ProtocolTranslator translator) {
        this.translator = translator;
    }
}
