package com.xzwl.cloud.billing.application.ledger;
import java.time.Instant;
import java.util.List;

/** Read-only ledger authority. A row exists only when an actual CONSUME entry exists. */
public interface ConsumptionStore {
  Page search(Filter filter);
  default DirectoryFacts directoryFacts(List<DirectoryOwner> owners, Instant from, Instant to) { throw new UnsupportedOperationException(); }
  default List<ApiDailyFact> apiDailyFacts(List<DirectoryOwner> owners,Instant from,Instant to) { throw new UnsupportedOperationException(); }
  record ApiDailyFact(String ownerId,Long todayCalls,Long todaySeconds,String todayAmount4,Instant lastCallAt) {}
  record DirectoryOwner(String ownerId,String keyId) {}
  record DirectoryFacts(List<String> paidOwners,List<String> activeOwners,List<DirectoryBalance> balances) {}
  record DirectoryBalance(String keyId,String availablePoints4) {}
  default Summary summary(String ownerId, Instant to) { return summary(ownerId, null, to); }
  Summary summary(String ownerId, Instant from, Instant to);
  default Summary directorySummary(String ownerId,String keyId,Instant to) { return summary(ownerId,null,to); }
  default DirectorySummary directoryFinancialSummary(String ownerId,String keyId,Instant to) { return new DirectorySummary(directorySummary(ownerId,keyId,to),null); }
  record DirectorySummary(Summary summary,String netConsumptionAmount4) {}
  java.util.Optional<AllocationFact> allocation(String ownerId, String allocationId, String fundingLotId);
  record AllocationFact(String ownerId, String allocationId, String fundingLotId, String type,
      String points4, String balanceBeforePoints4, String balanceAfterPoints4, String balanceBeforeAmount4, String balanceAfterAmount4) {}
  record Summary(long totalCalls, String debitedPoints4, String refundedPoints4, String netPoints4,
      String chargedAmount4, String totalRechargeAmount4) {}
  Row get(String id);
  List<Entry> entries(String id);
  record Filter(String userId,String type,String model,Instant from,Instant to,int offset,int limit) {}
  record Page(List<Row> items,long total) {}
  record Row(String id,String taskId,String keyId,String ownerId,String type,String model,String accountType,
      String createdAt,String debitedPoints4,String refundedPoints4,String netPoints4,String amount4,String rate4,
      String resolution,Integer count,Integer durationSeconds) {
    public Row(String id,String taskId,String keyId,String ownerId,String type,String model,String accountType,
        String createdAt,String debitedPoints4,String refundedPoints4,String netPoints4,String amount4,String rate4) {
      this(id,taskId,keyId,ownerId,type,model,accountType,createdAt,debitedPoints4,refundedPoints4,netPoints4,amount4,rate4,null,null,null);
    }
  }
  record Entry(String id,String requestId,String kind,String points4,String balanceBefore4,String balanceAfter4,String createdAt) {}
}
