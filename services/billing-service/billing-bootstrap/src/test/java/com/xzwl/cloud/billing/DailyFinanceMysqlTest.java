package com.xzwl.cloud.billing;

import com.xzwl.cloud.billing.application.ledger.DailyFinanceQuery;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_MYSQL_URL",matches=".+")
class DailyFinanceMysqlTest {
  @Test void aggregatesCreditedMoneyAndNetConsumptionByBeijingDayWithoutHistoryTruncation() {
    String root=System.getenv("BILLING_TEST_MYSQL_URL"),user=System.getenv("BILLING_TEST_MYSQL_USERNAME"),password=System.getenv("BILLING_TEST_MYSQL_PASSWORD");
    assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    String database="daily_finance_"+UUID.randomUUID().toString().replace("-","");
    var admin=new JdbcTemplate(new DriverManagerDataSource(root,user,password));admin.execute("CREATE DATABASE "+database+" COLLATE utf8mb4_0900_bin");
    try(var app=BillingLedgerMysqlTest.start(Map.of("spring.datasource.url",root+database,"spring.datasource.username",user,"spring.datasource.password",password,"abp.internal-token","test-only-service-token-0000000000000000"))) {
      var sql=app.getBean(JdbcTemplate.class);var query=app.getBean(DailyFinanceQuery.class);
      sql.update("INSERT INTO wallet(wallet_id,available_points4,version) VALUES ('wallet',1000,1)");
      // A late UTC evening is the next Beijing day; more than 100 credits must all count.
      for(int i=0;i<101;i++)sql.update("INSERT INTO recharge_credit_record VALUES (?,?,?, ?,1.2345,1,1.2345,?,1000,?)","paid"+i,"owner","wallet","wallet","2026-09-10T16:00:00Z",i+2);
      entry(sql,"debit","CONSUME","-12.3456","2026-09-11T15:59:59.999Z",200);
      sql.update("UPDATE media_charge_receipt SET amount4=1.2345,rate4=10 WHERE operation_id='debit'");
      entry(sql,"refund","REFUND","2.1234","2026-09-11T16:00:00Z",201);
      sql.update("UPDATE wallet_ledger SET source_id='debit' WHERE entry_id='refund'");
      entry(sql,"gift","GIFT","999","2026-09-11T00:00:00Z",202);
      var first=query.query(LocalDate.parse("2026-09-11"),LocalDate.parse("2026-09-11"),0,30);
      assertEquals("124.6845",first.totalRechargeAmount4());assertEquals("1.2345",first.totalConsumptionAmount4());assertEquals(1,first.totalDays());
      var second=query.query(LocalDate.parse("2026-09-12"),LocalDate.parse("2026-09-12"),0,30);
      assertEquals("-0.2123",second.totalConsumptionAmount4());assertEquals("0.0000",second.totalRechargeAmount4());
      for(int i=1;i<=31;i++)entry(sql,"aug"+i,"CONSUME","-0.0001",String.format("2026-08-%02dT00:00:00Z",i),300+i);
      var all=query.query(null,null,0,30);assertEquals(33,all.totalDays());assertEquals(30,all.items().size());assertEquals("2026-09-12",all.items().getFirst().date());assertEquals("1.0253",all.totalConsumptionAmount4());
      var page=query.query(null,null,30,30);assertEquals(3,page.items().size());assertEquals(all.totalRechargeAmount4(),page.totalRechargeAmount4());assertEquals(all.totalConsumptionAmount4(),page.totalConsumptionAmount4());
      var outside=query.query(null,null,Integer.MAX_VALUE,30);assertTrue(outside.items().isEmpty());assertEquals(33,outside.totalDays());
      var full=query.query(LocalDate.of(1,1,1),LocalDate.of(9999,12,31),0,30);assertEquals(all.items(),full.items());
      var empty=query.query(LocalDate.of(9999,12,31),LocalDate.of(9999,12,31),0,30);assertTrue(empty.items().isEmpty());assertEquals("0.0000",empty.totalRechargeAmount4());
      entry(sql,"split","CONSUME","-3.0000","2026-10-01T00:00:00Z",400);
      sql.update("UPDATE media_charge_receipt SET amount4=1.0001,rate4=3 WHERE operation_id='split'");
      for(int i=1;i<=3;i++){
        entry(sql,"split-refund"+i,"REFUND","1.0000","2026-10-0"+(i+1)+"T00:00:00Z",400+i);
        sql.update("UPDATE wallet_ledger SET source_id='split' WHERE entry_id=?","split-refund"+i);
      }
      assertEquals("0.0000",query.query(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-04"),0,30).totalConsumptionAmount4());
      assertEquals("-0.3334",query.query(LocalDate.parse("2026-10-04"),LocalDate.parse("2026-10-04"),0,30).totalConsumptionAmount4());
      entry(sql,"unknown","CONSUME","-1.0000","2026-10-05T00:00:00Z",404);
      sql.update("DELETE FROM media_charge_receipt WHERE operation_id='unknown'");
      var unknown=query.query(LocalDate.parse("2026-10-05"),LocalDate.parse("2026-10-05"),0,30);
      assertNull(unknown.totalConsumptionAmount4());assertNull(unknown.items().getFirst().consumptionAmount4());
      assertEquals("0.0000",unknown.totalRechargeAmount4());assertNull(query.query(null,null,0,30).totalConsumptionAmount4());
      entry(sql,"precise","CONSUME","-1000000000000.0000","2026-10-10T00:00:00Z",500);
      sql.update("UPDATE media_charge_receipt SET amount4=1 WHERE operation_id='precise'");
      entry(sql,"precise-refund","REFUND","999999999999.9999","2026-10-11T00:00:00Z",501);
      sql.update("UPDATE wallet_ledger SET source_id='precise' WHERE entry_id='precise-refund'");
      assertEquals("-0.9999",query.query(LocalDate.parse("2026-10-11"),LocalDate.parse("2026-10-11"),0,30).totalConsumptionAmount4());
      entry(sql,"precise-last","REFUND","0.0001","2026-10-12T00:00:00Z",502);
      sql.update("UPDATE wallet_ledger SET source_id='precise' WHERE entry_id='precise-last'");
      assertEquals("0.0000",query.query(LocalDate.parse("2026-10-10"),LocalDate.parse("2026-10-12"),0,30).totalConsumptionAmount4());
    } finally {admin.execute("DROP DATABASE "+database);}
  }
  static void entry(JdbcTemplate sql,String id,String kind,String points,String at,int version) {
    if(kind.equals("CONSUME"))sql.update("INSERT INTO media_charge_receipt(operation_id,owner_id,key_id,request_facts,amount4,rate4,points4) VALUES (?,'owner','wallet','test',?,1,?)",id,new java.math.BigDecimal(points).negate(),new java.math.BigDecimal(points).negate());
    sql.update("INSERT INTO wallet_ledger VALUES (?,'wallet',?,?,?, ?,1000,?,?)",id,id,kind,id,points,version,at);
  }
}
