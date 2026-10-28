package com.xzwl.cloud.media.application.provider;

import java.util.*;

public interface MpsConfigurationStore {
  Configuration load();
  Configuration save(Configuration requested);
  record Configuration(long version,String secretId,String secretKey,String region,String bucket,
      String bucketRegion,String outputDir,long template720p,long template1080p,long template4k,
      List<EnhancementTemplate> enhancementTemplates,List<BitrateProfile> bitrateProfiles) {
    public Configuration {
      enhancementTemplates=enhancementTemplates==null?null:List.copyOf(enhancementTemplates);
      bitrateProfiles=bitrateProfiles==null?null:List.copyOf(bitrateProfiles);
    }
    public Configuration(long version,String secretId,String secretKey,String region,String bucket,String bucketRegion,
        String outputDir,long template720p,long template1080p,long template4k) {
      this(version,secretId,secretKey,region,bucket,bucketRegion,outputDir,template720p,template1080p,template4k,null,null);
    }
    @Override public String toString() { return "MpsConfiguration[version="+version+", credentials=REDACTED]"; }
  }
  record EnhancementTemplate(String id,String outputResolution,List<String> acceptedInputResolutions,boolean enabled,
      String bitrateProfileId,Long definitionId,Map<String,Long> scheduleIds) {
    public EnhancementTemplate {
      acceptedInputResolutions=acceptedInputResolutions==null?List.of():List.copyOf(acceptedInputResolutions);
      scheduleIds=scheduleIds==null?Map.of():Map.copyOf(scheduleIds);
    }
  }
  record BitrateProfile(String id,String name,int videoBitrateKbps) {}
  static void validatePolicy(List<EnhancementTemplate> templates,List<BitrateProfile> profiles) {
    if(templates==null && profiles==null) return;
    if(templates==null || profiles==null || templates.size()>100 || profiles.size()>100) throw new IllegalArgumentException("Invalid MPS policy");
    Set<String> profileIds=new HashSet<>(),ids=new HashSet<>(),enabledOutputs=new HashSet<>();
    for(var p:profiles) {
      if(!identifier(p.id()) || !profileIds.add(p.id()) || p.name()==null || p.name().isBlank() || p.name().length()>128
          || p.videoBitrateKbps()<128 || p.videoBitrateKbps()>100000) throw new IllegalArgumentException("Invalid bitrate profile");
    }
    for(var t:templates) {
      String source=switch(t.outputResolution()==null?"":t.outputResolution()) {case "720p"->"480p";case "1080p"->"720p";case "2K","4K"->"1080p";default->null;};
      if(!identifier(t.id()) || !ids.add(t.id()) || source==null || !t.acceptedInputResolutions().contains(source)
          || !Set.of("480p","720p","1080p","2K","4K").containsAll(t.acceptedInputResolutions())
          || t.acceptedInputResolutions().stream().anyMatch(input->resolutionRank(input)>=resolutionRank(t.outputResolution()))
          || new HashSet<>(t.acceptedInputResolutions()).size()!=t.acceptedInputResolutions().size()
          || t.enabled() && !enabledOutputs.add(t.outputResolution())
          || t.bitrateProfileId()!=null && !profileIds.contains(t.bitrateProfileId())) throw new IllegalArgumentException("Invalid enhancement template");
      if(t.definitionId()!=null) {
        if(!validDefinition(t.definitionId()) || !t.scheduleIds().isEmpty()) throw new IllegalArgumentException("Invalid enhancement definition");
      } else if(!t.scheduleIds().keySet().equals(Set.of("WIDE","SQUARE","STANDARD","ULTRAWIDE"))
          || t.scheduleIds().values().stream().anyMatch(id->!validDefinition(id))) throw new IllegalArgumentException("Invalid enhancement schedules");
    }
  }
  static int resolutionRank(String resolution) { return switch(resolution==null?"":resolution) {case "480p"->0;case "720p"->1;case "1080p"->2;case "2K"->3;case "4K"->4;default->-1;}; }
  private static boolean identifier(String value) { return value!=null && value.matches("[A-Za-z0-9_-]{1,64}"); }
  private static boolean validDefinition(Long id) { return id!=null && id>0 && id<=9007199254740991L; }
  final class VersionConflict extends RuntimeException {}
}
