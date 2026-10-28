package com.xzwl.cloud.routing.application.route;

public final class RouteConflictException extends RuntimeException {
  public RouteConflictException() {
    super("task already has a route for a different requested model");
  }
}
