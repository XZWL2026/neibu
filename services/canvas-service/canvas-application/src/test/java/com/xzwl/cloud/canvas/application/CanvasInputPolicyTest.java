package com.xzwl.cloud.canvas.application;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CanvasInputPolicyTest {
  @Test void preservesOrdinaryTextAndHttpsPathsContainingEncodingWords() {
    for (String text : new String[]{"metadata: sunset", "base64 编码说明", "参考旧网页 http://example.test 后作图"})
      assertDoesNotThrow(() -> CanvasInputPolicy.name(text));
    assertDoesNotThrow(() -> CanvasInputPolicy.reference("https://cdn.example.test/base64-icon.png"));
  }
  @Test void rejectsActualInlineMediaAndUnsafeReferences() {
    for (String ref : new String[]{"data:image/png;base64,AAAA", "DATA:image/png;base64,AAAA", "http://cdn.example.test/a", "blob:local", "SGVsbG8=", "https://user:secret@cdn.example.test/a"})
      assertThrows(IllegalArgumentException.class, () -> CanvasInputPolicy.reference(ref), ref);
    assertThrows(IllegalArgumentException.class, () -> CanvasInputPolicy.text("preview data:image/png;base64,AAAA"));
  }
}
