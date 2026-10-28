package com.xzwl.cloud.media.infrastructure.provider;

import com.xzwl.cloud.media.application.provider.ArkModelCatalog.Capability;
import java.util.*;

/** Exact-version profiles reviewed 2026-09-09 against official Ark docs 1520757 / 1541523.
 * Profiles expose the intersection with this system's six ratios and four-image task limit.
 * Unknown versions deliberately have no profile. See docs/ark-model-capabilities.md. */
final class ArkModelCapabilities {
  private static final List<String> RATIOS=List.of("1:1","16:9","9:16","4:3","3:4","21:9");
  static Capability forModel(String id,String type) {
    if("VIDEO".equals(type)) return switch(id) {
      case "doubao-seedance-2-5-260628" -> video(List.of("480p","720p","1080p"),4,30);
      case "doubao-seedance-2-0-260128" -> video(List.of("480p","720p","1080p","4k"),4,15);
      case "doubao-seedance-2-0-fast-260128", "doubao-seedance-2-0-mini-260615" -> video(List.of("480p","720p"),4,15);
      case "doubao-seedance-1-5-pro-251215" -> video(List.of("480p","720p","1080p"),4,12);
      case "doubao-seedance-1-0-pro-250528", "doubao-seedance-1-0-pro-fast-251015" -> video(List.of("480p","720p","1080p"),2,12);
      default -> null;
    };
    if(!"IMAGE".equals(type)) return null;
    var twoK=sizes("2048x2048","2848x1600","1600x2848","2304x1728","1728x2304","3136x1344");
    var fourK=sizes("4096x4096","5504x3040","3040x5504","4704x3520","3520x4704","6240x2656");
    return switch(id) {
      case "doubao-seedream-4-0-250828" -> image(4,List.of("1K","2K","4K"),Map.of(
          "1K",sizes("1024x1024","1280x720","720x1280","1152x864","864x1152","1512x648"),"2K",twoK,"4K",fourK));
      case "doubao-seedream-4-5-251128" -> image(4,List.of("2K","4K"),Map.of("2K",twoK,"4K",fourK));
      case "doubao-seedream-5-0-260128" -> image(4,List.of("2K","3K","4K"),Map.of("2K",twoK,"4K",fourK,
          "3K",sizes("3072x3072","4096x2304","2304x4096","3456x2592","2592x3456","4704x2016")));
      // 5.0 pro's image generation endpoint does not support sequential-image generation.
      case "doubao-seedream-5-0-pro-260628" -> image(1,List.of("1K","1.5K","2K"),Map.of(
          "1K",sizes("1024x1024","1424x800","800x1424","1152x864","864x1152","1568x672"),
          "1.5K",sizes("1536x1536","2048x1152","1152x2048","1792x1344","1344x1792","2352x1008"),
          "2K",sizes("2048x2048","2816x1584","1584x2816","2368x1776","1776x2368","3136x1344")));
      default -> null;
    };
  }
  private static Capability video(List<String> resolutions,int min,int max) {
    return new Capability(resolutions,RATIOS,1,min,max,null);
  }
  private static Capability image(int count,List<String> resolutions,Map<String,Map<String,String>> sizes) {
    return new Capability(resolutions,RATIOS,count,0,0,sizes);
  }
  private static Map<String,String> sizes(String square,String landscape,String portrait,String fourThree,String threeFour,String ultraWide) {
    return Map.of("1:1",square,"16:9",landscape,"9:16",portrait,"4:3",fourThree,"3:4",threeFour,"21:9",ultraWide);
  }
}
