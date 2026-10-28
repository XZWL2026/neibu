import { t, formatMessage, useLanguage } from '../i18n';
import { useLayoutEffect, useRef } from "react";
import { CreatorMediaPreview } from "./CreatorMediaPreview";
import type { CreatorMedia } from "./types";

export type MediaQueueProps = {
  mode: "first-last" | "omni";
  items: readonly (CreatorMedia | null)[];
  onRemove(id: string): void;
  onManageOverflow(): void;
};

function prefersReducedMotion() {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export function MediaQueue({ mode, items, onRemove, onManageOverflow }: MediaQueueProps) {
  useLanguage();
  const itemNodes = useRef(new Map<string, HTMLElement>());
  const previousPositions = useRef(new Map<string, DOMRect>());
  const removingItems = useRef(new Set<string>());
  const removalAnimations = useRef(new Map<string, Animation>());
  const activeAnimations = useRef(new Set<Animation>());
  const mounted = useRef(true);

  useLayoutEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      for (const animation of activeAnimations.current) {
        if (typeof animation.cancel === "function") animation.cancel();
      }
      activeAnimations.current.clear();
      removalAnimations.current.clear();
      removingItems.current.clear();
    };
  }, []);

  useLayoutEffect(() => {
    const currentPositions = new Map<string, DOMRect>();
    for (const [id, node] of itemNodes.current) {
      const currentPosition = node.getBoundingClientRect();
      const previousPosition = previousPositions.current.get(id);
      const deltaX = previousPosition ? previousPosition.left - currentPosition.left : 0;
      const deltaY = previousPosition ? previousPosition.top - currentPosition.top : 0;
      if (!prefersReducedMotion() && previousPosition && (deltaX !== 0 || deltaY !== 0) && typeof node.animate === "function") {
        const animation = node.animate([
          { transform: `translate(${deltaX}px, ${deltaY}px)` },
          { transform: "translate(0, 0)" },
        ], { duration: 240, easing: "ease-out" });
        activeAnimations.current.add(animation);
        const clearAnimation = () => activeAnimations.current.delete(animation);
        void animation.finished.then(clearAnimation, clearAnimation);
      }
      currentPositions.set(id, currentPosition);
    }
    previousPositions.current = currentPositions;
  });

  const setItemNode = (id: string, node: HTMLElement | null) => {
    if (node) itemNodes.current.set(id, node);
    else itemNodes.current.delete(id);
  };

  const requestRemove = (id: string) => {
    if (removingItems.current.has(id)) return;
    const node = itemNodes.current.get(id);
    if (prefersReducedMotion() || !node || typeof node.animate !== "function") {
      onRemove(id);
      return;
    }

    removingItems.current.add(id);
    const animation = node.animate([
      { opacity: 1, transform: "translateX(0)" },
      { opacity: 0, transform: "translateX(-8px)" },
    ], { duration: 240, easing: "ease-in" });
    activeAnimations.current.add(animation);
    removalAnimations.current.set(id, animation);
    const finishRemoval = () => {
      activeAnimations.current.delete(animation);
      if (removalAnimations.current.get(id) !== animation) return;
      removalAnimations.current.delete(id);
      if (!removingItems.current.delete(id)) return;
      if (mounted.current) onRemove(id);
    };
    void animation.finished.then(finishRemoval, finishRemoval);
  };

  if (mode === "first-last") {
    const slots = [items[0], items[1]] as const;
    return <div className="media-queue media-queue-slots" role="group" aria-label={t("首尾帧素材")}>
      {slots.map((item, index) => <div className="media-slot" key={item?.id ?? `empty-${index}`}>
        {item ? <div ref={(node) => setItemNode(item.id, node)} role="group" aria-label={formatMessage("已选素材 {0}", item.name)} data-media-id={item.id} data-origin={item.origin} data-preview-url={item.previewUrl} data-storage-mode={item.storageMode}><CreatorMediaPreview media={item} /><button type="button" aria-label={formatMessage("移除 {0}", item.name)} onClick={() => requestRemove(item.id)}>×</button></div> : <span>{t(index === 0 ? "首帧" : "尾帧")}</span>}
      </div>)}
    </div>;
  }

  const allItems = items.filter((item): item is CreatorMedia => item !== null);
  const visible = allItems.slice(0, 4);
  const overflow = allItems.length - visible.length;
  return <div className="media-queue" role="group" aria-label={t("全能参考素材")}>
    {visible.map((item) => <div ref={(node) => setItemNode(item.id, node)} className="media-chip" role="group" aria-label={formatMessage("已选素材 {0}", item.name)} data-media-id={item.id} data-origin={item.origin} data-preview-url={item.previewUrl} data-storage-mode={item.storageMode} key={item.id}>
      <CreatorMediaPreview media={item} />
      <button type="button" aria-label={formatMessage("移除 {0}", item.name)} onClick={() => requestRemove(item.id)}>×</button>
    </div>)}
    {overflow > 0 && <button type="button" aria-label={formatMessage("管理其余 {0} 项素材", overflow)} onClick={(event) => {
      event.currentTarget.focus();
      onManageOverflow();
    }}>+{overflow}</button>}
  </div>;
}
