import { useEffect, useRef, useState } from 'react';

export const toolCategories = ['热门推荐', '商品', '图片', '视频'] as const;
export const inspirationIndustries = ['全部', '美妆护肤', '服饰穿搭', '家居生活', '3C数码', '食品饮品', '珠宝腕表'] as const;
const storageKey = 'xzwl:commerce-browse:v1';
type BrowseState = { kind: 'image' | 'video'; category: string; industry: string; active: number; scrollY: number };
const defaults: BrowseState = { kind: 'image', category: '热门推荐', industry: '全部', active: 2, scrollY: 0 };

function readState(): BrowseState {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null') as Partial<BrowseState> | null;
    if (!saved) return defaults;
    const category = toolCategories.find(value => value === saved.category) ?? defaults.category;
    const count = category === '热门推荐' ? 8 : category === '商品' ? 7 : 2;
    return {
      kind: saved.kind === 'video' ? 'video' : 'image', category,
      industry: inspirationIndustries.find(value => value === saved.industry) ?? defaults.industry,
      active: Number.isInteger(saved.active) && saved.active! >= 0 && saved.active! < count ? saved.active! : 0,
      scrollY: typeof saved.scrollY === 'number' && Number.isFinite(saved.scrollY) && saved.scrollY >= 0 ? saved.scrollY : 0,
    };
  } catch { return defaults; }
}

/** Tab-local browsing preferences only; prompts and account data have their own storage. */
export function useCommerceBrowseState() {
  const [state, setState] = useState(readState);
  const current = useRef(state);
  current.current = state;
  const initialScroll = useRef(state.scrollY);
  useEffect(() => {
    // UserFrame resets scroll during layout. Restore after it, once the grid is mounted.
    const frame = requestAnimationFrame(() => {
      if (initialScroll.current > 0) window.scrollTo({ top: initialScroll.current, behavior: 'instant' });
    });
    const save = () => {
      try { sessionStorage.setItem(storageKey, JSON.stringify({ ...current.current, scrollY: window.scrollY })); }
      catch { /* Browsing remains usable when storage is unavailable. */ }
    };
    window.addEventListener('scroll', save, { passive: true });
    window.addEventListener('pagehide', save);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener('scroll', save);
      window.removeEventListener('pagehide', save);
      // Use the last observed position; the destination may already have reset window scroll.
    };
  }, []);
  useEffect(() => {
    try {
      const previous = readState();
      sessionStorage.setItem(storageKey, JSON.stringify({ ...state, scrollY: previous.scrollY }));
    } catch { /* Preferences are optional. */ }
  }, [state]);
  return [state, (patch: Partial<BrowseState>) => setState(previous => ({ ...previous, ...patch }))] as const;
}
