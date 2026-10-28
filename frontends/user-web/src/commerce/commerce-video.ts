import type { CanvasGenerationSettings } from '../canvas/model';
import type { ComposerMaterial } from '../creator/ComposerMaterials';
import { draftTimestamp } from '../creator/draft-metadata';

export const commerceVideoTools = {
  'video-replica': { title: '视频复刻', action: '生成复刻视频' },
  'seller-video': { title: '带货视频', action: '生成带货视频' },
};
export type CommerceVideoTool = keyof typeof commerceVideoTools;
export type VideoBrief = {
  product: string; sellingPoints: string; platform: string; language: string; style: string;
  shots: { visual: string; narration: string }[];
};
export const emptyVideoBrief = (): VideoBrief => ({ product: '', sellingPoints: '', platform: '抖音电商', language: '中文', style: '商品展示', shots: [{ visual: '', narration: '' }] });
export function videoBriefScript(tool: CommerceVideoTool, brief: VideoBrief): string {
  return [
    tool === 'video-replica'
      ? '从第一帧开始，每个镜头中的商品主体都替换为商品图片中的目标商品，保持其外观、配色和包装文字一致。参考视频只借鉴镜头顺序、构图、运镜和节奏，不保留原商品作为开场或过渡，不生成原商品变成目标商品的过程。原商品专属动作须改编为适合目标商品的展示动作；若分镜仍描述原商品，按此规则替换后执行。不要照搬参考视频的品牌、商品宣称、字幕或水印。'
      : '根据商品图片制作带货视频，保持商品外观、配色、包装文字和配件一致。',
    brief.product.trim() && `商品：${brief.product.trim()}`,
    brief.sellingPoints.trim() && `已确认卖点：${brief.sellingPoints.trim()}`,
    `发布平台：${brief.platform}；文案语言：${brief.language}；风格：${brief.style}。`,
    ...brief.shots.map((shot, index) => (shot.visual.trim() || shot.narration.trim())
      && `分镜 ${index + 1}：${shot.visual.trim()}${shot.narration.trim() ? `\n口播：${shot.narration.trim()}` : ''}`),
    '按所选视频总时长安排镜头；不得编造用量、成分、功效、认证、价格或促销信息。',
  ].filter(Boolean).join('\n\n');
}
export type CommerceVideoDraft = {
  updatedAt?: number;
  brief: VideoBrief; prompt: string; materials: (ComposerMaterial | null)[];
  selection: CanvasGenerationSettings | null; mode: 'omni' | 'frames' | 'text' | null;
  bindings: { token: string; fileId: string }[];
};
const draftKey = (owner: string, tool: CommerceVideoTool) => `xzwl-commerce-video:${owner}:${tool}`;
export function readCommerceVideoDraft(owner: string | null, tool: CommerceVideoTool): CommerceVideoDraft | null {
  if (!owner) return null;
  try {
    const draft = JSON.parse(localStorage.getItem(draftKey(owner, tool)) || 'null') as CommerceVideoDraft | null;
    if (!draft || typeof draft.prompt !== 'string' || draft.prompt.length > 16000 || !draft.brief
      || !['product', 'sellingPoints', 'platform', 'language', 'style'].every(key => typeof draft.brief[key as keyof VideoBrief] === 'string')
      || !Array.isArray(draft.brief.shots) || draft.brief.shots.length < 1 || draft.brief.shots.length > 12
      || draft.brief.shots.some(shot => !shot || typeof shot.visual !== 'string' || typeof shot.narration !== 'string')
      || !Array.isArray(draft.materials) || draft.materials.length > 50
      || draft.materials.some(file => file !== null && (!file || typeof file.fileId !== 'string' || typeof file.name !== 'string' || file.sourceFileId !== undefined && typeof file.sourceFileId !== 'string' || ![undefined, 'IMAGE', 'VIDEO', 'AUDIO'].includes(file.kind)))
      || ![null, 'omni', 'frames', 'text'].includes(draft.mode)
      || !Array.isArray(draft.bindings) || draft.bindings.some(binding => !binding || typeof binding.token !== 'string' || typeof binding.fileId !== 'string')
      || draft.selection !== null && (!draft.selection || draft.selection.type !== 'VIDEO'
        || typeof draft.selection.model !== 'string' || typeof draft.selection.aspectRatio !== 'string' || typeof draft.selection.resolution !== 'string'
        || !Number.isInteger(draft.selection.durationSeconds) || draft.selection.count !== 1)) return null;
    return { ...draft, materials: draft.materials.map(file => file && { ...file, preview: '' }) };
  } catch { return null; }
}
export function saveCommerceVideoDraft(owner: string | null, tool: CommerceVideoTool, draft: CommerceVideoDraft): boolean {
  if (!owner) return true;
  try {
    const { updatedAt: _updatedAt, ...fields } = draft;
    const content = { ...fields, materials: draft.materials.map(file => file && { ...file, preview: '' }) };
    localStorage.setItem(draftKey(owner, tool), JSON.stringify({ ...content, updatedAt: draftTimestamp(content, readCommerceVideoDraft(owner, tool)) }));
    return true;
  } catch { return false; }
}
