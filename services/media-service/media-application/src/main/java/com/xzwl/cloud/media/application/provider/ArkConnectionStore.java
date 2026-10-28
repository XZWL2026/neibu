package com.xzwl.cloud.media.application.provider;

public interface ArkConnectionStore {
  Connection load(String providerId);
  Connection save(long expectedVersion, String providerId, String baseUrl, String apiKey);
  default Connection save(long expectedVersion,String providerId,String baseUrl,String apiKey,Integer timeoutSeconds,Integer retryCount) {
    if(timeoutSeconds!=null || retryCount!=null) throw new IllegalArgumentException("request policy not supported");
    return save(expectedVersion,providerId,baseUrl,apiKey);
  }
  boolean editable();
  boolean hasConfiguredConnection();

  record Connection(long version, String providerId, String baseUrl, String apiKey,int timeoutSeconds,int retryCount) {
    public Connection(long version,String providerId,String baseUrl,String apiKey) {
      this(version,providerId,baseUrl,apiKey,180,0);
    }
    public Connection {
      if(timeoutSeconds<1 || timeoutSeconds>180 || retryCount<0 || retryCount>3) throw new IllegalArgumentException("Invalid request policy");
    }
    @Override public String toString() {
      return "Connection[version=" + version + ", providerId=" + providerId + ", baseUrl=" + baseUrl + ", apiKey=REDACTED]";
    }
  }
  final class VersionConflict extends IllegalStateException {
    public VersionConflict() { super("Ark connection version conflict"); }
  }
  final class NotEditable extends IllegalStateException {
    public NotEditable() { super("Legacy Ark connection migration unavailable"); }
  }
}
