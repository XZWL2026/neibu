package com.xzwl.cloud.access.application.key;
import com.xzwl.cloud.access.domain.key.AccountType;
import java.util.Optional;
public interface AccessKeyStore {
  default java.util.List<DirectoryKey> directoryKeys(java.util.List<String> owners, String search) { throw new UnsupportedOperationException(); }
  record DirectoryKey(String ownerId,String keyId,String accountType,boolean enabled,String maskedKey,boolean matchesSearch) {}
  void manage(String requestId,String ownerId,AccountType type,boolean createKey,String operatorId);
  java.util.List<AccessKey> list(String ownerId);
  AccessKey ensurePrimary(String ownerId,AccountType type,String paidOrderId);
  default AccessKey ensurePrimary(String ownerId,AccountType type,String paidOrderId,java.time.Instant paidAt){return ensurePrimary(ownerId,type,paidOrderId);}
  Optional<AccessKey> findByOwner(String ownerId);
  Optional<AccessKey> findById(String keyId);
  Optional<AccessKey> findBySecret(String secret);
  void setEnabled(String keyId,boolean enabled);
}
