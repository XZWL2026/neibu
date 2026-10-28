package com.xzwl.cloud.payment;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.payment.infrastructure.shouqianba.PaymentDataCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;
class ShouqianbaCryptoTest {
  @Test void randomizedAuthenticatedCiphertextCannotMoveAcrossOrdersOrKeys(){
    var cipher=new PaymentDataCipher(Base64.getEncoder().encodeToString(new byte[32]));
    String value="fixture-private-value",encrypted=cipher.encrypt("one",value);
    assertNotEquals(encrypted,cipher.encrypt("one",value));assertFalse(encrypted.contains(value));
    assertEquals(value,cipher.decrypt("one",encrypted));
    assertThrows(IllegalStateException.class,()->cipher.decrypt("two",encrypted));
    byte[] other=new byte[32];other[0]=1;
    assertThrows(IllegalStateException.class,()->new PaymentDataCipher(Base64.getEncoder().encodeToString(other)).decrypt("one",encrypted));
    assertThrows(IllegalStateException.class,()->cipher.decrypt("one","plaintext"));
    assertThrows(IllegalArgumentException.class,()->new PaymentDataCipher("invalid"));
    assertThrows(IllegalArgumentException.class,()->new PaymentDataCipher(Base64.getEncoder().encodeToString(new byte[16])));
  }
}
