import { randomUUID } from '../../../shared/randomUUID';
import { t, formatMessage, useLanguage } from '../i18n';
import { useEffect, useLayoutEffect, useId, useRef, useState, type ChangeEvent, type CSSProperties, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { CreatorMediaProvider, useCreatorMedia, useOptionalCreatorMedia } from './CreatorMediaProvider';
import { ComposerIcon } from './ComposerControls';
import { MediaLibraryDialog } from './MediaLibraryDialog';
import type { MediaInputCapability } from './types';
import { reviewedLibraryMedia } from './reviewedLibraryMedia';
import { PortraitLibraryDialog } from './PortraitLibraryDialog';
import { LoadingMedia, MediaSpinner } from './LoadingMedia';

export type ComposerMaterial = { sourceFileId?: string; reviewStatus?: import('../canvas/model').CanvasReviewStatus; reviewId?: string; reviewPending?: boolean; portraitId?: string; fileId: string; name: string; preview: string; kind?: 'IMAGE' | 'VIDEO' | 'AUDIO'; durationSeconds?: number | null; sizeBytes?: number };
export function materialReviewIssue(file: ComposerMaterial, model: string): string {
  if (file.reviewStatus === 'ACTIVE') return '';
  if (!model) return '审核服务暂不可用';
  return file.reviewStatus === 'FAILED' ? '审核未通过' : file.reviewStatus === 'UNCERTAIN' ? '审核提交待确认' : '审核中';
}
export function typedMaterialLabel(materials: readonly (ComposerMaterial | null)[], index: number): string {
  const type = materials[index]?.kind ?? 'IMAGE';
  const ordinal = materials.slice(0, index + 1).filter(file => file && (file.kind ?? 'IMAGE') === type).length;
  return `${type === 'VIDEO' ? '视频' : type === 'AUDIO' ? '音频' : '图片'}${ordinal}`;
}
type Slot = { label: string; file: ComposerMaterial | null; issue?: string; available?: boolean };

type Props = {
  inline?: boolean;
  onFeedbackChange?(hasFeedback: boolean): void;
  sourceVideos?: boolean;
  review?: { owner: string | null; model: string; onSelecting?(busy: boolean): void; onReview(fileId: string, reviewId: string | undefined, status: ComposerMaterial['reviewStatus']): void };
  selectionKey?: string;
  capability?: MediaInputCapability; slots: Slot[]; nextIndex: number; disabled: boolean; busy: boolean; hint?: string; error?: string;
  onQueueBusyChange?(busy: boolean): void;
  onDialogOpenChange?(open: boolean): void;
  onUpload(file: File, index: number): Promise<boolean | ComposerMaterial>;
  onReuse?(material: ComposerMaterial, index: number): boolean; onRemove(index: number): void; children: ReactNode;
};
const imageCapability: MediaInputCapability = {
  acceptedKinds: ['image'], acceptedMimeTypes: ['image/png', 'image/jpeg'],
  acceptedSources: ['upload', 'library'], maxFileBytes: 20 * 1024 * 1024, maxItems: 1, verified: true,
};

export function ComposerMaterials(props: Props) {
  useLanguage();
  const media = useOptionalCreatorMedia();
  return media ? <MaterialPicker {...props} /> : <CreatorMediaProvider><MaterialPicker {...props} /></CreatorMediaProvider>;
}

function MaterialPicker({ review, inline = false, onFeedbackChange, sourceVideos = false, selectionKey, capability = imageCapability, slots, nextIndex, disabled, busy: uploading, hint, error, onUpload, onReuse, onRemove, onQueueBusyChange, onDialogOpenChange, children }: Props) {
  useLanguage();
  const { personalMedia, publicMedia, importMedia } = useCreatorMedia();
  const input = useRef<HTMLInputElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const entry = useRef<HTMLDivElement>(null);
  const isReviewing = (file: ComposerMaterial) => Boolean(review?.model && materialReviewIssue(file, review.model) === '审核中');
  const pending = useRef(false);
  const batchPending = useRef(false);
  const alive = useRef(true);
  const [batchBusy, setBatchBusy] = useState(false);
  const [uploadPreview, setUploadPreview] = useState<{ index: number; file: File; url: string; context: string } | null>(null);
  type QueuedFile = { file: File; index?: number };
  const [uploadQueue, setUploadQueue] = useState<QueuedFile[]>([]);
  const queuedUpload = useRef<QueuedFile | null>(null);
  const queueBusy = uploadQueue.length > 0;
  useEffect(() => {
    onQueueBusyChange?.(queueBusy);
    return () => onQueueBusyChange?.(false);
  }, [queueBusy, onQueueBusyChange]);
  const transferBusy = uploading || batchBusy || Boolean(uploadPreview);
  const busy = transferBusy || uploadQueue.length > 0;
  const [limitNotice, setLimitNotice] = useState('');
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const showLimit = (message: string) => {
    clearTimeout(noticeTimer.current); setLimitNotice(message);
    noticeTimer.current = setTimeout(() => setLimitNotice(''), 5000);
  };
  useEffect(() => () => clearTimeout(noticeTimer.current), []);
  const selectionContext = JSON.stringify([review?.owner, selectionKey, capability]);
  const latestContext = useRef(selectionContext); latestContext.current = selectionContext;
  const queueContext = useRef(selectionContext);
  const queueIdentity = JSON.stringify([review?.owner, selectionKey]);
  const previousQueueIdentity = useRef(queueIdentity);
  const panelId = useId();
  const closeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const cancelClose = () => clearTimeout(closeTimer.current);
  const [expanded, setExpanded] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [libraryIndex, setLibraryIndex] = useState<number | null>(null);
  const nativePicker = useRef(false);
  const nativePickerContext = useRef(selectionContext);
  const [filePickerOpen, setFilePickerOpen] = useState(false);
  useLayoutEffect(() => { onDialogOpenChange?.(libraryIndex !== null || filePickerOpen); }, [libraryIndex, filePickerOpen, onDialogOpenChange]);
  useLayoutEffect(() => () => onDialogOpenChange?.(false), [onDialogOpenChange]);
  const beginFilePick = () => {
    if (nativePicker.current) return;
    nativePickerContext.current = selectionContext;
    nativePicker.current = true; setFilePickerOpen(true);
    // Notify synchronously, before the menu loses focus or the OS chooser opens.
    onDialogOpenChange?.(true); cancelClose();
  };
  const finishFilePick = () => {
    if (!nativePicker.current) return;
    // Restore focus while dismissal is still held, on both selection and cancel.
    trigger.current?.focus({ preventScroll: true });
    nativePicker.current = false; setFilePickerOpen(false);
    onDialogOpenChange?.(libraryIndex !== null);
  };
  useLayoutEffect(() => {
    const element = entry.current;
    // React's onCancel is wired for <dialog>, not native file inputs.
    const cancel = (event: Event) => { if (event.target instanceof HTMLInputElement && event.target.type === 'file') finishFilePick(); };
    element?.addEventListener('cancel', cancel, true);
    return () => element?.removeEventListener('cancel', cancel, true);
  }, [libraryIndex, onDialogOpenChange]);
  const [libraryError, setLibraryError] = useState('');
  const hasFeedback = Boolean(error || libraryError || limitNotice);
  useEffect(() => { onFeedbackChange?.(hasFeedback); return () => onFeedbackChange?.(false); }, [hasFeedback, onFeedbackChange]);
  const files = slots.flatMap(slot => slot.file ? [slot.file] : []);
  const libraryCapability: MediaInputCapability = {
    ...capability,
    maxItems: capability.maxItems === null ? null : Math.max(0, capability.maxItems - files.length),
    maxItemsByKind: capability.maxItemsByKind && Object.fromEntries(Object.entries(capability.maxItemsByKind).map(([kind, maximum]) =>
      [kind, Math.max(0, maximum - files.filter(file => (file.kind ?? 'IMAGE').toLowerCase() === kind).length)])),
  };
  const displaySlots: (Slot & { uploading?: boolean })[] = [...slots];
  if (uploadPreview?.context === selectionContext) {
    const { index, file, url } = uploadPreview;
    displaySlots[index] = { label: slots[index]?.label ?? `${file.type.startsWith('video/') ? '视频' : file.type.startsWith('audio/') ? '音频' : '图片'}${index + 1}`, uploading: true,
      file: { fileId: `upload-${index}`, name: file.name, preview: url, kind: file.type.startsWith('video/') ? 'VIDEO' : file.type.startsWith('audio/') ? 'AUDIO' : 'IMAGE' } };
  }
  for (const queued of uploadQueue) {
    if (queued === queuedUpload.current) continue;
    const { file } = queued;
    const empty = displaySlots.findIndex(slot => !slot.file && slot.available !== false);
    const index = queued.index ?? (empty < 0 ? displaySlots.length : empty);
    const kind = file.type.startsWith('video/') ? 'VIDEO' : file.type.startsWith('audio/') ? 'AUDIO' : 'IMAGE';
    displaySlots[index] = { label: slots[index]?.label ?? `${kind === 'VIDEO' ? '视频' : kind === 'AUDIO' ? '音频' : '图片'}${index + 1}`, uploading: true,
      file: { fileId: `queued-${index}`, name: file.name, preview: '', kind } };
  }
  const displayFiles = displaySlots.flatMap(slot => slot.file ? [slot.file] : []);
  const hasMaterials = displayFiles.length > 0;
  useEffect(() => () => { if (uploadPreview?.url) URL.revokeObjectURL(uploadPreview.url); }, [uploadPreview]);
  useEffect(() => {
    if (previousQueueIdentity.current !== queueIdentity) setUploadQueue([]);
    queueContext.current = selectionContext;
    previousQueueIdentity.current = queueIdentity;
    setUploadPreview(null);
  }, [queueIdentity, selectionContext]);
  const close = () => { cancelClose(); setExpanded(false); setMenuOpen(false); };
  useEffect(() => { alive.current = true; return () => { alive.current = false; clearTimeout(closeTimer.current); }; }, []);
  useEffect(() => { if (!hasMaterials) setExpanded(false); }, [hasMaterials]);
  const blocked = disabled || batchBusy;
  const countLimit = (file?: File, additions: readonly File[] = []) => {
    const kind = file?.type.startsWith('video/') ? 'video' : file?.type.startsWith('audio/') ? 'audio' : 'image';
    const kindMax = capability.maxItemsByKind?.[kind];
    const label = kind === 'image' ? '张图片' : kind === 'video' ? '个视频' : '个音频';
    if (file && kindMax !== undefined && displayFiles.filter(item => (item.kind ?? 'IMAGE').toLowerCase() === kind).length + additions.filter(item => item.type.startsWith(`${kind}/`)).length >= kindMax) return `最多上传 ${kindMax} ${label}`;
    if (capability.maxItems !== null && displayFiles.length + additions.length >= capability.maxItems) return `最多上传 ${capability.maxItems} ${capability.acceptedKinds.length === 1 && capability.acceptedKinds[0] === 'image' ? '张图片' : '个素材'}`;
    return '';
  };
  useEffect(() => {
    if (blocked) setMenuOpen(false);
    if (blocked || busy) setLibraryIndex(null);
  }, [blocked, busy]);
  useEffect(() => {
    if (!menuOpen) return;
    entry.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
  }, [menuOpen]);
  useEffect(() => {
    if (!expanded && !menuOpen) return;
    const dismiss = (event: PointerEvent) => {
      if (!nativePicker.current && libraryIndex === null && !entry.current?.contains(event.target as Node)) close();
    };
    document.addEventListener('pointerdown', dismiss);
    return () => document.removeEventListener('pointerdown', dismiss);
  }, [expanded, menuOpen, libraryIndex]);
  const upload = async (file: File, index: number, fromLibrary = false) => {
    if (disabled || transferBusy || pending.current || index < 0) return false;
    pending.current = true; setLibraryError('');
    let url = '';
    try { url = URL.createObjectURL(file); } catch { /* Keep the file-specific loading placeholder if object URLs are unavailable. */ }
    const preview = { index, file, url, context: selectionContext };
    setUploadPreview(preview); setExpanded(true);
    try {
      const accepted = await onUpload(file, index);
      if (!alive.current || latestContext.current !== selectionContext) return false;
      if (accepted && !fromLibrary) {
        try {
          await importMedia({ id: typeof accepted === 'object' ? accepted.fileId : randomUUID(), ...(typeof accepted === 'object' ? { canvasFileIds: [accepted.fileId], ownerId: review?.owner } : {}), kind: file.type.startsWith('video/') ? 'video' : file.type.startsWith('audio/') ? 'audio' : 'image', name: file.name,
            mimeType: file.type, size: file.size, content: file });
        } catch { setLibraryError('素材已上传，但未保存到个人素材库'); }
      }
      return accepted;
    } finally { pending.current = false; if (alive.current) setUploadPreview(current => current === preview ? null : current); }
  };
  useEffect(() => {
    if (queueContext.current !== selectionContext || disabled || transferBusy || pending.current || queuedUpload.current || !uploadQueue.length) return;
    const queued = uploadQueue[0];
    const index = queued.index ?? nextIndex;
    if (index < 0) return;
    queuedUpload.current = queued;
    void upload(queued.file, index).finally(() => {
      queuedUpload.current = null;
      if (alive.current) setUploadQueue(current => current.filter(item => item !== queued));
    });
  }, [disabled, transferBusy, uploadQueue, nextIndex, selectionContext]);
  const select = (event: ChangeEvent<HTMLInputElement>, index?: number) => {
    const selected = Array.from(event.target.files ?? []); event.target.value = '';
    // The page may become temporarily disabled when focus returns from the OS chooser.
    // Capture only a selection opened in this context; the queue waits for revalidation.
    const returningPicker = nativePicker.current && nativePickerContext.current === selectionContext;
    if (!selected.length || batchBusy || (disabled && !returningPicker)) return;
    if (index !== undefined) {
      queueContext.current = selectionContext;
      setUploadQueue(current => [...current, { file: selected[0], index }]); setExpanded(true); return;
    }
    const accepted: File[] = [];
    for (const file of selected) {
      const issue = countLimit(file, accepted);
      if (issue) showLimit(issue);
      else accepted.push(file);
    }
    if (!accepted.length) return;
    queueContext.current = selectionContext;
    setUploadQueue(current => [...current, ...accepted.map(file => ({ file }))]); setExpanded(true); setMenuOpen(false);
  };
  const chooseFromLibrary = async (ids: readonly string[]) => {
    if (!ids.length || libraryIndex === null || blocked || busy || batchPending.current) return;
    const selected = ids.map(id => [...personalMedia, ...publicMedia].find(item => item.id === id));
    if (selected.some(media => !media || (media.ownerId !== undefined && media.ownerId !== review?.owner) || (!onReuse && !media.content))) { setLibraryError('此素材的本地文件不可用，请重新上传'); return; }
    batchPending.current = true; setBatchBusy(true); setLibraryError(''); review?.onSelecting?.(true);
    let index = libraryIndex;
    try {
      for (const media of selected) {
        if (!alive.current) break;
        if (latestContext.current !== selectionContext) {
          setLibraryError('模型或素材能力已变化，已添加的素材保留，请重新选择剩余素材');
          break;
        }
        while (index < slots.length && (slots[index].file || slots[index].available === false)) index++;
        const sourceFileId = sourceVideos && media!.kind === 'video' ? media!.canvasFileIds?.[0] : undefined;
        const existing: ComposerMaterial | null = sourceFileId ? { fileId: sourceFileId, sourceFileId, name: media!.name,
          preview: media!.previewUrl ?? '', kind: 'VIDEO', sizeBytes: media!.size }
          : sourceVideos && media!.kind === 'video' && media!.content ? null
          : review && onReuse ? await reviewedLibraryMedia(media!) : null;
        if (!alive.current || latestContext.current !== selectionContext) break;
        if (!existing && !media!.content) throw new Error('此素材的本地文件不可用，请重新上传');
        if (existing && media!.origin !== 'public' && media!.id !== existing.fileId && !media!.canvasFileIds?.includes(existing.fileId)
          && alive.current && latestContext.current === selectionContext) {
          const saved = await importMedia({ ...media!, ownerId: review!.owner, canvasFileIds: [...(media!.canvasFileIds ?? []), existing.fileId] });
          if (saved && existing.preview === media!.previewUrl) existing.preview = saved.previewUrl ?? '';
        }
        if (!alive.current || latestContext.current !== selectionContext) break;
        const accepted = existing ? onReuse!(existing, index++) : await upload(new File([media!.content!], media!.name, { type: media!.mimeType }), index++, true);
        if (latestContext.current !== selectionContext) {
          if (alive.current) setLibraryError('模型或素材能力已变化，已添加的素材保留，请重新选择剩余素材');
          break;
        }
        if (!accepted) {
          if (alive.current) setLibraryError('素材添加中断，已添加的素材保留，请检查提示后重新选择剩余素材');
          break;
        }

      }
    } catch (cause) {
      if (alive.current && latestContext.current === selectionContext) setLibraryError(cause instanceof Error ? cause.message : '素材添加失败，已添加的素材保留，请重试剩余素材');
    } finally {
      batchPending.current = false;
      if (alive.current) {
        setBatchBusy(false);
        review?.onSelecting?.(false);
      }
    }
  };
  const sourceMenu = menuOpen && <div role="menu" aria-label={t("素材来源")}>
    <button type="button" role="menuitem" onClick={() => { beginFilePick(); setMenuOpen(false); input.current?.click(); }}><span aria-hidden="true">＋</span>{t(" 本地上传")}</button>
    <button type="button" role="menuitem" disabled={busy} onClick={() => { setMenuOpen(false); setLibraryIndex(nextIndex); }}><svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"><path d="M12 5C9 3 5 3 2 4v15c3-1 7-1 10 1 3-2 7-2 10-1V4c-3-1-7-1-10 1Zm0 0v15" /></svg>{t(" 素材库")}</button>
  </div>;
  return <>
    <div className="composer-main production-composer-main" data-inline-materials={inline}>
      <div className="composer-material-entry media-trigger-wrap" ref={entry}
        onPointerEnter={event => { cancelClose(); if (event.pointerType !== 'touch' && hasMaterials) setExpanded(true); }}
        onPointerLeave={() => {
          cancelClose();
          closeTimer.current = setTimeout(() => {
            if (!nativePicker.current && libraryIndex === null && !pending.current && (!entry.current?.contains(document.activeElement) || document.activeElement === trigger.current)) close();
          }, 180);
        }}
        onFocusCapture={cancelClose}
        onBlurCapture={event => { if (!nativePicker.current && !entry.current?.contains(event.relatedTarget as Node) && libraryIndex === null) close(); }}
        onKeyDown={event => {
          if (event.key === 'Escape' && (expanded || menuOpen)) {
            event.preventDefault(); event.stopPropagation(); trigger.current?.focus(); close();
          }
        }}>
        <button ref={trigger} className={`media-trigger${displayFiles.length && (inline || !expanded) ? ' composer-material-stack' : ''}`} type="button" aria-label={t("添加素材")}
          aria-haspopup="menu" aria-controls={hasMaterials && !inline ? panelId : undefined} aria-expanded={inline ? menuOpen : expanded || menuOpen}
          disabled={!hasMaterials && (disabled || capability.maxItems === 0)} onFocus={() => { if (hasMaterials) setExpanded(true); }}
          onClick={() => { if (hasMaterials) setExpanded(true); if (!blocked) { const issue = countLimit(); if (issue) { setMenuOpen(false); showLimit(issue); } else setMenuOpen(value => !value); } }}>
          {displayFiles.length && (inline || !expanded) ? <><span className="composer-stack-images">{displayFiles.slice(-3).map((file, index) => file.preview && (!file.kind || file.kind === 'IMAGE')
            ? <LoadingMedia key={file.fileId} kind="image" src={file.preview} label={file.name} busy={displaySlots.some(slot => slot.uploading && slot.file === file) || isReviewing(file)} busyLabel={displaySlots.some(slot => slot.uploading && slot.file === file) ? '上传中' : '审核中'} style={{ '--stack-index': index } as CSSProperties} />
            : <span className="composer-stack-placeholder" key={file.fileId} style={{ '--stack-index': index } as CSSProperties}><span>{file.kind === 'VIDEO' ? t('视频') : file.kind === 'AUDIO' ? t('音频') : <ComposerIcon name="image" />}</span>{(displaySlots.some(slot => slot.uploading && slot.file === file) || isReviewing(file)) && <MediaSpinner label={formatMessage("{0} {1}", file.name, t(displaySlots.some(slot => slot.uploading && slot.file === file) ? '上传中' : '审核中'))} />}</span>)}</span>
            <span className="composer-stack-add" aria-hidden="true">＋</span><span className="composer-stack-count">{displayFiles.length}</span></>
            : <><span aria-hidden="true">＋</span><span>{t("素材")}</span></>}
        </button>
        {sourceMenu}
        <input ref={input} hidden type="file" multiple={capability.maxItems !== 1} aria-label={t("上传素材")} accept={capability.acceptedMimeTypes.join(',')} disabled={!filePickerOpen && (blocked || nextIndex < 0)}
          onClick={beginFilePick} onChange={event => { select(event); finishFilePick(); }} />
        {hint && <small>{t(hint)}</small>}
        <div id={panelId} className="composer-material-popover" role={inline ? 'group' : 'dialog'} aria-label={t("参考素材")} hidden={(!inline && !expanded) || !hasMaterials}>
          <div className="composer-materials" role="group" aria-label={t("已添加素材（按提交顺序）")}>{displaySlots.map(({ label, file, issue, uploading: fileUploading }, index) => { if (!file) return null; const fileBusy = fileUploading || isReviewing(file); const busyLabel = `${file.name} ${fileUploading ? '上传中' : '审核中'}`; return <div className="composer-material" aria-busy={Boolean(fileBusy)} data-incompatible={Boolean(issue && issue !== '审核中')} key={index}>
            {file?.preview ? <span className={file.kind && file.kind !== 'IMAGE' ? 'composer-material-media' : undefined}>{file.kind === 'VIDEO' ? <LoadingMedia kind="video" src={file.preview} label={file.name} busy={fileBusy} busyLabel={fileUploading ? '上传中' : '审核中'} controls /> : file.kind === 'AUDIO' ? <audio src={file.preview} aria-label={file.name} controls preload="metadata" /> : <a href={file.preview} target="_blank" rel="noreferrer" aria-label={formatMessage("预览{0}", t(label))}><LoadingMedia kind="image" src={file.preview} label={file.name} busy={fileBusy} busyLabel={fileUploading ? '上传中' : '审核中'} /></a>}</span>
              : <span className="composer-material-preview-empty"><ComposerIcon name="image" />{fileBusy && <MediaSpinner label={t(busyLabel)} />}</span>}
            {fileBusy && file.kind === 'AUDIO' && file.preview && <MediaSpinner label={t(busyLabel)} />}
            <div className="composer-material-info" data-approved={file.reviewStatus === 'ACTIVE'}><strong>{t(label)}</strong>{file.reviewStatus !== 'ACTIVE' && <span title={file.name}>{file.name}</span>}
              {file && !file.preview && !fileBusy && <small>{t("预览暂不可用")}</small>}
              {file?.portraitId && file.reviewStatus !== 'ACTIVE' && <small>{t("已审核人像")}</small>}
              {issue && issue !== '审核中' && <small role="alert">{t(issue)}</small>}
            </div>
            <label className="composer-material-upload">{t(file ? '替换' : '＋ 添加')}<input type="file" aria-label={formatMessage("{0}{1}", t(file ? '替换' : '上传'), t(label))} accept={capability.acceptedMimeTypes.join(',')} disabled={!filePickerOpen && (disabled || busy)}
              onClick={beginFilePick} onChange={event => { select(event, index); finishFilePick(); }} /></label>
            {file && <button type="button" className="composer-material-remove" aria-label={formatMessage("移除{0}", t(label))} disabled={disabled || busy} onClick={() => onRemove(index)}>×</button>}
          </div>; })}</div>
        </div>
      </div>
      {children}
    </div>
    {review && files.length > 0 && review.model && <PortraitLibraryDialog key={`${review.owner}:${review.model}`} owner={review.owner} model={review.model} visible={false} disabled={disabled}
      initialMaterial={files.find(file => !file.reviewId && !file.portraitId && (!file.reviewStatus || file.reviewStatus === 'SUBMITTING'))}
      canvas={{ materials: files.map(file => ({ ...file, reviewId: file.reviewId ?? file.portraitId })), onUploaded: () => {}, onReview: review.onReview }} onClose={() => {}} onPick={() => {}} />}
    {slots.some(slot => slot.issue && slot.issue !== '审核中' && slot.file) && !expanded && <p className="composer-upload-error" role="alert">{t(review && files.some(file => file.reviewStatus !== 'ACTIVE') ? '素材尚未全部审核通过' : '素材与当前设置不兼容，请展开素材处理。')}</p>}
    {libraryIndex !== null && createPortal(<MediaLibraryDialog open capability={libraryCapability} selectionMode={libraryCapability.maxItems === 1 ? 'single' : 'multiple'}
      initialSource="personal" initialSelection={[]} onConfirm={chooseFromLibrary}
      onClose={() => { setLibraryIndex(null); trigger.current?.focus(); }} />, document.body)}
    {limitNotice && createPortal(<div className="composer-limit-toast" role="alert"><span aria-hidden="true">!</span>{t(limitNotice)}</div>, document.body)}
    {libraryError && <p className="composer-upload-error" role="alert">{t(libraryError)}</p>}
    {error && <p className="composer-upload-error" role="alert">{t(error)}</p>}
  </>;
}
