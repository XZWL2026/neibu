package com.xzwl.cloud.media.domain.task;

import java.math.BigDecimal;
import java.util.List;

/** Persisted payload; transport responses use an explicit public projection instead. */
public record TaskData(TaskRequest request, List<MediaReference> executionReferences, RouteSnapshot route, ChargeReceipt charge,
    String providerTaskId, BigDecimal refundPoints, TaskStatus outcome, String failureCode, Presentation presentation) {
  public record Presentation(String modelName, boolean referenceFollowsFirstFrame) {}
  public TaskData(TaskRequest request, List<MediaReference> executionReferences, RouteSnapshot route, ChargeReceipt charge,
      String providerTaskId, BigDecimal refundPoints, TaskStatus outcome, String failureCode) {
    this(request,executionReferences,route,charge,providerTaskId,refundPoints,outcome,failureCode,null);
  }
  public TaskData withPresentation(Presentation value) {
    return new TaskData(request,executionReferences,route,charge,providerTaskId,refundPoints,outcome,failureCode,value);
  }
  public TaskData {
    executionReferences = executionReferences == null ? null : List.copyOf(executionReferences);
  }
  public TaskData(TaskRequest request, RouteSnapshot route, ChargeReceipt charge,
      String providerTaskId, BigDecimal refundPoints, TaskStatus outcome, String failureCode) {
    this(request,null,route,charge,providerTaskId,refundPoints,outcome,failureCode,null);
  }
  public TaskRequest providerRequest() {
    return executionReferences == null ? request : request.withReferences(executionReferences);
  }
  public TaskData withExecutionReferences(List<MediaReference> value) {
    return new TaskData(request,value,route,charge,providerTaskId,refundPoints,outcome,failureCode,presentation);
  }
  public TaskData withRoute(RouteSnapshot value) {
    return new TaskData(request,executionReferences,value,charge,providerTaskId,refundPoints,outcome,failureCode,presentation);
  }
  public TaskData withCharge(ChargeReceipt value) {
    return new TaskData(request,executionReferences,route,value,providerTaskId,refundPoints,outcome,failureCode,presentation);
  }
  public TaskData withProviderTask(String value) {
    MediaReference.text(value,"providerTaskId",256);
    return new TaskData(request,executionReferences,route,charge,value,refundPoints,outcome,failureCode,presentation);
  }
  public TaskData withOutcome(TaskStatus value, BigDecimal refund, String code) {
    return new TaskData(request,executionReferences,route,charge,providerTaskId,refund,value,code,presentation);
  }
}
