import { t, useLanguage } from '../i18n';
import { useEffect, useRef, type KeyboardEvent } from "react";

import type { PendingCreatorSwitch } from "./types";

export type IncompatibleModelDialogProps = {
  pending: PendingCreatorSwitch | null;
  title: string;
  onCancel(): void;
  onConfirm(): void;
};

const focusableSelector = "button:not([disabled])";

export function IncompatibleModelDialog({ pending, title, onCancel, onConfirm }: IncompatibleModelDialogProps) {
  useLanguage();
  const dialogRef = useRef<HTMLDivElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!pending) return;
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialogRef.current?.querySelector<HTMLElement>(focusableSelector)?.focus();
    return () => returnFocusRef.current?.focus();
  }, [pending]);

  if (!pending) return null;

  const trapFocus = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key === "Escape") {
      event.preventDefault();
      onCancel();
      return;
    }
    if (event.key !== "Tab") return;
    const focusable = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(focusableSelector) ?? []);
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (!first || !last) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return <div className="incompatible-model-backdrop">
    <div
      ref={dialogRef}
      className="incompatible-model-dialog"
      role="dialog"
      aria-modal="true"
      aria-label={t(title)}
      onKeyDown={trapFocus}
    >
      <h2>{t(title)}</h2>
      <p>{t("这次切换会调整当前草稿，请确认以下变化：")}</p>
      {pending.removedMedia.length > 0 && <section><h3>{t("移除素材")}</h3><ul>{pending.removedMedia.map((item) => <li key={item.id}>{item.name}</li>)}</ul></section>}
      {pending.resetParameters.length > 0 && <section><h3>{t("重置参数")}</h3><ul>{pending.resetParameters.map((item) => <li key={item}>{t(item)}</li>)}</ul></section>}
      <footer><button type="button" onClick={onCancel}>{t("取消切换")}</button><button type="button" onClick={onConfirm}>{t("确认切换")}</button></footer>
    </div>
  </div>;
}
