package com.xzwl.cloud.media.application.task;
import java.time.Duration;
import java.util.List;

public record TaskPolicy(Duration imageTimeout, Duration videoTimeout, Duration pollInterval,
                         Duration retryInterval, Duration leaseDuration, int batchSize) {
  public TaskPolicy {
    for (Duration value: List.of(imageTimeout,videoTimeout,pollInterval,retryInterval,leaseDuration)) {
      if(value.isNegative() || value.isZero()) throw new IllegalArgumentException("durations must be positive");
    }
    if(batchSize<1 || batchSize>100) throw new IllegalArgumentException("batch size must be 1..100");
  }
}
