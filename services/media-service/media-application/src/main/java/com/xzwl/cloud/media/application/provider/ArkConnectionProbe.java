package com.xzwl.cloud.media.application.provider;

/** A non-generating authentication check of one saved connection version. */
public interface ArkConnectionProbe {
  Result test(String providerId);
  record Result(long version, boolean reachable, String message) {}
}
