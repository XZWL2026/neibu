package com.xzwl.cloud.payment.application.workflow;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A payment receipt, stored with its order and available only to receipt reviewers. */
public record TransferProof(String fileName, String contentType, byte[] content) {
  public static final int MAX_BYTES = 10 * 1024 * 1024;
  public TransferProof {
    if (fileName == null || fileName.isBlank() || fileName.length() > 200 || fileName.chars().anyMatch(c -> c < 32)
        || fileName.contains("/") || fileName.contains("\\")) throw new IllegalArgumentException("invalid receipt filename");
    if (content == null || content.length == 0 || content.length > MAX_BYTES) throw new IllegalArgumentException("receipt must be 1 byte to 10MB");
    String detected = content.length >= 8 && Arrays.equals(Arrays.copyOf(content, 8), new byte[]{(byte)137,80,78,71,13,10,26,10}) ? "image/png"
        : content.length >= 3 && content[0] == (byte)255 && content[1] == (byte)216 && content[2] == (byte)255 ? "image/jpeg"
        : content.length >= 5 && new String(content, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-") ? "application/pdf" : "";
    if (detected.isEmpty() || !detected.equals(contentType)) throw new IllegalArgumentException("receipt must be JPG, PNG or PDF");
  }
}
