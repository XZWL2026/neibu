package com.xzwl.cloud.access;

import com.xzwl.cloud.access.application.key.*;
import com.xzwl.cloud.access.domain.key.AccountType;
import com.xzwl.cloud.access.interfaces.http.AccessKeyController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

class AccessMaskedKeyTest {
  @Test void adminLookupMatchesFullSecretIncludingDisabledKeysWithoutListingOrEchoingSecrets() throws Exception {
    var key = new AccessKey("key", "owner", AccountType.PERSONAL, "MEDIA", "sk-_" + "a".repeat(64), false, "paid");
    var calls = new java.util.ArrayList<String>();
    var store = (AccessKeyStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AccessKeyStore.class}, (p,m,a) -> {
      assertEquals("findBySecret", m.getName()); calls.add(m.getName());
      return key.secret().equals(a[0]) ? Optional.of(key) : Optional.empty();
    });
    var controller = new AccessKeyController(store, new AccessKeys(store, owner -> true));
    var request = new MockHttpServletRequest(); request.setUserPrincipal(() -> "admin"); request.addUserRole("ADMIN");
    var response = new MockHttpServletResponse();
    var body = new AccessKeyController.Lookup(key.secret());
    var result = controller.adminLookup(body, request, response);
    assertEquals("owner", result.ownerId()); assertFalse(result.enabled());
    assertEquals("no-store", response.getHeader("Cache-Control"));
    assertFalse(new ObjectMapper().writeValueAsString(result).contains(key.secret()));
    assertFalse(body.toString().contains(key.secret()));
    assertEquals(404, assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> controller.adminLookup(new AccessKeyController.Lookup(key.secret() + "wrong"), request, response)).getStatusCode().value());
    int authorizedCalls = calls.size();
    for (String role : new String[]{"NORMAL_ADMIN", "USER", "API_USER", "PROMOTER", ""}) {
      var denied = new MockHttpServletRequest(); denied.addUserRole(role);
      assertEquals(403, assertThrows(org.springframework.web.server.ResponseStatusException.class,
          () -> controller.adminLookup(body, denied, response)).getStatusCode().value());
    }
    assertEquals(authorizedCalls, calls.size());
  }
  @Test void portalRevealsOnlyOwnKeyWithoutAllowingMutations() {
    var key = new AccessKey("key", "owner", AccountType.API, "API", "sk-actualSecret1234", true, "paid");
    var store = (AccessKeyStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AccessKeyStore.class}, (p,m,a) -> {
      if (m.getName().equals("findByOwner") && a[0].equals("owner")) return Optional.of(key);
      throw new AssertionError("Forbidden request touched store: " + m.getName());
    });
    var controller = new AccessKeyController(store, new AccessKeys(store, owner -> true));
    var request = new MockHttpServletRequest(); request.setUserPrincipal(() -> "owner"); request.addUserRole("API_USER");
    assertEquals("key", controller.list(request).getFirst().keyId());
    var response = new MockHttpServletResponse();
    assertEquals(key.secret(), controller.secret("key", request, response).get("secret"));
    assertEquals("no-store", response.getHeader("Cache-Control"));
    assertEquals(404, assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.secret("other", request, response)).getStatusCode().value());
    assertEquals(401, assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.secret("key", new MockHttpServletRequest(), response)).getStatusCode().value());
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.create(request));
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.status("key", new AccessKeyController.Status(false), request));
    var promoter = new MockHttpServletRequest(); promoter.setUserPrincipal(() -> "owner"); promoter.addUserRole("PROMOTER");
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.list(promoter));
    assertEquals(403, assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.secret("key", promoter, response)).getStatusCode().value());
  }
  @Test void listProjectsOnlyRealMaskedSecretWhileExplicitRevealStaysAuthorized() throws Exception {
    var stored = new AtomicReference<AccessKey>();
    var store = (AccessKeyStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AccessKeyStore.class}, (p,m,a) -> {
      if (m.getName().equals("findByOwner")) return Optional.ofNullable(stored.get());
      throw new UnsupportedOperationException(m.getName());
    });
    var keys = new AccessKeys(store, owner -> true);
    var controller = new AccessKeyController(store, keys);
    var request = new MockHttpServletRequest(); request.setUserPrincipal(() -> "owner");
    var key = new AccessKey("internal-id", "owner", AccountType.PERSONAL, "MEDIA", "sk-actualSecret1234", true, "recharge");
    stored.set(key);
    var json = new ObjectMapper().valueToTree(controller.list(request).getFirst());
    assertEquals("sk-actualSecret****", json.path("maskedKey").asText());
    assertFalse(json.toString().contains(key.secret()));
    var response = new MockHttpServletResponse();
    assertEquals(key.secret(), controller.secret(key.id(), request, response).get("secret"));
    assertEquals("no-store", response.getHeader("Cache-Control"));
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.secret("other", request, response));
    for (String invalid : new String[]{"internal-id", "sk-", "sk-1234"}) {
      stored.set(new AccessKey("internal-id", "owner", AccountType.PERSONAL, "MEDIA", invalid, true, "recharge"));
      assertTrue(new ObjectMapper().valueToTree(controller.list(request).getFirst()).path("maskedKey").isNull());
    }
  }
}
