package com.xzwl.cloud.billing.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.billing.application.ledger.MediaBilling;
import com.xzwl.cloud.billing.application.recharge.RechargeRateStore;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.pricing.*;
import com.xzwl.cloud.billing.domain.recharge.RechargeRate;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class UserSpecialPricingPersistenceTest {
  @TempDir Path directory;
  static void migrateOwnerPrices(DriverManagerDataSource ds) {
    new ResourceDatabasePopulator(
        new ClassPathResource("db/migration/V5__media_charge_receipt.sql"),
        new ClassPathResource("db/migration/V12__api_customer_prices.sql"),
        new ClassPathResource("db/migration/V13__user_special_prices.sql")).execute(ds);
  }
  private static MediaPrice price(String type,String model,String amount,boolean enabled) {
    return new MediaPrice("IMAGE",type,model,"standard","IMAGE",null,amount,enabled);
  }
  @Test void migrationPreservesLegacyApiOverridesAndAccountTypesCanCoexist() {
    var ds=new DriverManagerDataSource("jdbc:h2:file:"+directory.resolve("migration")+";MODE=MySQL","sa","");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__media_charge_receipt.sql"),
        new ClassPathResource("db/migration/V6__media_prices.sql"),new ClassPathResource("db/migration/V7__media_yuan_prices.sql"),
        new ClassPathResource("db/migration/V12__api_customer_prices.sql")).execute(ds);
    var jdbc=new JdbcTemplate(ds);
    jdbc.update("INSERT INTO api_media_price_catalog(owner_id,version) VALUES('owner',1)");
    jdbc.update("INSERT INTO api_media_price_override(owner_id,media_type,model,resolution,yuan_per_unit) VALUES('owner','IMAGE','model','standard',0.1111)");
    jdbc.update("INSERT INTO api_media_price_audit(request_id,owner_id,operator_id,expected_version,version,prices_json,snapshot_json,changed_at) VALUES('legacy','owner','admin',0,1,'[]','{}','2026-09-20T00:00:00Z')");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V13__user_special_prices.sql")).execute(ds);
    var store=new JdbcMediaPriceStore(jdbc,new DataSourceTransactionManager(ds));
    store.save(1,List.of(price("API","model","4",true),price("PERSONAL","model","2",true),price("ENTERPRISE","model","3",true)));
    assertEquals(List.of(price("API","model","0.1111",true)),store.loadApi("owner").prices());
    assertEquals("legacy",store.apiAudit("owner").getFirst().requestId());
    assertTrue(store.ownerAudit("owner","PERSONAL").isEmpty());
    store.saveOwner("owner","PERSONAL","personal","admin",1,List.of(price("PERSONAL","model","0.2222",true)));
    store.saveOwner("owner","ENTERPRISE","enterprise","admin",2,List.of(price("ENTERPRISE","model","0.3333",true)));
    assertEquals(List.of(price("API","model","0.1111",true)),store.loadApi("owner").prices());
    assertEquals(List.of(price("PERSONAL","model","0.2222",true)),store.loadOwner("owner","PERSONAL").prices());
    assertEquals(List.of(price("ENTERPRISE","model","0.3333",true)),store.loadOwner("owner","ENTERPRISE").prices());
    store.saveApi("owner","clear-api","admin",3,List.of());
    assertEquals(List.of(price("API","model","4",true)),store.loadApi("owner").prices());
    assertEquals(List.of(price("PERSONAL","model","0.2222",true)),store.loadOwner("owner","PERSONAL").prices());
    assertEquals(List.of(price("ENTERPRISE","model","0.3333",true)),store.loadOwner("owner","ENTERPRISE").prices());
  }
  @Test void everyAccountTypeInheritsOverridesResetsAndStaysIsolatedWithStableReplay() {
    var ds=new DriverManagerDataSource("jdbc:h2:file:"+directory.resolve("prices")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V6__media_prices.sql"),
        new ClassPathResource("db/migration/V7__media_yuan_prices.sql")).execute(ds);
    migrateOwnerPrices(ds);
    var store=new JdbcMediaPriceStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));
    var defaults=List.of(price("PERSONAL","model","2",true),price("ENTERPRISE","model","3",true),price("API","model","4",true),
        price("PERSONAL","inherited","5",true),price("ENTERPRISE","inherited","6",true),price("API","inherited","7",true));
    store.save(1,defaults);
    var rates=new RechargeRateStore() {
      public RechargeRate load(){return new RechargeRate(Decimal4.of("10"),1);}
      public void save(RechargeRate rate){throw new UnsupportedOperationException();}
    };
    for(var accountType:List.of("PERSONAL","ENTERPRISE","API")) {
      var original=store.loadOwner("owner",accountType);
      assertEquals(defaults.stream().filter(p->p.accountType().equals(accountType)).toList().size(),original.prices().size());
      assertTrue(original.overrides().isEmpty());
      var override=price(accountType,"model","0.1234",true);
      var saved=store.saveOwner("owner",accountType,"save-"+accountType,"admin",original.version(),List.of(override));
      assertEquals(saved,new JdbcMediaPriceStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds)).loadOwner("owner",accountType));
      var billing=new MybatisMediaBilling(null,owner->new MediaBilling.Key("key",owner,accountType,"API".equals(accountType)?"API":"MEDIA",true),PricingCatalog.empty(),rates,null,store);
      var request=new MediaBilling.QuoteRequest("owner","IMAGE","model","standard",2,0);
      assertEquals("0.2468",billing.quote(request).amount().toPlainString());
      assertEquals("2.4680",billing.quote(request).points().toPlainString());
      assertEquals("0.1234",billing.availablePrices("owner").stream().filter(p->p.model().equals("model")).findFirst().orElseThrow().amountPerUnit().toPlainString());
      assertEquals(original.prices(),store.loadOwner("another",accountType).prices());
      assertEquals(original.defaults().stream().filter(p->p.model().equals("inherited")).findFirst().orElseThrow(),saved.prices().stream().filter(p->p.model().equals("inherited")).findFirst().orElseThrow());
      assertEquals(saved,store.saveOwner("owner",accountType,"save-"+accountType,"admin",original.version(),List.of(override)));
      assertThrows(IllegalStateException.class,()->store.saveOwner("owner",accountType,"save-"+accountType,"admin",original.version(),List.of()));
      assertThrows(IllegalStateException.class,()->store.saveOwner("another",accountType,"save-"+accountType,"admin",original.version(),List.of(override)));
      assertThrows(IllegalStateException.class,()->store.saveOwner("owner",accountType,"stale-"+accountType,"admin",original.version(),List.of()));
      String wrongType=accountType.equals("PERSONAL")?"API":"PERSONAL";
      assertThrows(IllegalArgumentException.class,()->store.saveOwner("owner",accountType,"wrong-"+accountType,"admin",saved.version(),List.of(price(wrongType,"model","1",true))));
      assertTrue(store.loadOwner("owner",wrongType).overrides().isEmpty());
      assertThrows(IllegalStateException.class,()->store.saveOwner("owner",wrongType,"save-"+accountType,"admin",original.version(),List.of(override)));
      var disabled=defaults.stream().map(p->p.model().equals("model")?price(p.accountType(),p.model(),p.yuanPerUnit(),false):p).toList();
      store.save(store.load().version(),disabled);
      assertThrows(IllegalArgumentException.class,()->billing.quote(request));
      assertEquals(1,billing.availablePrices("owner").size());
      assertThrows(IllegalArgumentException.class,()->store.saveOwner("owner",accountType,"disabled-"+accountType,"admin",saved.version(),List.of(override)));
      store.save(store.load().version(),defaults);
      var reset=store.saveOwner("owner",accountType,"reset-"+accountType,"admin",saved.version(),List.of());
      assertEquals(original.defaults(),reset.prices());
      assertThrows(IllegalStateException.class,()->store.saveOwner("owner",wrongType,"reset-"+accountType,"admin",saved.version(),List.of()));
      assertEquals(saved,store.saveOwner("owner",accountType,"save-"+accountType,"admin",original.version(),List.of(override)));
      assertEquals(reset,store.loadOwner("owner",accountType));
      assertEquals(2,store.ownerAudit("owner",accountType).size());
      assertEquals(List.of(override),store.ownerAudit("owner",accountType).get(1).prices());
      assertTrue(store.ownerAudit("another",accountType).isEmpty());
    }
    assertEquals(store.loadOwner("owner","API"),store.loadApi("owner"));
    assertEquals(store.loadForOwner("owner","API"),store.loadForOwner("owner"));
    assertThrows(IllegalArgumentException.class,()->store.loadOwner("owner","ADMIN"));
  }
}
