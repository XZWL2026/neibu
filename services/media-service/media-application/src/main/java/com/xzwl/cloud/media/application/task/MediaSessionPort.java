package com.xzwl.cloud.media.application.task;
public interface MediaSessionPort {
  String verify(String cookie,String csrfToken,boolean write);
  final class Denied extends RuntimeException {
    private final int status;
    public Denied(int status) { super("media session unavailable or denied"); this.status=status; }
    public int status() { return status; }
  }
}
