package com.xzwl.cloud.media.domain.task;

import java.net.URI;
import java.util.Locale;

/** Media bytes never enter a task document. Uploads are stored before task submission. */
public record MediaReference(String fileId, String httpsUrl, Kind kind, Double durationSeconds, String portraitId) {
  public enum Kind { IMAGE, VIDEO, AUDIO }
  public MediaReference(String fileId,String httpsUrl,Kind kind,Double durationSeconds) { this(fileId,httpsUrl,kind,durationSeconds,null); }
  public MediaReference(String fileId,String httpsUrl) { this(fileId,httpsUrl,Kind.IMAGE,null); }
  public MediaReference {
    kind = kind == null ? Kind.IMAGE : kind;
    if (durationSeconds != null && (!Double.isFinite(durationSeconds) || durationSeconds <= 0))
      throw new IllegalArgumentException("invalid reference duration");
    if ((fileId == null) == (httpsUrl == null)) {
      throw new IllegalArgumentException("exactly one fileId or httpsUrl is required");
    }
    if (fileId != null) identifier(fileId, "fileId");
    if (portraitId != null) {
      identifier(portraitId,"portraitId");
      if(fileId == null) throw new IllegalArgumentException("portrait requires an uploaded file");
    }
    if (httpsUrl != null) https(httpsUrl);
  }

  public static String identifier(String value, String name) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
      throw new IllegalArgumentException(name + " must be an identifier of at most 128 characters");
    }
    return value;
  }

  public static String text(String value, String name, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength
        || value.toLowerCase(Locale.ROOT).contains("data:")
        || value.toLowerCase(Locale.ROOT).contains("base64")) {
      throw new IllegalArgumentException(name + " is invalid; embedded media is forbidden");
    }
    return value;
  }

  public static String https(String value) {
    text(value, "httpsUrl", 8192);
    try {
      URI uri = URI.create(value);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("media URL must be HTTPS without user information or fragment");
    }
  }
}
