package com.xzwl.cloud.media.application.task;

/** User-facing vocabulary only; internal failure categories and provider diagnostics stay intact. */
public final class MediaFailureMessages {
  private MediaFailureMessages() {}
  public static String message(String category,ProviderPort.FailureDetail detail) {
    if ("REFERENCE_METADATA_REQUIRED".equals(category)) return "参考素材缺少校验信息，请重新上传后生成";
    if ("UNSUPPORTED_OMNI_REFERENCE".equals(category)) return "全能参考不符合当前模型要求，请检查素材数量、时长和类型";
    if ("REFERENCE_REJECTED".equals(category) || "REFERENCE_TYPE_MISMATCH".equals(category) || "INVALID_REFERENCE_TYPE".equals(category))
      return "参考素材不可用或类型不符，请替换或重新上传";
    if ("GENERATION_TIMEOUT".equals(category)) return "生成超时，请稍后重试";
    if ("ARCHIVE_REJECTED".equals(category)) return "生成已完成，但结果文件保存失败；请联系管理员检查存储配置与结果地址";
    if (detail != null) {
      if ("expired".equals(detail.status())) return "生成超时，请稍后重试";
      String code=detail.code()==null ? "" : detail.code();
      if (code.contains("SensitiveContentDetected") || code.endsWith("PolicyViolation") || code.equals("CONTENT_REJECTED"))
        return "内容审核未通过，请调整提示词或素材后重试";
      if (code.equals("GENERATION_TIMEOUT")) return "生成超时，请稍后重试";
      if (code.startsWith("InvalidParameter.Image") || code.startsWith("InvalidParameter.Video"))
        return "素材不符合要求，请检查格式、尺寸和内容后重试";
      if (detail.stage()!=null && detail.stage().startsWith("MPS_")) return "火山生成失败";
    }
    return "生成失败，请稍后重试";
  }
}
