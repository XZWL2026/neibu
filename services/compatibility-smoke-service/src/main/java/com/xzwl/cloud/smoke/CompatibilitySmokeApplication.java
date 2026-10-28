package com.xzwl.cloud.smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
public class CompatibilitySmokeApplication {
    public static void main(String[] args) {
        SpringApplication.run(CompatibilitySmokeApplication.class, args);
    }
}
