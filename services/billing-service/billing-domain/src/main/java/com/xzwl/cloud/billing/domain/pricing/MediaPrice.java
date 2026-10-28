package com.xzwl.cloud.billing.domain.pricing;
import java.math.BigDecimal;
import java.util.Set;
/** Exactly one currency is specified: yuan for new prices, points for retained legacy prices. */
public record MediaPrice(String type,String accountType,String model,String resolution,String unit,String pointsPerUnit,String yuanPerUnit,boolean enabled) {
  public MediaPrice {
    if(!Set.of("IMAGE","VIDEO","ENHANCEMENT").contains(type==null ? "" : type)
        || !Set.of("PERSONAL","ENTERPRISE","API").contains(accountType==null ? "" : accountType)
        || !("IMAGE".equals(type) ? "IMAGE" : "SECOND").equals(unit)) throw new IllegalArgumentException("invalid pricing dimension");
    if("ENHANCEMENT".equals(type) && (!"video-enhancement".equals(model) || !Set.of("720p","1080p","2K","4K").contains(resolution))) throw new IllegalArgumentException("invalid enhancement price");
    for(String value:new String[]{model,resolution})
      if(value==null || !value.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("invalid pricing identifier");
    if ((pointsPerUnit == null) == (yuanPerUnit == null)) throw new IllegalArgumentException("specify exactly one price currency");
    if (pointsPerUnit != null) pointsPerUnit = normalize(pointsPerUnit);
    if (yuanPerUnit != null) yuanPerUnit = normalize(yuanPerUnit);
    if ("ENHANCEMENT".equals(type) && new BigDecimal(yuanPerUnit != null ? yuanPerUnit : pointsPerUnit).signum() <= 0)
      throw new IllegalArgumentException("enhancement price must be positive");
  }
  public MediaPrice(String type,String accountType,String model,String resolution,String unit,String pointsPerUnit,boolean enabled) {
    this(type,accountType,model,resolution,unit,pointsPerUnit,null,enabled);
  }
  private static String normalize(String value) {
    if (!value.matches("[0-9]{1,12}(\\.[0-9]{1,4})?")) throw new IllegalArgumentException("invalid price");
    return new BigDecimal(value).setScale(4).toPlainString();
  }
  public String dimension() { return String.join("/",type,accountType,model,resolution); }
}
