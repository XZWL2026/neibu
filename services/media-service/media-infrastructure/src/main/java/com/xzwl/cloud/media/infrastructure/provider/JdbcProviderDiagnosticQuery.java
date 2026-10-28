package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.provider.ProviderDiagnosticQuery;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProviderDiagnosticQuery implements ProviderDiagnosticQuery {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public JdbcProviderDiagnosticQuery(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}

  public Page list(Filter filter) {
    var where=new StringBuilder(" WHERE created_at>=? AND created_at<?");
    var args=new ArrayList<Object>(List.of(filter.from(),filter.to()));
    if(filter.taskId()!=null) {where.append(" AND operation_id=?");args.add(filter.taskId());}
    if(filter.phase()!=null) {where.append(" AND phase=?");args.add(filter.phase());}
    long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM media_provider_http_diagnostic"+where,Long.class,args.toArray()));
    args.add(filter.limit());args.add(filter.offset());
    var entries=jdbc.query("SELECT id,operation_id,phase,created_at,detail_json FROM media_provider_http_diagnostic"+where
        +" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(r,i)->entry(r.getString("id"),r.getString("operation_id"),
        r.getString("phase"),r.getLong("created_at"),r.getString("detail_json")),args.toArray());
    return new Page(entries,total,filter.offset(),filter.limit(),filter.from(),filter.to());
  }
  public Optional<Detail> find(String id) {
    if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) throw new IllegalArgumentException("invalid diagnostic ID");
    return jdbc.query("SELECT id,operation_id,phase,created_at,detail_json FROM media_provider_http_diagnostic WHERE id=?",(r,i)->
        new Detail(entry(r.getString("id"),r.getString("operation_id"),r.getString("phase"),r.getLong("created_at"),r.getString("detail_json")),
            r.getString("detail_json")),id).stream().findFirst();
  }
  public TaskPage tasks(Filter filter) {
    var where=new StringBuilder(" WHERE created_at>=? AND created_at<?");
    var args=new ArrayList<Object>(List.of(filter.from(),filter.to()));
    if(filter.taskId()!=null) {where.append(" AND operation_id=?");args.add(filter.taskId());}
    if(filter.phase()!=null) {where.append(" AND phase=?");args.add(filter.phase());}
    long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(DISTINCT operation_id) FROM media_provider_http_diagnostic"+where,Long.class,args.toArray()));
    args.add(filter.limit());args.add(filter.offset());
    // Rank before pagination so one busy task cannot displace other tasks or split across pages.
    String sql="SELECT d.id,d.operation_id,d.phase,d.created_at,d.detail_json,r.record_count FROM media_provider_http_diagnostic d JOIN ("
        +"SELECT id,COUNT(*) OVER(PARTITION BY operation_id) AS record_count,"
        +"ROW_NUMBER() OVER(PARTITION BY operation_id ORDER BY created_at DESC,id DESC) AS position_in_task "
        +"FROM media_provider_http_diagnostic"+where+") r ON d.id=r.id WHERE r.position_in_task=1 "
        +"ORDER BY d.created_at DESC,d.id DESC LIMIT ? OFFSET ?";
    var groups=jdbc.query(sql,(r,i)->new TaskGroup(r.getString("operation_id"),r.getLong("record_count"),
        entry(r.getString("id"),r.getString("operation_id"),r.getString("phase"),r.getLong("created_at"),r.getString("detail_json"))),args.toArray());
    return new TaskPage(groups,total,filter.offset(),filter.limit(),filter.from(),filter.to());
  }
  private Entry entry(String id,String taskId,String phase,long createdAt,String detail) {
    try {
      var root=json.readTree(detail);
      String requestId=first(root,"/responseHeaders/x-request-id","/responseHeaders/x-tt-logid","/responseHeaders/request-id",
          "/response/request_id","/response/requestId","/response/ResponseMetadata/RequestId","/response/Response/RequestId");
      String code=first(root,"/response/error/code","/response/data/error/code","/response/Response/Error/Code","/response/ResponseMetadata/Error/Code");
      String message=first(root,"/failureKind","/response/error/message","/response/data/error/message","/response/Response/Error/Message",
          "/response/ResponseMetadata/Error/Message","/response/message","/response/preview");
      return new Entry(id,taskId,phase,createdAt,first(root,"/event"),first(root,"/request/model"),
          root.path("httpStatus").isInt()?root.path("httpStatus").intValue():null,
          root.path("elapsedMs").isIntegralNumber()?root.path("elapsedMs").longValue():null,requestId,code,message);
    } catch(Exception malformed) {return new Entry(id,taskId,phase,createdAt,null,null,null,null,null,null,"诊断记录格式异常");}
  }
  private static String first(JsonNode root,String... paths) {
    for(String path:paths) {var value=root.at(path);if(value.isTextual() && !value.asText().isBlank()) {
      String text=value.asText();return text.length()>1000?text.substring(0,1000)+"…":text;
    }}return null;
  }
}
