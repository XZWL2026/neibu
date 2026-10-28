package com.xzwl.cloud.routing.application.route;

import com.xzwl.cloud.routing.domain.route.RouteSnapshot;
import java.util.Optional;

public interface RouteSnapshotStore {
  boolean saveIfAbsent(RouteSnapshot snapshot);

  Optional<RouteSnapshot> findByTaskId(String taskId);
}
