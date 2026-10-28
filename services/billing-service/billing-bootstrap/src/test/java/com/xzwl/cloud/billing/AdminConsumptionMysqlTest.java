package com.xzwl.cloud.billing;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.application.credit.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.infrastructure.JdbcConsumptionStore;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_MYSQL_URL",matches=".+")
class AdminConsumptionMysqlTest {
  @Test void pagesAllDebitsAndExplainsMultipleRefundsWithoutInventingMoney() {
    String root=System.getenv("BILLING_TEST_MYSQL_URL");
    assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    String schema="consumption_"+UUID.randomUUID().toString().replace("-","");
    String user=System.getenv("BILLING_TEST_MYSQL_USERNAME"),password=System.getenv("BILLING_TEST_MYSQL_PASSWORD");
    var sql=new JdbcTemplate(new DriverManagerDataSource(root,user,password));
    sql.execute("CREATE DATABASE "+schema+" COLLATE utf8mb4_0900_bin");
    try(var app=BillingLedgerMysqlTest.start(Map.of("spring.datasource.url",root+schema,
        "spring.datasource.username",user,"spring.datasource.password",password,
        "abp.internal-token","test-only-service-token-0000000000000000"))) {
      var ledger=app.getBean(BillingLedgerStore.class);
      app.getBean(com.xzwl.cloud.billing.application.recharge.CreditPaidRechargeHandler.class).handle(new com.xzwl.cloud.billing.application.recharge.CreditPaidRechargeCommand("seed","owner","disabled-key","disabled-key",Decimal4.of("100"),Decimal4.of("10"),Instant.now()));
      for(int i=0;i<105;i++) ledger.consume("disabled-key","task-"+i,Decimal4.of("4"));
      ledger.refund("disabled-key","refund-a","task-0",Decimal4.of("1.0001"));
      ledger.refund("disabled-key","refund-b","task-0",Decimal4.of("0.9999"));
      var local=app.getBean(JdbcTemplate.class);
      local.update("INSERT INTO media_charge_receipt(operation_id,owner_id,key_id,request_facts,amount4,rate4,points4,account_type) VALUES(?,?,?,?,?,?,?,?)","task-0","owner","disabled-key","owner\nIMAGE\nmodel-v1\n2K\n4\n0",new java.math.BigDecimal("0.4000"),new java.math.BigDecimal("10"),new java.math.BigDecimal("4"),"PERSONAL");
      var store=new JdbcConsumptionStore(local);
      // Beijing midnight: old repeat payments count once; the exclusive cutoff excludes today's first payment.
      Instant midnight=Instant.parse("2026-09-21T16:00:00Z");
      String[][] history={{"history-old","history-paid","2026-09-20T16:00:00Z"},
          {"history-repeat","history-paid","2026-09-21T15:59:59.999999Z"},
          {"history-boundary","history-boundary","2026-09-21T16:00:00Z"},
          {"history-after","history-after","2026-09-21T16:00:00.000001Z"}};
      for(var row:history)local.update("INSERT INTO recharge_credit_record SELECT ?,?,wallet_id,key_id,amount4,rate_snapshot4,points4,?,balance_after4,balance_version FROM recharge_credit_record WHERE order_id='seed'",row[0],row[1],row[2]);
      var historyOwners=List.of(new ConsumptionStore.DirectoryOwner("history-paid",null),new ConsumptionStore.DirectoryOwner("history-boundary",null),new ConsumptionStore.DirectoryOwner("history-after",null),new ConsumptionStore.DirectoryOwner("history-unpaid",null));
      assertEquals(List.of("history-paid"),store.directoryFacts(historyOwners,null,midnight).paidOwners());
      assertEquals(Set.of("history-paid","history-boundary"),Set.copyOf(store.directoryFacts(historyOwners,null,midnight.plusNanos(1000)).paidOwners()));
      assertEquals(Set.of("history-paid","history-boundary","history-after"),Set.copyOf(store.directoryFacts(historyOwners,null,midnight.plusNanos(2000)).paidOwners()));
      assertEquals(Set.of("history-paid","history-boundary"),Set.copyOf(store.directoryFacts(historyOwners,midnight,midnight.plusNanos(1000)).paidOwners()),"a registration/activity lower bound does not reset cumulative paid status");
      assertTrue(store.directoryFacts(historyOwners,null,midnight.minusSeconds(86400)).paidOwners().isEmpty());
      var directory=store.directoryFacts(List.of(new ConsumptionStore.DirectoryOwner("owner","disabled-key"),new ConsumptionStore.DirectoryOwner("unpaid",null)),null,Instant.now().plusSeconds(1));
      assertEquals(List.of("owner"),directory.paidOwners());
      assertTrue(directory.activeOwners().contains("owner"),"legacy ledger activity without snapshots counts");
      assertEquals("582.0000",directory.balances().getFirst().availablePoints4());
      var unopened=store.directoryFacts(List.of(new ConsumptionStore.DirectoryOwner("unpaid","unopened-key")),null,Instant.now());
      assertEquals("0.0000",unopened.balances().getFirst().availablePoints4(),"missing wallet is explicitly zero in the existing ledger balance contract");
      var legacySummary=store.directorySummary("owner","disabled-key",Instant.now().plusSeconds(1));
      assertEquals(105,legacySummary.totalCalls());assertEquals("418.0000",legacySummary.netPoints4());
      assertNull(legacySummary.chargedAmount4(),"historical CNY without snapshots is unknown");
      assertNull(store.directoryFinancialSummary("owner","disabled-key",Instant.now()).netConsumptionAmount4());

      assertTrue(store.directoryFacts(List.of(new ConsumptionStore.DirectoryOwner("owner","disabled-key")),Instant.now().plusSeconds(1),Instant.now().plusSeconds(2)).activeOwners().isEmpty());

      for (int i=1;i<105;i++) local.update("INSERT INTO media_charge_receipt(operation_id,owner_id,key_id,request_facts,amount4,rate4,points4,account_type) VALUES(?,?,?,?,?,?,?,?)", "task-"+i,
          i==104 ? "other-owner" : "owner", "disabled-key", "owner\nIMAGE\nmodel-v1\n2K\n4\n0", new java.math.BigDecimal("0.4000"), new java.math.BigDecimal("10"), new java.math.BigDecimal("4"), "API");
      var summary=store.summary("owner", Instant.now());
      assertEquals(104, summary.totalCalls());
      assertEquals("416.0000", summary.debitedPoints4());
      assertEquals("2.0000", summary.refundedPoints4());
      assertEquals("414.0000", summary.netPoints4());
      assertEquals("41.6000", summary.chargedAmount4());
      assertEquals("100.0000", summary.totalRechargeAmount4());
      local.update("UPDATE recharge_conversion_rate SET points_per_yuan4=1000 WHERE id=1");
      assertEquals("41.4000",store.directoryFinancialSummary("owner","disabled-key",Instant.now()).netConsumptionAmount4(),"refunds use each original rate, not current rate");
      assertEquals(1, store.summary("other-owner", Instant.now()).totalCalls());
      Instant dayStart = Instant.now().minusSeconds(3600), cutoff = Instant.now().plusSeconds(2);
      local.update("UPDATE wallet_ledger SET recorded_at=? WHERE source_id='task-1' AND kind='CONSUME'", dayStart.minusSeconds(1).toString());
      local.update("UPDATE recharge_credit_record SET paid_at=? WHERE order_id='seed'", dayStart.minusSeconds(1).toString());
      var today = store.summary("owner", dayStart, cutoff);
      assertEquals(103, today.totalCalls());
      assertEquals("41.2000", today.chargedAmount4());
      assertEquals("410.0000", today.netPoints4());
      assertEquals("0.0000", today.totalRechargeAmount4());
      local.update("UPDATE wallet_ledger SET recorded_at=? WHERE request_id='refund-b' AND kind='REFUND'", cutoff.plusSeconds(1).toString());
      assertEquals("410.9999", store.summary("owner", dayStart, cutoff).netPoints4());
      assertEquals(104, store.summary("owner", cutoff).totalCalls());
      assertThrows(IllegalArgumentException.class, () -> store.summary("owner", cutoff, dayStart));
      local.update("DELETE FROM media_charge_receipt WHERE operation_id <> 'task-0'");
      String allocation = local.queryForObject("SELECT id FROM funding_allocation WHERE operation_id='task-0'", String.class);
      var fact=store.allocation("owner",allocation,"recharge:seed").orElseThrow();
      assertEquals("IMAGE", fact.type()); assertEquals("4.0000", fact.points4());
      assertEquals("1000.0000", fact.balanceBeforePoints4()); assertEquals("996.0000", fact.balanceAfterPoints4());
      assertEquals("100.0000", fact.balanceBeforeAmount4()); assertEquals("99.6000", fact.balanceAfterAmount4());
      // The receipt rate, not today's policy or net refunds, values the original debit balances.
      local.update("UPDATE media_charge_receipt SET rate4=3 WHERE operation_id='task-0'");
      assertEquals("333.3333", store.allocation("owner",allocation,"recharge:seed").orElseThrow().balanceBeforeAmount4());
      for (int invalidRate : new int[]{0,-1}) {
        local.update("UPDATE media_charge_receipt SET rate4=? WHERE operation_id='task-0'", invalidRate);
        var unknown=store.allocation("owner",allocation,"recharge:seed").orElseThrow();
        assertNull(unknown.balanceBeforeAmount4()); assertNull(unknown.balanceAfterAmount4());
        assertNull(store.directoryFinancialSummary("owner",null,Instant.now()).netConsumptionAmount4(),"invalid historical rates remain unknown");
      }
      local.update("UPDATE media_charge_receipt SET rate4=10 WHERE operation_id='task-0'");
      assertTrue(store.allocation("other-owner",allocation,"recharge:seed").isEmpty());
      assertTrue(store.allocation("owner",allocation,"different-lot").isEmpty());
      assertTrue(store.allocation("owner","unmatched","recharge:seed").isEmpty());
      var ids=new HashSet<String>();
      for(int offset=0;offset<105;offset+=20) {
        var page=store.search(new ConsumptionStore.Filter(null,null,null,null,Instant.now(),offset,20));
        assertEquals(105,page.total());
        page.items().forEach(r->assertTrue(ids.add(r.id())));
      }
      assertEquals(105,ids.size());
      var page=store.search(new ConsumptionStore.Filter("owner","IMAGE","model-v1",null,Instant.now(),0,20));
      assertEquals(1,page.total());
      var row=page.items().getFirst();
      assertEquals("4.0000",row.debitedPoints4());
      assertEquals("2.0000",row.refundedPoints4());
      assertEquals("2.0000",row.netPoints4());
      assertEquals("0.4000",row.amount4());
      assertEquals("PERSONAL",row.accountType());
      assertEquals("2K",row.resolution());
      assertEquals(4,row.count());
      assertEquals(0,row.durationSeconds());
      assertEquals(row.id(),store.get(row.taskId()).id());
      assertEquals(0,store.search(new ConsumptionStore.Filter(null,null,null,Instant.now().plusSeconds(1),Instant.now().plusSeconds(2),0,20)).total());
      var entries=store.entries(row.id());
      assertEquals(3,entries.size());
      assertEquals("1000.0000",entries.getFirst().balanceBefore4());
      assertEquals("996.0000",entries.getFirst().balanceAfter4());
      assertEquals("REFUND",entries.getLast().kind());
      var missing=store.search(new ConsumptionStore.Filter(null,null,null,null,Instant.now(),0,20)).items().getFirst();
      assertNull(missing.amount4());
      assertEquals(0,store.search(new ConsumptionStore.Filter(null,"VIDEO",null,null,Instant.now(),0,20)).total());
      assertEquals(0,store.search(new ConsumptionStore.Filter(null,"IMAGE","missing",null,Instant.now(),0,20)).total());
      assertThrows(IllegalArgumentException.class,()->store.search(new ConsumptionStore.Filter(null,null,null,null,Instant.now(),-1,20)));
      assertThrows(NoSuchElementException.class,()->store.get("non-ledger-task"));
      app.getBean(com.xzwl.cloud.billing.application.recharge.CreditPaidRechargeHandler.class).handle(new com.xzwl.cloud.billing.application.recharge.CreditPaidRechargeCommand("net-seed","net-owner","net-key","net-key",Decimal4.of("100"),Decimal4.of("10"),Instant.now()));
      ledger.consume("net-key","priced-a",Decimal4.of("100"));ledger.consume("net-key","priced-b",Decimal4.of("80"));
      local.update("INSERT INTO media_charge_receipt(operation_id,owner_id,key_id,request_facts,amount4,rate4,points4,account_type) VALUES('priced-a','net-owner','net-key','facts',10,10,100,'PERSONAL')");
      local.update("INSERT INTO media_charge_receipt(operation_id,owner_id,key_id,request_facts,amount4,rate4,points4,account_type) VALUES('priced-b','net-owner','net-key','facts',4,20,80,'PERSONAL')");
      ledger.refund("net-key","partial-a","priced-a",Decimal4.of("40"));ledger.refund("net-key","partial-b","priced-b",Decimal4.of("20"));
      var priced=store.directoryFinancialSummary("net-owner","net-key",Instant.now().plusSeconds(1));
      assertEquals("14.0000",priced.summary().chargedAmount4());assertEquals("120.0000",priced.summary().netPoints4());
      assertEquals("9.0000",priced.netConsumptionAmount4(),"each refund uses its own historical rate");
      ledger.refund("net-key","full-a","priced-a",Decimal4.of("60"));ledger.refund("net-key","full-b","priced-b",Decimal4.of("60"));
      assertEquals("0.0000",store.directoryFinancialSummary("net-owner","net-key",Instant.now().plusSeconds(1)).netConsumptionAmount4());
    } finally {sql.execute("DROP DATABASE "+schema);}
  }
}
