import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import * as session from '../userSession';
import { readComposerDraft, saveComposerDraft, type ComposerDraft } from '../creator/composer-draft';
import { emptyVideoBrief, saveCommerceVideoDraft } from './commerce-video';
import { ContinueCommerceCreation, latestCommerceDraft } from './ContinueCommerceCreation';
import { readSuiteDraft, saveSuiteDraft, type SuiteDraft } from './suite-draft';

vi.mock('../userSession', () => ({ useOptionalUserSession: vi.fn() }));
const image: ComposerDraft = { prompt: '陶瓷杯主图', mode: 'text', selection: null, materials: [], bindings: [] };
const suite: SuiteDraft = { request: { productName: '亚麻衬衣', sellingPoints: '', language: '中文', model: 'image', aspectRatio: '1:1', resolution: '1K', templateId: 'streetwear', templateVersion: 1, modules: ['WHITE_BACKGROUND'], fileIds: ['file-one'], options: null }, files: [{ fileId: 'file-one', name: 'shirt.png', preview: 'blob:expired' }], referenceFiles: [], modelSource: 'upload' };
beforeEach(() => {
  localStorage.clear();
  vi.mocked(session.useOptionalUserSession).mockReturnValue({ userId: 'a', authenticated: true, status: 'ready' } as never);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it('ignores empty drafts, other scopes and other accounts', () => {
  saveComposerDraft('a', 'commerce', 'IMAGE', { ...image, prompt: ' ' });
  saveComposerDraft('a', 'home', 'IMAGE', image);
  saveComposerDraft('b', 'commerce', 'IMAGE', image);
  saveCommerceVideoDraft('a', 'seller-video', { brief: emptyVideoBrief(), prompt: '', materials: [], bindings: [], mode: null, selection: null });
  expect(latestCommerceDraft('a')).toBeNull();
  expect(latestCommerceDraft(null)).toBeNull();
});

it('keeps unchanged reopen timestamps and selects the newest meaningful edit', () => {
  const now = vi.spyOn(Date, 'now').mockReturnValue(100);
  saveComposerDraft('a', 'commerce', 'IMAGE', image);
  now.mockReturnValue(200); saveSuiteDraft('a', 'product-suite', suite);
  now.mockReturnValue(300); saveComposerDraft('a', 'commerce', 'IMAGE', image);
  expect(readComposerDraft('a', 'commerce', 'IMAGE')?.updatedAt).toBe(100);
  expect(latestCommerceDraft('a')?.to).toBe('/commerce/product-suite');
  saveSuiteDraft('a', 'product-suite', suite);
  expect(readSuiteDraft('a', 'product-suite')?.updatedAt).toBe(200);
  expect(readSuiteDraft('a', 'product-suite')?.files[0].preview).toBe('');
  saveComposerDraft('a', 'commerce', 'IMAGE', { ...image, prompt: '新杯子主图' });
  expect(latestCommerceDraft('a')?.summary).toBe('新杯子主图');
});

it('continues an image draft in place and hides it immediately on account change', () => {
  saveComposerDraft('a', 'commerce', 'IMAGE', image);
  const onContinue = vi.fn();
  const view = render(<MemoryRouter><ContinueCommerceCreation onContinue={onContinue} /></MemoryRouter>);
  fireEvent.click(screen.getByRole('button', { name: '继续编辑' }));
  expect(onContinue).toHaveBeenCalledWith('image');
  vi.mocked(session.useOptionalUserSession).mockReturnValue({ userId: 'b', authenticated: true, status: 'ready' } as never);
  view.rerender(<MemoryRouter><ContinueCommerceCreation onContinue={onContinue} /></MemoryRouter>);
  expect(screen.queryByText('陶瓷杯主图')).not.toBeInTheDocument();
});

it('links suite drafts to the matching editor and tolerates unavailable storage', () => {
  saveSuiteDraft('a', 'product-suite', suite);
  render(<MemoryRouter><ContinueCommerceCreation onContinue={vi.fn()} /></MemoryRouter>);
  expect(screen.getByRole('link', { name: '继续编辑' })).toHaveAttribute('href', '/commerce/product-suite');
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
  expect(latestCommerceDraft('a')).toBeNull();
});

it('rejects malformed suite drafts and handles storage quota errors', () => {
  localStorage.setItem('xzwl-commerce-suite-draft:["a","product-suite"]', JSON.stringify({ ...suite, files: [null] }));
  expect(readSuiteDraft('a', 'product-suite')).toBeNull();
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
  expect(saveSuiteDraft('a', 'product-suite', suite)).toBe(false);
});
