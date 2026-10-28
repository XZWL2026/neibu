import { t, useLanguage } from '../i18n';
import { useEffect, useRef, type KeyboardEvent } from "react";

import { getCapabilityState } from "./modelCapabilities";
import type { CreatorModelId } from "./types";

export type CreatorSettingsPopoverProps = {
  open: boolean;
  kind: "image" | "video";
  currentModel: CreatorModelId;
  onSelectModel(model: CreatorModelId): void;
  onClose(): void;
};

const focusableSelector = "button:not([disabled]),[tabindex]:not([tabindex='-1'])";

export function CreatorSettingsPopover({
  open,
  kind,
  currentModel,
  onSelectModel,
  onClose,
}: CreatorSettingsPopoverProps) {
  useLanguage();
  const layerRef = useRef<HTMLDivElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) return;
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    layerRef.current?.querySelector<HTMLElement>(focusableSelector)?.focus();
    return () => returnFocusRef.current?.focus();
  }, [open]);

  if (!open) return null;

  const models: readonly CreatorModelId[] = kind === "image"
    ? ["gpt-image-2", "Nano Banana Pro"]
    : ["seedance-2.5", "Kling 2.1"];
  const labels: Readonly<Record<CreatorModelId, string>> = {
    "gpt-image-2": "GPT Image 2",
    "seedance-2.5": "Seedance 2.5",
    "Nano Banana Pro": "Nano Banana Pro",
    "Kling 2.1": "Kling 2.1",
  };

  const trapFocus = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== "Tab") return;
    const focusable = Array.from(layerRef.current?.querySelectorAll<HTMLElement>(focusableSelector) ?? []);
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
    ref={layerRef}
    className="creator-settings-popover"
    role="dialog"
    aria-modal="true"
    aria-label={t("创作设置")}
    onKeyDown={trapFocus}
  >
    <header><h2>{t("创作设置")}</h2><button type="button" aria-label={t("关闭创作设置")} onClick={onClose}>×</button></header>
    <fieldset>
      <legend>{t("本地已验证模型")}</legend>
      {models.map((model) => {
        const capability = getCapabilityState(model);
        return <div className="creator-model-setting" key={model}>
          <button
            type="button"
            aria-pressed={currentModel === model}
            disabled={!capability.available}
            onClick={() => onSelectModel(model)}
          >{t(labels[model])}</button>
          {!capability.available && <span>{t("本地能力未验证")}</span>}
        </div>;
      })}
    </fieldset>
    {kind === "image" && currentModel === "gpt-image-2" && <p>{t("本地估算价格键：fixture-1k")}</p>}
    <p>{t("这里只显示当前仓库已验证的本地事实。")}</p>
  </div>;
}
