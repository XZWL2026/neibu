import { useSyncExternalStore } from 'react';
import en from './en.json';
import ko from './ko.json';
import ja from './ja.json';

export const languages = [
  { code: 'zh-CN', name: '简体中文' },
  { code: 'en', name: 'English' },
  { code: 'ko', name: '한국어' },
  { code: 'ja', name: '日本語' },
] as const;
export type Language = typeof languages[number]['code'];
const dictionaries: Record<string, Record<string, string>> = { en, ko, ja };
const preferenceKey = 'yomio-language';
const listeners = new Set<() => void>();
function savedLanguage(): Language {
  try {
    const saved = localStorage.getItem(preferenceKey);
    return languages.find(item => item.code === saved)?.code ?? 'zh-CN';
  } catch { return 'zh-CN'; }
}
let language = savedLanguage();

export function selectLanguage(next: Language) {
  language = next;
  if (typeof document !== 'undefined') {
    document.documentElement.lang = next;
    document.title = t('YOMIO 一站式AI视频创作平台');
  }
  try { localStorage.setItem(preferenceKey, next); }
  catch { /* Switching remains available when browser storage is blocked. */ }
  listeners.forEach(listener => listener());
}
function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
export function useLanguage() {
  return useSyncExternalStore(subscribe, () => language, () => 'zh-CN' as Language);
}
export function restoreLanguage() { selectLanguage(savedLanguage()); }
export function currentLanguage() { return language; }

// Messages are stored in their original language; interpolate only at the display boundary.
const templates = Object.keys(en).filter(key => /\{\d+\}/.test(key)).map(key => {
  const slots: string[] = [];
  const escaped = key.split(/(\{\d+\})/).map(part => {
    if (/^\{\d+\}$/.test(part)) { slots.push(part); return '([\\s\\S]*?)'; }
    return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }).join('');
  return { key, slots, pattern: new RegExp(`^${escaped}$`) };
}).sort((a, b) => b.key.replace(/\{\d+\}/g, '').length - a.key.replace(/\{\d+\}/g, '').length);

export function t<T>(value: T): T {
  if (language === 'zh-CN' || typeof value !== 'string') return value;
  return translate(value, 0) as T;
}
function translate(value: string, depth: number): string {
  const dictionary = dictionaries[language];
  const text = value.replace(/\s+/g, ' ').trim();
  const direct = dictionary?.[text];
  if (direct !== undefined) return value.replace(text, direct);
  if (!/[\u3400-\u9fff]/.test(text) || depth > 3) return value;
  for (const { key, slots, pattern } of templates) {
    const match = pattern.exec(text);
    if (!match || !dictionary[key]) continue;
    const translated = dictionary[key].replace(/\{\d+\}/g, slot => translate(match[slots.indexOf(slot) + 1] ?? slot, depth + 1));
    return translated;
  }
  return value;
}

export function formatMessage(source: string, ...values: unknown[]): string {
  const key = source.replace(/\s+/g, ' ').trim();
  const translated = dictionaries[language]?.[key];
  const template = language === 'zh-CN' || translated === undefined ? source : source.replace(source.trim(), translated);
  return template.replace(/\{(\d+)\}/g, (_, index: string) => String(values[Number(index)] ?? ''));
}
