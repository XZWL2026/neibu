package com.xzwl.cloud.iam.infrastructure.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminMapper extends BaseMapper<AdminEntity> {
  @Select("SELECT lock_id FROM iam_admin_lock WHERE lock_id=1 FOR UPDATE")
  int lockAdministrators();

  @org.apache.ibatis.annotations.Insert("INSERT INTO agent_entry_audit(actor_id,credential_id,user_id) VALUES(#{actorId},#{credentialId},#{userId})")
  int recordAgentEntry(@Param("actorId") String actorId,@Param("credentialId") String credentialId,@Param("userId") String userId);

  @Delete("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=#{principal}")
  int revokeSessions(@Param("principal") String principal);
}
