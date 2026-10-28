package com.xzwl.cloud.billing.infrastructure;

import com.xzwl.cloud.billing.application.ledger.DailyFinanceQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDailyFinanceQuery implements DailyFinanceQuery {
  private final JdbcTemplate jdbc;
  public JdbcDailyFinanceQuery(JdbcTemplate jdbc){this.jdbc=jdbc;}
  @Override public Report query(LocalDate from,LocalDate to,int offset,int limit) {
    if(offset<0||limit<1||limit>30||(from==null)!=(to==null)
        ||from!=null&&(from.isAfter(to)||from.getYear()<1||to.getYear()>9999))throw new IllegalArgumentException("invalid daily finance range");
    var args=new ArrayList<Object>();
    String where="";
    if(from!=null){where=" WHERE day>=? AND day<=?";args.add(from.toString());args.add(to.toString());}
    // Only daily aggregates cross the JDBC boundary; no account/task enrichment or truncated history lists.
    // Compute refund portions over the full charge history before applying the selected date range.
    String entries="SELECT l.*,r.amount4,r.points4 charged_points,"
        +"SUM(CASE WHEN l.kind='REFUND' THEN l.points4 ELSE 0 END) OVER (PARTITION BY l.wallet_id,l.source_id ORDER BY l.balance_version ROWS UNBOUNDED PRECEDING) refunded "
        +"FROM wallet_ledger l LEFT JOIN media_charge_receipt r ON r.operation_id=l.source_id AND r.key_id=l.wallet_id WHERE l.kind IN ('CONSUME','REFUND')";
    String sql="SELECT day,SUM(recharge) recharge,CASE WHEN COUNT(*)=COUNT(consumption) THEN SUM(consumption) ELSE NULL END consumption FROM (SELECT "
        +day("paid_at")+" day,amount4 recharge,0 consumption FROM recharge_credit_record UNION ALL SELECT "
        +day("recorded_at")+" day,0 recharge,CASE WHEN amount4 IS NULL OR charged_points<=0 THEN NULL WHEN kind='CONSUME' THEN amount4 "
        +"ELSE -(TRUNCATE(amount4*refunded/charged_points,4)-TRUNCATE(amount4*(refunded-points4)/charged_points,4)) END consumption FROM ("+entries+") ledger) facts"
        +where+" GROUP BY day ORDER BY day DESC";
    var days=jdbc.query(sql,(rs,n)->new Day(rs.getString("day"),decimal(rs.getBigDecimal("recharge")),
        decimal(rs.getBigDecimal("consumption"))),args.toArray());
    BigDecimal recharge=BigDecimal.ZERO,consumption=BigDecimal.ZERO;
    for(var item:days){
      recharge=recharge.add(new BigDecimal(item.rechargeAmount4()));
      consumption=consumption==null||item.consumptionAmount4()==null?null:consumption.add(new BigDecimal(item.consumptionAmount4()));
    }
    int start=Math.min(offset,days.size()),end=(int)Math.min((long)start+limit,days.size());
    return new Report(from==null?null:from.toString(),to==null?null:to.toString(),List.copyOf(days.subList(start,end)),days.size(),offset,limit,
        decimal(recharge),decimal(consumption));
  }
  private static String decimal(BigDecimal value){return value==null?null:value.setScale(4).toPlainString();}
  private static String day(String column){
    return "DATE_FORMAT(DATE_ADD(CAST(SUBSTRING(REPLACE(REPLACE("+column+",'T',' '),'Z',''),1,26) AS DATETIME(6)),INTERVAL 8 HOUR),'%Y-%m-%d')";
  }
}
