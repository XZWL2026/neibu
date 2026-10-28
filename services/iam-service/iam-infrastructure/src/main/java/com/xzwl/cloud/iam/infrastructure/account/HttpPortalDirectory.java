package com.xzwl.cloud.iam.infrastructure.account;

import com.xzwl.cloud.iam.application.account.AuthFailure;
import com.xzwl.cloud.iam.application.account.PortalDirectory;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;

public final class HttpPortalDirectory implements PortalDirectory {
  private final RestClient access, promotion, payment;
  private final String token;
  public HttpPortalDirectory(String accessUrl, String promotionUrl, String paymentUrl, String token) { this(RestClient.builder(),accessUrl,promotionUrl,paymentUrl,token); }
  public HttpPortalDirectory(RestClient.Builder builder,String accessUrl, String promotionUrl, String paymentUrl, String token) {
    this.token = token;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
    access = builder.clone().baseUrl(accessUrl).requestFactory(factory).build();
    promotion = builder.clone().baseUrl(promotionUrl).requestFactory(factory).build();
    payment = builder.clone().baseUrl(paymentUrl).requestFactory(factory).build();
  }
  public boolean eligible(String role, String userId) { return eligible(role, userId, false); }
  @Override public boolean apiEntryEligible(String userId) { return eligible("API_USER", userId, true); }
  private boolean eligible(String role, String userId, boolean allowUnbound) {
    if (token == null || token.length() < 32) throw new AuthFailure(503, "PORTAL_DIRECTORY_UNAVAILABLE");
    try {
      if ("API_USER".equals(role)) {
        var account = (allowUnbound ? payment : access).get().uri(allowUnbound
            ? "/internal/v1/payments/accounts/{id}/identity" : "/internal/v1/access/owners/{id}/account-identity", userId)
            .header("X-Service-Token", token).retrieve().body(Account.class);
        return account != null && userId.equals(account.ownerId()) && ("API".equals(account.accountType()) || allowUnbound && account.accountType() == null);
      }
      if ("PROMOTER".equals(role)) {
        var profile = promotion.get().uri("/api/v1/internal/promotions/promoters/{id}/portal-eligibility", userId)
            .header("X-Service-Token", token).retrieve().body(Promoter.class);
        return profile != null && userId.equals(profile.userId()) && profile.eligible();
      }
      return false;
    } catch (HttpClientErrorException.NotFound failure) { return false; }
      catch (RestClientException failure) { throw new AuthFailure(503, "PORTAL_DIRECTORY_UNAVAILABLE"); }
  }
  public record Account(String ownerId, String accountType) {}
  public record Promoter(String userId, boolean eligible) {}
}
