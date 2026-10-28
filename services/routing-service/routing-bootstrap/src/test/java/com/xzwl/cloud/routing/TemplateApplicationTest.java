package com.xzwl.cloud.routing;

import com.xzwl.cloud.routing.application.model.ConfigureModelHandler;
import com.xzwl.cloud.routing.application.provider.ConfigureProviderHandler;
import com.xzwl.cloud.routing.application.route.RouteTaskHandler;
import com.xzwl.cloud.routing.infrastructure.MyBatisRoutingRepository;
import com.xzwl.cloud.routing.domain.provider.Provider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
    classes = TemplateApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:routing_boot;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.main.banner-mode=off"
    })
class TemplateApplicationTest {

    @Autowired MyBatisRoutingRepository repository;
    @Autowired ConfigureProviderHandler providerHandler;
    @Autowired ConfigureModelHandler modelHandler;
    @Autowired RouteTaskHandler routeTaskHandler;

    @Test
    void bootstrapIsTheSpringBootAssemblyRoot() {
        assertNotNull(TemplateApplication.class.getAnnotation(SpringBootApplication.class));
        assertNotNull(repository);
        assertNotNull(providerHandler);
        assertNotNull(modelHandler);
        assertNotNull(routeTaskHandler);
        repository.save(Provider.configure("bootstrap", "启动验证", "https://example.test", false, "{}"));
        assertEquals("启动验证", repository.findById("bootstrap").orElseThrow().displayName());
    }
}
