package com.xzwl.cloud.user.infrastructure.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserIdentityMapper extends BaseMapper<UserIdentityEntity> {
  @Insert("INSERT INTO user_identity(user_id,phone,nickname) VALUES(#{id},#{phone},#{nickname}) ON DUPLICATE KEY UPDATE phone=phone")
  int insertIfAbsent(@Param("id") String id, @Param("phone") String phone, @Param("nickname") String nickname);

  @Select("SELECT * FROM user_identity WHERE phone=#{phone} FOR UPDATE")
  UserIdentityEntity lockByPhone(@Param("phone") String phone);

  @Insert("INSERT INTO admin_user_create_request(request_id,creator_id,phone,nickname,user_id,source,remark) VALUES(#{requestId},#{creatorId},#{phone},#{nickname},#{userId},#{source},#{remark}) ON DUPLICATE KEY UPDATE request_id=request_id")
  int reserveCreate(@Param("requestId") String requestId, @Param("creatorId") String creatorId, @Param("phone") String phone, @Param("nickname") String nickname, @Param("userId") String userId, @Param("source") String source, @Param("remark") String remark);

  @Select("SELECT request_id,creator_id,phone,nickname,user_id,source,remark FROM admin_user_create_request WHERE request_id=#{requestId} FOR UPDATE")
  CreateRequest lockCreate(@Param("requestId") String requestId);
  record CreateRequest(String requestId, String creatorId, String phone, String nickname, String userId, String source, String remark) {}

  @Insert("INSERT INTO user_identity(user_id,phone,nickname) VALUES(#{id},#{phone},#{nickname})")
  int insertCreated(@Param("id") String id, @Param("phone") String phone, @Param("nickname") String nickname);

  @Select("SELECT * FROM user_identity WHERE #{search}='' OR LOCATE(#{search},phone)>0 OR LOCATE(#{search},nickname)>0 OR LOCATE(#{search},user_id)>0 ORDER BY created_at DESC,user_id LIMIT #{limit} OFFSET #{offset}")
  java.util.List<UserIdentityEntity> list(@Param("offset") int offset, @Param("limit") int limit, @Param("search") String search);

  @Select("SELECT COUNT(*) FROM user_identity WHERE #{search}='' OR LOCATE(#{search},phone)>0 OR LOCATE(#{search},nickname)>0 OR LOCATE(#{search},user_id)>0")
  long count(@Param("search") String search);

  @Update("UPDATE user_identity SET nickname=COALESCE(#{nickname},nickname),avatar_file_id=CASE WHEN #{avatarFileId} IS NULL THEN avatar_file_id ELSE NULLIF(#{avatarFileId},'') END WHERE user_id=#{id} AND enabled=true")
  int updateProfile(@Param("id") String id, @Param("nickname") String nickname, @Param("avatarFileId") String avatarFileId);

  @Update("UPDATE user_identity SET nickname=#{nickname} WHERE user_id=#{id}")
  int rename(@Param("id") String id, @Param("nickname") String nickname);

  @Select("SELECT created_at FROM user_identity WHERE user_id=#{id}")
  java.sql.Timestamp registeredAt(@Param("id") String id);

  @Update("UPDATE user_identity SET enabled=#{enabled},auth_version=auth_version+1 WHERE user_id=#{id} AND enabled<>#{enabled}")
  int setEnabledIfChanged(@Param("id") String id, @Param("enabled") boolean enabled);
}
