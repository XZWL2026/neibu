package com.xzwl.cloud.payment.application.identity;
public interface UserStatusPort {boolean enabled(String userId);default boolean exists(String userId){return enabled(userId);}}
