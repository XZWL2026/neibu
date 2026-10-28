package com.xzwl.cloud.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.application.provider.*;
import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.infrastructure.http.*;
import com.xzwl.cloud.media.infrastructure.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.xzwl.cloud.media.interfaces.http.MediaSessionFilter;
import java.net.http.HttpClient;
import java.time.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.*;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
@MapperScan("com.xzwl.cloud.media.infrastructure.task")
public class MediaConfiguration {
  private final RestClient.Builder internalBuilder;
  public MediaConfiguration() { this(RestClient.builder()); }
  @org.springframework.beans.factory.annotation.Autowired
  public MediaConfiguration(@org.springframework.beans.factory.annotation.Qualifier("internalRestClientBuilder") RestClient.Builder internalBuilder) {
    this.internalBuilder = internalBuilder;
  }
  java.util.function.Supplier<com.xzwl.cloud.web.ProviderConfigurationStatus> providerStatus(ProviderSettings settings) {
    return ()->{var missing=settings.missingEnvironmentVariables(); boolean configured=missing.isEmpty();
      return new com.xzwl.cloud.web.ProviderConfigurationStatus("方舟",configured,configured,configured ? "***" : "",missing);};
  }
  @Bean java.util.function.Supplier<com.xzwl.cloud.web.ProviderConfigurationStatus> liveProviderStatus(
      ProviderSettings settings,ArkConnectionStore connections) {
    return ()->{
      boolean configured=connections.hasConfiguredConnection();
      return new com.xzwl.cloud.web.ProviderConfigurationStatus("供应商",configured,configured,configured ? "***" : "",
          configured ? java.util.List.of() : settings.missingEnvironmentVariables());
    };
  }
  @Bean com.xzwl.cloud.media.infrastructure.task.MediaSettlementOutbox mediaSettlementOutbox(JdbcTemplate jdbc,ObjectMapper json) {
    return new com.xzwl.cloud.media.infrastructure.task.MediaSettlementOutbox(jdbc,json);
  }
  @Bean Clock mediaClock() { return Clock.systemUTC(); }
  @Bean ProductCopyWriter productCopyWriter(ProviderHttp http,
      @Value("${media.product-copy.base-url:${MEDIA_PRODUCT_COPY_BASE_URL:}}") String url,
      @Value("${media.product-copy.model:${MEDIA_PRODUCT_COPY_MODEL:qwen3-vl-flash}}") String model,
      @Value("${media.product-copy.api-key:${MEDIA_PRODUCT_COPY_API_KEY:}}") String key) {
    return new ProductCopyWriter(http,url,model,key);
  }
  @Bean com.xzwl.cloud.media.application.suite.ProductSuites productSuites(JdbcTemplate jdbc,ObjectMapper json,
      org.springframework.transaction.PlatformTransactionManager transactions,MediaTaskService tasks,
      com.xzwl.cloud.media.application.catalog.GenerationCatalogService catalog,StoragePort storage,Clock clock,ProductCopyWriter writer) {
    return new com.xzwl.cloud.media.infrastructure.suite.JdbcProductSuites(jdbc,json,transactions,tasks,catalog,storage,clock,writer);
  }
  @Bean TaskPolicy mediaTaskPolicy(
      @Value("${media.worker.image-timeout}") Duration imageTimeout,
      @Value("${media.worker.video-timeout}") Duration videoTimeout,
      @Value("${media.worker.poll-interval}") Duration poll,
      @Value("${media.worker.retry-interval}") Duration retry,
      @Value("${media.worker.lease-duration}") Duration lease,
      @Value("${media.worker.batch-size}") int batch,
      @Value("${media.http.read-timeout}") Duration readTimeout,
      @Value("${media.http.connect-timeout}") Duration connectTimeout) {
    if(lease.compareTo(readTimeout.plus(connectTimeout).plusSeconds(5))<=0)
      throw new IllegalArgumentException("worker lease must exceed HTTP connect + read timeout by at least 5 seconds");
    return new TaskPolicy(imageTimeout,videoTimeout,poll,retry,lease,batch);
  }
  @Bean com.xzwl.cloud.media.application.catalog.GenerationCatalogService generationCatalog(RoutingPort routing,BillingPort billing,StoragePort storage,PortraitReviews portraits) {
    return new com.xzwl.cloud.media.application.catalog.GenerationCatalogService(routing,billing,storage,portraits);
  }
  @Bean EnhancementQuoteStore enhancementQuoteStore(JdbcTemplate jdbc,ObjectMapper json) { return new com.xzwl.cloud.media.infrastructure.task.JdbcEnhancementQuoteStore(jdbc,json); }
  @Bean EnhancementQuoteService enhancementQuotes(MediaTaskService tasks,MediaTaskStore store,StoragePort storage,BillingPort billing,MpsConfigurationStore templates,MpsConfigurationProbe probe,EnhancementQuoteStore quotes,Clock clock) {
    return new EnhancementQuoteService(tasks,store,storage,billing,templates,probe,quotes,clock);
  }
  @Bean EnhancementProvider enhancementProvider(ProviderSettings settings,MpsConfigurationStore configuration,ProviderJournal journal,TencentMpsAdapter mps,StoragePort storage) {
    return new IndependentEnhancementProvider(()->settings.withMpsConfiguration(configuration.load()),journal,mps,storage);
  }
  @Bean EnhancementTasks enhancementTasks(MediaTaskStore store,EnhancementQuoteStore quotes,EnhancementQuoteService verification,EnhancementProvider provider,Clock clock,TaskPolicy policy) {
    return new EnhancementTasks(store,quotes,verification,provider,clock,policy);
  }
  @Bean MediaTaskService mediaTaskService(MediaTaskStore store,Clock clock,TaskPolicy policy) {
    return new MediaTaskService(store,clock,policy);
  }
  @Bean MediaTaskWorker mediaTaskWorker(MediaTaskStore store,RoutingPort routing,BillingPort billing,
      ProviderPort provider,StoragePort storage,Clock clock,TaskPolicy policy,PortraitReviews portraits,DepthTasks depth,EnhancementTasks enhancements) {
    return new MediaTaskWorker(store,routing,billing,provider,storage,clock,policy,portraits,depth,enhancements);
  }
  @Bean DepthTasks depthTasks(MediaTaskService tasks,ObjectMapper json,org.springframework.core.env.Environment environment,
      @Value("${media.depth.enabled:false}") boolean enabled,@Value("${media.depth.url:http://127.0.0.1:18727}") String worker,
      @Value("${media.depth.token:}") String workerToken,@Value("${media.storage.url}") String storage,
      @Value("${media.storage.token}") String storageToken,@Value("${media.http.connect-timeout}") Duration connect,
      @Value("${media.http.read-timeout}") Duration read) {
    boolean local=java.util.Arrays.asList(environment.getActiveProfiles()).contains("local");
    return new DepthTasks(tasks,new HttpDepthPort(client(storage,storageToken,connect,read),worker,workerToken,json,read),local && enabled && workerToken.length()>=32);
  }
  @Bean RoutingPort mediaRoutingPort(ObjectMapper json,
      @Value("${media.routing.url}") String url,@Value("${media.routing.token}") String token,
      @Value("${media.http.connect-timeout}") Duration connect,@Value("${media.http.read-timeout}") Duration read) {
    return new HttpRoutingPort(client(url,token,connect,read),json);
  }
  @Bean TaskFileAccess taskFileAccess(
      @Value("${media.storage.url}") String url,@Value("${media.storage.token}") String token,
      @Value("${media.http.connect-timeout}") Duration connect,@Value("${media.http.read-timeout}") Duration read) {
    return new HttpTaskFileAccess(client(url,token,connect,read));
  }
  @Bean StoragePort mediaStoragePort(
      ProviderSettings settings,TencentMpsAdapter mps,MpsConfigurationStore mpsConfiguration,ProviderJournal journal,
      @Value("${media.storage.url}") String url,@Value("${media.storage.token}") String token,
      @Value("${media.http.connect-timeout}") Duration connect,@Value("${media.http.read-timeout}") Duration read) {
    var storage=new HttpStoragePort(client(url,token,connect,read));
    return new StoragePort() {
      public java.util.List<MediaReference> resolveReferences(String owner, java.util.List<MediaReference> references) {
        return storage.resolveReferences(owner,references);
      }
      public java.util.List<MediaReference> resolveReferences(String owner,java.util.List<MediaReference> references,
          com.xzwl.cloud.media.domain.task.ReferenceMode mode) {
        return storage.resolveReferences(owner,references,mode);
      }
      public StoragePort.VideoSource probeVideo(String owner,String fileId) { return storage.probeVideo(owner,fileId); }
      public String archive(String owner,String task,int index,String source) {
        return archive(owner,task,index,source,ArchiveStage.RESULT);
      }
      public String archive(String owner,String task,int index,String source,ArchiveStage stage) {
        var plan=journal.executionPlan(task);
        String archiveSource=plan!=null&&plan.template()>0
            ?mps.archiveUrl(source,settings.withMpsConfiguration(mpsConfiguration.load()),plan):source;
        String file=storage.archive(owner,task,index,archiveSource,stage);
        if(plan!=null && plan.model().isBlank() && plan.enhancement()!=null && stage==ArchiveStage.RESULT) {
          var actual=storage.probeVideo(owner,file);var expected=plan.enhancement();
          if(actual.width()==null || actual.height()==null || actual.width()!=expected.width() || actual.height()!=expected.height())
            throw new DefinitiveRejectionException("INVALID_ENHANCEMENT_OUTPUT");
        }
        return file;
      }
    };
  }
  @Bean MediaSessionPort mediaSessionPort(@Value("${media.iam.url}") String url,
      @Value("${media.http.connect-timeout}") Duration connect,@Value("${media.http.read-timeout}") Duration read) {
    return new HttpMediaSessionPort(client(url,"",connect,read));
  }
  @Bean MediaSessionFilter mediaSessionFilter(MediaSessionPort sessions) { return new MediaSessionFilter(sessions); }
  @Bean @ConditionalOnMissingBean(BillingPort.class)
  BillingPort mediaBillingPort(@Value("${media.billing.url}") String url,
      @Value("${media.billing.token}") String token,
      @Value("${media.http.connect-timeout}") Duration connect,@Value("${media.http.read-timeout}") Duration read) {
    return new HttpBillingPort(client(url,token,connect,read));
  }
  @Bean ProviderSettings mediaProviderSettings(ObjectMapper json) {
    return ProviderSettings.from(System.getenv(),json);
  }
  @Bean ArkConnectionStore arkConnections(JdbcTemplate jdbc,ProviderSettings settings,
      @Value("${MEDIA_CONFIG_ENCRYPTION_KEY:}") String encryptionKey,
      @Value("${MEDIA_PROVIDER_ALLOWED_HOSTS:}") String additionalHosts) {
    var fallback=settings.arkConnection();
    var hosts=new java.util.HashSet<>(java.util.Set.of("ark.cn-beijing.volces.com"));
    // Only deployment configuration extends the trusted hosts; browser input cannot.
    java.util.Arrays.stream(additionalHosts.split(",")).map(String::trim).filter(host -> !host.isEmpty()).forEach(hosts::add);
    if(!fallback.baseUrl().isBlank()) hosts.add(java.net.URI.create(fallback.baseUrl()).getHost());
    return new JdbcArkConnectionStore(jdbc,encryptionKey,fallback,hosts);
  }
  @Bean ArkConnectionProbe arkConnectionProbe(ArkConnectionStore store,
      @Value("${media.http.connect-timeout}") Duration connect,@Value("${media.http.read-timeout}") Duration read) {
    return new HttpArkConnectionProbe(store,HttpClient.newBuilder().connectTimeout(connect)
        .followRedirects(HttpClient.Redirect.NEVER).build(),read);
  }
  @Bean ArkModelCatalog arkModelCatalog(ArkConnectionStore store,ProviderHttp http) {
    return new HttpArkModelCatalog(store,http);
  }
  @Bean ProviderHttp mediaProviderHttp(ObjectMapper json,
      @Value("${media.http.connect-timeout}") Duration connect,@Value("${media.http.read-timeout}") Duration read) {
    return new ProviderHttp(HttpClient.newBuilder().connectTimeout(connect)
        .followRedirects(HttpClient.Redirect.NEVER).build(),json,read);
  }
  @Bean ProviderJournal mediaProviderJournal(JdbcTemplate jdbc,ObjectMapper json) { return new ProviderJournal(jdbc,json); }
  @Bean MpsConfigurationStore mediaMpsConfiguration(JdbcTemplate jdbc,ProviderSettings settings) {
    return new JdbcMpsConfigurationStore(jdbc,settings.mpsConfiguration());
  }
  @Bean MpsConfigurationProbe mediaMpsProbe(MpsConfigurationStore store,ProviderSettings settings,TencentMpsAdapter mps,Clock clock) {
    return new TencentMpsConfigurationProbe(store,settings,mps,clock);
  }
  @Bean TencentMpsAdapter mediaMpsAdapter(ProviderHttp http,Clock clock) { return new TencentMpsAdapter(http,clock); }
  @Bean @ConditionalOnMissingBean(ProviderPort.class)
  ProviderPort mediaProductionProvider(ProviderSettings settings,ArkConnectionStore connections,
      ProviderJournal journal,ProviderHttp http,TencentMpsAdapter mps,MpsConfigurationStore mpsConfiguration,JdbcPortraitReviews portraits) {
    return new ProductionProviderPort(providerId->settings.withConnection(connections.load(providerId)).withMpsConfiguration(mpsConfiguration.load()),journal,new ArkMediaAdapter(http),mps,portraits);
  }
  @Bean JdbcPortraitReviews mediaPortraitReviews(JdbcTemplate jdbc,ObjectMapper json,ArkConnectionStore connections,
      RoutingPort routing,StoragePort storage,ProviderHttp http,Clock clock,org.springframework.core.env.Environment environment) {
    var config=new java.util.HashMap<String,String>();
    for(String key:java.util.List.of("PROVIDER_ID","PROJECT_NAME","PROJECT_NAMES","ENDPOINT","ACCESS_KEY_ID","SECRET_ACCESS_KEY")) {
      String value=environment.getProperty("media.asset."+key.toLowerCase(java.util.Locale.ROOT).replace('_','-'),
          environment.getProperty("MEDIA_ASSET_"+key));
      if(value!=null) config.put("MEDIA_ASSET_"+key,value);
    }
    return new JdbcPortraitReviews(jdbc,json,connections,routing,storage,http,clock,config);
  }
  RestClient client(String url,String token,Duration connect,Duration read) {
    if(connect.isNegative() || connect.isZero() || read.isNegative() || read.isZero())
      throw new IllegalArgumentException("HTTP timeouts must be positive");
    var requestFactory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect)
        .followRedirects(HttpClient.Redirect.NEVER).build());
    requestFactory.setReadTimeout(read);
    var builder=internalBuilder.clone().baseUrl(url).requestFactory(requestFactory);
    if(!token.isBlank()) builder.defaultHeader("X-Service-Token",token);
    return builder.build();
  }
  @Configuration(proxyBeanMethods=false) @EnableScheduling
  @ConditionalOnProperty(name="media.worker.enabled",havingValue="true",matchIfMissing=true)
  static class WorkerScheduling {
    private final MediaTaskWorker worker;
    private final JdbcPortraitReviews portraits;
    private final com.xzwl.cloud.media.infrastructure.task.MediaSettlementOutbox settlements;
    private final BillingPort billing;
    WorkerScheduling(MediaTaskWorker worker,JdbcPortraitReviews portraits,com.xzwl.cloud.media.infrastructure.task.MediaSettlementOutbox settlements,BillingPort billing) {
      this.worker=worker; this.portraits=portraits;this.settlements=settlements;this.billing=billing;
    }
    @Scheduled(fixedDelayString="${media.worker.scan-interval}") void settle() { settlements.deliver(billing,Instant.now()); }
    @Scheduled(fixedDelayString="${media.worker.scan-interval}") void run() { worker.tick(); }
    @Scheduled(fixedDelayString="${media.worker.scan-interval}") void reviewPortraits() { portraits.tick(); }
  }
}
