package com.xzwl.cloud.user.application.identity;

import java.time.Instant;
import java.util.List;

public interface AdminUserDirectory {
  Page search(Query query, boolean primaryAdmin);
  default ApiPage searchApi(Query query,boolean primaryAdmin) { throw new UnsupportedOperationException(); }
  record ApiItem(Item user,Long todayCalls,Long todaySeconds,String todayAmount4,Instant lastCallAt) {}
  record ApiSummary(long totalApiUsers,Long todayCalls,Long todaySeconds,String todayAmount4,Long previousMonthTotalApiUsers,Long yesterdayCalls,Long yesterdaySeconds,String yesterdayAmount4) {}
  record ApiPage(List<ApiItem> items,long total,int offset,int limit,ApiSummary summary) {}
  Detail detail(String id, boolean primaryAdmin);
  record Query(int offset,int limit,String search,String accountType,String source,Boolean enabled,String keyStatus,Instant from,Instant to) {
    public Query {
      search=search==null?"":search.strip();
      if(offset<0||limit<1||limit>100||search.length()>128||to==null||from!=null&&!from.isBefore(to))throw new IllegalArgumentException("invalid directory query");
      if(accountType!=null&&!List.of("UNSET","PERSONAL","ENTERPRISE","API").contains(accountType)
          ||source!=null&&!List.of("NATURAL","REFERRAL","ADMIN").contains(source)
          ||keyStatus!=null&&!List.of("NONE","ENABLED","DISABLED").contains(keyStatus))throw new IllegalArgumentException("invalid directory filter");
    }
  }
  record Page(List<Item> items,long total,int offset,int limit,Summary summary) {}
  record Summary(long totalUsers,long newUsers,long activeUsers,long paidUsers,long unpaidUsers) {}
  record Item(String id,String nickname,String phone,boolean enabled,String avatarFileId,Instant registeredAt,String source,
      String accountType,String keyId,String maskedKey,String keyStatus,String availablePoints4,String availableAmount4,boolean paid,boolean active,String remark) {}
  record Detail(Item user,ConsumptionSummary summary) {}
  record ConsumptionSummary(long totalCalls,String debitedPoints4,String refundedPoints4,String netPoints4,String chargedAmount4,String totalRechargeAmount4,String netConsumptionAmount4) {}
}
