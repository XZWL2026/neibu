package com.xzwl.cloud.billing.infrastructure;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.xzwl.cloud.billing.infrastructure.persistence.*;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
final class BillingTestStores {
  static BillingDatabase database(DataSource source) {
    try {
      var configuration=new MybatisConfiguration();
      configuration.setMapUnderscoreToCamelCase(true);
      for(var type:BillingMappers.class.getDeclaredClasses()) if(type.isInterface())configuration.addMapper(type);
      var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);
      return new BillingDatabase(new SqlSessionTemplate(factory.getObject()),new DataSourceTransactionManager(source));
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  static MybatisAdminCreditStore admin(DataSource source){return new MybatisAdminCreditStore(database(source));}
  static MybatisRechargeCreditStore recharge(DataSource source){return new MybatisRechargeCreditStore(database(source));}
  static MybatisRechargeRateStore rate(DataSource source){return new MybatisRechargeRateStore(database(source));}
}
