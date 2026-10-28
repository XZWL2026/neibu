package com.xzwl.cloud.operations;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.xzwl.cloud.operations.application.readmodel.OperationsQueryService;
import com.xzwl.cloud.operations.application.readmodel.OperationsStore;
import com.xzwl.cloud.operations.infrastructure.MyBatisOperationsStore;
import com.xzwl.cloud.operations.infrastructure.persistence.OperationsRecordMapper;
import com.xzwl.cloud.operations.infrastructure.persistence.ProjectionEventMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = OperationsRecordMapper.class)
public class OperationsProductionConfiguration {
  @Bean
  OperationsStore operationsStore(OperationsRecordMapper records, ProjectionEventMapper events,
      PlatformTransactionManager transactionManager) {
    return new MyBatisOperationsStore(records, events, transactionManager);
  }

  @Bean
  MybatisPlusInterceptor operationsPaginationInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
  }

  @Bean
  OperationsQueryService operationsQueryService(OperationsStore store) {
    return new OperationsQueryService(store);
  }
}
