package com.xzwl.cloud.media.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.domain.task.TaskRequest;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderHttpDiagnosticsTest {
  @Test void sanitizesNestedSecretsSignedUrlsPromptAndRestoresTaskScope() throws Exception {
    var rows=new ArrayList<String>();var json=new ObjectMapper();
    var request=new TaskRequest(MediaType.VIDEO,"model","private prompt","720p",1,15,List.of());
    var body=json.writeValueAsString(Map.of("model","model","prompt",request.prompt(),"content",List.of(
        Map.of("type","image_url","image_url",Map.of("url","https://media.example/private.jpg?signature=private-signature"))),
        "password","private-password","nested",Map.of("api_key","private-key")));
    try(var outer=new ProviderHttpDiagnostics(rows::add,"outer","ARK_SENDING",request,List.of("credential"))) {
      try(var inner=new ProviderHttpDiagnostics(rows::add,"inner","ARK_SENDING",request,List.of("credential"))) {
        ProviderHttpDiagnostics.begin("POST","https://user:credential@provider.example/tasks?token=private-token",
            Map.of("Authorization","Bearer credential"),body,0,Duration.ofSeconds(1)).failure("HttpTimeoutException");
      }
      ProviderHttpDiagnostics.begin("POST","https://provider.example/tasks",Map.of(),body,0,Duration.ofSeconds(1));
    }
    ProviderHttpDiagnostics.begin("POST","https://provider.example/tasks",Map.of(),body,0,Duration.ofSeconds(1));
    assertEquals(3,rows.size());
    assertEquals("inner",json.readTree(rows.getFirst()).path("taskId").asText());
    assertEquals("outer",json.readTree(rows.getLast()).path("taskId").asText());
    var detail=json.readTree(rows.getFirst());
    assertEquals("https://provider.example/tasks",detail.path("endpoint").asText());
    assertEquals(14,detail.path("request").path("prompt").path("utf8Bytes").asInt());
    assertTrue(rows.getFirst().contains("media.example"));
    for(String secret:List.of("private prompt","private-signature","private-password","private-key","private-token","credential","private.jpg"))
      assertFalse(rows.toString().contains(secret),secret);
  }
}
