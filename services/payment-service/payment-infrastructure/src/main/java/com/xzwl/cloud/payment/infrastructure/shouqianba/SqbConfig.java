package com.xzwl.cloud.payment.infrastructure.shouqianba;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.*;

/** Constructed from process environment by the production composition root. Never render credentials. */
public final class SqbConfig {
  final URI endpoint;final String merchant,app,device,vendor,vendorKey,code,notifyUrl,payway,seedSn,seedKey;
  final LocalDate seedDay;final PublicKey publicKey;final String encryptionKey,scope;
  private SqbConfig(Map<String,String> env,boolean fixture){
    endpoint=URI.create(required(env,"SQB_API_DOMAIN"));
    if(endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null
        ||!(endpoint.getPath().isEmpty()||endpoint.getPath().equals("/"))
        ||!("https".equals(endpoint.getScheme())||(fixture&&"http".equals(endpoint.getScheme())&&"127.0.0.1".equals(endpoint.getHost()))))
      throw new IllegalArgumentException("SQB_API_DOMAIN requires an HTTPS origin");
    merchant=required(env,"SQB_MERCHANT_SN");app=required(env,"SQB_APP_ID");device=required(env,"SQB_DEVICE_ID");
    vendor=env.getOrDefault("SQB_VENDOR_SN","");vendorKey=env.getOrDefault("SQB_VENDOR_KEY","");code=env.getOrDefault("SQB_CODE","");
    notifyUrl=required(env,"SQB_NOTIFY_URL");URI notify=URI.create(notifyUrl);
    if(!"https".equals(notify.getScheme())||notify.getHost()==null||notify.getUserInfo()!=null||notify.getFragment()!=null
        ||notifyUrl.getBytes(StandardCharsets.UTF_8).length>128||!"/callbacks/payment".equals(notify.getPath()))
      throw new IllegalArgumentException("SQB_NOTIFY_URL requires the new HTTPS callback endpoint");
    payway=required(env,"SQB_PAYWAY");if(!Set.of("2","3").contains(payway))throw new IllegalArgumentException("SQB_PAYWAY must be 2 or 3");
    seedSn=env.getOrDefault("SQB_TERMINAL_SN","");seedKey=env.getOrDefault("SQB_TERMINAL_KEY","");
    if(seedSn.isEmpty()!=seedKey.isEmpty())throw new IllegalArgumentException("SQB terminal seed requires both values");
    seedDay=seedSn.isEmpty()?null:LocalDate.parse(required(env,"SQB_TERMINAL_KEY_DATE"));
    encryptionKey=required(env,"SQB_DATA_ENCRYPTION_KEY");new PaymentDataCipher(encryptionKey);
    try{
      String pem=required(env,"SQB_PUBLIC_KEY").replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
      publicKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
      if(((java.security.interfaces.RSAPublicKey)publicKey).getModulus().bitLength()<2048)throw new IllegalArgumentException();
      scope=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((endpoint+"\n"+merchant+"\n"+app+"\n"+device).getBytes(StandardCharsets.UTF_8)));
    }catch(Exception failure){throw new IllegalArgumentException("invalid SQB public key or identity configuration");}
  }
  public static SqbConfig fromEnvironment(Map<String,String> env,boolean fixture){return new SqbConfig(env,fixture);}
  public PaymentDataCipher cipher(){return new PaymentDataCipher(encryptionKey);}
  private static String required(Map<String,String> env,String key){String value=env.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("missing "+key);return value;}
  @Override public String toString(){return "SqbConfig[redacted]";}
}
