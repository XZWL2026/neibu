package com.xzwl.cloud.iam.infrastructure.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PhoneOtpMapper extends BaseMapper<PhoneOtpEntity> {
  @Insert("INSERT INTO iam_phone_otp(phone) VALUES(#{phone}) ON DUPLICATE KEY UPDATE phone=phone")
  int insertIfAbsent(@Param("phone") String phone);

  @Select("SELECT * FROM iam_phone_otp WHERE phone=#{phone} FOR UPDATE")
  PhoneOtpEntity lockByPhone(@Param("phone") String phone);
}
