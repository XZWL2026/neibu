package com.xzwl.cloud.storage;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.storage.infrastructure.TosPrivateObjectStorage;
import com.xzwl.cloud.storage.application.file.FileStorageException;
import com.xzwl.cloud.storage.interfaces.http.ProviderStatusController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
@SpringBootTest(classes=TemplateApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "spring.datasource.url=jdbc:h2:mem:storage_missing;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=",
    "STORAGE_RESULT_MAX_BYTES=104857600",
    "TOS_REGION=", "TOS_ENDPOINT=", "TOS_BUCKET=", "TOS_ACCESS_KEY_ID=", "TOS_SECRET_ACCESS_KEY="})
class StorageMissingConfigurationTest {
  @Autowired ProviderStatusController controller;
  @Autowired TosPrivateObjectStorage storage;
  @Autowired com.xzwl.cloud.storage.infrastructure.HttpsRemoteFileSource remoteFiles;
  @Test void statusRemainsAvailableWhileUnconfiguredStorageOperationsFailClosed() {
    var request=new MockHttpServletRequest();request.setUserPrincipal(()->"primary");request.addUserRole("ADMIN");request.addUserRole("PRIMARY_ADMIN");
    var status=controller.get(request);
    assertFalse(status.configured());assertFalse(status.available());
    assertTrue(status.missingEnvironmentVariables().contains("TOS_SECRET_ACCESS_KEY"));
    assertThrows(FileStorageException.class,()->storage.signGet("object",60));
    assertThrows(com.xzwl.cloud.storage.application.file.FileValidationException.class,
        () -> remoteFiles.open(java.net.URI.create("https://unconfigured.example/file.png")));
  }
}
