package com.xzwl.cloud.access;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;
import com.xzwl.cloud.access.application.key.*;
import com.xzwl.cloud.access.infrastructure.key.*;
import com.xzwl.cloud.access.infrastructure.identity.HttpIdentityClient;
import com.xzwl.cloud.access.interfaces.http.AccessSecurityFilter;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Configuration(proxyBeanMethods=false)
@MapperScan("com.xzwl.cloud.access.infrastructure.key")
public class AccessConfiguration {
  @Bean KeyCipher keyCipher(@Value("${access.key-encryption-key}") String key){return new KeyCipher(key);}
  @Bean AccessKeyStore accessKeyStore(AccessKeyMapper mapper,KeyCipher cipher,PlatformTransactionManager tx){return new MybatisAccessKeyStore(mapper,cipher,new TransactionTemplate(tx));}
  @Bean HttpIdentityClient identityClient(@Qualifier("internalRestClientBuilder") RestClient.Builder builder, @Value("${access.iam-service-url:http://127.0.0.1:8210}") String iam,
      @Value("${access.user-service-url:http://127.0.0.1:8211}") String users,@Value("${access.user-internal-token}") String token){return new HttpIdentityClient(builder,iam,users,token);}
  @Bean AccessKeys accessKeys(AccessKeyStore store,UserStatusPort users){return new AccessKeys(store,users);}
  @Bean AccessSecurityFilter accessSecurityFilter(HttpIdentityClient identity,@Value("${abp.internal-token}") String token){return new AccessSecurityFilter(identity,token);}
}
