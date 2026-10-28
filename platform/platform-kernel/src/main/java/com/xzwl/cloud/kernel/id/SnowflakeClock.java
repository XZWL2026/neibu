package com.xzwl.cloud.kernel.id;

@FunctionalInterface
public interface SnowflakeClock {

    long currentTimeMillis();

    static SnowflakeClock system() {
        return System::currentTimeMillis;
    }
}
