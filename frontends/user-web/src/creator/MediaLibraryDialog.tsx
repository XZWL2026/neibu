import { t, formatMessage, useLanguage } from '../i18n';
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";

import { useOptionalUserSession } from "../userSession";
import { useCreatorMedia } from "./CreatorMediaProvider";
import { CreatorMediaPreview } from "./CreatorMediaPreview";
import type { CreatorMedia, MediaInputCapability, MediaKind } from "./types";

export type MediaLibraryDialogProps = {
  open: boolean;
  capability: MediaInputCapability;
  selectionMode: "single" | "multiple";
  initialSelection: readonly string[];
  initialSource?: "public" | "personal";
  onConfirm(ids: readonly string[]): void;
  onClose(): void;
  onPersonalMediaRemoved?(id: string): void;
};

const kindLabels: Readonly<Record<MediaKind, string>> = {
  image: "图片",
  video: "视频",
  audio: "音频",
};

const kinds: readonly MediaKind[] = ["image", "video", "audio"];

const focusableSelector = "button:not([disabled]):not([tabindex='-1']),input:not([disabled]):not([tabindex='-1']),[tabindex]:not([tabindex='-1']):not([disabled])";

export function MediaLibraryDialog({
  open,
  capability,
  selectionMode,
  initialSelection,
  initialSource = "public",
  onConfirm,
  onClose,
  onPersonalMediaRemoved,
}: MediaLibraryDialogProps) {
  useLanguage();
  const { publicMedia, personalMedia: storedPersonal, removeMedia, notice, refreshMedia } = useCreatorMedia();
  const account = useOptionalUserSession();
  const settledOwner = useRef(account?.userId ?? null);
  if (account?.status !== 'loading') settledOwner.current = account?.userId ?? null;
  const owner = account?.status === 'loading' ? settledOwner.current : account?.userId ?? null;
  const personalMedia = useMemo(() => storedPersonal.filter(item => item.ownerId === undefined || item.ownerId === owner), [storedPersonal, owner]);
  useEffect(() => { if (open) void refreshMedia(); }, [open, refreshMedia]);
  const [source, setSource] = useState<"public" | "personal">("public");
  const [kind, setKind] = useState<MediaKind>("image");
  const [selectionMessage, setSelectionMessage] = useState("");
  const [deleting, setDeleting] = useState<readonly string[]>([]);
  const [draft, setDraft] = useState<readonly string[]>(initialSelection);
  const [activeOptionId, setActiveOptionId] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const mountedRef = useRef(true);
  const panelId = useId();
  const sourceTabId = `${panelId}-${source}`;
  const kindTabId = `${panelId}-${kind}`;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (!open) return;
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setSource(initialSource);
    setKind("image");
    setSelectionMessage("");
    setDraft([...initialSelection]);
    dialogRef.current?.querySelector<HTMLElement>(focusableSelector)?.focus();
    return () => {
      returnFocusRef.current?.focus();
    };
  }, [open]);

  const visibleMedia = useMemo(() => {
    const collection = source === "public" ? publicMedia : personalMedia;
    return collection.filter((item) => item.kind === kind);
  }, [source, publicMedia, personalMedia, kind]);
  const unavailableReason = (item: CreatorMedia): string => {
    if (!capability.verified || !capability.acceptedSources.includes("library") || !capability.acceptedKinds.includes(item.kind)) return `当前模型或槽位不支持${kindLabels[item.kind]}`;
    if (!capability.acceptedMimeTypes.includes(item.mimeType)) return "当前模型不支持此文件格式";
    if (item.size > (capability.maxFileBytesByKind?.[item.kind] ?? capability.maxFileBytes)) return "素材大小超过当前模型限制";
    if (capability.maxItemsByKind?.[item.kind] === 0) return `当前${kindLabels[item.kind]}已达数量上限`;
    return "";
  };
  const limit = selectionMode === "single" ? 1 : capability.maxItems;


  useEffect(() => {
    if (!open) return;
    setActiveOptionId((current) => visibleMedia.some((item) => item.id === current)
      ? current
      : visibleMedia[0]?.id ?? null);
  }, [open, visibleMedia]);

  if (!open) return null;

  const toggle = (item: CreatorMedia): void => {
    const reason = unavailableReason(item);
    if (reason) { setSelectionMessage(reason); return; }
    if (deleting.includes(item.id)) return;
    setSelectionMessage("");
    if (!draft.includes(item.id) && limit !== null && (limit <= 0 || (selectionMode === "multiple" && draft.length >= limit))) {
      setSelectionMessage(`最多选择 ${limit} 项素材`);
      return;
    }
    const kindLimit = capability.maxItemsByKind?.[item.kind];
    const collection = [...publicMedia, ...personalMedia];
    if (!draft.includes(item.id) && selectionMode === "multiple" && kindLimit !== undefined
      && draft.filter(id => collection.find(media => media.id === id)?.kind === item.kind).length >= kindLimit) {
      setSelectionMessage(`${kindLabels[item.kind]}最多选择 ${kindLimit} 项`);
      return;
    }
    setDraft((current) => {
      if (selectionMode === "single") return current.includes(item.id) ? [] : [item.id];
      return current.includes(item.id) ? current.filter((id) => id !== item.id) : [...current, item.id];
    });
  };

  const moveOptionFocus = (target: HTMLElement, direction: 1 | -1): void => {
    const options = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>("[role='option']") ?? []);
    const index = options.indexOf(target);
    if (index < 0 || options.length === 0) return;
    const next = options[(index + direction + options.length) % options.length];
    if (!next) return;
    setActiveOptionId(next.dataset.optionId ?? null);
    next.focus();
  };

  const deletePersonalMedia = async (id: string): Promise<void> => {
    if (deleting.includes(id)) return;
    setDeleting((current) => [...current, id]);
    try {
      const removed = await removeMedia(id);
      if (!removed || !mountedRef.current) return;
      setDraft((current) => current.filter((selectedId) => selectedId !== id));
      onPersonalMediaRemoved?.(id);
    } catch {
      setSelectionMessage("删除失败，请重试");
    } finally {
      if (mountedRef.current) setDeleting((current) => current.filter((item) => item !== id));
    }
  };

  const moveTab = <Value extends string>(
    event: KeyboardEvent<HTMLButtonElement>,
    values: readonly Value[],
    current: Value,
    update: (next: Value) => void,
  ): void => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const index = values.indexOf(current);
    const nextIndex = event.key === "Home"
      ? 0
      : event.key === "End"
        ? values.length - 1
        : (index + (event.key === "ArrowRight" ? 1 : -1) + values.length) % values.length;
    const next = values[nextIndex];
    if (next === undefined) return;
    update(next);
    const tabs = Array.from(event.currentTarget.parentElement?.querySelectorAll<HTMLElement>("[role='tab']") ?? []);
    tabs[nextIndex]?.focus();
  };

  const handleDialogKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== "Tab") return;
    const focusable = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(focusableSelector) ?? []);
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return <div
    ref={dialogRef}
    className="media-library-dialog"
    role="dialog"
    aria-modal="true"
    aria-label={t("素材库")}
    onKeyDown={handleDialogKeyDown}
  >
    <header className="media-library-header">
      <div role="tablist" aria-label={t("素材类型")} className="media-library-kinds">
        {kinds.map((value) => <button id={`${panelId}-${value}`} key={value} type="button" role="tab"
          aria-controls={panelId} aria-selected={kind === value} tabIndex={kind === value ? 0 : -1}
          onClick={() => { setKind(value); setSelectionMessage(""); }}
          onKeyDown={(event) => moveTab(event, kinds, kind, setKind)}>{t(kindLabels[value])}</button>)}
      </div>
      <div className="media-library-actions">
        <span aria-live="polite">{formatMessage("已选：{0}", draft.length)}</span>
        <button type="button" className="media-library-confirm" disabled={deleting.length > 0}
          onClick={() => {
            const collection = [...publicMedia, ...personalMedia];
            if ((limit !== null && draft.length > limit) || kinds.some(kind => {
              const maximum = capability.maxItemsByKind?.[kind];
              return maximum !== undefined && draft.filter(id => collection.find(item => item.id === id)?.kind === kind).length > maximum;
            }) || draft.some(id => {
              const item = collection.find(media => media.id === id);
              return !item || unavailableReason(item);
            })) { setSelectionMessage("所选素材与当前模型或槽位不兼容，请重新选择"); return; }
            onConfirm(draft); onClose();
          }}>{t("确定")}</button>
        <button type="button" className="media-library-close" aria-label={t("关闭素材库")} onClick={onClose}>×</button>
      </div>
    </header>
    <div role="tablist" aria-label={t("素材库范围")} className="media-library-sources">
      {(["public", "personal"] as const).map((value) => <button
        id={`${panelId}-${value}`} key={value} type="button" role="tab" aria-controls={panelId}
        aria-selected={source === value} tabIndex={source === value ? 0 : -1}
        onClick={() => setSource(value)}
        onKeyDown={(event) => moveTab(event, ["public", "personal"], source, setSource)}
      >{t(value === "public" ? "公开" : "个人")}</button>)}
    </div>
    <p className="media-library-hint">{t(!capability.acceptedKinds.includes(kind) || !capability.verified
      ? formatMessage("可浏览{0}，当前模型或槽位不支持用于生成", t(kindLabels[kind]))
      : selectionMode === "single" ? "选择一项回填当前素材槽位" : formatMessage("按选择顺序回填{0}", t(limit === null ? "" : formatMessage("，最多 {0} 项", limit))))}</p>
    {selectionMessage && <p role="status">{t(selectionMessage)}</p>}
    {notice && <p role={notice.kind === "write-failed" ? "alert" : "status"}>{t(notice.message)}</p>}
    <div id={panelId} role="tabpanel" aria-labelledby={`${sourceTabId} ${kindTabId}`}>
      <div role="listbox" aria-label={formatMessage("{0}素材", t(source === "public" ? "公开" : "个人"))}
        aria-multiselectable={selectionMode === "multiple"}
        aria-owns={visibleMedia.map((_, index) => `${panelId}-option-${index}`).join(" ") || undefined} />
      <div className="media-library-grid">
        {visibleMedia.map((item, index) => <div className="media-library-card" key={item.id}>
          <div id={`${panelId}-option-${index}`} className="media-library-item" data-option-id={item.id}
            role="option" aria-label={item.name} title={item.name}
            aria-description={t(unavailableReason(item) || undefined)}
            aria-disabled={Boolean(unavailableReason(item))}
            tabIndex={activeOptionId === item.id || (!activeOptionId && visibleMedia[0]?.id === item.id) ? 0 : -1}
            aria-selected={draft.includes(item.id)}
            onClick={() => { setActiveOptionId(item.id); toggle(item); }}
            onFocus={() => setActiveOptionId(item.id)}
            onKeyDown={(event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault(); toggle(item);
              } else if (event.key === "ArrowDown" || event.key === "ArrowRight") {
                event.preventDefault(); moveOptionFocus(event.currentTarget, 1);
              } else if (event.key === "ArrowUp" || event.key === "ArrowLeft") {
                event.preventDefault(); moveOptionFocus(event.currentTarget, -1);
              }
            }}>
            <CreatorMediaPreview media={item} thumbnail />
            {draft.includes(item.id) && <span className="media-library-check" aria-hidden="true">✓</span>}
            {unavailableReason(item) && <span className="media-library-unavailable">{t(unavailableReason(item))}</span>}
          </div>
          {source === "personal" && <button type="button" className="media-library-delete"
            aria-label={formatMessage("删除 {0}", item.name)} disabled={deleting.includes(item.id)}
            onClick={(event) => { event.stopPropagation(); void deletePersonalMedia(item.id); }}>
            <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7">
              <path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 10v7m4-7v7" />
            </svg>
          </button>}
        </div>)}
      </div>
      {visibleMedia.length === 0 && <p>{t("没有匹配的素材")}</p>}
    </div>
  </div>;
}
