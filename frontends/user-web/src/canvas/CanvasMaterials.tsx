import { randomUUID } from '../../../shared/randomUUID';
import { t, useLanguage } from '../i18n';
import { reviewedLibraryMedia } from '../creator/reviewedLibraryMedia';
import { CanvasIcon } from './CanvasIcon';
import { useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react';
import { createPortal } from 'react-dom';
import { CreatorMediaProvider, useCreatorMedia, useOptionalCreatorMedia } from '../creator/CreatorMediaProvider';
import { MediaLibraryDialog } from '../creator/MediaLibraryDialog';
import { resolvePreview } from './production-api';
import type { CreatorMedia, MediaInputCapability } from '../creator/types';
import { PortraitLibraryDialog, REVIEW_MEDIA_ACCEPT } from '../creator/PortraitLibraryDialog';
import type { ComposerMaterial } from '../creator/ComposerMaterials';
import { useOptionalUserSession } from '../userSession';
import type { CanvasDocument, CanvasPoint, ImageCanvasNode } from './model';
import type { CanvasGenerationOption } from './media-task-port';

type Props = {
  document: CanvasDocument; disabled: boolean; options: readonly CanvasGenerationOption[];
  uploadMediaRef?: Ref<(nodeId?: string, position?: CanvasPoint) => void>;
  update(transform: (document: CanvasDocument) => CanvasDocument): void;
};
const libraryCapability: MediaInputCapability = {
  acceptedKinds: ['image', 'video', 'audio'], acceptedMimeTypes: REVIEW_MEDIA_ACCEPT.split(','), acceptedSources: ['library'],
  maxFileBytes: 20 * 1024 * 1024, maxFileBytesByKind: { video: 200 * 1024 * 1024, audio: 15 * 1024 * 1024 }, maxItems: null, verified: true,
};
export function CanvasMaterials(props: Props) {
  useLanguage();
  const media = useOptionalCreatorMedia();
  return media ? <CanvasMaterialPicker {...props} /> : <CreatorMediaProvider><CanvasMaterialPicker {...props} /></CreatorMediaProvider>;
}
function CanvasMaterialPicker({ document, disabled, options, update, uploadMediaRef }: Props) {
  useLanguage();
  const { personalMedia, publicMedia, importMedia, refreshMedia } = useCreatorMedia();
  const [library, setLibrary] = useState<{ owner: string | null; model: string } | null>(null);
  const [libraryError, setLibraryError] = useState('');
  const queue = useRef<{ file: File; libraryMedia?: CreatorMedia; position?: CanvasPoint }[]>([]);
  const batchIndex = useRef(0);
  const input = useRef<HTMLInputElement>(null);
  const [picker, setPicker] = useState<{ owner: string | null; model: string; nodeId?: string; prompt?: string; accept: string; position?: CanvasPoint } | null>(null);
  useEffect(() => {
    const element = input.current;
    const cancel = () => setPicker(null);
    element?.addEventListener("cancel", cancel);
    return () => element?.removeEventListener("cancel", cancel);
  }, []);
  const account = useOptionalUserSession();
  const settledOwner = useRef(account?.userId ?? null);
  if (account?.status !== 'loading') settledOwner.current = account?.userId ?? null;
  const owner = account?.status === 'loading' ? settledOwner.current : account?.userId ?? null;
  const accountLocked = Boolean(account && (!owner || (account.status !== 'loading' && !account.authenticated)));
  const locked = disabled || accountLocked;
  const models = options.filter(option => option.type === 'VIDEO' && option.operation !== 'DEPTH' && option.omniReference);
  const node = document.nodes.find(node => node.kind === 'generation' && node.settings.type === 'VIDEO' && node.settings.operation !== 'DEPTH'
    && models.some(model => model.model === node.settings.model));
  const [uploadModel, setUploadModel] = useState<{ owner: string | null; model: string } | null>(null);
  useEffect(() => { setUploadModel(null); queue.current = []; }, [owner]);
  const model = uploadModel?.owner === owner ? uploadModel.model : (node?.kind === 'generation' ? node.settings.model : models[0]?.model) ?? '';
  const context = `${owner}:${model}`;
  const currentContext = useRef(context); currentContext.current = context;
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const [opened, setOpened] = useState<{ context: string; file?: File; libraryMedia?: CreatorMedia; nodeId?: string; prompt?: string; position?: CanvasPoint } | null>(null);
  useEffect(() => { setOpened(previous => previous?.context === context ? previous : null); }, [context]);
  const open = opened?.context === context;
  const chooseFile = (nodeId?: string, position?: CanvasPoint) => {
    if (locked || uploadModel || !input.current) return;
    const target = document.nodes.find(node => node.id === nodeId);
    const accept = !nodeId ? position ? 'image/png,image/jpeg,video/mp4,video/quicktime' : REVIEW_MEDIA_ACCEPT : target?.kind === 'generation' && target.settings.type === 'VIDEO'
      ? 'video/mp4,video/quicktime' : 'image/png,image/jpeg';
    input.current.accept = accept;
    input.current.multiple = !nodeId;
    const targetModel = target?.kind === 'generation' && models.some(option => option.model === target.settings.model) ? target.settings.model : model;
    setPicker({ owner, model: targetModel, nodeId, accept, position, prompt: target?.kind === 'generation' ? target.prompt : undefined });
    input.current.click();
  };
  useImperativeHandle(uploadMediaRef, () => chooseFile);
  const local = [...new Map(document.nodes.flatMap(node => node.kind === 'image' || node.kind === 'video' || node.kind === 'audio'
    ? [[node.asset.assetRef, { fileId: node.asset.assetRef, name: node.asset.alt, preview: node.asset.previewRef,
      kind: node.kind === 'video' ? 'VIDEO' as const : node.kind === 'audio' ? 'AUDIO' as const : 'IMAGE' as const,
      portraitId: node.asset.portraitId, reviewId: node.asset.reviewId, reviewPending: node.asset.reviewPending, reviewStatus: node.asset.reviewStatus,
      durationSeconds: node.asset.durationSeconds, sizeBytes: node.asset.sizeBytes }] as const] : [])).values()];
  const add = (materials: ComposerMaterial[], onlyNew = false, offset = 0, nodeId?: string, expectedContext = context, position?: CanvasPoint) => {
    if (!alive.current || currentContext.current !== expectedContext) return;
    update(document => {
      const target = nodeId ? document.nodes.find(node => node.id === nodeId) : undefined;
      if (nodeId && (target?.kind !== 'generation' || target.settings.operation === 'DEPTH' || target.taskState !== 'idle' || target.taskRef || target.prompt !== opened?.prompt
        || document.edges.some(edge => edge.target === nodeId))) return document;
      const additions = materials.filter(material => nodeId ? target?.kind === 'generation' && material.kind === target.settings.type : !onlyNew || !document.nodes.some(node => node.kind !== 'generation' && node.kind !== 'text' && node.asset.assetRef === material.fileId));
      if (!additions.length) return document;
      const mediaNodes = additions.map<ImageCanvasNode>((material, index) => {
        const kind = material.kind === 'VIDEO' ? 'video' : material.kind === 'AUDIO' ? 'audio' : 'image';
        return { id: target?.id ?? `media-${randomUUID()}`, kind, version: 1, position: target?.position ?? { x: (position?.x ?? (80 - document.viewport.x) / document.viewport.zoom) + (index + offset) * 40, y: (position?.y ?? (120 - document.viewport.y) / document.viewport.zoom) + (index + offset) * 40 }, sourceTaskRef: null,
          asset: { assetRef: material.fileId, kind, alt: material.name, previewRef: material.preview, ...(material.portraitId ? { portraitId: material.portraitId } : {}),
            ...(material.reviewId ? { reviewId: material.reviewId } : {}), ...(material.reviewPending ? { reviewPending: true } : {}), ...(material.reviewStatus ? { reviewStatus: material.reviewStatus } : {}),
            ...(material.durationSeconds != null ? { durationSeconds: material.durationSeconds } : {}), ...(material.sizeBytes != null ? { sizeBytes: material.sizeBytes } : {}) } };
      });
      return { ...document, updatedAt: new Date().toISOString(), nodes: nodeId
        ? document.nodes.map(node => node.id === nodeId ? mediaNodes[0] : node)
        : [...document.nodes, ...mediaNodes] };
    });
  };
  const reviewed = (fileId: string, reviewId: string | undefined, reviewStatus: ComposerMaterial["reviewStatus"]) => {
    if (!alive.current || currentContext.current !== context) return;
    update(document => {
      let changed = false;
      const nodes = document.nodes.map(node => {
        if (node.kind === 'text' || node.kind === 'generation' || node.asset.assetRef !== fileId || node.asset.portraitId
          || !node.asset.reviewPending && !node.asset.reviewId || node.asset.reviewId === reviewId && node.asset.reviewStatus === reviewStatus && (!reviewId || !node.asset.reviewPending)) return node;
        changed = true;
        const { reviewPending: _pending, ...asset } = node.asset;
        return { ...node, asset: { ...asset, ...(reviewId ? { reviewId } : { reviewPending: true }), reviewStatus } };
      });
      return changed ? { ...document, nodes, updatedAt: new Date().toISOString() } : document;
    });
  };
  const syncExisting = async () => {
    setLibraryError('');
    const stored = await refreshMedia();
    for (const material of local.filter(item => (item.portraitId || item.reviewId && item.reviewStatus === 'ACTIVE') && !stored.some(media => (media.id === item.fileId || media.canvasFileIds?.includes(item.fileId))))) {
      if (!alive.current || currentContext.current !== context) return;
      try {
        const response = await fetch(await resolvePreview(material.fileId));
        if (!response.ok) throw new Error('download failed');
        const content = await response.blob();
        if (!alive.current || currentContext.current !== context) return;
        const saved = await importMedia({ id: material.fileId, ownerId: owner, kind: material.kind.toLowerCase() as 'image' | 'video' | 'audio', name: material.name, mimeType: content.type.split(';')[0], size: content.size, content });
        if (!saved) throw new Error('library unavailable');
      } catch { /* Background sync stays silent, as requested. */ }
    }
  };
  const chooseFromLibrary = async (ids: readonly string[]) => {
    if (accountLocked || uploadModel || !library || library.owner !== owner) return;
    const selected = ids.map(id => [...personalMedia, ...publicMedia].find(item => item.id === id && (item.ownerId === undefined || item.ownerId === owner)));
    if (selected.some(item => !item)) { setLibraryError('素材文件不可用，请重新上传。'); return; }
    const libraryContext = `${owner}:${library.model}`;
    setUploadModel(library); setLibraryError('');
    try {
      const existing = await Promise.all(selected.map(item => reviewedLibraryMedia(item!)));
      if (!alive.current || currentContext.current !== libraryContext) return;
      const files = selected.flatMap((item, index) => {
        if (existing[index]) return [];
        if (!item!.content) throw new Error('素材文件不可用，请重新上传。');
        return [{ file: new File([item!.content], item!.name, { type: item!.mimeType }), libraryMedia: item! }];
      });
      for (let index = 0; index < selected.length; index++) {
        if (!alive.current || currentContext.current !== libraryContext) return;
        const item = selected[index]!; const material = existing[index];
        if (material && item.origin !== 'public' && item.id !== material.fileId && !item.canvasFileIds?.includes(material.fileId)) {
          const saved = await importMedia({ ...item, ownerId: owner, canvasFileIds: [...(item.canvasFileIds ?? []), material.fileId] });
          if (saved && material.preview === item.previewUrl) material.preview = saved.previewUrl ?? '';
        }
      }
      if (!alive.current || currentContext.current !== libraryContext) return;
      const reused = existing.filter((item): item is ComposerMaterial => item !== null);
      add(reused, false, 0, undefined, libraryContext); batchIndex.current = reused.length;
      const first = files.shift(); queue.current = files;
      if (first) setOpened({ context: `${owner}:${library.model}`, ...first });
      else setUploadModel(null);
    } catch (cause) {
      if (alive.current && currentContext.current === libraryContext) {
        setLibraryError(cause instanceof Error ? cause.message : '素材添加失败，请重试'); setUploadModel(null);
      }
    }
  };
  return <>
    <button aria-label={t("素材库")} className="canvas-action" type="button" disabled={locked || Boolean(uploadModel)} onClick={() => { setLibrary({ owner, model }); void syncExisting(); }}><CanvasIcon name="library" /><span className="canvas-action-tip" aria-hidden="true">{t("素材库")}</span></button>
    <button aria-label={t("本地上传")} className="canvas-action" type="button" disabled={locked || Boolean(uploadModel)} onClick={() => chooseFile()}><CanvasIcon name="upload" /><span className="canvas-action-tip" aria-hidden="true">{t("本地上传")}</span></button>
    <input ref={input} type="file" multiple={!picker?.nodeId} hidden aria-label={t("本地上传文件")} accept={REVIEW_MEDIA_ACCEPT} disabled={accountLocked || (disabled && picker?.owner !== owner)} onChange={event => {
      const files = Array.from(event.target.files ?? []);
      const [file, ...remaining] = picker ? files.filter(file => picker.accept.split(',').includes(file.type)) : files;
      event.target.value = '';
      // Returning from the native picker can refresh the catalog before change arrives.
      if (file && (!picker || picker.accept.split(',').includes(file.type)) && !accountLocked && !uploadModel && (!picker ? !disabled : picker.owner === owner)) {
        const selectedModel = picker?.model ?? model;
        batchIndex.current = 0;
        queue.current = picker?.nodeId ? [] : remaining.map(file => ({ file, position: picker?.position }));
        setUploadModel({ owner, model: selectedModel }); setOpened({ context: `${owner}:${selectedModel}`, file, nodeId: picker?.nodeId, prompt: picker?.prompt, position: picker?.position });
      }
      setPicker(null);
    }} />
    {(open || local.some(item => item.reviewId || item.reviewPending)) && <PortraitLibraryDialog key={context} visible={false} title={t("素材库")} owner={owner} model={model} disabled={locked} initialFile={open ? opened?.file : undefined}
      canvas={{ materials: local, onUploadState: (busy, failed) => {
        if (failed) queue.current = [];
        const next = !busy ? queue.current.shift() : undefined;
        if (next) { setUploadModel({ owner, model }); setOpened({ context, ...next }); }
        else setUploadModel(busy ? { owner, model } : null);
      }, onUploaded: (material, file) => {
        if (!alive.current || currentContext.current !== context) return;
        add([material], true, opened?.nodeId ? 0 : batchIndex.current++, opened?.nodeId, context, opened?.position);
        const existing = opened?.libraryMedia;
        if (file) void importMedia({ ...existing, id: existing?.origin === 'public' ? material.fileId : existing?.id ?? material.fileId, ownerId: existing && existing.origin !== 'public' ? existing.ownerId : owner, kind: material.kind!.toLowerCase() as 'image' | 'video' | 'audio', name: file.name, mimeType: file.type, size: file.size, content: file, canvasFileIds: [...(existing?.canvasFileIds ?? []), material.fileId] })
          .then(saved => { if (!saved && alive.current && currentContext.current === context) setLibraryError('素材已上传，但未保存到个人素材库。'); })
          .catch(() => { if (alive.current && currentContext.current === context) setLibraryError('素材已上传，但未保存到个人素材库。'); });
      }, onReview: reviewed }}
      onClose={() => setOpened(null)} onPick={materials => { if (!locked) { add(materials); setOpened(null); } }} />}
    {library && library.owner === owner && !accountLocked && createPortal(<MediaLibraryDialog open capability={libraryCapability} selectionMode="multiple" initialSource="personal" initialSelection={[]}
      onConfirm={chooseFromLibrary} onClose={() => setLibrary(null)} />, window.document.body)}
    {libraryError && <p role="alert">{t(libraryError)}</p>}
  </>;
}
