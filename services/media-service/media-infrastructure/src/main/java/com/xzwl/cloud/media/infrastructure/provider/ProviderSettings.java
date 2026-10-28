package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore.Connection;
import com.xzwl.cloud.media.application.provider.MpsConfigurationStore.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.*;
import com.xzwl.cloud.media.domain.task.*;
import java.net.URI;
import java.util.*;

/** Immutable provider settings; never serialize this credential holder. */
public final class ProviderSettings {
  final String arkBase, providerId, arkKey, mpsEndpoint, secretId, secretKey, region, bucket, bucketRegion;
  private final int timeoutSeconds, retryCount;
  private final ObjectMapper json;
  private final Map<String,Model> models;
  private final Map<String,Long> templates;
  private final Map<String,Long> schedules;
  private final String outputDir;
  private final List<EnhancementTemplate> enhancementTemplates;
  private final List<BitrateProfile> bitrateProfiles;
  private final long mpsVersion;
  private ProviderSettings(Map<String,String> env,ObjectMapper json) {
    this.json=json; timeoutSeconds=180; retryCount=0; enhancementTemplates=null; bitrateProfiles=null; mpsVersion=0;
    arkBase=value(env,"MEDIA_ARK_BASE_URL"); providerId=value(env,"MEDIA_ARK_PROVIDER_ID");
    arkKey=value(env,"VOLC_ARK_API_KEY"); mpsEndpoint=value(env,"MEDIA_MPS_ENDPOINT");
    secretId=value(env,"TENCENTCLOUD_SECRET_ID"); secretKey=value(env,"TENCENTCLOUD_SECRET_KEY");
    region=value(env,"MEDIA_MPS_REGION"); bucket=value(env,"MEDIA_MPS_BUCKET"); bucketRegion=value(env,"MEDIA_MPS_BUCKET_REGION");
    outputDir=directory(env.getOrDefault("MEDIA_MPS_OUTPUT_DIR","/media/"));
    try {
      var configured=json.readValue(env.getOrDefault("MEDIA_ARK_MODELS","[]"),Model[].class);
      Map<String,Model> selected=new HashMap<>();
      for(Model model:configured) {
        MediaReference.identifier(model.model(),"model");
        Objects.requireNonNull(model.type());
        if(model.resolutions()==null || model.resolutions().isEmpty()
            || selected.putIfAbsent(model.model(),model)!=null) throw new IllegalArgumentException();
        model.resolutions().forEach(r->MediaReference.identifier(r,"resolution"));
        if(model.type()==MediaType.VIDEO && (model.minDuration()<1 || model.maxDuration()<model.minDuration() || model.maxDuration()>600))
          throw new IllegalArgumentException();
      }
      models=Map.copyOf(selected);
      Map<String,Long> ids=new HashMap<>();
      for(String r:List.of("720P","1080P","4K")) {
        String raw=value(env,"MEDIA_MPS_TEMPLATE_"+r);
        if(!raw.isEmpty()) { long id=Long.parseLong(raw); if(id<=0) throw new IllegalArgumentException(); ids.put(r.toLowerCase(Locale.ROOT),id); }
      }
      templates=Map.copyOf(ids);
      Map<String,Long> configuredSchedules=new HashMap<>();
      for(String resolution:List.of("720P","1080P","4K")) for(String shape:List.of("WIDE","SQUARE","STANDARD","ULTRAWIDE")) {
        String raw=value(env,"TENCENTCLOUD_MPS_TRANSPARENT_SCHEDULE_ID_"+resolution+"_"+shape);
        if(!raw.isEmpty()) {
          long id=Long.parseLong(raw);if(id<=0) throw new IllegalArgumentException();
          configuredSchedules.put(resolution+"_"+shape,id);
        }
      }
      schedules=Map.copyOf(configuredSchedules);
      if(!arkBase.isEmpty()) providerEndpoint(arkBase);
      if(!mpsEndpoint.isEmpty()) {
        URI uri=endpoint(mpsEndpoint);
        if(!uri.getPath().isEmpty() && !uri.getPath().equals("/")) throw new IllegalArgumentException();
      }
    } catch(Exception invalid) { throw new IllegalArgumentException("invalid media provider environment configuration"); }
  }
  private ProviderSettings(ProviderSettings source,Connection connection) {
    enhancementTemplates=source.enhancementTemplates;bitrateProfiles=source.bitrateProfiles;mpsVersion=source.mpsVersion;
    json=source.json; models=source.models; templates=source.templates; schedules=source.schedules;
    outputDir=source.outputDir; timeoutSeconds=connection.timeoutSeconds(); retryCount=connection.retryCount();
    arkBase=Objects.requireNonNull(connection.baseUrl()); providerId=Objects.requireNonNull(connection.providerId());
    arkKey=Objects.requireNonNull(connection.apiKey());
    if(!arkBase.isEmpty()) providerEndpoint(arkBase);
    mpsEndpoint=source.mpsEndpoint; secretId=source.secretId; secretKey=source.secretKey;
    region=source.region; bucket=source.bucket; bucketRegion=source.bucketRegion;
  }
  public Connection arkConnection() { return new Connection(0,providerId,arkBase,arkKey,timeoutSeconds,retryCount); }
  public ProviderSettings withConnection(Connection connection) { return new ProviderSettings(this,connection); }
  private ProviderSettings(ProviderSettings source,Configuration config) {
    timeoutSeconds=source.timeoutSeconds;retryCount=source.retryCount;
    enhancementTemplates=config.enhancementTemplates();bitrateProfiles=config.bitrateProfiles();mpsVersion=config.version();
    json=source.json;schedules=source.schedules;models=source.models;arkBase=source.arkBase;providerId=source.providerId;arkKey=source.arkKey;
    mpsEndpoint=source.mpsEndpoint.isBlank()?"https://mps.tencentcloudapi.com":source.mpsEndpoint;
    secretId=config.secretId();secretKey=config.secretKey();region=config.region();bucket=config.bucket();bucketRegion=config.bucketRegion();
    outputDir=directory(config.outputDir());
    templates=Map.of("720p",config.template720p(),"1080p",config.template1080p(),"4k",config.template4k());
  }
  public ProviderSettings withMpsConfiguration(Configuration config) { return new ProviderSettings(this,config); }
  public Configuration mpsConfiguration() { return new Configuration(0,secretId,secretKey,region,bucket,bucketRegion,outputDir,templates.getOrDefault("720p",0L),templates.getOrDefault("1080p",0L),templates.getOrDefault("4k",0L)); }
  private static String directory(String value) {
    if(value==null) return "/media/";
    if(!value.matches("[A-Za-z0-9_/-]{0,512}")||value.contains("//")) throw new IllegalArgumentException("Invalid output directory");
    String path=value.startsWith("/")?value:"/"+value;
    return path.endsWith("/")?path:path+"/";
  }
  public static ProviderSettings from(Map<String,String> env,ObjectMapper json) { return new ProviderSettings(env,json); }
  private static String value(Map<String,String> env,String name) { return env.getOrDefault(name,"").trim(); }
  static URI endpoint(String raw) {
    URI uri=URI.create(raw);
    boolean fixture="http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
    if((!"https".equals(uri.getScheme()) && !fixture) || uri.getHost()==null || uri.getUserInfo()!=null
        || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
    return uri;
  }
  // The host identifies ZEX; the loopback /v1 fixture exercises its protocol.
  static boolean zex(String raw) {
    URI uri=RouteSnapshot.providerUri(raw);
    return "zexapi.com".equalsIgnoreCase(uri.getHost())
        || ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && "/v1".equals(uri.getPath()));
  }
  // New API's Doubao bridge keeps task URLs here but exposes its shared catalog at /v1/models.
  static boolean newApiDoubao(String raw) { return "/doubao/api/v3".equals(providerEndpoint(raw).getPath()); }
  static URI providerEndpoint(String raw) {
    URI uri=RouteSnapshot.providerUri(raw.trim());
    String path=uri.getRawPath().replaceAll("/+$", "");
    if(!path.matches("(/[A-Za-z0-9._~-]+)*") || Arrays.stream(path.split("/")).anyMatch(p -> p.equals(".") || p.equals("..")))
      throw new IllegalArgumentException("Invalid provider base path");
    if(path.isEmpty()) path=zex(raw.trim()) ? "/v1" : "/api/v3";
    String scheme=uri.getScheme().toLowerCase(Locale.ROOT);
    int port=uri.getPort();
    if((port==443 && scheme.equals("https")) || (port==80 && scheme.equals("http"))) port=-1;
    return URI.create(scheme+"://"+uri.getHost().toLowerCase(Locale.ROOT)+(port==-1 ? "" : ":"+port)+path);
  }
  static URI arkEndpoint(String raw) { return providerEndpoint(raw); }
  public List<String> missingEnvironmentVariables() {
    return Map.of("VOLC_ARK_API_KEY",arkKey,"MEDIA_ARK_BASE_URL",arkBase,"MEDIA_ARK_PROVIDER_ID",providerId)
        .entrySet().stream().filter(e->e.getValue().isBlank()).map(Map.Entry::getKey).sorted().toList();
  }
  private Model routeModel(RouteSnapshot route) {
    try {
      var c=json.readTree(route.modelConfigurationJson());
      if(c.has("type") && c.has("resolutions") && c.has("aspectRatios")) {
        var configured=new Model(route.providerModel(),MediaType.valueOf(c.path("type").asText()),
            json.convertValue(c.get("resolutions"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}),
            json.convertValue(c.get("aspectRatios"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}),
            c.hasNonNull("sizes") ? json.convertValue(c.get("sizes"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Map<String,String>>>() {})
                : models.containsKey(route.providerModel()) ? models.get(route.providerModel()).sizes() : null,
            optionalInteger(c,"minDurationSeconds",0),optionalInteger(c,"maxDurationSeconds",0),optionalInteger(c,"maxCount",null));
        return configured;
      }
      return models.get(route.providerModel());
    } catch(Exception invalid) { throw new DefinitiveRejectionException("INVALID_PROVIDER_MODEL_CONFIGURATION"); }
  }
  private static Integer optionalInteger(com.fasterxml.jackson.databind.JsonNode value,String key,Integer fallback) {
    var field=value.get(key);if(field==null || field.isNull()) return fallback;
    if(!field.isIntegralNumber() || !field.canConvertToInt()) throw new IllegalArgumentException();
    return field.intValue();
  }
  private void validateCapability(Model configured) {
    var capability=zex(arkBase)?ZexMediaAdapter.capability(configured.model()):ArkModelCapabilities.forModel(configured.model(),configured.type().name());
    if(capability==null) {
      if(!models.containsKey(configured.model()) && !"127.0.0.1".equals(URI.create(arkBase).getHost())) throw new IllegalArgumentException();
      return; // Explicit deployment profiles preserve existing custom adapters/fixtures.
    }
    if(configured.maxCount()!=null && (configured.maxCount()<1 || configured.maxCount()>capability.maxCount())
        || (configured.type()==MediaType.VIDEO)!=(capability.minDurationSeconds()>0)) throw new IllegalArgumentException();
    if(configured.resolutions()==null || configured.resolutions().isEmpty() || configured.aspectRatios()==null || configured.aspectRatios().isEmpty()
        || !capability.resolutions().containsAll(configured.resolutions()) || !capability.aspectRatios().containsAll(configured.aspectRatios())
        || new HashSet<>(configured.resolutions()).size()!=configured.resolutions().size()
        || new HashSet<>(configured.aspectRatios()).size()!=configured.aspectRatios().size()) throw new IllegalArgumentException();
    if(configured.type()==MediaType.VIDEO && (configured.minDuration()<capability.minDurationSeconds()
        || configured.maxDuration()>capability.maxDurationSeconds() || configured.maxDuration()<configured.minDuration())) throw new IllegalArgumentException();
    if(configured.type()==MediaType.IMAGE && capability.sizes()==null && configured.sizes()!=null && !configured.sizes().isEmpty()) throw new IllegalArgumentException();
    if(configured.type()==MediaType.IMAGE && capability.sizes()!=null) {
      if(configured.sizes()==null || !configured.sizes().keySet().equals(new HashSet<>(configured.resolutions()))) throw new IllegalArgumentException();
      for(String resolution:configured.resolutions()) {
        var sizes=configured.sizes().get(resolution);
        if(sizes==null || !sizes.keySet().equals(new HashSet<>(configured.aspectRatios()))) throw new IllegalArgumentException();
        for(String ratio:configured.aspectRatios()) if(!Objects.equals(sizes.get(ratio),capability.sizes().getOrDefault(resolution,Map.of()).get(ratio))) throw new IllegalArgumentException();
      }
    }
  }
  public List<EnhancementTemplate> projectedTemplates() {
    if(enhancementTemplates!=null) return enhancementTemplates;
    var result=new ArrayList<EnhancementTemplate>();
    for(String target:List.of("720p","1080p","4K")) {
      var ids=new LinkedHashMap<String,Long>();
      for(String shape:List.of("WIDE","SQUARE","STANDARD","ULTRAWIDE")) {
        var id=schedules.get(target.toUpperCase(Locale.ROOT)+"_"+shape);if(id!=null) ids.put(shape,id);
      }
      long definition=templates.getOrDefault(target.toLowerCase(Locale.ROOT),0L);
      result.add(new EnhancementTemplate("mps-"+target.toLowerCase(Locale.ROOT),target,List.of(sourceResolution(target)),true,null,
          schedules.isEmpty() && definition>0?definition:null,ids));
    }
    return List.copyOf(result);
  }
  private static String sourceResolution(String target) {
    return switch(target) {case "720p"->"480p";case "1080p"->"720p";case "2K","4K"->"1080p";default->throw new IllegalArgumentException("Invalid enhancement target");};
  }
  private static String shape(String ratio) {
    return switch(ratio) {case "16:9","9:16"->"WIDE";case "1:1"->"SQUARE";case "4:3","3:4"->"STANDARD";case "21:9"->"ULTRAWIDE";default->throw new IllegalArgumentException("Invalid aspect ratio");};
  }
  Plan enhancementPlan(EnhancementTemplate selected,String ratio) { return enhancementPlan(selected,ratio,sourceResolution(selected.outputResolution())); }
  Plan enhancementPlan(EnhancementTemplate selected,String ratio,String source) {
    String shape=shape(ratio);
    Long id=selected.scheduleIds().get(shape);
    long definition=selected.definitionId()==null?0:selected.definitionId();
    if(id==null && definition==0 || secretId.isBlank() || secretKey.isBlank() || region.isBlank()
        || !bucket.matches("[a-z0-9-]+-[0-9]+") || !bucketRegion.matches("[a-z0-9-]+")) throw unavailable();
    Integer bitrate=selected.bitrateProfileId()==null?null:Objects.requireNonNull(bitrateProfiles).stream()
        .filter(p->p.id().equals(selected.bitrateProfileId())).findFirst().orElseThrow(ProviderSettings::unavailable).videoBitrateKbps();
    var enhancement=new Enhancement(mpsVersion,selected.id(),bitrate,List.of(),0,0,List.of());
    return new Plan(arkBase,"",MediaType.VIDEO,source,selected.outputResolution(),ratio,
        mpsEndpoint,region,bucket,bucketRegion,definition,outputDir,id==null?null:new Schedule(id,List.of(),0,0),timeoutSeconds,retryCount,enhancement);
  }
  void available(RouteSnapshot route) {
    if(arkBase.isEmpty() || arkKey.isEmpty() || providerId.isEmpty()) throw unavailable();
    if(zex(arkBase) && ZexMediaAdapter.capability(route.providerModel())==null)
      throw new DefinitiveRejectionException("UNSUPPORTED_PROVIDER_MODEL");
    if(!providerId.equals(route.providerId()) || routeModel(route)==null)
      throw new DefinitiveRejectionException("UNSUPPORTED_PROVIDER_MODEL");
  }
  Plan plan(TaskRequest request,RouteSnapshot route) {
    int timeout=timeoutSeconds,retries=retryCount;
    try {
      var policy=json.readTree(route.modelConfigurationJson()).get("requestPolicy");
      if(policy!=null && !policy.isNull()) {
        if(!policy.isObject()) throw new IllegalArgumentException();
        if(policy.hasNonNull("timeoutSeconds")) {
          var value=policy.get("timeoutSeconds");
          if(!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<1 || value.intValue()>180) throw new IllegalArgumentException();
          timeout=value.intValue();
        }
        if(policy.hasNonNull("retryCount")) {
          var value=policy.get("retryCount");
          if(!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<0 || value.intValue()>3) throw new IllegalArgumentException();
          retries=value.intValue();
        }
      }
    } catch(Exception invalid) { throw new DefinitiveRejectionException("INVALID_PROVIDER_MODEL_CONFIGURATION"); }
    return buildPlan(request,route).withRequestPolicy(timeout,retries);
  }
  private Plan buildPlan(TaskRequest request,RouteSnapshot route) {
    available(route);
    Model model=routeModel(route);
    try { validateCapability(model); }
    catch(IllegalArgumentException|NullPointerException invalid) { throw new DefinitiveRejectionException("INVALID_PROVIDER_MODEL_CONFIGURATION"); }
    var capability=zex(arkBase)?ZexMediaAdapter.capability(model.model()):ArkModelCapabilities.forModel(model.model(),model.type().name());
    int maxCount=model.maxCount()!=null?model.maxCount():capability==null?4:capability.maxCount();
    if(request.count()>maxCount) throw new DefinitiveRejectionException("UNSUPPORTED_PROVIDER_REQUEST");
    if(model.type()!=request.type() || !model.resolutions().contains(request.resolution())
        || !route.requestedModel().equals(request.model())) throw new DefinitiveRejectionException("UNSUPPORTED_PROVIDER_REQUEST");
    if(model.aspectRatios()==null || !model.aspectRatios().contains(request.aspectRatio()))
      throw new DefinitiveRejectionException("UNSUPPORTED_PROVIDER_ASPECT_RATIO");
    // The worker must resolve owner-authorized file references before creating the provider plan.
    if(request.references().stream().anyMatch(r->r.fileId()!=null)) throw new DefinitiveRejectionException("FILE_REFERENCE_RESOLVER_UNAVAILABLE");
    request.references().forEach(r->MediaReference.https(r.httpsUrl()));
    if(request.referenceMode()==ReferenceMode.OMNI) {
      var omni=OmniReference.forModel(route.providerModel());
      if(zex(arkBase) || omni==null || !omni.accepts(request.references(),true))
        throw new DefinitiveRejectionException("UNSUPPORTED_OMNI_REFERENCE");
    }
    if(zex(arkBase)) return ZexMediaAdapter.plan(arkBase,request,route.providerModel());
    if(request.type()==MediaType.IMAGE) {
      if(request.count()+request.references().size()>15) throw new DefinitiveRejectionException("IMAGE_LIMIT_EXCEEDED");
      String size=model.sizes()==null ? null : model.sizes().getOrDefault(request.resolution(),Map.of()).get(request.aspectRatio());
      if(size==null || !size.matches("[1-9][0-9]{1,4}x[1-9][0-9]{1,4}"))
        throw new DefinitiveRejectionException("UNSUPPORTED_PROVIDER_IMAGE_SIZE");
      return new Plan(arkBase,route.providerModel(),request.type(),size,request.resolution(),request.aspectRatio(),null,null,null,null,0);
    }
    if(request.durationSeconds()<model.minDuration() || request.durationSeconds()>model.maxDuration()
        || request.referenceMode()!=ReferenceMode.OMNI && request.references().size()>2)
      throw new DefinitiveRejectionException("UNSUPPORTED_VIDEO_INPUT");
    VideoResolution target=switch(request.resolution().toLowerCase(Locale.ROOT)) {
      case "480p" -> VideoResolution.R480P; case "720p" -> VideoResolution.R720P;
      case "1080p" -> VideoResolution.R1080P; case "4k" -> VideoResolution.R4K;
      default -> throw new DefinitiveRejectionException("UNSUPPORTED_VIDEO_RESOLUTION");
    };
    VideoDeliveryPlan delivery=VideoDeliveryPlan.forTarget(target);
    String source=switch(delivery.sourceResolution()) {
      case R480P -> "480p"; case R720P -> "720p"; case R1080P -> "1080p"; case R4K -> "4K";
    };
    long template=0;Schedule schedule=null;Enhancement enhancement=null;
    if(delivery.mode()==VideoDeliveryMode.ENHANCEMENT) {
      if(enhancementTemplates!=null) {
        String targetName=target==VideoResolution.R4K?"4K":request.resolution().toLowerCase(Locale.ROOT);
        var selected=enhancementTemplates.stream().filter(t->t.enabled() && t.outputResolution().equals(targetName)).findFirst()
            .orElseThrow(()->new DefinitiveRejectionException("MPS_TEMPLATE_DISABLED"));
        if(!selected.acceptedInputResolutions().contains(source)) throw new DefinitiveRejectionException("UNSUPPORTED_MPS_INPUT");
        var enhanced=enhancementPlan(selected,request.aspectRatio());template=enhanced.template();schedule=enhanced.schedule();enhancement=enhanced.enhancement();
      } else if(schedules.isEmpty()) template=templates.getOrDefault(request.resolution().toLowerCase(Locale.ROOT),0L);
      else {
        String shape=switch(request.aspectRatio()) {
          case "16:9","9:16" -> "WIDE"; case "1:1" -> "SQUARE";
          case "4:3","3:4" -> "STANDARD"; case "21:9" -> "ULTRAWIDE";
          default -> throw new DefinitiveRejectionException("UNSUPPORTED_PROVIDER_ASPECT_RATIO");
        };
        Long id=schedules.get(request.resolution().toUpperCase(Locale.ROOT)+"_"+shape);
        if(id==null) throw unavailable();
        schedule=new Schedule(id,List.of(),0,0);
      }
      if((template==0 && schedule==null) || mpsEndpoint.isEmpty() || secretId.isEmpty() || secretKey.isEmpty() || region.isEmpty()
          || !bucket.matches("[a-z0-9-]+-[0-9]+") || !bucketRegion.matches("[a-z0-9-]+")) throw unavailable();
    }
    return new Plan(arkBase,route.providerModel(),request.type(),source,request.resolution(),request.aspectRatio(),mpsEndpoint,region,bucket,bucketRegion,template,outputDir,schedule,null,null,enhancement);
  }
  static DependencyUnavailableException unavailable() { return new DependencyUnavailableException("media provider configuration unavailable"); }
  public record Model(String model,MediaType type,List<String> resolutions,List<String> aspectRatios,
      Map<String,Map<String,String>> sizes,int minDuration,int maxDuration,Integer maxCount) {
    public Model(String model,MediaType type,List<String> resolutions,List<String> aspectRatios,Map<String,Map<String,String>> sizes,int minDuration,int maxDuration) {
      this(model,type,resolutions,aspectRatios,sizes,minDuration,maxDuration,null);
    }
  }
  public record Schedule(long id,List<Long> definitions,int width,int height) {
    public Schedule { definitions=List.copyOf(definitions); }
  }
  public record Activity(String activityType,List<Integer> reardriveIndex,Long definition,Integer videoBitrateKbps,com.fasterxml.jackson.databind.JsonNode transcodeTask) {
    public Activity { reardriveIndex=List.copyOf(reardriveIndex);transcodeTask=transcodeTask==null || transcodeTask.isNull()?null:transcodeTask.deepCopy(); }
    @Override public com.fasterxml.jackson.databind.JsonNode transcodeTask() { return transcodeTask==null?null:transcodeTask.deepCopy(); }
  }
  public record Enhancement(long configurationVersion,String templateId,Integer videoBitrateKbps,List<Long> definitions,int width,int height,List<Activity> activities) {
    public Enhancement { definitions=List.copyOf(definitions);activities=List.copyOf(activities); }
  }
  /** Immutable non-secret execution settings. Environment rotation cannot silently re-plan paid work. */
  public record Plan(String arkBase,String model,MediaType type,String sourceResolution,String targetResolution,String aspectRatio,
      String mpsEndpoint,String region,String bucket,String bucketRegion,long template,String outputDir,Schedule schedule,
      Integer timeoutSeconds,Integer retryCount,Enhancement enhancement) {
    public Plan { outputDir=directory(outputDir); }
    public Plan(String arkBase,String model,MediaType type,String sourceResolution,String targetResolution,String aspectRatio,
        String mpsEndpoint,String region,String bucket,String bucketRegion,long template) {
      this(arkBase,model,type,sourceResolution,targetResolution,aspectRatio,mpsEndpoint,region,bucket,bucketRegion,template,"/media/");
    }
    public Plan(String arkBase,String model,MediaType type,String sourceResolution,String targetResolution,String aspectRatio,
        String mpsEndpoint,String region,String bucket,String bucketRegion,long template,String outputDir) {
      this(arkBase,model,type,sourceResolution,targetResolution,aspectRatio,mpsEndpoint,region,bucket,bucketRegion,template,outputDir,null);
    }
    public Plan(String arkBase,String model,MediaType type,String sourceResolution,String targetResolution,String aspectRatio,
        String mpsEndpoint,String region,String bucket,String bucketRegion,long template,String outputDir,Schedule schedule) {
      this(arkBase,model,type,sourceResolution,targetResolution,aspectRatio,mpsEndpoint,region,bucket,bucketRegion,template,outputDir,schedule,null,null,null);
    }
    public Plan(String arkBase,String model,MediaType type,String sourceResolution,String targetResolution,String aspectRatio,
        String mpsEndpoint,String region,String bucket,String bucketRegion,long template,String outputDir,Schedule schedule,Integer timeoutSeconds,Integer retryCount) {
      this(arkBase,model,type,sourceResolution,targetResolution,aspectRatio,mpsEndpoint,region,bucket,bucketRegion,template,outputDir,schedule,timeoutSeconds,retryCount,null);
    }
    Plan withRequestPolicy(int timeout,int retries) {
      return new Plan(arkBase,model,type,sourceResolution,targetResolution,aspectRatio,mpsEndpoint,region,bucket,bucketRegion,
          template,outputDir,schedule,timeout,retries,enhancement);
    }
    Plan withEnhancement(Enhancement resolved) {
      return new Plan(arkBase,model,type,sourceResolution,targetResolution,aspectRatio,mpsEndpoint,region,bucket,bucketRegion,
          template,outputDir,schedule,timeoutSeconds,retryCount,resolved);
    }
    boolean enhanced() { return template>0 || schedule!=null; }
    Plan withSchedule(Schedule resolved) {
      return new Plan(arkBase,model,type,sourceResolution,targetResolution,aspectRatio,mpsEndpoint,region,bucket,bucketRegion,
          resolved.definitions().getLast(),outputDir,resolved,timeoutSeconds,retryCount,enhancement);
    }
  }
}
