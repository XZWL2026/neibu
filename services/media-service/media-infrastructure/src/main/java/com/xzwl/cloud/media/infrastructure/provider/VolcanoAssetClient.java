package com.xzwl.cloud.media.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.xzwl.cloud.media.application.task.DefinitiveRejectionException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Volcano 2024-01-01 asset protocol, independent from Ark generation bearer credentials. */
final class VolcanoAssetClient {
  private final ProviderHttp http;
  private final Clock clock;
  VolcanoAssetClient(ProviderHttp http,Clock clock) { this.http=http; this.clock=clock; }
  JsonNode call(String endpoint,String accessKey,String secret,String action,Map<String,String> payload) {
    String body=http.encode(payload);
    String datetime=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(clock.instant());
    String date=datetime.substring(0,8), scope=date+"/cn-beijing/ark/request";
    String hash=hash(body), query="Action="+action+"&Version=2024-01-01";
    String host=java.net.URI.create(endpoint).getRawAuthority();
    String signed="content-type;host;x-content-sha256;x-date";
    String canonical="POST\n/\n"+query+"\ncontent-type:application/json\nhost:"+host+"\nx-content-sha256:"+hash
        +"\nx-date:"+datetime+"\n\n"+signed+"\n"+hash;
    byte[] signing=hmac(hmac(hmac(hmac(secret.getBytes(StandardCharsets.UTF_8),date),"cn-beijing"),"ark"),"request");
    String authorization="HMAC-SHA256 Credential="+accessKey+"/"+scope+", SignedHeaders="+signed+", Signature="
        +HexFormat.of().formatHex(hmac(signing,"HMAC-SHA256\n"+datetime+"\n"+scope+"\n"+hash(canonical)));
    // Java HttpClient owns Host; its authority is identical to the signed authority.
    JsonNode response=http.call("POST",endpoint+"/?"+query,Map.of("Content-Type","application/json",
        "X-Date",datetime,"X-Content-Sha256",hash,"Authorization",authorization),body);
    if(response.path("ResponseMetadata").hasNonNull("Error")) throw new DefinitiveRejectionException("PORTRAIT_REVIEW_FAILED");
    if(!response.path("Result").isObject()) throw ProviderHttp.uncertain();
    return response.path("Result");
  }
  static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch(Exception impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
  }
  static String identity(String... values) {
    StringBuilder joined=new StringBuilder();
    for(String value:values) joined.append(value.length()).append(':').append(value);
    return hash(joined.toString());
  }
  private static byte[] hmac(byte[] key,String value) {
    try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
    catch(Exception impossible) { throw new IllegalStateException("HMAC unavailable"); }
  }
}
