package com.xzwl.cloud.access.infrastructure.key;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
@Mapper
public interface AccessKeyMapper extends BaseMapper<AccessKeyRow> {
  @Select("SELECT CONCAT(owner_id,'|',account_type,'|',COALESCE(CAST(paid_at AS CHAR),'')) FROM access_recharge_application WHERE order_id=#{id}")
  String recharge(@Param("id") String id);
  @Insert("INSERT INTO access_recharge_application(order_id,owner_id,account_type,paid_at) VALUES(#{id},#{owner},#{type},#{at})")
  void recordRecharge(@Param("id") String id,@Param("owner") String owner,@Param("type") String type,@Param("at") java.math.BigDecimal paidAt);
  @Select("SELECT order_id FROM access_recharge_application WHERE owner_id=#{owner} AND (paid_at>#{at} OR (paid_at=#{at} AND order_id>#{id})) LIMIT 1")
  String newerRecharge(@Param("owner") String owner,@Param("id") String id,@Param("at") java.math.BigDecimal paidAt);
  @Select("SELECT CONCAT(owner_id,'|',account_type,'|',create_key,'|',operator_id) FROM access_admin_change WHERE request_id=#{id}")
  String change(@Param("id") String id);
  @Insert("INSERT INTO access_admin_change(request_id,owner_id,account_type,create_key,operator_id) VALUES(#{id},#{owner},#{type},#{create},#{operator})")
  void recordChange(@Param("id") String id,@Param("owner") String owner,@Param("type") String type,@Param("create") boolean create,@Param("operator") String operator);
  @Insert("INSERT INTO access_account_lock(owner_id) VALUES(#{owner}) ON DUPLICATE KEY UPDATE owner_id=owner_id")
  void ensureOwner(@Param("owner") String owner);
  @Select("SELECT owner_id FROM access_account_lock WHERE owner_id=#{owner} FOR UPDATE")
  String lockOwner(@Param("owner") String owner);
}
