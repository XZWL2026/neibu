package com.xzwl.cloud.media.application.task;
import com.xzwl.cloud.media.domain.task.RouteSnapshot;
public interface RoutingPort {
  default java.util.List<Model> models() { throw new DependencyUnavailableException("model catalog unavailable"); }
  record Model(String modelId,String providerModel,java.util.Map<String,Object> configuration,String providerId) {
    public Model(String modelId,String providerModel,java.util.Map<String,Object> configuration) { this(modelId,providerModel,configuration,null); }
  }
  RouteSnapshot route(String taskId, String requestedModel);
}
