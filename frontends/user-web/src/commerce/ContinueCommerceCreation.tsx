import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { formatMessage, t, useLanguage } from '../i18n';
import { useOptionalUserSession } from '../userSession';
import { readComposerDraft } from '../creator/composer-draft';
import { commerceVideoTools, readCommerceVideoDraft, type CommerceVideoTool } from './commerce-video';
import { commerceTools, type CommerceTool } from './commerce-tools';
import { hasSuiteDraftContent, readSuiteDraft } from './suite-draft';

type RecentDraft = { title: string; summary: string; updatedAt: number; to?: string; kind?: 'image' | 'video' };
const timestamp = (value?: number) => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0;

export function latestCommerceDraft(owner: string | null): RecentDraft | null {
  if (!owner) return null;
  const drafts: RecentDraft[] = [];
  for (const kind of ['IMAGE', 'VIDEO'] as const) {
    const draft = readComposerDraft(owner, 'commerce', kind);
    if (draft && (draft.prompt.trim() || draft.materials.some(Boolean))) drafts.push({ title: kind === 'IMAGE' ? '图片生成' : '视频生成',
      summary: draft.prompt.trim(), updatedAt: timestamp(draft.updatedAt), kind: kind === 'IMAGE' ? 'image' : 'video' });
  }
  for (const tool of Object.keys(commerceVideoTools) as CommerceVideoTool[]) {
    const draft = readCommerceVideoDraft(owner, tool);
    if (draft && (draft.prompt.trim() || draft.materials.some(Boolean) || draft.brief.product.trim() || draft.brief.sellingPoints.trim()
      || draft.brief.shots.some(shot => shot.visual.trim() || shot.narration.trim()))) drafts.push({ title: commerceVideoTools[tool].title,
      summary: draft.brief.product.trim() || draft.prompt.trim() || draft.brief.sellingPoints.trim(), updatedAt: timestamp(draft.updatedAt), to: `/commerce/${tool}` });
  }
  for (const tool of Object.keys(commerceTools) as CommerceTool[]) {
    const draft = readSuiteDraft(owner, tool);
    if (draft && hasSuiteDraftContent(draft)) drafts.push({ title: commerceTools[tool].title, summary: draft.request.productName?.trim() || draft.request.sellingPoints?.trim() || '',
      updatedAt: timestamp(draft.updatedAt), to: `/commerce/${tool}` });
  }
  return drafts.sort((a, b) => b.updatedAt - a.updatedAt)[0] ?? null;
}

export function ContinueCommerceCreation({ onContinue }: { onContinue(kind: 'image' | 'video'): void }) {
  useLanguage();
  const account = useOptionalUserSession();
  const owner = account?.authenticated && !['loading', 'error'].includes(account.status) ? account.userId : null;
  const [revision, refresh] = useState(0);
  const draft = useMemo(() => latestCommerceDraft(owner), [owner, revision]);
  useEffect(() => {
    const update = () => refresh(value => value + 1);
    window.addEventListener('focus', update);
    window.addEventListener('storage', update);
    return () => { window.removeEventListener('focus', update); window.removeEventListener('storage', update); };
  }, []);
  if (!draft) return null;
  return <aside className="commerce-continue" aria-label={t('继续上次创作')}>
    <div><strong>{t('继续上次创作')}</strong><span>{formatMessage('本机草稿 · {0}', t(draft.title))}</span>
      {draft.summary && <p>{draft.summary.slice(0, 120)}</p>}</div>
    {draft.to ? <Link to={draft.to}>{t('继续编辑')}</Link> : <button type="button" onClick={() => onContinue(draft.kind!)}>{t('继续编辑')}</button>}
  </aside>;
}
