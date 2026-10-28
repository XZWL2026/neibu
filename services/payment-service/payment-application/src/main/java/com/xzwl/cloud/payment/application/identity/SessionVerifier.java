package com.xzwl.cloud.payment.application.identity;
import java.util.Set;
public interface SessionVerifier {
  Identity verify(String cookie,String csrf,boolean mutation);
  record Identity(String userId,Set<String> roles) {}
  final class Rejected extends RuntimeException {
    public final int status;
    public Rejected(int status){this.status=status;}
  }
}
