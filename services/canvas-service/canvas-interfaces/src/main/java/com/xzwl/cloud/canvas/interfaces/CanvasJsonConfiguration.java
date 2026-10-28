package com.xzwl.cloud.canvas.interfaces;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xzwl.cloud.canvas.domain.CanvasContent;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CanvasJsonConfiguration {
  /** Keep derived domain predicates out of the persisted and public snapshot shape. */
  public abstract static class ContentMixin {
    @JsonIgnore public abstract boolean isEmpty();
  }
  @Bean Jackson2ObjectMapperBuilderCustomizer canvasJson() {
    return builder -> builder.mixIn(CanvasContent.class, ContentMixin.class);
  }
}
