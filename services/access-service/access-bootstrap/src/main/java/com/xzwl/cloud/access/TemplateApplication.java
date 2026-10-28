package com.xzwl.cloud.access;

import com.xzwl.cloud.web.InternalClientConfiguration;
import com.xzwl.cloud.web.WebErrorAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = WebErrorAutoConfiguration.class)
@Import(InternalClientConfiguration.class)
public class TemplateApplication {

    public static void main(String[] args) {
        SpringApplication.run(TemplateApplication.class, args);
    }
}
