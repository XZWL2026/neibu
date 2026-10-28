package com.xzwl.cloud.access.infrastructure.key;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
/** Envelope secret storage: deployment key is supplied externally and never persisted in the schema. */
public final class KeyCipher {
  private final SecretKeySpec key;private final SecureRandom random=new SecureRandom();
  public KeyCipher(String base64){
    byte[] bytes=Base64.getDecoder().decode(base64);
    if(bytes.length!=32)throw new IllegalArgumentException("ACCESS_KEY_ENCRYPTION_KEY must decode to 32 bytes");
    key=new SecretKeySpec(bytes,"AES");
  }
  public String encrypt(String owner,String secret){
    try{
      byte[] iv=new byte[12];random.nextBytes(iv);
      var cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
    }catch(GeneralSecurityException e){throw new IllegalStateException("cannot encrypt key",e);}
  }
  public String decrypt(String owner,String stored){
    try{
      var parts=stored.split("\\.",-1);if(parts.length!=2)throw new IllegalStateException("invalid encrypted key");
      var cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
      cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
    }catch(GeneralSecurityException e){throw new IllegalStateException("cannot decrypt key",e);}
  }
  public static String digest(String value){
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }
  public String generate(boolean api){
    if(!api){byte[] bytes=new byte[32];random.nextBytes(bytes);return "sk-_"+HexFormat.of().formatHex(bytes);}
    String alphabet="abcdefghijklmnopqrstuvwxyz0123456789";var value=new StringBuilder("sk-");
    for(int i=0;i<32;i++)value.append(alphabet.charAt(random.nextInt(alphabet.length())));
    return value.toString();
  }
}
