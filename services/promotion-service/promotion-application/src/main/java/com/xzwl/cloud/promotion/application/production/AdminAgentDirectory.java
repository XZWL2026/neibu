package com.xzwl.cloud.promotion.application.production;

import java.time.Instant;
import java.util.List;

public interface AdminAgentDirectory {
  DirectoryPage search(Query query, boolean fullPhone);
  Detail detail(String userId, boolean fullPhone);
  PromotionStore.Page<Downline> downlines(String userId, String search, String accountType, int page, int size, boolean fullPhone);
  PromotionStore.Page<Operation> operations(String userId, int page, int size);
  record Query(int page, int size, String search, String source, Boolean enabled, Instant from, Instant to) {
    public Query {
      pagination(page,size);
      search=search==null?"":search.strip();
      if(search.length()>128 || source!=null&&!List.of("ADMIN","APPLICATION","UNKNOWN").contains(source)
          ||from!=null&&to!=null&&!from.isBefore(to))throw new IllegalArgumentException("invalid directory filter");
    }
  }
  static void pagination(int page,int size) { if(page<0||size<1||size>100)throw new IllegalArgumentException("invalid pagination"); }
  record DirectoryPage(List<Agent> items,long total,int page,int size,Summary summary) {}
  record Summary(long totalAgents,long todayNewAgents,long todayReferredUsers,long paidDownlineUsers,String pendingCommissionAmount4,Yesterday yesterday) {}
  record Yesterday(long totalAgents,long newAgents,long referredUsers,long paidDownlineUsers,String pendingCommissionAmount4) {}
  record Agent(String userId,String nickname,String phone,boolean enabled,Instant createdAt,String source,String invitationCode,
      long invitedCount,long paidCount,String totalRechargeAmount4,String totalCommissionAmount4,String pendingCommissionAmount4,
      long personalPaidCount,long enterprisePaidCount,long apiPaidCount,Instant registeredAt) {}
  record Detail(Agent agent,long personalPaidCount,long enterprisePaidCount,long apiPaidCount,String paidPercent,
      String withdrawnAmount4,String level1CashRate6,String level2CashRate6) {}
  record Downline(String userId,String nickname,String phone,String accountType,boolean enabled,Instant registeredAt,Instant boundAt,boolean paid,String totalRechargeAmount4) {}
  record Operation(String id,String type,String operatorId,Instant occurredAt) {}
}
