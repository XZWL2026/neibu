package com.xzwl.cloud.billing;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.infrastructure.HttpAdminConsumptionQuery;
import com.xzwl.cloud.billing.interfaces.http.AdminConsumptionController;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class AdminConsumptionAnalyticsTest {
  private static final String PATH="/api/v1/admin/billing/consumptions/analytics";
  private static final String END="2026-09-21T00:00:00Z";
  private static final ObjectMapper JSON=new ObjectMapper();

  @Test void aggregatesEveryFilteredRecordWithFourDecimalsAndShanghaiMidnight() throws Exception {
    try(var f=new Fixture()) {
      JsonNode result=f.analytics("");
      assertEquals(101,result.path("total").asLong());assertEquals("13.4900",result.path("amount4").asText());
      assertEquals(0,result.path("unknownAmounts").asLong());assertEquals(2,f.scans);
      assertEquals(101,f.snapshots);assertEquals(0,f.userReads);
      var models=result.path("models");assertEquals(2,models.size());
      var image=model(models,"IMAGE");assertEquals(99,image.path("calls").asLong());assertEquals(198,image.path("quantity").asLong());
      assertEquals("9.9099",image.path("amount4").asText());assertEquals("Historical image",image.path("modelName").asText());
      var video=model(models,"VIDEO");assertEquals(24,video.path("quantity").asLong());assertEquals("3.5801",video.path("amount4").asText());
      assertEquals("720p",video.path("resolutions").get(0).path("resolution").asText());
      assertEquals(24,video.path("resolutions").get(0).path("quantity").asLong());
      var days=result.path("days");assertEquals(2,days.size());
      assertEquals("2026-09-19",days.get(0).path("date").asText());assertEquals("11.1444",days.get(0).path("amount4").asText());
      assertEquals("1.2345",days.get(0).path("videoAmount4").asText());assertEquals("9.9099",days.get(0).path("imageAmount4").asText());
      assertEquals("2026-09-20",days.get(1).path("date").asText());assertEquals("2.3456",days.get(1).path("videoAmount4").asText());
      assertEquals("0.0000",days.get(1).path("imageAmount4").asText());
      assertEquals(1,f.analytics("&status=FAILED&type=VIDEO&model=video&userId=owner").path("total").asLong());
      assertEquals(0,f.analytics("&userId=someone-else").path("total").asLong());
      assertEquals(1,f.analytics("&from=2026-09-19T16:00:00Z").path("total").asLong());
    }
  }

  @Test void preservesUnknownAmountsQuantitiesResolutionAndMissingTasks() throws Exception {
    try(var f=new Fixture()) {
      f.rows.clear();f.rows.add(row(0,"IMAGE",null));f.rows.add(row(1,null,"2.0000"));
      f.missingTasks.add("task-1");f.missingQuantity=true;
      var result=f.analytics("");assertEquals(2,result.path("total").asLong());assertTrue(result.path("amount4").isNull());
      assertEquals(1,result.path("unknownAmounts").asLong());
      var image=model(result.path("models"),"IMAGE");assertTrue(image.path("quantity").isNull());assertTrue(image.path("amount4").isNull());
      assertTrue(image.path("resolutions").get(0).path("resolution").isNull());
      var days=result.path("days");assertTrue(days.get(0).path("amount4").isNull());assertTrue(days.get(0).path("imageAmount4").isNull());
      // A known amount on a task with missing type cannot be assigned to either daily type series.
      assertTrue(days.get(0).path("videoAmount4").isNull());
      var missing=f.analytics("&status=MISSING");assertEquals(1,missing.path("total").asLong());assertEquals("2.0000",missing.path("amount4").asText());
      assertTrue(missing.path("models").get(0).path("quantity").isNull());
    }
  }

  @Test void groupsModelIdentityAcrossHistoricalNamesAndMissingSnapshots() throws Exception {
    try(var f=new Fixture()) {
      f.rows.clear();for(int i=0;i<4;i++)f.rows.add(row(i,"IMAGE","0.1001"));
      f.missingTasks.add("task-0");f.names.put("task-1"," ");
      f.names.put("task-2","New historical name");f.names.put("task-3","Old historical name");
      var models=f.analytics("").path("models");assertEquals(1,models.size());
      var model=models.get(0);assertEquals("image",model.path("model").asText());assertEquals("New historical name",model.path("modelName").asText());
      assertEquals(4,model.path("calls").asLong());assertEquals("0.4004",model.path("amount4").asText());
      assertTrue(model.path("quantity").isNull());
    }
  }

  @Test void doesNotAttributeResolutionFromDifferentTaskModel() throws Exception {
    try(var f=new Fixture()) {
      f.rows.clear();f.rows.add(row(99,"IMAGE","1.2345"));
      var model=f.analytics("").path("models").get(0);
      assertEquals("image",model.path("model").asText());assertTrue(model.path("quantity").isNull());
      assertTrue(model.path("resolutions").get(0).path("resolution").isNull());
      assertEquals("image",model.path("modelName").asText());
    }
  }

  @Test void authorizesBothAdminRolesValidatesDatesAndFailsClosedOnOwnership() throws Exception {
    try(var f=new Fixture()) {
      f.mvc.perform(get(PATH).param("to",END)).andExpect(status().isUnauthorized());
      f.mvc.perform(get(PATH).param("to",END).principal(()->"user")).andExpect(status().isForbidden());
      f.mvc.perform(get(PATH).param("to",END).principal(()->"staff").with(r->{r.addUserRole("ADMIN");return r;})).andExpect(status().isOk());
      f.mvc.perform(get(PATH).param("to",END).principal(()->"staff").with(r->{r.addUserRole("NORMAL_ADMIN");return r;})).andExpect(status().isOk());
      f.mvc.perform(get(PATH).principal(()->"staff").with(r->{r.addUserRole("ADMIN");return r;})).andExpect(status().isBadRequest());
      assertThrows(Exception.class,()->f.analytics("&from=2026-09-22T00:00:00Z"));
      assertThrows(Exception.class,()->f.analytics("&status=INVENTED"));
      f.wrongOwner=true;assertThrows(Exception.class,()->f.analytics(""));
      var r=f.rows.getFirst();f.rows.set(0,new ConsumptionStore.Row(r.id(),r.taskId(),r.keyId(),null,r.type(),r.model(),r.accountType(),r.createdAt(),r.debitedPoints4(),r.refundedPoints4(),r.netPoints4(),r.amount4(),r.rate4()));
      assertThrows(Exception.class,()->f.analytics(""));
    }
  }

  private static JsonNode model(JsonNode models,String type) {for(var m:models)if(type.equals(m.path("type").asText()))return m;throw new AssertionError(type);}
  private static ConsumptionStore.Row row(int i,String type,String amount) {
    return new ConsumptionStore.Row("entry-"+i,"task-"+i,"key","owner",type,type==null?null:type.toLowerCase(Locale.ROOT),"PERSONAL",
        i==100?"2026-09-19T16:00:00Z":"2026-09-19T15:59:59Z","9.0000","8.0000","1.0000",amount,"1.0000");
  }
  private static final class Fixture implements AutoCloseable {
    final List<ConsumptionStore.Row> rows=new ArrayList<>();final Set<String> missingTasks=new HashSet<>();final Map<String,String> names=new HashMap<>();
    boolean wrongOwner,missingQuantity;int scans,snapshots,userReads;final HttpServer server;final MockMvc mvc;
    Fixture() throws Exception {
      for(int i=0;i<101;i++)rows.add(row(i,i<99?"IMAGE":"VIDEO",i<99?"0.1001":i==99?"1.2345":"2.3456"));
      var store=new ConsumptionStore() {
        public Optional<AllocationFact> allocation(String a,String b,String c){throw new AssertionError();}
        public Summary summary(String a,Instant b,Instant c){throw new AssertionError();}
        public Page search(Filter q){scans++;var matches=rows.stream().filter(r->!Instant.parse(r.createdAt()).isAfter(q.to())&&(q.from()==null||!Instant.parse(r.createdAt()).isBefore(q.from()))).toList();return new Page(matches.subList(Math.min(q.offset(),matches.size()),Math.min(q.offset()+q.limit(),matches.size())),matches.size());}
        public Row get(String id){return rows.stream().filter(r->r.id().equals(id)).findFirst().orElseThrow();}
        public List<Entry> entries(String id){throw new AssertionError();}
      };
      server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/",exchange->{
        String path=exchange.getRequestURI().getPath(),body;int code=200;
        if(path.startsWith("/api/v1/admin/media/tasks/")) {
          snapshots++;String id=path.substring(path.lastIndexOf('/')+1);int i=Integer.parseInt(id.substring(5));
          if(missingTasks.contains(id)){code=404;body="{}";}
          else {String type=i<99?"IMAGE":"VIDEO";body="{\"ownerId\":\""+(wrongOwner?"other":"owner")+"\",\"references\":[],\"task\":{\"id\":\""+id+"\",\"status\":\""+(i==100?"FAILED":"SUCCEEDED")+"\",\"type\":\""+type+"\",\"model\":\""+type.toLowerCase(Locale.ROOT)+"\",\"modelName\":\""+names.getOrDefault(id,"Historical "+type.toLowerCase(Locale.ROOT))+"\""+(missingQuantity?"":",\"count\":2,\"durationSeconds\":6,\"resolution\":\"720p\"")+"}}";}
        } else if(path.startsWith("/internal/v1/access/"))body="{\"keyId\":\"key\",\"ownerId\":\"owner\",\"accountType\":\"PERSONAL\",\"enabled\":false}";
        else {userReads++;body="{}";}
        byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Connection","close");exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
      });server.start();
      var client=RestClient.create("http://127.0.0.1:"+server.getAddress().getPort());
      mvc=MockMvcBuilders.standaloneSetup(new AdminConsumptionController(new HttpAdminConsumptionQuery(store,client,client,client))).build();
    }
    JsonNode analytics(String query) throws Exception {
      return JSON.readTree(mvc.perform(get(PATH+"?to="+END+query).principal(()->"staff").with(r->{r.addUserRole("ADMIN");return r;})).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    public void close(){server.stop(0);}
  }
}
