package com.xzwl.cloud.billing.application.ledger;

import java.math.BigDecimal;
import java.util.List;

/** Media supplies task dimensions; Billing owns the wallet, price, rate and durable receipt. */
public interface MediaBilling {
  Receipt charge(Charge command);
  Quote quote(QuoteRequest request);
  List<AvailablePrice> availablePrices(String ownerId);
  record QuoteRequest(String ownerId,String type,String model,String resolution,int count,int durationSeconds) {
    public QuoteRequest { new Charge("quote",ownerId,type,model,resolution,count,durationSeconds); }
  }
  record Quote(BigDecimal amount,BigDecimal rate,BigDecimal points) {}
  record AvailablePrice(String model,String resolution,BigDecimal amountPerUnit,String type) {
    public AvailablePrice(String model,String resolution,BigDecimal amountPerUnit){this(model,resolution,amountPerUnit,null);}
  }
  void refund(String operationId, Refund command);
  /** Expected cost comes only from Media's persisted, server-issued enhancement quote. */
  record ExpectedCost(BigDecimal amount,BigDecimal rate,BigDecimal points) {
    public ExpectedCost {
      amount=canonical(amount);rate=canonical(rate);points=canonical(points);
      if(rate.signum()<=0)throw new IllegalArgumentException("invalid expected rate");
    }
    private static BigDecimal canonical(BigDecimal value) {
      if(value==null||value.signum()<0||value.scale()>4||value.compareTo(BigDecimal.TEN.pow(16))>=0)
        throw new IllegalArgumentException("invalid expected cost");
      return value.setScale(4);
    }
  }
  record Charge(String operationId,String ownerId,String type,String model,String resolution,int count,int durationSeconds,ExpectedCost expectedCost) {
    public Charge(String operationId,String ownerId,String type,String model,String resolution,int count,int durationSeconds) {
      this(operationId,ownerId,type,model,resolution,count,durationSeconds,null);
    }
    public Charge {
      for(String value:new String[]{operationId,ownerId,model,resolution})
        if(value==null||!value.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid media dimension");
      if(!java.util.Set.of("IMAGE","VIDEO","ENHANCEMENT").contains(type==null?"":type))throw new IllegalArgumentException("invalid media type");
      if(expectedCost!=null&&!"ENHANCEMENT".equals(type))throw new IllegalArgumentException("unexpected enhancement cost");
      if(count<1||count>4||("IMAGE".equals(type)&&durationSeconds!=0)
          ||("VIDEO".equals(type)&&(count!=1||durationSeconds<1||durationSeconds>600))
          ||("ENHANCEMENT".equals(type)&&(count!=1||durationSeconds<1||durationSeconds>3600||!"video-enhancement".equals(model)||!java.util.Set.of("720p","1080p","2K","4K").contains(resolution))))throw new IllegalArgumentException("invalid media quantity");
    }
  }
  record Refund(String ownerId,String refundId,BigDecimal points) {}
  record Receipt(String chargeId,BigDecimal amount,BigDecimal rate,BigDecimal points) {}
  interface Keys { Key forOwner(String ownerId); default Key forAdministrationOwner(String ownerId) { return forOwner(ownerId); } }
  record Key(String keyId,String ownerId,String accountType,String kind,boolean enabled) {}
}
