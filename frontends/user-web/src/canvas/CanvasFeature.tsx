import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { t, useLanguage } from '../i18n';
import { CanvasFeatureBackdrop } from './CanvasFeatureBackdrop';

export type CanvasFeatureProps = {
  unavailable: boolean;
  creating: boolean;
  onCreate(): void;
  children?: ReactNode;
};

function ShortcutIcon({ kind }: { kind: 'blank' | 'image' | 'video' | 'commerce' }) {
  const shapes = {
    blank: <path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Zm0 0v6h6" />,
    image: <><rect x="3" y="3" width="18" height="18" rx="3" /><circle cx="8" cy="8" r="1.5" /><path d="m3 17 6-6 5 5 3-3 4 4" /></>,
    video: <><rect x="3" y="3" width="18" height="18" rx="3" /><path d="m10 8 6 4-6 4Z" /></>,
    commerce: <><path d="M5 7h14l2 14H3ZM8 9V6a4 4 0 0 1 8 0v3" /></>,
  };
  return <svg className="canvas-shortcut-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">{shapes[kind]}</svg>;
}

function Arrow() {
  return <svg className="canvas-feature-arrow" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M4 12h16m-6-6 6 6-6 6" /></svg>;
}

const shortcuts = [
  { kind: 'blank', title: '空白画布', description: '从零开始创作' },
  { kind: 'image', title: '图片生成', description: '让灵感成为画面', href: '/create/image' },
  { kind: 'video', title: '视频生成', description: '用影像讲述故事', href: '/create/video' },
  { kind: 'commerce', title: '电商创作', description: '让商品更有吸引力', href: '/commerce' },
] as const;

export function CanvasFeature({ theme, unavailable, creating, onCreate, children }: CanvasFeatureProps & { theme: 'light' | 'dark' }) {
  useLanguage();
  return <section className="canvas-feature" aria-label={t('画布创作')}>
    <h1 className="sr-only">{t('画布创作')}</h1>
    <CanvasFeatureBackdrop theme={theme}>
      <div className="canvas-feature-actions">
        <button className="canvas-feature-start" disabled={unavailable} onClick={onCreate}>{t(creating ? '正在创建…' : '开始创作')}<Arrow /></button>
        {children}
      </div>
    </CanvasFeatureBackdrop>
    <section className="canvas-feature-shortcuts" aria-label={t('快速开始')}>
      {shortcuts.map(item => {
        const content = <><ShortcutIcon kind={item.kind} /><span className="canvas-shortcut-copy"><strong>{t(item.title)}</strong><span>{t(item.description)}</span></span><Arrow /></>;
        return 'href' in item
          ? <Link className="canvas-feature-shortcut" key={item.kind} to={item.href}>{content}</Link>
          : <button className="canvas-feature-shortcut" key={item.kind} disabled={unavailable} onClick={onCreate}>{content}</button>;
      })}
    </section>
  </section>;
}
