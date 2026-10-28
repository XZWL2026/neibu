package com.xzwl.cloud.media.domain.task;

import com.xzwl.cloud.media.domain.generation.MediaType;
import java.util.List;
import java.util.Objects;

public record TaskRequest(MediaType type, String model, String prompt, String aspectRatio, String resolution,
                          int count, int durationSeconds, List<MediaReference> references, ReferenceMode referenceMode, TaskOperation operation) {
  public TaskRequest {
    Objects.requireNonNull(type, "type");
    operation = operation == null ? TaskOperation.GENERATION : operation;
    boolean depth = operation == TaskOperation.DEPTH;
    boolean enhancement=operation==TaskOperation.ENHANCEMENT;
    referenceMode = referenceMode == null ? ReferenceMode.FIRST_LAST_FRAME : referenceMode;
    model = model == null ? (type == MediaType.IMAGE ? "seedream-5.0-lite" : "seedance-2.5") : model;
    aspectRatio = aspectRatio == null ? (type == MediaType.IMAGE ? "1:1" : "16:9") : aspectRatio;
    resolution = resolution == null ? (type == MediaType.IMAGE ? "2K" : "720p") : resolution;
    count = count == 0 ? 1 : count;
    if (type == MediaType.VIDEO && durationSeconds == 0) durationSeconds = 5;
    if (!(depth && "source".equals(aspectRatio)) && !List.of("1:1", "16:9", "9:16", "4:3", "3:4", "21:9").contains(aspectRatio))
      throw new IllegalArgumentException("unsupported aspect ratio");
    MediaReference.identifier(model, "model");
    MediaReference.text(prompt, "prompt", 16000);
    MediaReference.identifier(resolution, "resolution");
    references = references == null ? List.of() : List.copyOf(references);
    if (references.size() > (referenceMode == ReferenceMode.OMNI ? 50 : 16) || count < 1 || count > 4
        || (type == MediaType.IMAGE && durationSeconds != 0)
        || (type == MediaType.VIDEO && (count != 1 || durationSeconds < 1 || (!depth && !enhancement && durationSeconds > 600) || (enhancement && durationSeconds>3600)))) {
      throw new IllegalArgumentException("invalid reference count, output count or duration");
    }
    if (references.stream().anyMatch(r -> r.portraitId()!=null) && (type!=MediaType.VIDEO || referenceMode!=ReferenceMode.OMNI))
      throw new IllegalArgumentException("portrait requires omni video generation");
    if (referenceMode == ReferenceMode.OMNI && type != MediaType.VIDEO)
      throw new IllegalArgumentException("omni reference requires video generation");
    if (!depth && referenceMode != ReferenceMode.OMNI && references.stream().anyMatch(r -> r.kind()!=MediaReference.Kind.IMAGE))
      throw new IllegalArgumentException("audio/video references require omni mode");
  }

  public TaskRequest(MediaType type,String model,String prompt,String aspectRatio,String resolution,int count,int durationSeconds,List<MediaReference> references,ReferenceMode mode) {
    this(type,model,prompt,aspectRatio,resolution,count,durationSeconds,references,mode,TaskOperation.GENERATION);
  }

  public TaskRequest(MediaType type,String model,String prompt,String aspectRatio,String resolution,int count,int durationSeconds,List<MediaReference> references) {
    this(type,model,prompt,aspectRatio,resolution,count,durationSeconds,references,null);
  }

  public TaskRequest(MediaType type, String model, String prompt, String resolution,
      int count, int durationSeconds, List<MediaReference> references) {
    this(type, model, prompt, null, resolution, count, durationSeconds, references);
  }

  public TaskRequest withReferences(List<MediaReference> value) {
    return new TaskRequest(type, model, prompt, aspectRatio, resolution, count, durationSeconds, value,referenceMode,operation);
  }
}
