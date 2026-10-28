package com.xzwl.cloud.user.application.identity;

public interface AvatarVerifier {
  void verify(String fileId, String cookie);
  final class Rejected extends RuntimeException {
    private final int status;
    public Rejected(int status) { this.status = status; }
    public int status() { return status; }
  }
}
