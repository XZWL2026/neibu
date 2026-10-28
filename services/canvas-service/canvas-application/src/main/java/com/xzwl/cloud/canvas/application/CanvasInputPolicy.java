package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.CanvasContent;
import java.net.URI;
import java.util.*;

/** Production transport/storage policy. Domain snapshot and version rules remain unchanged. */
public final class CanvasInputPolicy {
  private CanvasInputPolicy() {}
  public static final Set<String> REFERENCE_KEYS = Set.of("publicId", "fileId", "url", "src");
  public static String identifier(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
      throw new IllegalArgumentException("Invalid identifier");
    return value;
  }
  public static String name(String value) {
    text(value);
    if (value.isBlank() || value.length() > 200) throw new IllegalArgumentException("Invalid canvas name");
    return value;
  }
  public static void reference(String value) {
    text(value);
    if (value.startsWith("https://")) {
      URI uri = URI.create(value);
      if (uri.getHost() == null || uri.getRawUserInfo() != null || value.length() > 2048)
        throw new IllegalArgumentException("Invalid HTTPS reference");
    } else identifier(value);
  }
  public static void text(String value) {
    if (value == null || value.length() > 65536) throw new IllegalArgumentException("Invalid text");
    String lower = value.strip().toLowerCase(Locale.ROOT);
    if (java.util.regex.Pattern.compile("(?i)\\bdata:[^\\s,]*,").matcher(value).find()
        || lower.startsWith("blob:") || lower.startsWith("http://") || lower.startsWith("file:") || value.indexOf('\0') >= 0
        || value.matches("[A-Za-z0-9+/]{4,}={1,2}")
        || value.matches("[A-Za-z0-9+/_-]{128,}"))
      throw new IllegalArgumentException("Inline media is forbidden; use a file ID or HTTPS reference");
  }
  public static void content(CanvasContent content) {
    Objects.requireNonNull(content, "content");
    if (content.nodes().size() > 2000 || content.edges().size() > 5000) throw new IllegalArgumentException("Canvas is too large");
    Set<String> nodes = new HashSet<>(), edges = new HashSet<>();
    for (var node : content.nodes()) {
      identifier(node.id()); identifier(node.kind());
      if (!nodes.add(node.id())) throw new IllegalArgumentException("Duplicate node ID");
      if (node.properties().size() > 100) throw new IllegalArgumentException("Too many properties");
      for (var property : node.properties().entrySet()) {
        identifier(property.getKey()); text(property.getValue());
        if (property.getKey().toLowerCase(Locale.ROOT).contains("base64"))
          throw new IllegalArgumentException("Base64 properties are forbidden");
        if (REFERENCE_KEYS.contains(property.getKey())) reference(property.getValue());
      }
    }
    for (var edge : content.edges()) {
      identifier(edge.id());
      if (!edges.add(edge.id()) || !nodes.contains(edge.source()) || !nodes.contains(edge.target()))
        throw new IllegalArgumentException("Invalid edge");
    }
  }
}
