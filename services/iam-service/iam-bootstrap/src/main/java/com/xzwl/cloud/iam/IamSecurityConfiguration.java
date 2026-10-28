package com.xzwl.cloud.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.iam.application.account.AdminLoginHandler;
import com.xzwl.cloud.iam.application.account.AdminAccounts;
import com.xzwl.cloud.iam.application.account.UserDirectory;
import com.xzwl.cloud.iam.interfaces.http.SessionStatusFilter;
import java.time.Clock;
import com.xzwl.cloud.iam.interfaces.http.SessionLogin;
import com.xzwl.cloud.web.ErrorEnvelope;
import com.xzwl.cloud.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
public class IamSecurityConfiguration {
  @Profile("local & !prod & !production & !prod-fixed")
  @Bean AdminLoginHandler adminLoginHandler() {
    return new AdminLoginHandler(System.getenv("IAM_ADMIN_LOGIN_TOKEN"));
  }
  @Bean HttpSessionSecurityContextRepository securityContexts() { return new HttpSessionSecurityContextRepository(); }
  @Bean HttpSessionCsrfTokenRepository csrfTokens() { return new HttpSessionCsrfTokenRepository(); }
  @Bean SessionLogin sessionLogin(HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository csrf, Clock clock) {
    return new SessionLogin(contexts, new CompositeSessionAuthenticationStrategy(List.of(
        new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf))), clock);
  }
  @Bean DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
    var cookie = new DefaultCookieSerializer();
    cookie.setCookieName("XZWL_SESSION"); cookie.setCookiePath("/"); cookie.setUseHttpOnlyCookie(true);
    cookie.setUseSecureCookie(secure); cookie.setSameSite("Lax"); cookie.setCookieMaxAge(180 * 24 * 60 * 60);
    return cookie;
  }
  // Controllers use the explicit credential services; never generate a Boot default password user.
  @Bean AuthenticationManager authenticationManager() {
    return authentication -> {
      throw new BadCredentialsException("Use the explicit IAM login endpoints");
    };
  }
  @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, HttpSessionSecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository csrf, ObjectMapper json, AdminAccounts admins, UserDirectory users, Clock clock) throws Exception {
    return http
        .securityContext(config -> config.securityContextRepository(contexts))
        .addFilterAfter(new SessionStatusFilter(admins, users, clock, json), SecurityContextHolderFilter.class)
        .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .requestCache(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(config -> config
            .requestMatchers(org.springframework.http.HttpMethod.GET, "/actuator/health").permitAll()
            .requestMatchers("/api/v1/auth/session", "/api/v1/auth/admin/login", "/api/v1/auth/admin/local-login", "/api/v1/auth/otp/send", "/api/v1/auth/otp/login", "/api/v1/auth/phone/login").permitAll()
            .requestMatchers("/api/v1/auth/portal/login").permitAll()
            .requestMatchers("/api/v1/auth/portal/options", "/api/v1/auth/portal/tickets").hasRole("USER")
            .requestMatchers("/internal/v1/iam/session", "/internal/v1/iam/session/verify").authenticated()
            .requestMatchers("/api/v1/admin/iam/sms-provider-status").hasRole("ADMIN")
            .requestMatchers("/api/v1/auth/admin/password").hasAnyRole("ADMIN", "NORMAL_ADMIN", "API_USER", "PROMOTER")
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/admin/api-users/{id}/agent-ticket").hasRole("ADMIN")
            .requestMatchers("/api/v1/admin/admins/**").hasAnyRole("ADMIN", "NORMAL_ADMIN")
            .anyRequest().denyAll())
        .exceptionHandling(config -> config
            .authenticationEntryPoint((request, response, error) -> writeError(json, request, response, HttpStatus.UNAUTHORIZED))
            .accessDeniedHandler((request, response, error) -> writeError(json, request, response, HttpStatus.FORBIDDEN)))
        .logout(config -> config.logoutUrl("/api/v1/auth/logout").logoutSuccessHandler((request, response, auth) -> {
          response.setContentType("application/json");
          json.writeValue(response.getWriter(), Map.of("success", true));
        })).build();
  }
  private static void writeError(ObjectMapper json, HttpServletRequest request, HttpServletResponse response,
      HttpStatus status) throws IOException {
    response.setStatus(status.value());
    response.setContentType("application/json");
    json.writeValue(response.getWriter(), new ErrorEnvelope(status.name(), status.getReasonPhrase(),
        (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), Map.of()));
  }
}
