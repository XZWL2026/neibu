package com.xzwl.cloud.storage.application.file;

import com.xzwl.cloud.storage.domain.file.FilePurpose;
import java.io.InputStream;

public record StoreFileCommand(
    String ownerId,
    FilePurpose purpose,
    String declaredMimeType,
    long declaredSizeBytes,
    InputStream body) {}
