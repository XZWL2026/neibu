package com.xzwl.cloud.media.application.task;

import com.xzwl.cloud.media.domain.task.*;
import java.util.List;

/** Owner-scoped review receipts. Public callers never receive provider asset identifiers. */
public interface PortraitReviews {
  record Portrait(String id,String fileId,String model,String status,String errorMessage,MediaReference.Kind kind,Double durationSeconds) {
    public Portrait(String id,String fileId,String model,String status,String errorMessage) {
      this(id,fileId,model,status,errorMessage,MediaReference.Kind.IMAGE,null);
    }
  }
  default Portrait submit(String owner,String key,String fileId,String model) {
    return submit(owner,key,fileId,model,MediaReference.Kind.IMAGE);
  }
  Portrait submit(String owner,String key,String fileId,String model,MediaReference.Kind kind);
  Portrait get(String owner,String id);
  List<Portrait> list(String owner,int limit,String beforeId);
  void validate(String owner,TaskRequest request);
  void freeze(String taskId,String owner,TaskRequest request,RouteSnapshot route);
  static void rejectUnsupported(TaskRequest request) {
    if(request.references().stream().anyMatch(r -> r.portraitId()!=null))
      throw new DefinitiveRejectionException("PORTRAIT_REVIEW_UNAVAILABLE");
  }
}
