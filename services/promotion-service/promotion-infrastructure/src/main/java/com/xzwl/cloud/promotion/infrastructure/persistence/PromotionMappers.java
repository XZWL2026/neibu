package com.xzwl.cloud.promotion.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xzwl.cloud.promotion.infrastructure.persistence.PromotionRows.*;
import org.apache.ibatis.annotations.Mapper;

/** Table-scoped CRUD mappers. Composite keys are queried through complete wrappers. */
public final class PromotionMappers {
  private PromotionMappers() {}

  @Mapper
  public interface Eligibilities extends BaseMapper<ApplicantEligibilityRow> {}

  @Mapper
  public interface Applications extends BaseMapper<ApplicationRow> {}

  @Mapper
  public interface Promoters extends BaseMapper<PromoterRow> {}

  @Mapper
  public interface Receipts extends BaseMapper<ReceiptRow> {}

  @Mapper
  public interface Referrals extends BaseMapper<ReferralBindingRow> {}

  @Mapper
  public interface Policies extends BaseMapper<BenefitPolicyRow> {
    @org.apache.ibatis.annotations.Select("SELECT customer_user_id,point_rate6 FROM promotion_customer_point_override WHERE policy_version=#{version} ORDER BY customer_user_id")
    java.util.List<CustomerPointOverrideRow> customerOverrides(long version);
    @org.apache.ibatis.annotations.Insert("INSERT INTO promotion_customer_point_override SELECT #{next},customer_user_id,point_rate6 FROM promotion_customer_point_override WHERE policy_version=#{previous}")
    void copyCustomerOverrides(@org.apache.ibatis.annotations.Param("previous") long previous,@org.apache.ibatis.annotations.Param("next") long next);
    @org.apache.ibatis.annotations.Delete("DELETE FROM promotion_customer_point_override WHERE policy_version=#{version} AND customer_user_id=#{customer}")
    void deleteCustomerOverride(@org.apache.ibatis.annotations.Param("version") long version,@org.apache.ibatis.annotations.Param("customer") String customer);
    @org.apache.ibatis.annotations.Insert("INSERT INTO promotion_customer_point_override(policy_version,customer_user_id,point_rate6) VALUES(#{version},#{customer},#{rate})")
    void insertCustomerOverride(@org.apache.ibatis.annotations.Param("version") long version,@org.apache.ibatis.annotations.Param("customer") String customer,
        @org.apache.ibatis.annotations.Param("rate") java.math.BigDecimal rate);
    @org.apache.ibatis.annotations.Select("SELECT promoter_user_id,level1_cash_rate6,level2_cash_rate6 FROM promotion_benefit_override WHERE policy_version=#{version}")
    java.util.List<BenefitOverrideRow> overrides(long version);
    @org.apache.ibatis.annotations.Insert("INSERT INTO promotion_benefit_override SELECT #{next},promoter_user_id,level1_cash_rate6,level2_cash_rate6 FROM promotion_benefit_override WHERE policy_version=#{previous}")
    void copyOverrides(@org.apache.ibatis.annotations.Param("previous") long previous,@org.apache.ibatis.annotations.Param("next") long next);
    @org.apache.ibatis.annotations.Delete("DELETE FROM promotion_benefit_override WHERE policy_version=#{version} AND promoter_user_id=#{promoter}")
    void deleteOverride(@org.apache.ibatis.annotations.Param("version") long version,@org.apache.ibatis.annotations.Param("promoter") String promoter);
    @org.apache.ibatis.annotations.Insert("INSERT INTO promotion_benefit_override(policy_version,promoter_user_id,level1_cash_rate6,level2_cash_rate6) VALUES(#{version},#{promoter},#{l1},#{l2})")
    void insertOverride(@org.apache.ibatis.annotations.Param("version") long version,@org.apache.ibatis.annotations.Param("promoter") String promoter,
        @org.apache.ibatis.annotations.Param("l1") java.math.BigDecimal l1,@org.apache.ibatis.annotations.Param("l2") java.math.BigDecimal l2);
  }

  @Mapper
  public interface Accounts extends BaseMapper<CommissionAccountRow> {}

  @Mapper
  public interface FundingBenefits extends BaseMapper<FundingBenefitRow> {}

  @Mapper
  public interface Ledger extends BaseMapper<CommissionLedgerRow> {}

  @Mapper
  public interface Payouts extends BaseMapper<PayoutAccountRow> {}

  @Mapper
  public interface Withdrawals extends BaseMapper<WithdrawalRow> {}
}
