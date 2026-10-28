package com.xzwl.cloud.canvas.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable complete canvas snapshot. */
public record CanvasContent(List<Node> nodes, List<Edge> edges, Viewport viewport) {

  public CanvasContent {
    nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
    edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    viewport = Objects.requireNonNull(viewport, "viewport");
  }

  public static CanvasContent empty() {
    return new CanvasContent(List.of(), List.of(), new Viewport(0, 0, 1));
  }

  public boolean isEmpty() {
    return nodes.isEmpty() && edges.isEmpty();
  }

  public record Node(String id, String kind, Map<String, String> properties) {

    public Node {
      id = requireNonBlank(id, "id");
      kind = requireNonBlank(kind, "kind");
      properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }
  }

  public record Edge(String id, String source, String target) {

    public Edge {
      id = requireNonBlank(id, "id");
      source = requireNonBlank(source, "source");
      target = requireNonBlank(target, "target");
    }
  }

  public record Viewport(double x, double y, double zoom) {

    public Viewport {
      if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(zoom) || zoom <= 0) {
        throw new IllegalArgumentException("viewport must contain finite values and positive zoom");
      }
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
