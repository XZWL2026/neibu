package com.xzwl.cloud.storage.application.file;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceMediaProbeTest {
  @TempDir Path directory;

  @Test void timedOutProbeIsTerminatedAndReaped() throws Exception {
    Path pidFile = directory.resolve("process.pid");
    Path executable = directory.resolve("probe");
    Files.writeString(executable, "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec sleep 30\n");
    assertTrue(executable.toFile().setExecutable(true));
    assertTimeout(Duration.ofSeconds(5), () -> assertThrows(FileValidationException.class,
        () -> ReferenceMediaProbe.probe(directory.resolve("local.media"), executable.toString(), 2000)));
    long pid = Long.parseLong(Files.readString(pidFile).trim());
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }

  @Test void oversizedProbeOutputIsRejectedAndProcessReaped() throws Exception {
    Path pidFile = directory.resolve("output.pid");
    Path executable = directory.resolve("noisy-probe");
    Files.writeString(executable, "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec yes metadata\n");
    assertTrue(executable.toFile().setExecutable(true));
    assertTimeout(Duration.ofSeconds(5), () -> assertThrows(FileValidationException.class,
        () -> ReferenceMediaProbe.probe(directory.resolve("local.media"), executable.toString(), 1000)));
    long pid = Long.parseLong(Files.readString(pidFile).trim());
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }
}
