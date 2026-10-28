package com.xzwl.cloud.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IdentifierTest {

    @Test
    void identifierRejectsNullAndBlankValues() {
        assertThatThrownBy(() -> Identifier.of(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifier.of(" \t"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifierPreservesAValidValue() {
        assertThat(Identifier.of("  entity-1  ").value()).isEqualTo("  entity-1  ");
    }

    @Test
    void requestIdRejectsNullAndBlankValuesAndIsImmutable() throws NoSuchFieldException {
        assertThatThrownBy(() -> new RequestId(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestId.of("\n"))
                .isInstanceOf(IllegalArgumentException.class);

        var requestId = RequestId.of("req-1");

        assertThat(requestId.value()).isEqualTo("req-1");
        assertValueObjectIsImmutableRecord(RequestId.class);
    }

    @Test
    void idempotencyKeyRejectsNullAndBlankValuesAndIsImmutable()
            throws NoSuchFieldException {
        assertThatThrownBy(() -> new IdempotencyKey(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("   "))
                .isInstanceOf(IllegalArgumentException.class);

        var idempotencyKey = IdempotencyKey.of("idem-1");

        assertThat(idempotencyKey.value()).isEqualTo("idem-1");
        assertValueObjectIsImmutableRecord(IdempotencyKey.class);
    }

    @Test
    void kernelTypesAndModuleRemainFreeOfApplicationFrameworks() throws IOException {
        var pom = Files.readString(Path.of(System.getProperty("basedir"), "pom.xml"));
        assertThat(pom)
                .doesNotContain(
                        "<groupId>org.springframework",
                        "<groupId>com.fasterxml.jackson",
                        "<groupId>jakarta.persistence",
                        "<groupId>javax.persistence",
                        "<groupId>org.mybatis",
                        "<groupId>com.baomidou");

        assertThat(Identifier.class.getDeclaredAnnotations()).isEmpty();
        assertThat(RequestId.class.getDeclaredAnnotations()).isEmpty();
        assertThat(IdempotencyKey.class.getDeclaredAnnotations()).isEmpty();
        assertThat(DomainEventEnvelope.class.getDeclaredAnnotations()).isEmpty();
    }

    private static void assertValueObjectIsImmutableRecord(Class<?> type)
            throws NoSuchFieldException {
        assertThat(type.isRecord()).isTrue();
        assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(type.getDeclaredField("value").getModifiers())).isTrue();
    }
}
