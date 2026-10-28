package com.xzwl.cloud.architecture.fixtures.forbidden.domain;

import com.fasterxml.jackson.databind.ForbiddenJacksonMapper;
import feign.ForbiddenFeignClient;
import jakarta.persistence.ForbiddenJpaEntity;
import jakarta.servlet.ForbiddenServlet;
import org.apache.ibatis.session.ForbiddenMyBatisSession;
import org.apache.rocketmq.client.ForbiddenRocketMqClient;
import org.mybatis.mapper.ForbiddenMyBatisMapper;
import org.springframework.stereotype.Service;

@Service
public final class ForbiddenTechnologyDomain {

    ForbiddenFeignClient feign;
    ForbiddenRocketMqClient rocketMq;
    ForbiddenServlet servlet;
    javax.servlet.LegacyForbiddenServlet legacyServlet;
    ForbiddenJacksonMapper jackson;
    ForbiddenMyBatisSession myBatis;
    ForbiddenMyBatisMapper alternativeMyBatis;
    ForbiddenJpaEntity jpa;
    javax.persistence.LegacyForbiddenJpaEntity legacyJpa;
}
