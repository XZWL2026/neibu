package com.xzwl.cloud.media;

import com.xzwl.cloud.media.infrastructure.provider.ProductCopyWriter;
import com.xzwl.cloud.media.infrastructure.provider.ProviderHttp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.*;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductCopyConfigurationTest {
  private ProductCopyWriter writer(MockEnvironment env) {
    var beans = new DefaultListableBeanFactory();
    beans.setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver());
    beans.addEmbeddedValueResolver(env::resolveRequiredPlaceholders);
    beans.registerSingleton("configuration", new MediaConfiguration());
    beans.registerSingleton("environment", env);
    beans.registerSingleton("http", mock(ProviderHttp.class));
    var definition = new RootBeanDefinition();
    definition.setFactoryBeanName("configuration");
    definition.setFactoryMethodName("productCopyWriter");
    definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
    beans.registerBeanDefinition("writer", definition);
    return beans.getBean(ProductCopyWriter.class);
  }

  @Test void nacosPropertiesResolveExistingArkCredentialReference() {
    var env = new MockEnvironment()
        .withProperty("media.product-copy.base-url", "https://ark.cn-beijing.volces.com/api/v3")
        .withProperty("media.product-copy.model", "deepseek-v4-1-flash-260910")
        .withProperty("media.product-copy.api-key", "${VOLC_ARK_API_KEY:}")
        .withProperty("VOLC_ARK_API_KEY", "fixture-ark-key");
    assertTrue(writer(env).configured());
    env.setProperty("VOLC_ARK_API_KEY", "");
    assertFalse(writer(env).configured());
  }

  @Test void retainsLegacyConfigurationAndMissingConfigRemainsDisabled() {
    assertTrue(writer(legacy()).configured());
    assertFalse(writer(new MockEnvironment()).configured());
  }

  @Test void explicitBlankNacosCredentialDoesNotFallBackToAnotherProvider() {
    assertFalse(writer(legacy().withProperty("media.product-copy.api-key", "")).configured());
  }

  @Test void rejectsInvalidNacosValuesRatherThanUsingLegacyConfiguration() {
    for (String property : new String[]{"base-url", "model", "api-key"}) {
      assertThrows(RuntimeException.class,
          () -> writer(legacy().withProperty("media.product-copy." + property, "invalid value")));
    }
  }

  private MockEnvironment legacy() {
    return new MockEnvironment()
        .withProperty("MEDIA_PRODUCT_COPY_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
        .withProperty("MEDIA_PRODUCT_COPY_MODEL", "qwen3-vl-flash")
        .withProperty("MEDIA_PRODUCT_COPY_API_KEY", "fixture-bailian-key");
  }
}
