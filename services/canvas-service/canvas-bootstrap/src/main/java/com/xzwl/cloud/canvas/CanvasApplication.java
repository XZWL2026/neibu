package com.xzwl.cloud.canvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.canvas.application.OwnerCanvasStores;
import com.xzwl.cloud.canvas.infrastructure.*;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication
public class CanvasApplication {
  public static void main(String[] args) { SpringApplication.run(CanvasApplication.class, args); }
  @Bean Clock canvasClock() { return Clock.systemUTC(); }
  @Bean OwnerCanvasStores canvasStores(CanvasMapper documents, CanvasVersionMapper versions,
      CanvasMediaReferenceMapper references, ObjectMapper json, PlatformTransactionManager manager) {
    return new MyBatisCanvasStores(documents, versions, references, json, new TransactionTemplate(manager));
  }
}
