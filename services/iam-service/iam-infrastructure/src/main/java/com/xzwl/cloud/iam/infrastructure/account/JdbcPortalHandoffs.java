package com.xzwl.cloud.iam.infrastructure.account;

import com.xzwl.cloud.iam.application.account.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.function.BiPredicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Short lived, browser-bound grants. Atomic consumption works across IAM instances. */
public final class JdbcPortalHandoffs implements PortalHandoffs {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final BiPredicate<String, AuthPrincipal> sourceCurrent;
  public JdbcPortalHandoffs(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
      BiPredicate<String, AuthPrincipal> sourceCurrent) {
    this.jdbc=jdbc; this.transactions=transactions; this.clock=clock; this.sourceCurrent=sourceCurrent;
  }
  public String issue(AuthPrincipal principal, String sourceSession, String challenge) {
    if (!hex(challenge)) throw new AuthFailure(400, "INVALID_PORTAL_CHALLENGE");
    byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
    String ticket=HexFormat.of().formatHex(bytes);
    transactions.executeWithoutResult(status -> {
      jdbc.update("DELETE FROM portal_handoff WHERE expires_at<=?",clock.millis());
      jdbc.update("INSERT INTO portal_handoff (ticket_hash,challenge,source_session,credential_id,login_name,role,credential_version,user_id,user_version,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
          hash(ticket),challenge,sourceSession,principal.id(),principal.login(),principal.role(),principal.version(),principal.boundUserId(),principal.boundUserVersion(),clock.millis()+60_000);
    });
    return ticket;
  }
  public AuthPrincipal redeem(String ticket, String verifier) {
    if (!hex(ticket) || !hex(verifier)) throw denied();
    return transactions.execute(status -> {
      var rows=jdbc.query("SELECT * FROM portal_handoff WHERE ticket_hash=? FOR UPDATE",(rs,index) -> new Grant(
          rs.getString("challenge"),rs.getString("source_session"),rs.getLong("expires_at"),new AuthPrincipal(
          rs.getString("credential_id"),rs.getString("login_name"),rs.getString("role"),false,rs.getLong("credential_version"),rs.getString("user_id"),rs.getLong("user_version"))),hash(ticket));
      if (rows.isEmpty()) throw denied();
      var grant=rows.getFirst();
      if (!MessageDigest.isEqual(grant.challenge().getBytes(StandardCharsets.US_ASCII),hash(verifier).getBytes(StandardCharsets.US_ASCII))
          || clock.millis()>=grant.expiresAt() || !sourceCurrent.test(grant.sourceSession(),grant.principal())) throw denied();
      jdbc.update("DELETE FROM portal_handoff WHERE ticket_hash=?",hash(ticket));
      return grant.principal();
    });
  }
  private record Grant(String challenge,String sourceSession,long expiresAt,AuthPrincipal principal) {}
  private static boolean hex(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
  private static AuthFailure denied() { return new AuthFailure(403,"PORTAL_HANDOFF_DENIED"); }
  private static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
}
