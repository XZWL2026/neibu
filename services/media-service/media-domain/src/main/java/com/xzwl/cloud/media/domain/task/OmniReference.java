package com.xzwl.cloud.media.domain.task;

import java.util.List;

/** Exact Seedance versions supported by the reviewed multimodal content protocol. */
public record OmniReference(int maxImages,int maxVideos,int maxAudios,int maxTotal,
    int maxVideoDurationSeconds,int maxAudioDurationSeconds,long maxVideoBytes,long maxAudioBytes,boolean audioOnly) {
  public static OmniReference forModel(String model) {
    return switch(model) {
      case "doubao-seedance-2-0-260128","doubao-seedance-2-0-fast-260128","doubao-seedance-2-0-mini-260615" ->
          new OmniReference(9,3,3,12,15,15,200L*1024*1024,15L*1024*1024,false);
      case "doubao-seedance-2-5-260628" -> new OmniReference(30,10,10,50,30,30,200L*1024*1024,15L*1024*1024,true);
      default -> null;
    };
  }
  public boolean accepts(List<MediaReference> references,boolean resolved) {
    long images=references.stream().filter(r -> r.kind()==MediaReference.Kind.IMAGE).count();
    long videos=references.stream().filter(r -> r.kind()==MediaReference.Kind.VIDEO).count();
    long audios=references.stream().filter(r -> r.kind()==MediaReference.Kind.AUDIO).count();
    if(references.isEmpty() || references.size()>maxTotal || images>maxImages || videos>maxVideos || audios>maxAudios
        || !audioOnly && images+videos==0) return false;
    if(!resolved) return true;
    for(var kind:List.of(MediaReference.Kind.VIDEO,MediaReference.Kind.AUDIO)) {
      double total=0; int max=kind==MediaReference.Kind.VIDEO?maxVideoDurationSeconds:maxAudioDurationSeconds;
      for(var ref:references) if(ref.kind()==kind) {
        if(ref.durationSeconds()==null || ref.durationSeconds()<2 || ref.durationSeconds()>max) return false;
        total+=ref.durationSeconds();
      }
      if(total>max) return false;
    }
    return true;
  }
}
