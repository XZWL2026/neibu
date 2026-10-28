package com.xzwl.cloud.kernel;

public record Identifier(String value) {

    public Identifier {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("identifier must not be blank");
        }
    }

    public static Identifier of(String value) {
        return new Identifier(value);
    }
}
