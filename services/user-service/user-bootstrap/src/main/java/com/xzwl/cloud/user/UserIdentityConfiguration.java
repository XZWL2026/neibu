package com.xzwl.cloud.user;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

import com.xzwl.cloud.user.application.identity.*;
import com.xzwl.cloud.user.infrastructure.identity.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
public class UserIdentityConfiguration {
  @Bean AdminUserDirectory adminUserDirectory(org.springframework.jdbc.core.JdbcTemplate jdbc,
      @Qualifier("internalRestClientBuilder") RestClient.Builder builder,
      @Value("${user.access-service-url:${ACCESS_SERVICE_URL:http://127.0.0.1:8212}}") String access,
      @Value("${user.payment-service-url:${PAYMENT_SERVICE_URL:http://127.0.0.1:8215}}") String payment,
      @Value("${user.billing-service-url:${BILLING_SERVICE_URL:http://127.0.0.1:8214}}") String billing,
      @Value("${user.promotion-service-url:${PROMOTION_SERVICE_URL:http://127.0.0.1:8213}}") String promotion,
      @Value("${abp.internal-token:${ABP_INTERNAL_TOKEN:}}") String token) {
    var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(java.time.Duration.ofSeconds(2));factory.setReadTimeout(java.time.Duration.ofSeconds(5));
    java.util.function.Function<String,org.springframework.web.client.RestClient> client=url->builder.clone()
        .baseUrl(url).requestFactory(factory).defaultHeader("X-Service-Token",token).build();
    return new HttpAdminUserDirectory(jdbc,client.apply(access),client.apply(payment),client.apply(billing),client.apply(promotion));
  }
  @Bean UserIdentityStore users(UserIdentityMapper users, PlatformTransactionManager transactions) { return new MyBatisUserIdentityStore(users, new TransactionTemplate(transactions)); }
  @Bean SessionVerifier sessionVerifier(@Qualifier("internalRestClientBuilder") RestClient.Builder builder, @Value("${user.iam-service-url}") String url) { return new HttpIamSessionVerifier(builder,url); }
  @Bean AvatarVerifier avatarVerifier(@Value("${user.storage-service-url}") String url) { return new HttpAvatarVerifier(url); }
  @Bean AuthenticationManager authenticationManager() { return a -> { throw new BadCredentialsException("Use IAM authentication"); }; }
  @Bean SecurityFilterChain security(HttpSecurity http, SessionVerifier verifier) throws Exception {
    String token = System.getenv("IAM_USER_INTERNAL_TOKEN");
    if (token == null || token.length() < 32) throw new IllegalStateException("IAM_USER_INTERNAL_TOKEN must contain at least 32 characters");
    byte[] expected = token.getBytes(StandardCharsets.UTF_8);
    var filter = new OncePerRequestFilter() {
      @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String path = request.getServletPath();
        try {
          if (path.startsWith("/internal/v1/users")) {
            String provided = request.getHeader("X-IAM-INTERNAL-TOKEN");
            if (provided == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) throw new SessionVerifier.Rejected(401);
            authenticate("iam", List.of(new SimpleGrantedAuthority("ROLE_IAM_SERVICE")));
          } else if (path.equals("/api/v1/users/me") || (path.equals("/api/v1/admin/users") || path.startsWith("/api/v1/admin/users/") || path.equals("/api/v1/admin/api-users/directory"))) {
            boolean mutation = !List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
            var identity = verifier.verify(request.getHeader("Cookie"), request.getHeader("X-CSRF-TOKEN"), mutation);
            authenticate(identity.id(), identity.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase(Locale.ROOT))).toList());
          }
        } catch (SessionVerifier.Rejected failure) { error(response, failure.status()); return; }
        chain.doFilter(request, response);
      }
    };
    // IAM performs the session-bound CSRF check for every public mutation, including this service's status command.
    return http.sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
        .addFilterAfter(filter, SecurityContextHolderFilter.class)
        .authorizeHttpRequests(c -> c
            .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
            .requestMatchers("/internal/v1/users/**").hasRole("IAM_SERVICE")
            .requestMatchers(HttpMethod.PATCH, "/api/v1/users/me").hasAnyRole("USER", "API_USER", "PROMOTER")
            .requestMatchers(HttpMethod.GET, "/api/v1/users/me").hasAnyRole("USER", "API_USER", "PROMOTER")
            .requestMatchers(HttpMethod.GET, "/api/v1/admin/users", "/api/v1/admin/users/*", "/api/v1/admin/users/*/directory-detail", "/api/v1/admin/api-users/directory").hasAnyRole("ADMIN", "NORMAL_ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/v1/admin/users").hasAnyRole("ADMIN", "NORMAL_ADMIN")
            .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/users/*/status").hasAnyRole("ADMIN", "NORMAL_ADMIN")
            .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/users/*/nickname").hasAnyRole("ADMIN", "NORMAL_ADMIN")
            .anyRequest().denyAll())
        .exceptionHandling(c -> c.authenticationEntryPoint((req,res,e) -> error(res,401)).accessDeniedHandler((req,res,e) -> error(res,403)))
        .build();
  }
  private static void authenticate(String id, List<SimpleGrantedAuthority> roles) {
    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(id, null, roles));
  }
  private static void error(HttpServletResponse response, int status) throws IOException {
    response.setStatus(status); response.setContentType("application/json");
    response.getWriter().write("{\"code\":\"" + (status == 401 ? "UNAUTHORIZED" : status == 403 ? "FORBIDDEN" : "IAM_UNAVAILABLE") + "\"}");
  }
}
