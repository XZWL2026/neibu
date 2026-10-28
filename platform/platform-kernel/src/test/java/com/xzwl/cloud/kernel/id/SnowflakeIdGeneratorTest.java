package com.xzwl.cloud.kernel.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    private static final long EPOCH =
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    @Test
    void rejectsWorkerOutsideTenBitRange() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1))
                .isInstanceOf(SnowflakeIdException.class)
                .extracting("reason")
                .isEqualTo(SnowflakeIdException.Reason.INVALID_WORKER_ID);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1024))
                .isInstanceOf(SnowflakeIdException.class)
                .extracting("reason")
                .isEqualTo(SnowflakeIdException.Reason.INVALID_WORKER_ID);
    }

    @Test
    void encodesTimestampWorkerAndSequenceIntoPositiveLong() {
        var clock = new MutableClock(EPOCH + 1234);
        var generator = new SnowflakeIdGenerator(513, clock, () -> {}, 10);

        long id = generator.nextId();

        assertThat(id).isPositive();
        assertThat(id >>> 22).isEqualTo(1234);
        assertThat((id >>> 12) & 0x3ffL).isEqualTo(513);
        assertThat(id & 0xfffL).isZero();
    }

    @Test
    void waitsPastTheOnlyZeroValuedCombination() {
        var clock = new MutableClock(EPOCH);

        long id =
                new SnowflakeIdGenerator(0, clock, () -> clock.value++, 10).nextId();

        assertThat(id).isEqualTo(1L << 22);
        assertThat(id >>> 22).isEqualTo(1);
        assertThat((id >>> 12) & 0x3ffL).isZero();
        assertThat(id & 0xfffL).isZero();
    }

    @Test
    void acceptsBoundaryWorkersAndTheLastRepresentableTimestamp() {
        var clock = new MutableClock(EPOCH + (1L << 41) - 1);

        long first = new SnowflakeIdGenerator(0, clock, () -> {}, 10).nextId();
        long last = new SnowflakeIdGenerator(1023, clock, () -> {}, 10).nextId();

        assertThat(first).isPositive();
        assertThat((first >>> 12) & 0x3ffL).isZero();
        assertThat(last).isPositive();
        assertThat(last >>> 22).isEqualTo((1L << 41) - 1);
        assertThat((last >>> 12) & 0x3ffL).isEqualTo(1023);
    }

    @Test
    void rejectsClockBeforeEpochRollbackAndTimestampOverflow() {
        var clock = new MutableClock(EPOCH - 1);
        var generator = new SnowflakeIdGenerator(1, clock, () -> {}, 10);
        assertReason(generator, SnowflakeIdException.Reason.CLOCK_BEFORE_EPOCH);

        clock.value = EPOCH + 10;
        generator.nextId();
        clock.value = EPOCH + 9;
        assertReason(generator, SnowflakeIdException.Reason.CLOCK_ROLLBACK);

        clock.value = EPOCH + (1L << 41);
        assertReason(
                new SnowflakeIdGenerator(1, clock, () -> {}, 10),
                SnowflakeIdException.Reason.TIMESTAMP_OVERFLOW);
    }

    @Test
    void rollbackDoesNotChangeTheLastSuccessfulState() {
        var clock = new MutableClock(EPOCH + 10);
        var generator = new SnowflakeIdGenerator(1, clock, () -> {}, 10);
        long first = generator.nextId();

        clock.value = EPOCH + 9;
        assertReason(generator, SnowflakeIdException.Reason.CLOCK_ROLLBACK);
        clock.value = EPOCH + 10;

        long second = generator.nextId();
        assertThat(first & 0xfffL).isZero();
        assertThat(second & 0xfffL).isEqualTo(1);
    }

    @Test
    void sequenceExhaustionWaitsForNextMillisecondAndResetsSequence() {
        var clock = new MutableClock(EPOCH + 50);
        var generator = new SnowflakeIdGenerator(7, clock, () -> clock.value++, 10);
        long last = -1;
        var values = new HashSet<Long>();

        for (int index = 0; index < 4096; index++) {
            long current = generator.nextId();
            assertThat(current).isGreaterThan(last);
            assertThat(values.add(current)).isTrue();
            last = current;
        }

        assertThat(values).hasSize(4096);
        assertThat(last & 0xfffL).isEqualTo(4095);
        long next = generator.nextId();
        assertThat(next >>> 22).isEqualTo(51);
        assertThat(next & 0xfffL).isZero();
    }

    @Test
    void waitTimeoutDoesNotCorruptTheLastSuccessfulState() {
        var clock = new MutableClock(EPOCH + 60);
        var generator = new SnowflakeIdGenerator(1, clock, () -> {}, 1);
        for (int index = 0; index < 4096; index++) {
            generator.nextId();
        }

        assertReason(generator, SnowflakeIdException.Reason.SEQUENCE_WAIT_TIMEOUT);
        assertReason(generator, SnowflakeIdException.Reason.SEQUENCE_WAIT_TIMEOUT);

        clock.value++;
        long next = generator.nextId();
        assertThat(next >>> 22).isEqualTo(61);
        assertThat(next & 0xfffL).isZero();
    }

    @Test
    void interruptedWaitFailsClosedWithoutCorruptingState() {
        var clock = new MutableClock(EPOCH + 80);
        var generator = new SnowflakeIdGenerator(3, clock, () -> {}, 10);
        for (int index = 0; index < 4096; index++) {
            generator.nextId();
        }

        Thread.currentThread().interrupt();
        try {
            assertReason(generator, SnowflakeIdException.Reason.SEQUENCE_WAIT_INTERRUPTED);
        } finally {
            Thread.interrupted();
        }

        assertReason(generator, SnowflakeIdException.Reason.SEQUENCE_WAIT_TIMEOUT);
        clock.value++;
        assertThat(generator.nextId() & 0xfffL).isZero();
    }

    @Test
    void differentWorkersNeverCollideAtTheSameTimeAndSequence() {
        var clock = new MutableClock(EPOCH + 70);

        long first = new SnowflakeIdGenerator(1, clock, () -> {}, 1).nextId();
        long second = new SnowflakeIdGenerator(2, clock, () -> {}, 1).nextId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void oneMillionConcurrentIdsAreUnique() throws Exception {
        var generator = new SnowflakeIdGenerator(9);
        var values = ConcurrentHashMap.<Long>newKeySet();

        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                futures.add(executor.submit(() -> {
                    for (int index = 0; index < 125_000; index++) {
                        values.add(generator.nextId());
                    }
                }));
            }
            for (var future : futures) {
                future.get();
            }
        }

        assertThat(values).hasSize(1_000_000);
    }

    @Test
    void snowflakeTypesAndKernelRemainFreeOfApplicationFrameworks() throws IOException {
        var pom = Files.readString(Path.of(System.getProperty("basedir"), "pom.xml"));

        assertThat(pom)
                .doesNotContain(
                        "<groupId>org.springframework",
                        "<groupId>com.fasterxml.jackson",
                        "<groupId>jakarta.persistence",
                        "<groupId>javax.persistence",
                        "<groupId>org.mybatis",
                        "<groupId>com.baomidou");
        assertThat(SnowflakeClock.class.getDeclaredAnnotations())
                .singleElement()
                .satisfies(
                        annotation ->
                                assertThat(annotation.annotationType())
                                        .isEqualTo(FunctionalInterface.class));
        assertThat(SnowflakeIdException.class.getDeclaredAnnotations()).isEmpty();
        assertThat(SnowflakeIdGenerator.class.getDeclaredAnnotations()).isEmpty();
    }

    private static void assertReason(
            SnowflakeIdGenerator generator, SnowflakeIdException.Reason reason) {
        assertThatThrownBy(generator::nextId)
                .isInstanceOf(SnowflakeIdException.class)
                .extracting("reason")
                .isEqualTo(reason);
    }

    static final class MutableClock implements SnowflakeClock {
        private long value;

        MutableClock(long value) {
            this.value = value;
        }

        @Override
        public long currentTimeMillis() {
            return value;
        }
    }
}
