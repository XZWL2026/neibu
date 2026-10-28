package com.xzwl.cloud.routing;

import com.xzwl.cloud.routing.application.model.ConfigureModelHandler;
import com.xzwl.cloud.routing.application.provider.ConfigureProviderHandler;
import com.xzwl.cloud.routing.application.route.RouteTaskHandler;
import com.xzwl.cloud.routing.infrastructure.MyBatisRoutingRepository;
import com.xzwl.cloud.routing.infrastructure.RoutingProviderMapper;
import com.xzwl.cloud.routing.infrastructure.RoutingModelMapper;
import com.xzwl.cloud.routing.infrastructure.RouteSnapshotMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RoutingConfiguration {
  @Bean
  com.xzwl.cloud.routing.interfaces.http.RoutingMediaFilter mediaServiceIdentity(
      @org.springframework.beans.factory.annotation.Value("${routing.media-token:}") String token) {
    return new com.xzwl.cloud.routing.interfaces.http.RoutingMediaFilter(token);
  }


  @Bean
  MyBatisRoutingRepository routingRepository(
      RoutingProviderMapper providers, RoutingModelMapper models, RouteSnapshotMapper snapshots) {
    return new MyBatisRoutingRepository(providers, models, snapshots);
  }

  @Bean
  ConfigureProviderHandler configureProviderHandler(MyBatisRoutingRepository repository) {
    return new ConfigureProviderHandler(repository);
  }

  @Bean
  ConfigureModelHandler configureModelHandler(MyBatisRoutingRepository repository) {
    return new ConfigureModelHandler(repository, repository);
  }

  @Bean
  RouteTaskHandler routeTaskHandler(MyBatisRoutingRepository repository, Clock clock) {
    return new RouteTaskHandler(repository, repository, repository, clock);
  }

  @Bean
  Clock routingClock() {
    return Clock.systemUTC();
  }
}
