package com.xzwl.cloud.media.infrastructure.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.task.NativeVideoUsageQuery;
import com.xzwl.cloud.media.domain.task.RouteSnapshot;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNativeVideoUsageQuery implements NativeVideoUsageQuery {
  private static final ZoneId BEIJING=ZoneId.of("Asia/Shanghai");
  private static final Metric ZERO=new Metric(0,0);
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public JdbcNativeVideoUsageQuery(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}

  @Override public Report query(LocalDate from,LocalDate to) {
    if(from==null || to==null || from.isAfter(to)) throw new IllegalArgumentException("invalid date range");
    long start=from.atStartOfDay(BEIJING).toInstant().toEpochMilli();
    long end=to.plusDays(1).atStartOfDay(BEIJING).toInstant().toEpochMilli();
    Map<String,Item> groups=new HashMap<>();
    // ponytail: aggregate only the indexed date window in Java; move JSON dimensions to indexed columns if report volume grows.
    jdbc.query("SELECT t.task_json,p.plan_json,p.native_resolution,p.generation_finished_at "
        +"FROM media_provider_operation p JOIN media_task t ON t.id=p.operation_id "
        +"WHERE p.generation_finished_at>=? AND p.generation_finished_at<? "
        +"AND (p.phase='COMPLETE' OR p.phase IN ('MPS_READY','MPS_SENDING','MPS_RUNNING') "
        +"OR (p.phase='FAILED' AND p.failure_stage IN ('MPS_READY','MPS_SENDING','MPS_RUNNING')))",
        (RowCallbackHandler) row->{
          JsonNode task=tree(row.getString("task_json")),plan=tree(row.getString("plan_json"));
          if(task==null || plan==null || !volcano(plan.path("arkBase").asText(""))) return;
          JsonNode data=task.path("data"),request=data.path("request"),duration=request.path("durationSeconds"),operation=request.path("operation");
          // Older generation tasks predate this discriminator; the joined native provider execution still identifies them.
          if(!request.path("type").asText().equals("VIDEO")
              || !(operation.isMissingNode() || operation.isNull() || operation.asText().equals("GENERATION"))
              || !duration.isIntegralNumber() || !duration.canConvertToInt() || duration.intValue()<=0) return;
          String model=model(request.path("model"),data.path("route").path("requestedModel"),plan.path("model"));
          String nativeResolution=normalize(row.getString("native_resolution"));
          if(model==null || !Set.of("480p","720p","1080p").contains(nativeResolution)) return;
          String date=Instant.ofEpochMilli(row.getLong("generation_finished_at")).atZone(BEIJING).toLocalDate().toString();
          String key=date+":"+model;
          Item old=groups.getOrDefault(key,new Item(date,model,ZERO,ZERO,ZERO,ZERO,ZERO,ZERO,ZERO));
          Metric amount=new Metric(1,duration.intValue());
          String selected=normalize(request.path("resolution").asText());
          boolean enhanced720=nativeResolution.equals("480p") && selected.equals("720p");
          boolean enhanced1080=!nativeResolution.equals("1080p") && selected.equals("1080p");
          boolean enhanced4k=selected.equals("4k");
          boolean nativeOutput=!(enhanced720 || enhanced1080 || enhanced4k);
          groups.put(key,new Item(date,model,
              old.native480().plus(nativeOutput && nativeResolution.equals("480p")?amount:ZERO),
              old.native720().plus(nativeOutput && nativeResolution.equals("720p")?amount:ZERO),
              old.native1080().plus(nativeOutput && nativeResolution.equals("1080p")?amount:ZERO),
              old.enhanced720().plus(enhanced720?amount:ZERO),
              old.enhanced1080().plus(enhanced1080?amount:ZERO),
              old.enhanced4k().plus(enhanced4k?amount:ZERO),
              old.total().plus(amount)));
        },start,end);
    var items=groups.values().stream().sorted(Comparator.comparing(Item::date).reversed().thenComparing(Item::model)).toList();
    return new Report(from.toString(),to.toString(),items,items.stream().map(Item::total).reduce(ZERO,Metric::plus));
  }
  private JsonNode tree(String value) {
    if(value==null) return null;
    try {return json.readTree(value);} catch(JsonProcessingException malformed) {return null;}
  }
  private static boolean volcano(String base) {
    try {
      var uri=RouteSnapshot.providerUri(base);
      return ("https".equalsIgnoreCase(uri.getScheme()) && "ark.cn-beijing.volces.com".equalsIgnoreCase(uri.getHost()))
          || "/doubao/api/v3".equals(uri.getRawPath().replaceAll("/+$",""));
    }
    catch(IllegalArgumentException invalid) {return false;}
  }
  private static String normalize(String value) {return value==null?"":value.trim().toLowerCase(Locale.ROOT);}
  private static String model(JsonNode requested,JsonNode routed,JsonNode actual) {
    for(var value:List.of(requested,routed)) {
      if(value.isMissingNode() || value.isNull() || value.isTextual() && value.textValue().isBlank()) continue;
      return switch(normalize(value.asText())) {
        case "seedance-2.0","seedance 2.0" -> "Seedance 2.0";
        case "seedance-2.0-fast","seedance 2.0 fast" -> "Seedance 2.0 Fast";
        case "seedance-2.0-mini","seedance 2.0 mini" -> "Seedance 2.0 Mini";
        case "seedance-2.5","seedance 2.5" -> "Seedance 2.5";
        default -> null;
      };
    }
    return switch(actual.asText()) {
      case "doubao-seedance-2-0-260128" -> "Seedance 2.0";
      case "doubao-seedance-2-0-fast-260128" -> "Seedance 2.0 Fast";
      case "doubao-seedance-2-0-mini-260615" -> "Seedance 2.0 Mini";
      case "doubao-seedance-2-5-260628" -> "Seedance 2.5";
      default -> null;
    };
  }
}
