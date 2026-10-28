package com.xzwl.cloud.user.application.identity;

import java.util.List;

public interface SessionVerifier {
  Identity verify(String cookie, String csrf, boolean mutation);
  record Identity(String id, List<String> roles) {}
  final class Rejected extends RuntimeException {
    private final int status;
    public Rejected(int status) { super("Session verification rejected"); this.status = status; }
    public int status() { return status; }
  }
}
