package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.task.MediaReference;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** MPS API 2019-06-12, TC3 signing and private COS archive access. */
public final class TencentMpsAdapter {
  private final ProviderHttp http;
  private final Clock clock;
  public TencentMpsAdapter(ProviderHttp http,Clock clock) { this.http=http; this.clock=clock; }
  String submit(String operation,String source,ProviderSettings.Plan plan,ProviderSettings settings) {
    MediaReference.https(source);
    Map<String,Object> payload=new LinkedHashMap<>(Map.of(
        "InputInfo",Map.of("Type","URL","UrlInputInfo",Map.of("Url",source)),
        "OutputStorage",Map.of("Type","COS","CosOutputStorage",Map.of("Bucket",plan.bucket(),"Region",plan.bucketRegion())),
        "OutputDir",plan.outputDir()+operation+"/","SessionId",operation));
    if(plan.enhancement()!=null && !plan.enhancement().activities().isEmpty()) {
      var activities=new ArrayList<Map<String,Object>>();
      for(var activity:plan.enhancement().activities()) {
        Map<String,Object> node=new LinkedHashMap<>(Map.of("ActivityType",activity.activityType(),"ReardriveIndex",activity.reardriveIndex()));
        if(activity.definition()!=null) {
          var input=activity.transcodeTask()==null?com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode():(com.fasterxml.jackson.databind.node.ObjectNode)activity.transcodeTask();
          input.put("Definition",activity.definition());
          if(activity.videoBitrateKbps()!=null) input.putObject("OverrideParameter").putObject("VideoTemplate").put("Bitrate",activity.videoBitrateKbps());
          node.put("ActivityPara",Map.of("TranscodeTask",input));
        }
        activities.add(node);
      }
      payload.put("Activities",activities);
    } else if(plan.schedule()!=null) payload.put("ScheduleId",plan.schedule().id());
    else payload.put("MediaProcessTask",Map.of("TranscodeTaskSet",List.of(Map.of("Definition",plan.template()))));
    JsonNode result=call("ProcessMedia",payload,plan,settings);
    return ArkMediaAdapter.taskId(result.path("TaskId"));
  }
  ProviderPort.ProviderPoll poll(String id,ProviderSettings.Plan plan,ProviderSettings settings) {
    JsonNode result=call("DescribeTaskDetail",Map.of("TaskId",id),plan,settings);
    if(result.hasNonNull("TaskId") && !id.equals(result.path("TaskId").asText())) throw ProviderHttp.uncertain();
    String status=result.path("Status").asText("");
    if(Set.of("WAITING","PROCESSING").contains(status)) return ProviderPort.ProviderPoll.pending();
    if(!"FINISH".equals(status)) throw ProviderHttp.uncertain();
    if(plan.schedule()!=null) return scheduleResult(id,result,plan);
    JsonNode workflow=result.path("WorkflowTask");
    if(workflow.path("ErrCode").asInt(0)!=0) return ProviderPort.ProviderPoll.failed(errorCode(workflow));
    JsonNode stages=workflow.path("MediaProcessResultSet");
    if(!stages.isArray()) return ProviderPort.ProviderPoll.failed();
    List<JsonNode> matches=new ArrayList<>();
    for(JsonNode stage:stages) {
      JsonNode task=stage.path("TranscodeTask");
      if(task.path("Input").path("Definition").asLong(-1)==plan.template()
          || task.path("Output").path("Definition").asLong(-1)==plan.template()) matches.add(task);
    }
    if(matches.size()!=1) return ProviderPort.ProviderPoll.failed();
    JsonNode task=matches.getFirst(), output=task.path("Output");
    if(!"SUCCESS".equals(task.path("Status").asText()) || task.path("ErrCode").asInt(0)!=0
        || output.path("Definition").asLong(-1)!=plan.template()) return ProviderPort.ProviderPoll.failed(errorCode(task));
    int shortEdge=Math.min(output.path("Width").asInt(),output.path("Height").asInt());
    int expected=switch(plan.targetResolution().toLowerCase(Locale.ROOT)) { case "720p" -> 720; case "1080p" -> 1080; case "2k" -> 1440; case "4k" -> 2160; default -> -1; };
    if(plan.enhancement()!=null) {
      if(output.path("Width").asInt()!=plan.enhancement().width() || output.path("Height").asInt()!=plan.enhancement().height()) return ProviderPort.ProviderPoll.failed();
    } else if(shortEdge!=expected) return ProviderPort.ProviderPoll.failed();
    return output(output,plan);
  }
  private ProviderPort.ProviderPoll output(JsonNode output,ProviderSettings.Plan plan) {
    JsonNode storage=output.path("OutputStorage"), cos=storage.path("CosOutputStorage");
    if(!"COS".equals(storage.path("Type").asText()) || !plan.bucket().equals(cos.path("Bucket").asText())
        || !plan.bucketRegion().equals(cos.path("Region").asText())) return ProviderPort.ProviderPoll.failed();
    try {
      String path=output.path("Path").asText("");
      if(!path.startsWith("/") || path.startsWith("//") || path.contains("\\") || path.contains("..") || path.length()>4096)
        return ProviderPort.ProviderPoll.failed();
      String url=new URI("https",cosHost(plan.bucket(),plan.bucketRegion()),path,null).toASCIIString();
      return ProviderPort.ProviderPoll.completed(List.of(new ProviderPort.ProviderOutput(0,url)));
    } catch(Exception invalid) { return ProviderPort.ProviderPoll.failed(); }
  }
  /** Read-only preflight runs before generation/charging, and is never repeated for a persisted operation. */
  ProviderSettings.Plan prepare(ProviderSettings.Plan plan,ProviderSettings settings) {
    if(plan.schedule()==null && plan.enhancement()==null) return plan;
    long deadline=System.nanoTime()+Duration.ofSeconds(plan.timeoutSeconds()==null?180:Math.min(180,plan.timeoutSeconds())).toNanos();
    List<Long> definitions=new ArrayList<>();List<JsonNode> transcodeInputs=new ArrayList<>();
    if(plan.schedule()!=null) {
    JsonNode rows=call("DescribeSchedules",Map.of("ScheduleIds",List.of(plan.schedule().id())),plan,settings,deadline).path("ScheduleInfoSet");
    if(!rows.isArray() || rows.size()!=1 || rows.get(0).path("ScheduleId").asLong(-1)!=plan.schedule().id()) throw invalidSchedule();
    JsonNode activities=rows.get(0).path("Activities");
    if(!activities.isArray() || activities.size()<3) throw invalidSchedule();
    int start=-1;
    for(int i=0;i<activities.size();i++) if("input".equals(activities.get(i).path("ActivityType").asText())) {
      if(start!=-1) throw invalidSchedule();start=i;
    }
    Set<Integer> visited=new HashSet<>();int index=start;
    while(true) {
      if(index<0 || index>=activities.size() || !visited.add(index)) throw invalidSchedule();
      JsonNode node=activities.get(index),next=node.path("ReardriveIndex");
      if(!next.isArray()) throw invalidSchedule();
      String type=node.path("ActivityType").asText();
      if("output".equals(type)) {
        if(!next.isEmpty() || definitions.isEmpty() || visited.size()!=activities.size()) throw invalidSchedule();
        break;
      }
      if(index!=start) {
        if(!"action-trans".equals(type)) throw invalidSchedule();
        JsonNode input=node.path("ActivityPara").path("TranscodeTask"),definition=input.path("Definition");
        // These schedules inherit storage and use unmodified templates; overrides would invalidate the frozen dimensions.
        if(input.hasNonNull("OverrideParameter") || input.hasNonNull("OutputStorage")
            || !definition.isIntegralNumber() || !definition.canConvertToLong() || definition.asLong()<=0
            || definitions.contains(definition.asLong())) throw invalidSchedule();
        definitions.add(definition.asLong());
        transcodeInputs.add(input.deepCopy());
      }
      if(next.size()!=1 || !next.get(0).isIntegralNumber() || !next.get(0).canConvertToInt()) throw invalidSchedule();
      index=next.get(0).asInt();
    }
    } else { definitions.add(plan.template());transcodeInputs.add(null); }
    long terminal=definitions.getLast();
    JsonNode templates=call("DescribeTranscodeTemplates",Map.of("Definitions",List.of(terminal)),plan,settings,deadline).path("TranscodeTemplateSet");
    if(!templates.isArray() || templates.size()!=1 || templates.get(0).path("Definition").asLong(-1)!=terminal) throw invalidSchedule();
    JsonNode video=templates.get(0).path("VideoTemplate");
    if(!video.path("Width").isIntegralNumber() || !video.path("Width").canConvertToInt()
        || !video.path("Height").isIntegralNumber() || !video.path("Height").canConvertToInt()) throw invalidSchedule();
    int width=video.path("Width").asInt(),height=video.path("Height").asInt();
    if(width<=0 || height<=0 || !"open".equals(video.path("ResolutionAdaptive").asText())) throw invalidSchedule();
    boolean portrait=Set.of("9:16","3:4").contains(plan.aspectRatio());
    int longest=Math.max(width,height),shortest=Math.min(width,height);
    int finalWidth=portrait?shortest:longest,finalHeight=portrait?longest:shortest;
    if(plan.enhancement()!=null) {
      int[] expected=expectedDimensions(plan.targetResolution(),plan.aspectRatio());
      if(finalWidth!=expected[0] || finalHeight!=expected[1]) throw invalidSchedule();
    }
    if(plan.schedule()!=null) plan=plan.withSchedule(new ProviderSettings.Schedule(plan.schedule().id(),definitions,finalWidth,finalHeight));
    if(plan.enhancement()==null) return plan;
    var policy=plan.enhancement();var activities=new ArrayList<ProviderSettings.Activity>();
    if(policy.videoBitrateKbps()!=null) {
      activities.add(new ProviderSettings.Activity("input",List.of(1),null,null,null));
      for(int i=0;i<definitions.size();i++) activities.add(new ProviderSettings.Activity("action-trans",List.of(i+2),definitions.get(i),i==definitions.size()-1?policy.videoBitrateKbps():null,transcodeInputs.get(i)));
      activities.add(new ProviderSettings.Activity("output",List.of(),null,null,null));
      if(plan.schedule()==null) plan=plan.withSchedule(new ProviderSettings.Schedule(0,definitions,finalWidth,finalHeight));
    }
    return plan.withEnhancement(new ProviderSettings.Enhancement(policy.configurationVersion(),policy.templateId(),policy.videoBitrateKbps(),definitions,finalWidth,finalHeight,activities));
  }
  // Accepted dimensions from docs/business-control/mps-schedules/design.md, including non-short-edge tiers.
  private static int[] expectedDimensions(String target,String ratio) {
    int tier=switch(target.toLowerCase(Locale.ROOT)) {case "720p"->0;case "1080p"->1;case "2k"->2;case "4k"->3;default->throw invalidSchedule();};
    int shape=switch(ratio) {case "16:9","9:16"->0;case "1:1"->1;case "4:3","3:4"->2;case "21:9"->3;default->throw invalidSchedule();};
    int[][][] accepted={{{1280,720},{960,960},{1112,834},{1470,630}},{{1920,1080},{1440,1440},{1664,1248},{2206,946}},{{2560,1440},{1920,1920},{2218,1664},{2938,1260}},{{3840,2160},{2880,2880},{3326,2494},{4398,1886}}};
    int[] size=accepted[tier][shape];return Set.of("9:16","3:4").contains(ratio)?new int[]{size[1],size[0]}:size;
  }
  private static DefinitiveRejectionException invalidSchedule() { return new DefinitiveRejectionException("INVALID_MPS_SCHEDULE"); }
  private ProviderPort.ProviderPoll scheduleResult(String id,JsonNode result,ProviderSettings.Plan plan) {
    JsonNode schedule=result.path("ScheduleTask"),stages=schedule.path("ActivityResultSet");
    if(!"ScheduleTask".equals(result.path("TaskType").asText()) || !id.equals(schedule.path("TaskId").asText())
        || !"FINISH".equals(schedule.path("Status").asText())) return ProviderPort.ProviderPoll.failed();
    if(schedule.path("ErrCode").asInt(0)!=0) return ProviderPort.ProviderPoll.failed(errorCode(schedule));
    if(!stages.isArray() || stages.size()!=plan.schedule().definitions().size()) return ProviderPort.ProviderPoll.failed();
    Set<Long> seen=new HashSet<>();JsonNode terminal=null;
    Map<Long,ProviderPort.EnhancementOutput> captured=new HashMap<>();
    for(JsonNode stage:stages) {
      JsonNode task=stage.path("ActivityResItem").path("TranscodeTask"),output=task.path("Output");
      JsonNode definition=output.path("Definition"),inputDefinition=task.path("Input").path("Definition");
      if(!"Transcode".equals(stage.path("ActivityType").asText()) || !definition.isIntegralNumber() || !definition.canConvertToLong()
          || !inputDefinition.isIntegralNumber() || !inputDefinition.canConvertToLong()
          || inputDefinition.asLong()!=definition.asLong() || !plan.schedule().definitions().contains(definition.asLong())
          || !seen.add(definition.asLong())) return ProviderPort.ProviderPoll.failed();
      String code=task.path("ErrCodeExt").asText("");
      if(!"SUCCESS".equals(task.path("Status").asText()) || task.path("ErrCode").asInt(0)!=0
          || !code.isEmpty() && !"SUCCESS".equals(code)) return ProviderPort.ProviderPoll.failed(errorCode(task));
      var validated=output(output,plan);
      boolean dimensions=output.path("Width").canConvertToInt() && output.path("Height").canConvertToInt()
          && output.path("Width").isIntegralNumber() && output.path("Height").isIntegralNumber()
          && output.path("Width").asInt()>0 && output.path("Height").asInt()>0;
      boolean accessible=dimensions && validated.status()==ProviderPort.ProviderPoll.Status.COMPLETED;
      if(definition.asLong()==plan.template() && !accessible) return ProviderPort.ProviderPoll.failed();
      // Invalid intermediate diagnostics cannot turn a valid final delivery into a refund.
      captured.put(definition.asLong(),new ProviderPort.EnhancementOutput(definition.asLong(),dimensions?output.path("Width").asInt():0,
          dimensions?output.path("Height").asInt():0,duration(task),accessible?validated.outputs().getFirst().httpsUrl():null));
      if(definition.asLong()==plan.template()) terminal=output;
    }
    if(terminal==null || !terminal.path("Width").isIntegralNumber() || !terminal.path("Height").isIntegralNumber()
        || terminal.path("Width").asLong()!=plan.schedule().width() || terminal.path("Height").asLong()!=plan.schedule().height())
      return ProviderPort.ProviderPoll.failed();
    return new ProviderPort.ProviderPoll(ProviderPort.ProviderPoll.Status.COMPLETED,output(terminal,plan).outputs(),null,null,null,null,
        plan.schedule().definitions().stream().map(captured::get).toList());
  }
  private static Long duration(JsonNode task) {
    try {
      long ms=Duration.between(Instant.parse(task.path("BeginProcessTime").asText()),Instant.parse(task.path("FinishTime").asText())).toMillis();
      return ms>=0?ms:null;
    } catch(java.time.DateTimeException | ArithmeticException missing) { return null; }
  }
  private static String errorCode(JsonNode node) {
    String extended=node.path("ErrCodeExt").asText("");
    return !extended.isBlank() ? extended : node.path("ErrCode").asText("INVALID_ENHANCEMENT_OUTPUT");
  }
  private JsonNode call(String action,Object payload,ProviderSettings.Plan plan,ProviderSettings settings) {
    return call(action,payload,plan,settings,System.nanoTime()+Duration.ofSeconds(plan.timeoutSeconds()==null?180:Math.min(180,plan.timeoutSeconds())).toNanos());
  }
  private JsonNode call(String action,Object payload,ProviderSettings.Plan plan,ProviderSettings settings,long deadline) {
    if(settings.secretId.isBlank() || settings.secretKey.isBlank()) throw ProviderSettings.unavailable();
    String body=http.encode(payload);
    URI uri=URI.create(plan.mpsEndpoint());
    String host=uri.getRawAuthority();
    long timestamp=clock.instant().getEpochSecond();
    String authorization=authorization(host,action,body,timestamp,settings.secretId,settings.secretKey);
    long remaining=deadline-System.nanoTime();if(remaining<=0) throw ProviderHttp.uncertain();
    JsonNode response=http.call("POST",plan.mpsEndpoint().replaceAll("/+$","")+"/",Map.of(
        "Content-Type","application/json","Authorization",authorization,"X-TC-Action",action,
        "X-TC-Version","2019-06-12","X-TC-Region",plan.region(),"X-TC-Timestamp",Long.toString(timestamp)),body,Duration.ofNanos(remaining),0).path("Response");
    if(!response.isObject()) throw ProviderHttp.uncertain();
    if(response.hasNonNull("Error")) {
      String code=response.path("Error").path("Code").asText("");
      if(code.startsWith("InvalidParameter") || code.startsWith("AuthFailure") || code.startsWith("UnauthorizedOperation")
          || code.startsWith("ResourceNotFound") || code.startsWith("UnsupportedOperation"))
        throw new DefinitiveRejectionException(code);
      // Duplicate SessionId is not a rejection of the first submission; its result remains uncertain.
      throw ProviderHttp.uncertain();
    }
    return response;
  }
  static String authorization(String host,String action,String body,long timestamp,String secretId,String secretKey) {
    String date=LocalDate.ofInstant(Instant.ofEpochSecond(timestamp),ZoneOffset.UTC).toString();
    String canonical="POST\n/\n\ncontent-type:application/json\nhost:"+host+"\nx-tc-action:"+action.toLowerCase(Locale.ROOT)
        +"\n\ncontent-type;host;x-tc-action\n"+hash("SHA-256",body);
    String scope=date+"/mps/tc3_request";
    String sign="TC3-HMAC-SHA256\n"+timestamp+"\n"+scope+"\n"+hash("SHA-256",canonical);
    byte[] key=hmac("HmacSHA256",bytes("TC3"+secretKey),date);
    key=hmac("HmacSHA256",key,"mps"); key=hmac("HmacSHA256",key,"tc3_request");
    return "TC3-HMAC-SHA256 Credential="+secretId+"/"+scope
        +", SignedHeaders=content-type;host;x-tc-action, Signature="+hex(hmac("HmacSHA256",key,sign));
  }
  /** Only generate signatures in memory immediately before Storage fetches; never persist SecretId in result URLs. */
  public String archiveUrl(String source,ProviderSettings settings) {
    return archiveUrl(source,settings,settings.bucket,settings.bucketRegion);
  }
  public String archiveUrl(String source,ProviderSettings settings,ProviderSettings.Plan plan) {
    return archiveUrl(source,settings,plan.bucket(),plan.bucketRegion());
  }
  private String archiveUrl(String source,ProviderSettings settings,String bucket,String bucketRegion) {
    URI uri=URI.create(MediaReference.https(source));
    if(!cosHost(bucket,bucketRegion).equals(uri.getHost())) return source;
    if(uri.getQuery()!=null || uri.getPort()!=-1 || settings.secretId.isBlank() || settings.secretKey.isBlank())
      throw ProviderSettings.unavailable();
    long now=clock.instant().getEpochSecond(); String time=(now-60)+";"+(now+900);
    String key=hex(hmac("HmacSHA1",bytes(settings.secretKey),time));
    String canonical="get\n"+uri.getPath()+"\n\nhost="+uri.getHost()+"\n";
    String sign=hex(hmac("HmacSHA1",bytes(key),"sha1\n"+time+"\n"+hash("SHA-1",canonical)+"\n"));
    return source+"?q-sign-algorithm=sha1&q-ak="+URLEncoder.encode(settings.secretId,StandardCharsets.UTF_8)
        +"&q-sign-time="+time+"&q-key-time="+time+"&q-header-list=host&q-url-param-list=&q-signature="+sign;
  }
  private static String cosHost(String bucket,String region) { return bucket+".cos."+region+".myqcloud.com"; }
  private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
  private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
  private static String hash(String algorithm,String value) {
    try { return hex(MessageDigest.getInstance(algorithm).digest(bytes(value))); }
    catch(Exception impossible) { throw new IllegalStateException("provider signing unavailable"); }
  }
  private static byte[] hmac(String algorithm,byte[] key,String value) {
    try { Mac mac=Mac.getInstance(algorithm); mac.init(new SecretKeySpec(key,algorithm)); return mac.doFinal(bytes(value)); }
    catch(Exception impossible) { throw new IllegalStateException("provider signing unavailable"); }
  }
}
