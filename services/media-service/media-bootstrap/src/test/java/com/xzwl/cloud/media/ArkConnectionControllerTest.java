package com.xzwl.cloud.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.xzwl.cloud.media.application.provider.ArkConnectionStore;
import com.xzwl.cloud.media.interfaces.http.ArkConnectionController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ArkConnectionControllerTest {
  static final String PATH = "/api/v1/admin/media/ark-connection";
  static final String SECRET = "never-return-this-key";
  static final String BODY = "{\"expectedVersion\":0,\"providerId\":\"ark\",\"baseUrl\":\"https://ark.example/api/v3\",\"apiKey\":\"" + SECRET + "\"}";
  static final class Store implements ArkConnectionStore {
    Connection value = new Connection(0, "ark", "https://ark.example/api/v3", SECRET);
    int reads, writes;
    RuntimeException failure;
    public boolean hasConfiguredConnection() { return true; }
    public boolean editable() { return failure == null; }
    public Connection load(String providerId) { reads++; if (failure != null) throw failure; return value; }
    public Connection save(long expectedVersion, String providerId, String baseUrl, String apiKey) {
      return save(expectedVersion,providerId,baseUrl,apiKey,null,null);
    }
    public Connection save(long expectedVersion,String providerId,String baseUrl,String apiKey,Integer timeoutSeconds,Integer retryCount) {
      writes++;if(failure!=null) throw failure;
      return value=new Connection(1,providerId,baseUrl,apiKey,timeoutSeconds==null?value.timeoutSeconds():timeoutSeconds,retryCount==null?value.retryCount():retryCount);
    }
  }
  MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
    return request.principal(() -> "primary-admin").with(r -> { r.addUserRole("ADMIN"); r.addUserRole("PRIMARY_ADMIN"); return r; });
  }
  @Test void requestPolicyRoundTripsAndLegacyUpdateRetainsIt() throws Exception {
    Store store=new Store();var mvc=MockMvcBuilders.standaloneSetup(new ArkConnectionController(store)).build();
    String body=BODY.substring(0,BODY.length()-1)+",\"timeoutSeconds\":60,\"retryCount\":2}";
    mvc.perform(admin(put(PATH).contentType("application/json").content(body))).andExpect(status().isOk())
        .andExpect(jsonPath("timeoutSeconds").value(60)).andExpect(jsonPath("retryCount").value(2));
    mvc.perform(admin(put(PATH).contentType("application/json").content(BODY))).andExpect(status().isOk())
        .andExpect(jsonPath("timeoutSeconds").value(60)).andExpect(jsonPath("retryCount").value(2));
    for(String invalid:new String[]{body.replace(":60",":181"),body.replace(":60",":0"),body.replace(":2}",":4}"),body.replace(":2}",":-1}")})
      mvc.perform(admin(put(PATH).contentType("application/json").content(invalid))).andExpect(status().isBadRequest());
  }
  @Test void everyEndpointRequiresVerifiedPrimaryAdministratorBeforeStoreAccess() throws Exception {
    Store store = new Store();
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArkConnectionController(store)).build();
    for (boolean save : new boolean[] {false, true}) {
      mvc.perform(save ? put(PATH).contentType("application/json").content(BODY) : get(PATH).param("providerId","ark")).andExpect(status().isUnauthorized());
      for (String role : new String[] {"NORMAL_ADMIN", "ADMIN", "PRIMARY_ADMIN", "USER"}) {
        var request = save ? put(PATH).contentType("application/json").content(BODY) : get(PATH).param("providerId","ark");
        mvc.perform(request.principal(() -> "other").with(r -> { r.addUserRole(role); return r; }))
            .andExpect(status().isForbidden());
      }
    }
    assertEquals(0, store.reads); assertEquals(0, store.writes);
  }
  @Test void primaryAdministratorCanReadSavedPlaintextWithoutCachingOrLoggingIt() throws Exception {
    Store store = new Store();
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArkConnectionController(store)).build();
    String getBody = mvc.perform(admin(get(PATH).param("providerId","ark"))).andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("apiKey").value(SECRET)).andExpect(jsonPath("apiKeyConfigured").value(true))
        .andExpect(jsonPath("maskedApiKey").value("***")).andExpect(jsonPath("editable").value(true))
        .andExpect(jsonPath("source").value("ENVIRONMENT")).andReturn().getResponse().getContentAsString();
    String savedBody = mvc.perform(admin(put(PATH).contentType("application/json").content(BODY)))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("apiKey").value(SECRET)).andExpect(jsonPath("version").value(1))
        .andExpect(jsonPath("maskedApiKey").value("***")).andExpect(jsonPath("source").value("DATABASE"))
        .andReturn().getResponse().getContentAsString();
    assertTrue(getBody.contains(SECRET)); assertTrue(savedBody.contains(SECRET));
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setUserPrincipal(() -> "primary-admin"); request.addUserRole("ADMIN"); request.addUserRole("PRIMARY_ADMIN");
    assertFalse(new ArkConnectionController(store).get("ark", request, new org.springframework.mock.web.MockHttpServletResponse()).toString().contains(SECRET));
    assertFalse(new ArkConnectionController.SaveRequest(0, "ark", "https://ark.example", SECRET).toString().contains(SECRET));
    assertEquals(SECRET, store.value.apiKey());
  }
  @Test void invalidConflictAndUnavailableUseSafeStatusCodes() throws Exception {
    Store store = new Store();
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArkConnectionController(store)).build();
    RuntimeException[] errors = {new IllegalArgumentException(SECRET), new ArkConnectionStore.VersionConflict(), new ArkConnectionStore.NotEditable()};
    int[] statuses = {400, 409, 503};
    for (int i=0; i<errors.length; i++) {
      store.failure = errors[i];
      String body = mvc.perform(admin(put(PATH).contentType("application/json").content(BODY)))
          .andExpect(status().is(statuses[i])).andExpect(header().string("Cache-Control", "no-store"))
          .andReturn().getResponse().getContentAsString();
      assertFalse(body.contains(SECRET));
    }
    mvc.perform(admin(get(PATH).param("providerId","ark"))).andExpect(status().isServiceUnavailable());
  }
}
