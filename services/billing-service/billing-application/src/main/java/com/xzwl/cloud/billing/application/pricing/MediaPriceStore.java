package com.xzwl.cloud.billing.application.pricing;
import com.xzwl.cloud.billing.domain.pricing.MediaPrice;
import java.util.List;
public interface MediaPriceStore {
  Snapshot load();
  Snapshot save(long expectedVersion,List<MediaPrice> prices);
  default Snapshot loadForOwner(String ownerId) { return loadForOwner(ownerId,"API"); }
  default Snapshot loadForOwner(String ownerId,String accountType) { return load(); }
  default ApiSnapshot loadApi(String ownerId) { return loadOwner(ownerId,"API"); }
  default ApiSnapshot saveApi(String ownerId,String requestId,String operatorId,long expectedVersion,List<MediaPrice> prices) { return saveOwner(ownerId,"API",requestId,operatorId,expectedVersion,prices); }
  default List<ApiAudit> apiAudit(String ownerId) { return ownerAudit(ownerId,"API"); }
  default ApiSnapshot loadOwner(String ownerId,String accountType) { throw new UnsupportedOperationException(); }
  default ApiSnapshot saveOwner(String ownerId,String accountType,String requestId,String operatorId,long expectedVersion,List<MediaPrice> prices) { throw new UnsupportedOperationException(); }
  default List<ApiAudit> ownerAudit(String ownerId,String accountType) { throw new UnsupportedOperationException(); }
  record ApiSnapshot(long version,List<MediaPrice> defaults,List<MediaPrice> overrides,List<MediaPrice> prices) {}
  record ApiAudit(String requestId,String operatorId,long version,String changedAt,List<MediaPrice> prices) {}
  record Snapshot(long version,boolean managed,List<MediaPrice> prices) {}
}
