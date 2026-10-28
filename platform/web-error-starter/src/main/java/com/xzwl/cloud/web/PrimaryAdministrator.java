package com.xzwl.cloud.web;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** PRIMARY_ADMIN is supplied only by a verified IAM session, never an HTTP header. */
public final class PrimaryAdministrator {
  private PrimaryAdministrator() {}
  public static void require(HttpServletRequest request) {
    if(request.getUserPrincipal()==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if(!request.isUserInRole("ADMIN") || !request.isUserInRole("PRIMARY_ADMIN"))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
}
