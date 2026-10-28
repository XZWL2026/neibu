package com.xzwl.cloud.billing.infrastructure;

import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.application.recharge.RechargeRateStore;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.pricing.*;
import com.xzwl.cloud.billing.infrastructure.persistence.*;
import java.math.BigDecimal;

/** The receipt and the existing FIFO debit commit in the same Billing transaction. */
public final class MybatisMediaBilling implements MediaBilling {
  private final com.xzwl.cloud.billing.application.pricing.MediaPriceStore managedPrices;
  private final BillingDatabase db;
  private final Keys keys;
  private final PricingCatalog prices;
  private final RechargeRateStore rates;
  private final BillingLedgerStore ledger;
  public MybatisMediaBilling(BillingDatabase db,Keys keys,PricingCatalog prices,RechargeRateStore rates,BillingLedgerStore ledger) {
    this(db,keys,prices,rates,ledger,null);
  }
  public MybatisMediaBilling(BillingDatabase db,Keys keys,PricingCatalog prices,RechargeRateStore rates,BillingLedgerStore ledger,
      com.xzwl.cloud.billing.application.pricing.MediaPriceStore managedPrices) {
    this.db=db;this.keys=keys;this.prices=prices;this.rates=rates;this.ledger=ledger;this.managedPrices=managedPrices;
  }
  public Receipt charge(Charge command) {
    String facts=facts(command);
    var receipts=db.mapper(MediaChargeMapper.class);
    return db.transaction(()->{
        // Serialize before any eligibility refusal: an earlier timed-out request may still debit.
        receipts.lockOperation(command.operationId());
        var existing=receipts.selectById(command.operationId());
        if(existing!=null)return replay(existing,facts);
        boolean enhancement="ENHANCEMENT".equals(command.type());
        if(enhancement && command.expectedCost()==null)throw new IllegalArgumentException("expected enhancement cost required");
        Key key=mediaKey(command.ownerId());
        if(db.mapper(BillingMappers.Wallets.class).lock(key.keyId())==null)throw new IllegalArgumentException("unknown wallet");
        var quote=calculateCurrent(key.ownerId(),key.accountType(),
            command.type(),command.model(),command.resolution(),command.count(),command.durationSeconds(),enhancement);
        // Wallet, rate and price catalog remain locked through both debit and durable receipt.
        if(enhancement && !matches(command.expectedCost(),quote))throw new IllegalStateException("enhancement quote changed");
        if(quote.points().signum()>0)ledger.consume(key.keyId(),command.operationId(),Decimal4.of(quote.points()));
        var row=new MediaChargeRow();row.operationId=command.operationId();row.ownerId=command.ownerId();row.keyId=key.keyId();row.requestFacts=facts;row.accountType=key.accountType();
        row.chargedAt=java.time.Instant.now().toString();row.amount4=quote.amount();row.rate4=quote.rate();row.points4=quote.points();receipts.insert(row);
        return receipt(row);
    });
  }

  public Quote quote(QuoteRequest request) {
    Key key=mediaKey(request.ownerId());
    return calculateCurrent(key.ownerId(),key.accountType(),request.type(),
        request.model(),request.resolution(),request.count(),request.durationSeconds());
  }
  public java.util.List<AvailablePrice> availablePrices(String ownerId) {
    String accountType=mediaKey(ownerId).accountType();
    var snapshot=managedPrices==null ? null : managedPrices.loadForOwner(ownerId,accountType);
    if(snapshot!=null && snapshot.managed()) return snapshot.prices().stream().filter(p->p.enabled() && p.accountType().equals(accountType))
        .map(p->new AvailablePrice(p.model(),p.resolution(),p.yuanPerUnit()!=null ? new BigDecimal(p.yuanPerUnit()) : new BigDecimal(p.pointsPerUnit()).divide(rates.load().pointsPerYuan4().asBigDecimal(),4,java.math.RoundingMode.DOWN),p.type())).toList();
    return prices.entriesFor(accountType).stream()
        .map(entry -> new AvailablePrice(entry.key().model(),entry.key().resolution(),entry.pricePerUnit().asBigDecimal())).toList();
  }
  private Key mediaKey(String ownerId) {
    MybatisLedgerStore.id(ownerId);
    Key key=keys.forOwner(ownerId);
    if(key==null||!ownerId.equals(key.ownerId())||!key.enabled()||!("API".equals(key.accountType()) ? "API".equals(key.kind())
        : java.util.Set.of("PERSONAL","ENTERPRISE").contains(key.accountType()) && "MEDIA".equals(key.kind())))
      throw new IllegalArgumentException("media key unavailable");
    return key;
  }
  private Quote calculateCurrent(String ownerId,String accountType,String type,String model,String resolution,int count,int durationSeconds) {
    return calculateCurrent(ownerId,accountType,type,model,resolution,count,durationSeconds,false);
  }
  private Quote calculateCurrent(String ownerId,String accountType,String type,String model,String resolution,int count,int durationSeconds,boolean locked) {
    var rate=(locked ? rates.loadForCharge() : rates.load()).pointsPerYuan4();
    var snapshot=managedPrices==null ? null : managedPrices.loadForOwner(ownerId,accountType);
    if("ENHANCEMENT".equals(type) && (snapshot==null || !snapshot.managed())) throw new IllegalArgumentException("enhancement price unavailable");
    if(snapshot==null || !snapshot.managed()) return calculate(prices,rate,accountType,type,model,resolution,count,durationSeconds);
    var configured=snapshot.prices().stream().filter(p->p.enabled() && p.type().equals(type)
        && p.accountType().equals(accountType) && p.model().equals(model) && p.resolution().equals(resolution))
        .findFirst().orElseThrow(()->new IllegalArgumentException("price is unavailable"));
    var key=new PricingKey(accountType,model,resolution);
    if (configured.yuanPerUnit()!=null) return requirePayable(type,calculate(
        PricingCatalog.empty().withPrice(new PricingEntry(key,Decimal4.of(configured.yuanPerUnit()))),
        rate,accountType,type,model,resolution,count,durationSeconds));
    var points=PricingCatalog.empty().withPrice(new PricingEntry(key,Decimal4.of(configured.pointsPerUnit())))
        .quote(key,Decimal4.of(BigDecimal.valueOf("IMAGE".equals(type) ? count : durationSeconds))).asBigDecimal();
    var amount=points.divide(rate.asBigDecimal(),4,java.math.RoundingMode.DOWN);
    if(points.precision()>20 || amount.precision()>20) throw new IllegalArgumentException("media amount out of range");
    return requirePayable(type,new Quote(amount,rate.asBigDecimal(),points));
  }
  private static Quote requirePayable(String type,Quote quote) {
    if("ENHANCEMENT".equals(type) && quote.points().signum()<=0)
      throw new IllegalArgumentException("enhancement charge must have positive points");
    return quote;
  }
  private static Quote calculate(PricingCatalog prices,Decimal4 rate,String accountType,String type,
      String model,String resolution,int count,int durationSeconds) {
    var quantity=Decimal4.of(BigDecimal.valueOf("IMAGE".equals(type)?count:durationSeconds));
    var amount=prices.quote(new PricingKey(accountType,model,resolution),quantity);
    var points=amount.multiply(rate);
    for(var value:new Decimal4[]{amount,rate,points})
      if(value.asBigDecimal().precision()>20)throw new IllegalArgumentException("media amount out of range");
    return new Quote(amount.asBigDecimal(),rate.asBigDecimal(),points.asBigDecimal());
  }

  public void refund(String operationId,Refund command) {
    MybatisLedgerStore.id(operationId);MybatisLedgerStore.id(command.refundId());MybatisLedgerStore.id(command.ownerId());
    var row=db.mapper(MediaChargeMapper.class).selectById(operationId);
    if(row==null||!row.ownerId.equals(command.ownerId()))throw new IllegalArgumentException("unknown media charge");
    BigDecimal points=command.points();
    if(points==null||points.signum()<0||points.scale()>4||points.compareTo(row.points4)>0)throw new IllegalArgumentException("invalid media refund");
    if(points.signum()>0)ledger.refund(row.keyId,command.refundId(),operationId,Decimal4.of(points));
  }
  private static Receipt replay(MediaChargeRow row,String facts) {
    if(!row.requestFacts.equals(facts))throw new IllegalStateException("media charge facts conflict");
    return receipt(row);
  }
  private static Receipt receipt(MediaChargeRow row) {return new Receipt(row.operationId,row.amount4,row.rate4,row.points4);}
  private static boolean matches(ExpectedCost expected,Quote actual) {
    return expected.amount().compareTo(actual.amount())==0 && expected.rate().compareTo(actual.rate())==0
        && expected.points().compareTo(actual.points())==0;
  }
  private static String facts(Charge c) {
    // Identifiers cannot contain newlines; quantities are canonical integers.
    String dimensions=String.join("\n",c.ownerId(),c.type(),c.model(),c.resolution(),Integer.toString(c.count()),Integer.toString(c.durationSeconds()));
    return c.expectedCost()==null ? dimensions : dimensions+"\n"+String.join("\n",c.expectedCost().amount().toPlainString(),c.expectedCost().rate().toPlainString(),c.expectedCost().points().toPlainString());
  }
}
