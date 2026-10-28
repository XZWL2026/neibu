package com.xzwl.cloud.media;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.media.application.task.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
class DepthConfigurationTest {
  @Test void switchAndSecretCannotEnableDepthOutsideLocalProfile() {
    for(String profile:new String[]{"","production","local"}) for(boolean enabled:new boolean[]{false,true}) {
      var environment=new MockEnvironment();if(!profile.isEmpty()) environment.setActiveProfiles(profile);
      var depths=new MediaConfiguration().depthTasks(null,new ObjectMapper(),environment,enabled,
          "http://127.0.0.1:18727","t".repeat(32),"http://127.0.0.1:8219","s".repeat(32),Duration.ofSeconds(1),Duration.ofSeconds(5));
      assertEquals(profile.equals("local") && enabled,depths.enabled());
    }
    var local=new MockEnvironment();local.setActiveProfiles("local");
    assertFalse(new MediaConfiguration().depthTasks(null,new ObjectMapper(),local,true,
        "http://127.0.0.1:18727","","http://127.0.0.1:8219","",Duration.ofSeconds(1),Duration.ofSeconds(5)).enabled());
  }
}
