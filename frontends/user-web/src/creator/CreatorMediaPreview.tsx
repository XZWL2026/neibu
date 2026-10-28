import { t, formatMessage, useLanguage } from '../i18n';
import { LoadingMedia } from './LoadingMedia';
import type { CreatorMedia } from "./types";

export type CreatorMediaPreviewProps = {
  media: CreatorMedia;
  thumbnail?: boolean;
};

const safePreviewUrl = (previewUrl: string | undefined): previewUrl is string =>
  typeof previewUrl === "string" && /^(?:blob:|data:(?:image|video|audio)\/)/i.test(previewUrl);

export function CreatorMediaPreview({ media, thumbnail = false }: CreatorMediaPreviewProps) {
  useLanguage();
  const previewUrl = (thumbnail ? media.previewUrl?.startsWith("blob:") : safePreviewUrl(media.previewUrl)) ? media.previewUrl : null;
  if (thumbnail) return <div className={`creator-media-preview creator-media-preview-${media.kind}`}>
    {previewUrl && media.kind === "image" && <LoadingMedia kind="image" src={previewUrl} label={media.name} />}
    {previewUrl && media.kind === "video" && <LoadingMedia kind="video" src={previewUrl} label={formatMessage("{0} 预览", media.name)} muted />}
    {media.kind !== "image" && <span className="media-library-kind-icon" aria-hidden="true">{media.kind === "video" ? "▶" : "♫"}</span>}
    {media.kind === "audio" && <span className="media-library-audio-name">{media.name}</span>}
    {!previewUrl && media.kind !== "audio" && <span className="media-library-preview-empty">{t("预览暂不可用")}</span>}
  </div>;
  return <div className={`creator-media-preview creator-media-preview-${media.kind}`}>
    {previewUrl && media.kind === "image" && <LoadingMedia kind="image" src={previewUrl} label={media.name} />}
    {previewUrl && media.kind === "video" && <LoadingMedia kind="video" src={previewUrl} label={formatMessage("{0} 预览", media.name)} controls />}
    {previewUrl && media.kind === "audio" && <audio src={previewUrl} aria-label={formatMessage("{0} 预览", media.name)} controls preload="metadata" />}
    <span>{media.name}</span>
  </div>;
}
