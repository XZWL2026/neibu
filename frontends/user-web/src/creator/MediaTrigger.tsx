import { t, formatMessage, useLanguage } from '../i18n';
import { useEffect, useRef, useState, type DragEvent } from "react";

import { useCreatorMedia } from "./CreatorMediaProvider";
import { MediaLibraryDialog } from "./MediaLibraryDialog";
import { validateMediaSelection } from "./modelCapabilities";
import type { CreatorMedia, MediaInputCapability, MediaSelectionSource } from "./types";

export type MediaTriggerProps = {
  capability: MediaInputCapability;
  selected: readonly CreatorMedia[];
  label: string;
  selectionMode: "single" | "multiple";
  openLibrarySignal?: number;
  createMediaId?: () => string;
  onValidated(next: readonly CreatorMedia[]): void;
  onOpenLibrary(): void;
};

const defaultMediaId = (): string => {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return `local-media-${globalThis.crypto.randomUUID()}`;
  }
  const random = new Uint32Array(4);
  if (typeof globalThis.crypto?.getRandomValues === "function") {
    globalThis.crypto.getRandomValues(random);
  } else {
    random.forEach((_, index) => { random[index] = Math.floor(Math.random() * 0x1_0000_0000); });
  }
  return `local-media-${Date.now().toString(36)}-${Array.from(random, (value) => value.toString(36)).join("-")}`;
};

const kindForFile = (file: File): CreatorMedia["kind"] => {
  if (file.type.startsWith("video/")) return "video";
  if (file.type.startsWith("audio/")) return "audio";
  return "image";
};

const mediaForFile = (file: File, createMediaId: () => string): CreatorMedia => ({
  id: createMediaId(),
  kind: kindForFile(file),
  name: file.name,
  mimeType: file.type,
  size: file.size,
  content: file,
  origin: "personal",
});

const isFileDragPayload = (dataTransfer: DataTransfer): boolean =>
  Array.from(dataTransfer.types ?? []).includes("Files")
  || Array.from(dataTransfer.items ?? []).some((item) => item.kind === "file")
  || (dataTransfer.files?.length ?? 0) > 0;

export function MediaTrigger({
  capability,
  selected,
  label,
  selectionMode,
  openLibrarySignal,
  createMediaId = defaultMediaId,
  onValidated,
  onOpenLibrary,
}: MediaTriggerProps) {
  useLanguage();
  const { publicMedia, personalMedia, importMedia, notice } = useCreatorMedia();
  const [menuOpen, setMenuOpen] = useState(false);
  const [libraryOpen, setLibraryOpen] = useState(false);
  const [dragActive, setDragActive] = useState(false);
  const [validationMessage, setValidationMessage] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  const dragDepthRef = useRef(0);
  const previousOpenLibrarySignal = useRef(openLibrarySignal);
  const selectedRef = useRef(selected);
  const mountedRef = useRef(true);
  const deliveryQueueRef = useRef<Promise<void>>(Promise.resolve());
  selectedRef.current = selected;
  const effectiveCapability = selectionMode === "single"
    ? { ...capability, maxItems: 1 }
    : capability;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (openLibrarySignal === undefined || previousOpenLibrarySignal.current === openLibrarySignal) return;
    previousOpenLibrarySignal.current = openLibrarySignal;
    setMenuOpen(false);
    setLibraryOpen(true);
    onOpenLibrary();
  }, [onOpenLibrary, openLibrarySignal]);

  const deliverNewMedia = async (source: MediaSelectionSource, incoming: readonly CreatorMedia[]): Promise<void> => {
    const currentSelection = selectedRef.current;
    const imported: CreatorMedia[] = [];
    try {
      const validation = await validateMediaSelection({
        source,
        current: selectionMode === "single" ? [] : currentSelection,
        incoming,
        capability: effectiveCapability,
        price: "1.0000",
        persist: async (media) => {
          for (const item of media) {
            if (!mountedRef.current) return;
            const stored = await importMedia(item);
            if (!stored) throw new Error("media session changed before import completed");
            imported.push(stored);
          }
        },
      });
      if (!mountedRef.current) return;
      if (!validation.valid) {
        setValidationMessage(validation.message);
        return;
      }
      const additions = imported;
      const latestSelection = selectedRef.current;
      const nextSelection = selectionMode === "single"
        ? additions
        : [...latestSelection.filter((item) => !additions.some((next) => next.id === item.id)), ...additions];
      selectedRef.current = nextSelection;
      onValidated(nextSelection);
      setValidationMessage("");
      setMenuOpen(false);
      setLibraryOpen(false);
    } catch {
      if (mountedRef.current) setValidationMessage("素材处理失败，请保留原选择后重试");
    }
  };

  const deliverLibrarySelection = async (ids: readonly string[]): Promise<void> => {
    const finalSelection = ids.flatMap((id) => {
      const item = allLibraryMedia.find((media) => media.id === id);
      return item ? [item] : [];
    });
    if (finalSelection.length === 0) {
      if (!mountedRef.current) return;
      selectedRef.current = [];
      onValidated([]);
      setValidationMessage("");
      setLibraryOpen(false);
      return;
    }
    try {
      const validation = await validateMediaSelection({
        source: "library",
        current: [],
        incoming: finalSelection,
        capability: effectiveCapability,
        price: "1.0000",
        persist: async () => undefined,
      });
      if (!mountedRef.current) return;
      if (!validation.valid) {
        setValidationMessage(validation.message);
        return;
      }
      selectedRef.current = finalSelection;
      onValidated(finalSelection);
      setValidationMessage("");
      setLibraryOpen(false);
    } catch {
      if (mountedRef.current) setValidationMessage("素材处理失败，请保留原选择后重试");
    }
  };

  const enqueueDelivery = (operation: () => Promise<void>): void => {
    deliveryQueueRef.current = deliveryQueueRef.current
      .then(async () => {
        if (mountedRef.current) await operation();
      })
      .catch(() => undefined);
  };

  const onDrop = (event: DragEvent<HTMLButtonElement>): void => {
    if (!isFileDragPayload(event.dataTransfer)) return;
    event.preventDefault();
    dragDepthRef.current = 0;
    setDragActive(false);
    const incoming = Array.from(event.dataTransfer.files).map((file) => mediaForFile(file, createMediaId));
    enqueueDelivery(() => deliverNewMedia("drop", incoming));
  };

  const allLibraryMedia = [...publicMedia, ...personalMedia];

  return <div className="media-trigger-wrap">
    <button
      className={`media-trigger${dragActive ? " drag-active" : ""}`}
      type="button"
      aria-label={t(label)}
      aria-expanded={menuOpen}
      onClick={() => setMenuOpen((open) => !open)}
      onDragEnter={(event) => {
        if (!isFileDragPayload(event.dataTransfer)) return;
        event.preventDefault();
        dragDepthRef.current += 1;
        setDragActive(true);
      }}
      onDragOver={(event) => {
        if (isFileDragPayload(event.dataTransfer)) event.preventDefault();
      }}
      onDragLeave={(event) => {
        if (!isFileDragPayload(event.dataTransfer)) return;
        event.preventDefault();
        dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
        if (dragDepthRef.current === 0) setDragActive(false);
      }}
      onDrop={onDrop}
    >＋<span>{t("素材")}</span></button>
    {menuOpen && <div role="menu" aria-label={t("素材来源")}>
      <button type="button" role="menuitem" onClick={() => inputRef.current?.click()}>{t("本地上传")}</button>
      <button type="button" role="menuitem" onClick={() => { setLibraryOpen(true); onOpenLibrary(); }}>{t("素材库")}</button>
    </div>}
    <input
      ref={inputRef}
      className="sr-only"
      aria-label={formatMessage("{0}文件", t(label))}
      type="file"
      multiple={selectionMode === "multiple"}
      accept={capability.acceptedMimeTypes.join(",")}
      onChange={(event) => {
        const incoming = Array.from(event.currentTarget.files ?? []).map((file) => mediaForFile(file, createMediaId));
        enqueueDelivery(() => deliverNewMedia("upload", incoming));
        event.currentTarget.value = "";
      }}
    />
    {validationMessage && <p role="alert">{t(validationMessage)}</p>}
    {notice && <p role={notice.kind === "validation" ? "alert" : "status"}>{t(notice.message)}</p>}
    <MediaLibraryDialog
      open={libraryOpen}
      capability={capability}
      selectionMode={selectionMode}
      initialSelection={selected.map((item) => item.id)}
      onConfirm={(ids) => {
        enqueueDelivery(() => deliverLibrarySelection(ids));
      }}
      onPersonalMediaRemoved={(id) => {
        const nextSelection = selectedRef.current.filter((item) => item.id !== id);
        selectedRef.current = nextSelection;
        if (mountedRef.current) onValidated(nextSelection);
      }}
      onClose={() => setLibraryOpen(false)}
    />
  </div>;
}
