package com.xzwl.cloud.billing.infrastructure.persistence;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.*;
public final class BillingMappers {
  private BillingMappers() {}
  @Mapper public interface Wallets extends BaseMapper<BillingRows.Wallet> {
    @Insert("INSERT INTO wallet(wallet_id,available_points4,version) VALUES(#{id},0,1) ON DUPLICATE KEY UPDATE wallet_id=wallet_id")
    void ensure(@Param("id") String id);
    @Select("SELECT * FROM wallet WHERE wallet_id=#{id} FOR UPDATE")
    BillingRows.Wallet lock(@Param("id") String id);
    @Update("UPDATE wallet SET available_points4=available_points4+#{delta},version=version+1 WHERE wallet_id=#{id} AND available_points4+#{delta}>=0")
    int change(@Param("id") String id, @Param("delta") BigDecimal delta);
    @Update("UPDATE wallet SET available_points4=#{next},version=#{version} WHERE wallet_id=#{id} AND version=#{previousVersion} AND available_points4=#{previous}")
    int compareAndSet(@Param("id") String id,@Param("next") BigDecimal next,@Param("version") long version,
        @Param("previous") BigDecimal previous,@Param("previousVersion") long previousVersion);
  }
  @Mapper public interface AdminCredits extends BaseMapper<BillingRows.AdminCredit> {}
  @Mapper public interface AdminDebits extends BaseMapper<BillingRows.AdminDebit> {
    @Insert("INSERT INTO admin_debit_lock(request_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE request_id=request_id")
    void lockRequest(@Param("id") String id);
  }
  @Mapper public interface Recharges extends BaseMapper<BillingRows.Recharge> {}
  @Mapper public interface Lots extends BaseMapper<BillingRows.Lot> {
    @Update("UPDATE funding_lot SET remaining_points4=remaining_points4+#{delta} WHERE lot_id=#{id} AND remaining_points4+#{delta} BETWEEN 0 AND original_points4")
    int change(@Param("id") String id,@Param("delta") BigDecimal delta);
  }
  @Mapper public interface Rates extends BaseMapper<BillingRows.Rate> {
    @Select("SELECT id,points_per_yuan4,version FROM recharge_conversion_rate WHERE id=1 FOR UPDATE")
    BillingRows.Rate lock();
    @Update("UPDATE recharge_conversion_rate SET points_per_yuan4=#{points},version=version+1 WHERE id=1 AND version=#{version}")
    int compareAndSet(@Param("points") BigDecimal points,@Param("version") long version);
  }
  @Mapper public interface Ledger extends BaseMapper<BillingRows.Ledger> {}
  @Mapper public interface Allocations extends BaseMapper<BillingRows.Allocation> {
    @Update("UPDATE funding_allocation SET refunded_points4=refunded_points4+#{points} WHERE id=#{id} AND refunded_points4+#{points}<=points4")
    int refund(@Param("id") String id,@Param("points") BigDecimal points);
  }
}
