package com.xzwl.cloud.feign;

final class MethodKeyNormalizer {
    private static final int MAX_LENGTH = 256;
    private static final Result UNKNOWN = new Result("<unknown>");
    private static final Result INVALID = new Result("<invalid>");

    private MethodKeyNormalizer() {
    }

    static Result normalize(String methodKey) {
        if (methodKey == null || methodKey.isBlank()) {
            return UNKNOWN;
        }
        if (methodKey.length() > MAX_LENGTH) {
            return INVALID;
        }
        for (int index = 0; index < methodKey.length(); index++) {
            char character = methodKey.charAt(index);
            if (Character.isISOControl(character) || character == '\u2028' || character == '\u2029') {
                return INVALID;
            }
        }
        return new Result(methodKey);
    }

    record Result(String value) {
    }
}
