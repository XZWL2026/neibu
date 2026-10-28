package com.xzwl.cloud.iam.interfaces.http;

import com.xzwl.cloud.iam.application.otp.OtpService;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public final class OtpController {
  private final OtpService otp;
  private final SessionLogin sessions;
  public OtpController(OtpService otp, SessionLogin sessions) { this.otp = otp; this.sessions = sessions; }
  @PostMapping("/api/v1/auth/otp/send")
  public Map<String, Object> send(@RequestBody Phone body) {
    otp.send(body.phone()); return Map.of("success", true, "expiresInSeconds", 300, "resendAfterSeconds", 60);
  }
  @PostMapping("/api/v1/auth/otp/login")
  public Map<String, Boolean> login(@RequestBody Verification body, HttpServletRequest request, HttpServletResponse response) {
    sessions.establish(otp.login(body.phone(), body.code()), request, response); return Map.of("success", true);
  }
  public record Phone(String phone) {}
  public record Verification(String phone, String code) { @Override public String toString() { return "Verification[redacted]"; } }
}
