package com.xzwl.cloud.billing;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.infrastructure.HttpAdminConsumptionQuery;
import com.xzwl.cloud.billing.interfaces.http.AdminConsumptionController;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminConsumptionQueryTest {
  @Test void scansBeyond100ForStatusAndEnforcesAuthorizationAndFailsClosed() throws Exception {
    var rows=new ArrayList<ConsumptionStore.Row>();
    for(int i=0;i<205;i++)rows.add(new ConsumptionStore.Row("entry-"+i,"task-"+i,"key","owner","IMAGE","model",null,Instant.now().toString(),"4.0000","2.0000","2.0000",null,null));
    var store=new ConsumptionStore() {
      public java.util.Optional<AllocationFact> allocation(String owner,String allocation,String lot){throw new AssertionError("not used by admin query");}
      public Summary summary(String ownerId,Instant from,Instant to){throw new AssertionError("not used by admin query");}
      public Summary summary(String ownerId,Instant to){throw new AssertionError("not used by admin query");}
      public Page search(Filter f){return new Page(rows.subList(Math.min(f.offset(),rows.size()),Math.min(f.offset()+f.limit(),rows.size())),rows.size());}
      public Row get(String id){return rows.stream().filter(r->r.id().equals(id)).findFirst().orElseThrow();}
      public List<Entry> entries(String id){get(id);return List.of(new Entry("debit","task","CONSUME","-4.0000","10.0000","6.0000",Instant.now().toString()),new Entry("refund","refund","REFUND","3.0000","6.0000","9.0000",Instant.now().toString()));}
    };
    var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    var failed=new java.util.concurrent.atomic.AtomicBoolean();
    server.createContext("/",exchange->{
      String path=exchange.getRequestURI().getPath();String body;int code=200;
      if(failed.get()){code=503;body="{}";}
      else if(path.startsWith("/api/v1/admin/media/tasks/")){
        if(path.contains("/files/")) {code=404;body="{}";}
        else {
          String id=path.substring(path.lastIndexOf('/')+1);int i=Integer.parseInt(id.substring(5));
          if(i==204){code=404;body="{}";}
          else body="{\"ownerId\":\"owner\",\"references\":[],\"task\":{\"id\":\""+id+"\",\"status\":\""+(i>=200?"FAILED":"SUCCEEDED")+"\",\"modelName\":\"Historical model\"}}";
        }
      } else if(path.startsWith("/internal/v1/access/"))body="{\"keyId\":\"key\",\"ownerId\":\"owner\",\"accountType\":\"PERSONAL\",\"enabled\":false}";
      else body="{\"id\":\"owner\",\"nickname\":\"Real name\",\"phone\":\"+86138****0000\"}";
      if(path.startsWith("/api/v1/admin/media/tasks/") && !path.contains("/files/") && code==200) {
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(body);
        for(String kind:List.of("IMAGE","VIDEO","AUDIO")) ((com.fasterxml.jackson.databind.node.ArrayNode)tree.get("references")).addObject().put("index",tree.get("references").size()).put("fileId",kind+"-file").put("kind",kind);
        tree.putObject("enhancement").putArray("steps").addObject().put("index",0).put("definitionId",10).put("width",2560).put("height",1440)
            .put("status","COMPLETE").put("durationMs",65000).put("fileId","intermediate-file");
        body=tree.toString();
      }
      byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });server.start();
    try {
      var client=RestClient.create("http://127.0.0.1:"+server.getAddress().getPort());
      var query=new HttpAdminConsumptionQuery(store,client,client,client);
      var page=query.search(new AdminConsumptionQuery.Query(null,null,null,"FAILED",null,Instant.now(),2,2),"session");
      assertEquals(4,page.total());assertEquals(List.of("entry-202","entry-203"),page.items().stream().map(r->r.ledger().id()).toList());
      assertFalse(page.items().getFirst().keyEnabled());
      var old=rows.getLast();
      rows.set(204,new ConsumptionStore.Row(old.id(),old.taskId(),old.keyId(),null,null,null,null,old.createdAt(),"4.0000","2.0000","2.0000",null,null));
      var legacy=query.search(new AdminConsumptionQuery.Query("owner",null,null,null,null,Instant.now(),200,20),"session");
      assertEquals(205,legacy.total());assertTrue(legacy.items().stream().anyMatch(r->r.ledger().id().equals("entry-204")));
      var missing=query.detail("entry-204","session");assertNull(missing.snapshot());assertEquals("1.0000",missing.item().ledger().netPoints4());
      assertEquals("3.0000",missing.item().ledger().refundedPoints4());
      assertThrows(NoSuchElementException.class,()->query.detail("task-without-ledger","session"));
      var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AdminConsumptionController(query)).build();
      String path="/api/v1/admin/billing/consumptions/entry-1";
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).principal(()->"user")).andExpect(status().isForbidden());
      mvc.perform(get(path).principal(()->"staff").with(r->{r.addUserRole("NORMAL_ADMIN");return r;})).andExpect(status().isOk())
          .andExpect(r->assertEquals("IMAGE",new ObjectMapper().readTree(r.getResponse().getContentAsString()).at("/snapshot/references/0/kind").asText()))
          .andExpect(r->assertEquals("VIDEO",new ObjectMapper().readTree(r.getResponse().getContentAsString()).at("/snapshot/references/1/kind").asText()))
          .andExpect(r->assertEquals("AUDIO",new ObjectMapper().readTree(r.getResponse().getContentAsString()).at("/snapshot/references/2/kind").asText()))
          .andExpect(r->assertFalse(r.getResponse().getContentAsString().contains("secret")));
      var step=query.detail("entry-1","session").snapshot().enhancement().steps().getFirst();
      assertEquals("intermediate-file",step.fileId());assertEquals(65000L,step.durationMs());assertEquals(2560,step.width());
      failed.set(true);
      mvc.perform(get(path).principal(()->"staff").with(r->{r.addUserRole("ADMIN");return r;})).andExpect(status().isServiceUnavailable());
    } finally {server.stop(0);}
  }
}
