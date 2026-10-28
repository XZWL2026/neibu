package com.xzwl.cloud.payment.infrastructure.shouqianba;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** AES-GCM binds each ciphertext to its row/field, preventing cross-order substitution. */
public final class PaymentDataCipher {
  private final SecretKeySpec key;private final SecureRandom random=new SecureRandom();
  public PaymentDataCipher(String encoded){
    try{byte[] bytes=Base64.getDecoder().decode(encoded);if(bytes.length!=32)throw new IllegalArgumentException();key=new SecretKeySpec(bytes,"AES");}
    catch(RuntimeException failure){throw new IllegalArgumentException("SQB_DATA_ENCRYPTION_KEY requires 32 base64-encoded bytes");}
  }
  public String encrypt(String owner,String value){
    if(value==null)return null;
    try{
      byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
      byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      return "sqb1:"+Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array());
    }catch(Exception failure){throw new IllegalStateException("payment encryption failed");}
  }
  public String decrypt(String owner,String value){
    if(value==null)return null;
    try{
      if(!value.startsWith("sqb1:"))throw new IllegalArgumentException();
      var buffer=ByteBuffer.wrap(Base64.getDecoder().decode(value.substring(5)));byte[] nonce=new byte[12];buffer.get(nonce);
      byte[] encrypted=new byte[buffer.remaining()];buffer.get(encrypted);var cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(owner.getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8);
    }catch(Exception failure){throw new IllegalStateException("payment encrypted state cannot be read");}
  }
}
