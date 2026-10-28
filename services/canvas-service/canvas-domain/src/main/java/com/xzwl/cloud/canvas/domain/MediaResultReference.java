package com.xzwl.cloud.canvas.domain;

import java.util.Map;
import java.util.Objects;

/** A reference to an already completed public media result; it never starts a media task. */
public record MediaResultReference(Kind kind, String publicId) {

  public MediaResultReference {
    kind = Objects.requireNonNull(kind, "kind");
    if (publicId == null || publicId.isBlank()) {
      throw new IllegalArgumentException("publicId must not be blank");
    }
  }

  public CanvasContent.Node toNode(String nodeId) {
    String nodeKind = kind == Kind.IMAGE ? "image" : "video";
    return new CanvasContent.Node(nodeId, nodeKind, Map.of("publicId", publicId));
  }

  public enum Kind {
    IMAGE,
    VIDEO
  }
}
