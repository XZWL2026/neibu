package com.xzwl.cloud.payment.infrastructure.workflow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
public final class PaymentMappers {
  private PaymentMappers(){}
  @Mapper public interface ProviderState {
    @Insert("INSERT INTO payment_provider_state(id) VALUES(#{id}) ON DUPLICATE KEY UPDATE id=id") void ensure(@Param("id") String id);
    @Select("SELECT payload FROM payment_provider_state WHERE id=#{id} FOR UPDATE") String lock(@Param("id") String id);
    @Update("UPDATE payment_provider_state SET payload=#{payload} WHERE id=#{id}") void save(@Param("id") String id,@Param("payload") String payload);
  }
  @Mapper public interface Orders extends BaseMapper<PaymentRows.Order>{
    @Insert("INSERT INTO payment_transfer_proof(order_id,file_name,content_type,size_bytes,content) VALUES(#{orderId},#{fileName},#{contentType},#{sizeBytes},#{content})")
    void insertProof(PaymentRows.Proof proof);
    @Select("SELECT order_id,file_name,content_type,size_bytes FROM payment_transfer_proof WHERE order_id=#{id}") PaymentRows.Proof proofMetadata(@Param("id") String id);
    @Select("SELECT * FROM payment_transfer_proof WHERE order_id=#{id}") PaymentRows.Proof proof(@Param("id") String id);
    @Select("SELECT * FROM payment_order WHERE id=#{id} FOR UPDATE") PaymentRows.Order lock(@Param("id") String id);
    @Update("UPDATE payment_order SET status='EXPIRED' WHERE status='PENDING' AND expires_at<=#{now}")
    int expire(@Param("now") long now);
  }
  public record ApiIdentityTime(String userId,java.math.BigDecimal completedEpoch,String paidAt) {}
  @Mapper public interface Accounts extends BaseMapper<PaymentRows.Account>{
    @Select("""
      <script>
      SELECT user_id,UNIX_TIMESTAMP(completed_at) completed_epoch,NULL paid_at FROM payment_account_change
      WHERE account_type='API' AND completed=true AND completed_at IS NOT NULL AND user_id IN
      <foreach collection="owners" item="owner" open="(" separator="," close=")">#{owner}</foreach>
      UNION ALL
      SELECT user_id,NULL completed_epoch,paid_at FROM payment_order WHERE recharge_type='API' AND paid_at IS NOT NULL AND (status='PAID' OR fulfilled=true) AND user_id IN
      <foreach collection="owners" item="owner" open="(" separator="," close=")">#{owner}</foreach>
      </script>
      """) java.util.List<ApiIdentityTime> apiIdentityTimes(@Param("owners") java.util.List<String> owners);

    @Select("SELECT * FROM payment_account_change WHERE request_id=#{id}") PaymentRows.AccountChange change(@Param("id") String id);
    @Select("SELECT * FROM payment_account_change WHERE completed=false ORDER BY recorded_at,request_id LIMIT 100") java.util.List<PaymentRows.AccountChange> pendingChanges();
    @Insert("INSERT INTO payment_account_change(request_id,user_id,account_type,create_key,operator_id) VALUES(#{requestId},#{userId},#{accountType},#{createKey},#{operatorId})") void insertChange(PaymentRows.AccountChange change);
    @Update("UPDATE payment_account_change SET completed=true,completed_at=CURRENT_TIMESTAMP(6) WHERE request_id=#{id}") void completeChange(@Param("id") String id);
    @Update("UPDATE payment_account SET pending_request_id=NULL,locked_type=#{type} WHERE user_id=#{owner} AND pending_request_id=#{request}") int finishChange(@Param("owner") String owner,@Param("request") String request,@Param("type") String type);
    @Insert("INSERT INTO payment_account(user_id) VALUES(#{user}) ON DUPLICATE KEY UPDATE user_id=user_id")
    void ensure(@Param("user") String user);
    @Select("SELECT * FROM payment_account WHERE user_id=#{user} FOR UPDATE")PaymentRows.Account lock(@Param("user") String user);
  }
  @Mapper public interface Requests extends BaseMapper<PaymentRows.Request>{}
  @Mapper public interface Work extends BaseMapper<PaymentRows.Work>{
    @Insert("INSERT INTO payment_promotion_delivery(order_id,version,occurred_at) VALUES(#{id},#{version},#{at}) ON DUPLICATE KEY UPDATE version=VALUES(version),occurred_at=VALUES(occurred_at),completed=false,next_attempt_at=0")
    void enqueuePromotion(@Param("id") String id,@Param("version") long version,@Param("at") String at);
    @Select("SELECT order_id,version,occurred_at FROM payment_promotion_delivery WHERE completed=false AND next_attempt_at<=#{now} ORDER BY next_attempt_at,order_id LIMIT 100")
    java.util.List<PaymentRows.PromotionDelivery> pendingPromotions(@Param("now") long now);
    @Update("UPDATE payment_promotion_delivery SET completed=true WHERE order_id=#{id} AND version=#{version}")
    void completePromotion(@Param("id") String id,@Param("version") long version);
    @Update("UPDATE payment_promotion_delivery SET next_attempt_at=#{next} WHERE order_id=#{id} AND version=#{version} AND completed=false")
    void retryPromotion(@Param("id") String id,@Param("version") long version,@Param("next") long next);

    @Update("UPDATE payment_work SET lease_token=#{token},lease_until=#{until} WHERE order_id=#{id} AND completed=false AND next_attempt_at<=#{now} AND lease_until<=#{now}")
    int claim(@Param("id") String id,@Param("token") String token,@Param("now") long now,@Param("until") long until);
    @Select("SELECT * FROM payment_work WHERE order_id=#{id} FOR UPDATE") PaymentRows.Work lock(@Param("id") String id);
  }
}
