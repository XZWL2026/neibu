package com.xzwl.cloud.billing.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.billing.application.ledger.MediaBilling;
import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.pricing.*;
import com.xzwl.cloud.billing.domain.recharge.RechargeRate;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class MediaPricingPersistenceTest {
  @Test void enhancementUsesConfiguredSecondsPricesAndRejectsMissingOrZeroPrices() {
    var configured=new java.util.concurrent.atomic.AtomicReference<List<MediaPrice>>(List.of(
        new MediaPrice("ENHANCEMENT","PERSONAL","video-enhancement","2K","SECOND",null,"0.2500",true)));
    var store=new MediaPriceStore() {
      public Snapshot load(){return new Snapshot(1,true,configured.get());}
      public Snapshot save(long version,List<MediaPrice> prices){throw new UnsupportedOperationException();}
    };
    var rates=new com.xzwl.cloud.billing.application.recharge.RechargeRateStore() {
      public RechargeRate load(){return new RechargeRate(Decimal4.of("10"),1);}
      public void save(RechargeRate rate){throw new UnsupportedOperationException();}
    };
    var billing=new MybatisMediaBilling(null,owner->new MediaBilling.Key("key",owner,"PERSONAL","MEDIA",true),PricingCatalog.empty(),rates,null,store);
    var request=new MediaBilling.QuoteRequest("owner","ENHANCEMENT","video-enhancement","2K",1,6);
    assertEquals("1.5000",billing.quote(request).amount().toPlainString());
    assertEquals("15.0000",billing.quote(request).points().toPlainString());
    configured.set(List.of());
    assertThrows(IllegalArgumentException.class,()->billing.quote(request));
    assertThrows(IllegalArgumentException.class,()->new MediaPrice("ENHANCEMENT","PERSONAL","video-enhancement","2K","SECOND",null,"0.0000",true));
  }
  @Test void apiAccountUsesOnlyApiPricesAndRejectsDisabledOrMismatchedKeys() {
    var prices=PricingCatalog.empty()
        .withPrice(new PricingEntry(new PricingKey("API","model","standard"),Decimal4.of("2")))
        .withPrice(new PricingEntry(new PricingKey("PERSONAL","model","standard"),Decimal4.of("9")));
    var current=new java.util.concurrent.atomic.AtomicReference<>(new MediaBilling.Key("key","owner","API","API",true));
    var rates=new com.xzwl.cloud.billing.application.recharge.RechargeRateStore() {
      public RechargeRate load(){return new RechargeRate(Decimal4.of("10"),1);}
      public void save(RechargeRate rate){throw new UnsupportedOperationException();}
    };
    var billing=new MybatisMediaBilling(null,owner->current.get(),prices,rates,null);
    var request=new MediaBilling.QuoteRequest("owner","IMAGE","model","standard",2,0);
    assertEquals("40.0000",billing.quote(request).points().toPlainString());
    assertEquals(1,billing.availablePrices("owner").size());
    for(var invalid:List.of(new MediaBilling.Key("key","owner","API","API",false),
        new MediaBilling.Key("key","other","API","API",true),
        new MediaBilling.Key("key","owner","API","MEDIA",true))) {
      current.set(invalid);assertThrows(IllegalArgumentException.class,()->billing.quote(request));
    }
  }
  @TempDir Path directory;
  @Test void yuanPricePersistsAndUsesCurrentRateWithoutChangingTheYuanQuote() {
    var ds=new DriverManagerDataSource("jdbc:h2:file:"+directory.resolve("yuan")+";MODE=MySQL","sa","");
    var jdbc=new JdbcTemplate(ds);
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V6__media_prices.sql")).execute(ds);
    jdbc.update("INSERT INTO media_price VALUES('IMAGE','PERSONAL','legacy','2k','IMAGE',12.3456,TRUE)");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V7__media_yuan_prices.sql")).execute(ds);
    UserSpecialPricingPersistenceTest.migrateOwnerPrices(ds);
    var store=new JdbcMediaPriceStore(jdbc,new DataSourceTransactionManager(ds));
    var legacy=store.load().prices().getFirst();
    assertEquals("12.3456",legacy.pointsPerUnit()); assertNull(legacy.yuanPerUnit());
    var rate=new com.xzwl.cloud.billing.application.recharge.RechargeRateStore() {
      RechargeRate value=new RechargeRate(Decimal4.of("10"),1);
      public RechargeRate load(){return value;}
      public void save(RechargeRate next){value=next;}
    };
    var price=new MediaPrice("VIDEO","PERSONAL","video","720p","SECOND",null,"0.5000",true);
    store.save(1,List.of(legacy,price));
    var reopened=new JdbcMediaPriceStore(jdbc,new DataSourceTransactionManager(ds));
    assertEquals(List.of(legacy,price),reopened.load().prices());
    var billing=new MybatisMediaBilling(null,owner->new MediaBilling.Key("key",owner,"PERSONAL","MEDIA",true),PricingCatalog.empty(),rate,null,reopened);
    var request=new MediaBilling.QuoteRequest("owner","VIDEO","video","720p",1,4);
    assertEquals("2.0000",billing.quote(request).amount().toPlainString());
    assertEquals("20.0000",billing.quote(request).points().toPlainString());
    rate.save(new RechargeRate(Decimal4.of("20"),2));
    assertEquals("2.0000",billing.quote(request).amount().toPlainString());
    assertEquals("40.0000",billing.quote(request).points().toPlainString());
    assertEquals("0.5000",billing.availablePrices("owner").stream().filter(p->p.model().equals("video")).findFirst().orElseThrow().amountPerUnit().toPlainString());
    assertThrows(IllegalArgumentException.class,()->new MediaPrice("IMAGE","PERSONAL","m","2k","IMAGE","1","1",true));
    assertThrows(IllegalArgumentException.class,()->new MediaPrice("IMAGE","PERSONAL","m","2k","IMAGE",null,"0.12345",true));
  }
  @Test void savedPointsTakeEffectImmediatelySurviveReopenAndDisabledPricesNeverFallBack() {
    String url="jdbc:h2:file:"+directory.resolve("prices")+";MODE=MySQL";
    var ds=new DriverManagerDataSource(url,"sa","");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V6__media_prices.sql"),new ClassPathResource("db/migration/V7__media_yuan_prices.sql")).execute(ds);
    UserSpecialPricingPersistenceTest.migrateOwnerPrices(ds);
    var store=new JdbcMediaPriceStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));
    var billing=new MybatisMediaBilling(null,owner->new MediaBilling.Key("key",owner,"PERSONAL","MEDIA",true),
        PricingCatalog.empty().withPrice(new PricingEntry(new PricingKey("PERSONAL","image-model","2k"),Decimal4.of("1"))),
        new com.xzwl.cloud.billing.application.recharge.RechargeRateStore() {
          public RechargeRate load(){return new RechargeRate(Decimal4.of("10"),1);}
          public void save(RechargeRate r){throw new UnsupportedOperationException();}
        },null,store);
    var request=new MediaBilling.QuoteRequest("owner","IMAGE","image-model","2k",2,0);
    assertEquals("20.0000",billing.quote(request).points().toPlainString());
    var price=new MediaPrice("IMAGE","PERSONAL","image-model","2k","IMAGE","12.3456",true);
    store.save(1,List.of(price));
    assertEquals("24.6912",billing.quote(request).points().toPlainString());
    assertEquals(1,billing.availablePrices("owner").size());
    var reopenedDs=new DriverManagerDataSource(url,"sa","");
    var reopened=new JdbcMediaPriceStore(new JdbcTemplate(reopenedDs),new DataSourceTransactionManager(reopenedDs));
    assertEquals(List.of(price),reopened.load().prices());
    assertThrows(IllegalStateException.class,()->reopened.save(1,List.of()));
    reopened.save(2,List.of(new MediaPrice("IMAGE","PERSONAL","image-model","2k","IMAGE","12.3456",false)));
    assertThrows(IllegalArgumentException.class,()->billing.quote(request));
    assertTrue(billing.availablePrices("owner").isEmpty());
    reopened.save(3,List.of());
    assertThrows(IllegalArgumentException.class,()->billing.quote(request));
  }
}
