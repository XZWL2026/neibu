import '@testing-library/jest-dom/vitest';
import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import type { VideoBrief } from '../commerce/commerce-video';
import { CommerceVideoBrief } from '../commerce/CommerceVideoBrief';
import { LightHomeFeature } from '../home/LightHomeFeature';
import { DarkHomeFeature } from '../home/DarkHomeFeature';
import { LightCanvasFeature } from '../canvas/LightCanvasFeature';
import { DarkCanvasFeature } from '../canvas/DarkCanvasFeature';
import { t, formatMessage, selectLanguage, restoreLanguage, useLanguage } from './index';
import en from './en.json';
import ko from './ko.json';
import ja from './ja.json';

const dictionaries = { en, ko, ja };
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); act(() => selectLanguage('zh-CN')); localStorage.removeItem('yomio-language'); });
it('has a complete dictionary and preserves every interpolation slot in each language', () => {
 for (const dictionary of Object.values(dictionaries)) {
  expect(Object.keys(dictionary).sort()).toEqual(Object.keys(en).sort());
  for (const [key, value] of Object.entries(dictionary)) {
   expect(value.trim(), key).not.toBe('');
   expect(value.match(/\{\d+\}/g)?.sort() ?? [], key).toEqual(key.match(/\{\d+\}/g)?.sort() ?? []);
  }
 }
});
it('formats trusted copy without translating interpolated user content', () => {
 selectLanguage('en');
 expect(formatMessage('删除 {0}', '首页')).toBe('Delete 首页');
 expect(t('此处是用户自己的句子')).toBe('此处是用户自己的句子');
 expect(t(null)).toBeNull();
 expect(t(12)).toBe(12);
});
it.each(['en', 'ko', 'ja'] as const)('changes all form labels in %s while preserving input and selected business values', language => {
 function Form() {
  useLanguage();
  const [value, onChange] = useState<VideoBrief>({ product: '首页', sellingPoints: '用户原文', platform: '淘宝天猫', language: '中文', style: '商品展示', shots: [] });
  return <CommerceVideoBrief tool="seller-video" value={value} onChange={onChange} disabled={false} hasScript={false} onApply={() => {}} />;
 }
 render(<Form />);
 const name = screen.getByLabelText('商品名称');
 fireEvent.change(name, { target: { value: '首页 · My product' } });
 const platform = screen.getByLabelText('发布平台');
 fireEvent.change(platform, { target: { value: '抖音电商' } });
 act(() => selectLanguage(language));
 expect(screen.getByLabelText(t('商品名称'))).toBe(name);
 expect(name).toHaveValue('首页 · My product');
 expect(screen.getByLabelText(t('发布平台'))).toBe(platform);
 expect(platform).toHaveValue('抖音电商');
 expect(screen.getByLabelText(t('文案语言'))).toHaveValue('中文');
 expect(screen.getByLabelText(t('视频风格'))).toHaveValue('商品展示');
 expect(screen.queryByText('商品与分镜')).not.toBeInTheDocument();
 act(() => selectLanguage('zh-CN'));
 expect(screen.getByLabelText('商品名称')).toHaveValue('首页 · My product');
});
it('restores a saved language and uses Chinese for an unknown preference', () => {
 localStorage.setItem('yomio-language', 'ja');
 restoreLanguage();
 expect(document.documentElement.lang).toBe('ja');
 expect(t('首页')).toBe('ホーム');
 localStorage.setItem('yomio-language', 'unsupported');
 restoreLanguage();
 expect(t('首页')).toBe('首页');
});
it('switches while the actual localStorage getter throws', () => {
 const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
 Object.defineProperty(globalThis, 'localStorage', { configurable: true, get() { throw new DOMException('Denied', 'SecurityError'); } });
 try {
  expect(() => restoreLanguage()).not.toThrow();
  expect(() => selectLanguage('ko')).not.toThrow();
  expect(t('首页')).toBe('홈');
 } finally { if (original) Object.defineProperty(globalThis, 'localStorage', original); }
});
it.each([
 ['light-home', LightHomeFeature, '所想，即所见。'],
 ['dark-home', DarkHomeFeature, '所想，即所见。'],
 ['light-canvas', LightCanvasFeature, '画布创作'],
 ['dark-canvas', DarkCanvasFeature, '画布创作'],
] as const)('preserves the %s artwork, cards and controls through every language', (name, Feature, heading) => {
 const create = vi.fn();
 const { container } = render(<MemoryRouter><Feature unavailable={false} creating={false} onCreate={create} /></MemoryRouter>);
 const section = container.firstElementChild;
 const artwork = Array.from(container.querySelectorAll('img, svg image'));
 const sources = artwork.map(node => node.getAttribute('src') ?? node.getAttribute('href'));
 const cards = Array.from(container.querySelectorAll('.reference-card'));
 const crops = Array.from(container.querySelectorAll('svg')).map(node => node.getAttribute('viewBox'));
 for (const language of ['en', 'ko', 'ja', 'zh-CN'] as const) {
  act(() => selectLanguage(language));
  expect(container.firstElementChild).toBe(section);
  expect(Array.from(container.querySelectorAll('img, svg image'))).toEqual(artwork);
  expect(artwork.map(node => node.getAttribute('src') ?? node.getAttribute('href'))).toEqual(sources);
  expect(Array.from(container.querySelectorAll('.reference-card'))).toEqual(cards);
  expect(Array.from(container.querySelectorAll('svg')).map(node => node.getAttribute('viewBox'))).toEqual(crops);
  if (heading) {
   const title = screen.getByRole('heading', { name: t(heading) });
   if (language !== 'zh-CN' && !name.includes('canvas')) expect(title).not.toHaveClass('sr-only');
  } else expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument();
  expect(container.querySelector('.localized-feature')).toBeNull();
  if (name.includes('canvas')) fireEvent.click(screen.getByRole('button', { name: t('开始创作') }));
  else expect(screen.getByRole('link', { name: t('开始创作') })).toHaveAttribute('href', '/create/image');
 }
 if (name.includes('canvas')) expect(create).toHaveBeenCalledTimes(4);
});

it('includes every source-owned translated literal and sentence in the dictionaries', async () => {
 const { readdirSync, readFileSync } = await import('node:fs');
 const ts = await import('typescript');
 const root = `${process.cwd()}/src/`;
 const missing: string[] = [];
 for (const file of readdirSync(root, { recursive: true }) as string[]) {
  if (!file.endsWith('.tsx') || file.includes('.test.') || /^i18n[\\/]/.test(file)) continue;
  const source = ts.createSourceFile(file, readFileSync(root + file, 'utf8'), ts.ScriptTarget.Latest, true);
  function isChineseOnly(node: import('typescript').Node) {
   for (let child = node, parent = node.parent; parent; child = parent, parent = parent.parent) {
    if (ts.isConditionalExpression(parent) && parent.whenTrue === child && ts.isBinaryExpression(parent.condition)
     && parent.condition.operatorToken.kind === ts.SyntaxKind.EqualsEqualsEqualsToken
     && parent.condition.left.getText(source) === 'language' && ts.isStringLiteral(parent.condition.right)
     && parent.condition.right.text === 'zh-CN') return true;
   }
   return false;
  }
  function visit(node: import('typescript').Node) {
   if (ts.isBinaryExpression(node) && [ts.SyntaxKind.EqualsEqualsEqualsToken, ts.SyntaxKind.ExclamationEqualsEqualsToken].includes(node.operatorToken.kind)) {
    for (const operand of [node.left, node.right]) if (ts.isCallExpression(operand) && operand.expression.getText(source) === 't') missing.push(`${file}: translated business comparison`);
   }
   if (ts.isCallExpression(node) && ['t', 'formatMessage'].includes(node.expression.getText(source))) {
    const argument = node.arguments[0];
    function checkCopy(expression: import('typescript').Node) {
     if (ts.isConditionalExpression(expression)) { checkCopy(expression.whenTrue); checkCopy(expression.whenFalse); }
     if (ts.isStringLiteral(expression) && /\p{Script=Han}/u.test(expression.text)) {
      const key = expression.text.replace(/\s+/g, ' ').trim();
      if (!(key in en)) missing.push(`${file}: ${key}`);
     }
    }
    if (argument) checkCopy(argument);
   }
   // UI text and accessibility attributes must pass through the display boundary.
   // The registered ICP identifier is a legal identifier, not translatable copy.
   if ((ts.isJsxText(node) || ts.isStringLiteral(node) && ts.isJsxAttribute(node.parent))
    && /\p{Script=Han}/u.test(node.text) && node.text.trim() !== '粤ICP备2026023603号'
    && !isChineseOnly(node)) missing.push(`${file}: untranslated JSX ${node.text.trim()}`);
   ts.forEachChild(node, visit);
  }
  visit(source);
 }
 expect(missing).toEqual([]);
});

it('covers source-owned navigation, showcase and commerce data, including dynamically rendered labels', async () => {
 const { readFileSync } = await import('node:fs');
 const ts = await import('typescript');
 const missing: string[] = [];
 for (const file of ['CreationNavigation.tsx', 'home/YomioHome.tsx', 'home/yomio-content.tsx', 'home/HomeShowcase.tsx', 'home/HomeCommerceCarousel.tsx', 'commerce/CommerceWorkbench.tsx']) {
  const source = ts.createSourceFile(file, readFileSync(`${process.cwd()}/src/${file}`, 'utf8'), ts.ScriptTarget.Latest, true);
  function visit(node: import('typescript').Node) {
   if (ts.isStringLiteral(node) && /\p{Script=Han}/u.test(node.text)) {
    // Author names belong to the creator; they must remain unchanged.
    if (ts.isPropertyAssignment(node.parent) && (ts.isIdentifier(node.parent.name) || ts.isStringLiteral(node.parent.name)) && node.parent.name.text === 'author') return;
    const key = node.text.replace(/\s+/g, ' ').trim();
    if (!(key in en)) missing.push(`${file}: ${key}`);
   }
   ts.forEachChild(node, visit);
  }
  visit(source);
 }
 expect(missing).toEqual([]);
});

it('updates an open canvas menu and group controls without translating the group name', async () => {
 const { CanvasContextMenu } = await import('../canvas/CanvasContextMenu');
 const { CanvasGroupFrame } = await import('../canvas/CanvasGroups');
 const run = vi.fn();
 render(<>
  <CanvasContextMenu x={20} y={20} actions={[{ label: '复制节点', icon: 'copy', run }]} onClose={() => {}} returnFocus={null} />
  <CanvasGroupFrame group={{ id: 'group', name: '首页', nodeIds: ['a', 'b'] }} bounds={{ x: 0, y: 0, width: 300, height: 200 }} positions={{}} selected={false}
   toCanvasPoint={point => point} onSelect={() => {}} onPreview={() => {}} onFinish={() => {}} onCancel={() => {}} onRename={() => {}} onUngroup={() => {}} />
 </>);
 const action = screen.getByRole('menuitem', { name: '复制节点' });
 for (const language of ['en', 'ja', 'ko', 'zh-CN'] as const) {
  act(() => selectLanguage(language));
  expect(screen.getByRole('menuitem', { name: t('复制节点') })).toBe(action);
  expect(screen.getByRole('group', { name: formatMessage('节点分组：{0}', '首页') })).toHaveTextContent('首页');
  expect(screen.getByText(formatMessage('{0} 个节点', 2))).toBeVisible();
  expect(screen.getByRole('button', { name: formatMessage('重命名分组：{0}', '首页') })).toHaveAttribute('title', t('重命名分组'));
 }
 fireEvent.click(action);
 expect(run).toHaveBeenCalledOnce();
});

it('keeps a task prompt and media filename unchanged even when they match a dictionary key', async () => {
 const { MediaTaskCard } = await import('../creator/VideoTaskResults');
 const { LoadingMedia } = await import('../creator/LoadingMedia');
 selectLanguage('en');
 render(<><MediaTaskCard entry={{ key: 'translation-test', snapshot: { type: 'IMAGE', prompt: '首页', status: 'FAILED' } }} />
  <LoadingMedia kind="image" src="/local-test.png" label="首页" busy /></>);
 expect(screen.getByRole('article')).toHaveAccessibleName('Image Task: 首页');
 expect(screen.getByRole('img')).toHaveAccessibleName('首页');
 expect(screen.getByRole('status', { name: `首页 ${t('上传中')}` })).toBeInTheDocument();
});

it('localizes the existing delete confirmation without submitting when cancelled', async () => {
 const { MediaTaskCard } = await import('../creator/VideoTaskResults');
 const api = await import('../api');
 vi.spyOn(api, 'mediaGetGetApiV1MediaTasksTaskId').mockResolvedValue({ data: { id: 'completed-task', status: 'FAILED', prompt: '首页', type: 'IMAGE' } } as never);
 const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
 const remove = vi.fn();
 selectLanguage('en');
 render(<MediaTaskCard entry={{ key: 'translation-test', taskRef: 'completed-task', snapshot: { type: 'IMAGE', prompt: '首页', status: 'FAILED' } }} onDelete={remove} />);
 fireEvent.click(screen.getByRole('button', { name: 'Delete Image' }));
 expect(confirm).toHaveBeenCalledWith('Delete this Image generation record?');
 expect(remove).not.toHaveBeenCalled();
 confirm.mockRestore();
});
