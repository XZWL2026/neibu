package com.xzwl.cloud.iam.application.account;

import com.xzwl.cloud.iam.domain.account.Administrator;
import java.util.List;
import java.util.function.Supplier;

public interface AdminStore {
  <T> T locked(Supplier<T> work);
  long count();
  Administrator find(String id);
  Administrator byUsername(String username);
  Administrator primary();
  List<Administrator> list();
  void insert(Administrator admin);
  void update(Administrator admin);
  void recordAgentEntry(String actorId, String credentialId, String userId);
}
