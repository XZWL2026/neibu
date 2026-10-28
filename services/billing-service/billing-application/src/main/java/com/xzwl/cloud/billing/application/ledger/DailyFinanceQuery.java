package com.xzwl.cloud.billing.application.ledger;

import java.time.LocalDate;
import java.util.List;

/** Credited recharge money and net consumption yuan, by Beijing posting day. */
public interface DailyFinanceQuery {
  Report query(LocalDate from,LocalDate to,int offset,int limit);
  record Day(String date,String rechargeAmount4,String consumptionAmount4) {}
  record Report(String from,String to,List<Day> items,int totalDays,int offset,int limit,
      String totalRechargeAmount4,String totalConsumptionAmount4) {}
}
