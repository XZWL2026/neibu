package com.xzwl.cloud.media.application;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Keeps the pre-existing executable business assertions in the ordinary Maven test run. */
class ExistingMediaAssertionsTest {
  @Test void existingGenerationAndWorkerAssertions() throws Exception {
    Path root = Path.of("src/test/java");
    try (var files = Files.walk(root)) {
      for (Path path : files.filter(p -> p.toString().endsWith("Test.java"))
          .filter(p -> p.toString().contains("generation") || p.toString().contains("worker")).toList()) {
        String name = root.relativize(path).toString().replace('/', '.').replace('\\', '.').replace(".java", "");
        Class.forName(name).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
      }
    }
  }
}
