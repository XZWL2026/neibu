package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.provider.ProviderDiagnosticQuery.Filter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcProviderDiagnosticQueryTest {
  @Test void filtersAndPaginatesStoredEvidenceWithStableOrdering() throws Exception {
    var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    try(var in=getClass().getResourceAsStream("/db/media-migration/common/V21__media_provider_http_diagnostics.sql")) {
      for(String sql:new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split(";")) if(!sql.isBlank())jdbc.execute(sql);
    }
    var detail="{\"event\":\"response_received\",\"httpStatus\":400,\"elapsedMs\":12,\"request\":{\"model\":\"seedance\"},\"responseHeaders\":{\"x-request-id\":\"upstream\"},\"response\":{\"error\":{\"code\":\"invalid_request\",\"message\":\"image failed\"}}}";
    for(String id:List.of("a","b","c")) jdbc.update("INSERT INTO media_provider_http_diagnostic VALUES(?,?,?,?,?)",id,id.equals("c")?"other":"task","ARK_SENDING",1000,detail);
    jdbc.update("INSERT INTO media_provider_http_diagnostic VALUES(?,?,?,?,?)","d","task","ARK_RUNNING",2000,"invalid-json");
    var query=new JdbcProviderDiagnosticQuery(jdbc,new ObjectMapper());
    var page=query.list(new Filter("task","ARK_SENDING",0,2000,0,1));
    assertEquals(2,page.total());assertEquals("b",page.items().getFirst().id());
    assertEquals("a",query.list(new Filter("task","ARK_SENDING",0,2000,1,1)).items().getFirst().id());
    var entry=page.items().getFirst();assertEquals(400,entry.httpStatus());assertEquals("upstream",entry.requestId());assertEquals("image failed",entry.message());
    assertEquals(detail,query.find("b").orElseThrow().detailJson());assertTrue(query.find("missing").isEmpty());
    assertEquals("诊断记录格式异常",query.find("d").orElseThrow().entry().message());
    assertThrows(IllegalArgumentException.class,()->new Filter("' OR 1=1",null,0,2000,0,20));
    var grouped=query.tasks(new Filter(null,null,0,3000,0,1));
    assertEquals(2,grouped.total());assertEquals("task",grouped.items().getFirst().taskId());
    assertEquals(3,grouped.items().getFirst().recordCount());assertEquals("d",grouped.items().getFirst().latest().id());
    assertEquals("other",query.tasks(new Filter(null,null,0,3000,1,1)).items().getFirst().taskId());
    var filtered=query.tasks(new Filter("task","ARK_SENDING",0,2000,0,20));
    assertEquals(1,filtered.total());assertEquals(2,filtered.items().getFirst().recordCount());assertEquals("b",filtered.items().getFirst().latest().id());
    assertEquals(0,query.tasks(new Filter(null,"MPS_RUNNING",0,3000,0,20)).total());
    // Many rows spanning several flat pages still occupy one task slot, including tied timestamps.
    for(int i=0;i<65;i++)jdbc.update("INSERT INTO media_provider_http_diagnostic VALUES(?,?,?,?,?)","many-"+String.format("%03d",i),"busy-task","ARK_RUNNING",2500,detail);
    var tasks=query.tasks(new Filter(null,null,0,3000,0,2));
    assertEquals(3,tasks.total());assertEquals(List.of("busy-task","task"),tasks.items().stream().map(t->t.taskId()).toList());
    assertEquals(65,tasks.items().getFirst().recordCount());assertEquals("many-064",tasks.items().getFirst().latest().id());
    assertEquals(List.of("other"),query.tasks(new Filter(null,null,0,3000,2,2)).items().stream().map(t->t.taskId()).toList());
    assertEquals(65,query.list(new Filter("busy-task",null,0,3000,20,20)).total());
    assertEquals(20,query.list(new Filter("busy-task",null,0,3000,20,20)).items().size());
  }
}
