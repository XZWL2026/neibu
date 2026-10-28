package com.xzwl.cloud.billing.infrastructure;
import com.xzwl.cloud.billing.application.recharge.RechargeRateStore;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.recharge.RechargeRate;
import com.xzwl.cloud.billing.infrastructure.persistence.*;
public final class MybatisRechargeRateStore implements RechargeRateStore {
  private final BillingMappers.Rates rates;
  public MybatisRechargeRateStore(BillingDatabase db) { rates=db.mapper(BillingMappers.Rates.class); }
  public RechargeRate load() { var r=rates.selectById(1);return new RechargeRate(Decimal4.of(r.pointsPerYuan4),r.version); }
  public RechargeRate loadForCharge() { var r=rates.lock();return new RechargeRate(Decimal4.of(r.pointsPerYuan4),r.version); }
  public void save(RechargeRate rate) {
    if(rates.compareAndSet(rate.pointsPerYuan4().asBigDecimal(),rate.version()-1)!=1)throw new IllegalStateException("recharge rate version has changed");
  }
}
