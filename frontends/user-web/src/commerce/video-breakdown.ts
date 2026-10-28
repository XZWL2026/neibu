import { mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown, storageUploadReferenceImagePostApiV1FilesReferenceImages, storageVideoContentGetApiV1FilesFileIdVideoContent } from '../api';
import type { VideoBrief } from './commerce-video';
import { extractVideoFrames } from './video-frames';

export async function breakDownVideo(fileId: string, brief: VideoBrief, isCurrent: () => boolean) {
  const { data: content } = await storageVideoContentGetApiV1FilesFileIdVideoContent(fileId);
  if (!isCurrent()) return null;
  if (!content.base64 || !content.mimeType?.startsWith('video/')) throw new Error('视频内容不可用');
  const bytes = Uint8Array.from(atob(content.base64), character => character.charCodeAt(0));
  const url = URL.createObjectURL(new Blob([bytes], { type: content.mimeType }));
  let sampled: Awaited<ReturnType<typeof extractVideoFrames>>;
  try { sampled = await extractVideoFrames(url); } finally { URL.revokeObjectURL(url); }
  const fileIds: string[] = [];
  for (const frame of sampled.frames) {
    if (!isCurrent()) return null;
    const { data } = await storageUploadReferenceImagePostApiV1FilesReferenceImages({ file: frame.file });
    if (!data.fileId) throw new Error('视频帧上传失败');
    fileIds.push(data.fileId);
  }
  if (!isCurrent()) return null;
  const { data } = await mediaVideoBreakdownPostApiV1MediaProductSuitesVideoBreakdown({
    productName: brief.product.trim().slice(0, 120) || '商品图片中的商品', sellingPoints: brief.sellingPoints,
    language: brief.language, fileIds, timestamps: sampled.frames.map(frame => frame.timestamp), durationSeconds: sampled.durationSeconds,
  });
  if (!isCurrent()) return null;
  if (!data.shots?.length) throw new Error('未返回可用分镜');
  return data.shots.map(shot => {
    if (typeof shot.visual !== 'string' || !shot.visual.trim()) throw new Error('未返回可用分镜');
    return { visual: shot.visual, narration: '' };
  });
}
