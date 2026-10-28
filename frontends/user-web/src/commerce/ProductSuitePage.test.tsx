import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ProductSuitePage } from './ProductSuitePage';
import * as api from '../api';
import * as session from '../userSession';
import { resolvePreview } from '../canvas/production-api';
import { clearPendingSuite, readPendingSuite, savePendingSuite } from './suite-recovery';
import { readSuiteDraft } from './suite-draft';
vi.mock('../UserFrame', () => ({ UserFrame: ({ children }: { children: React.ReactNode }) => <main>{children}</main> }));
vi.mock('../userSession', async original => ({ ...await original<typeof import('../userSession')>(), useOptionalUserSession: vi.fn() }));
vi.mock('../creator/VideoTaskResults', () => ({ MediaTaskCard: ({ entry }: { entry: { taskRef: string } }) => <div>任务 {entry.taskRef}</div> }));
vi.mock('../canvas/production-api', async original => ({ ...await original<typeof import('../canvas/production-api')>(), resolvePreview: vi.fn().mockResolvedValue('https://example.test/product.png') }));
vi.mock('../api', async original => ({ ...await original<typeof import('../api')>(),
  mediaOptionsGetApiV1MediaGenerationOptions: vi.fn(), storageUploadReferenceImagePostApiV1FilesReferenceImages: vi.fn(),
  mediaTemplatesGetApiV1MediaProductSuitesTemplates: vi.fn(), mediaListGetApiV1MediaProductSuites: vi.fn(),
  mediaPlanPostApiV1MediaProductSuitesPlan: vi.fn(), mediaGetGetApiV1MediaProductSuitesSuiteId: vi.fn(), mediaQuotePostApiV1MediaProductSuitesQuotes: vi.fn(),
  mediaSubmitPostApiV1MediaProductSuites: vi.fn(), mediaCopyPostApiV1MediaProductSuitesCopy: vi.fn(),
  mediaRegeneratePostApiV1MediaProductSuitesSuiteIdModulesModuleRegenerations: vi.fn(),
  mediaSubmitPostApiV1MediaTasks: vi.fn(), mediaGetGetApiV1MediaTasksTaskId: vi.fn(), mediaQuotePostApiV1MediaQuotes: vi.fn(),
}));
const reply = (data: unknown) => ({ data, status: 200, headers: new Headers() }) as never;
const owner = { userId: 'owner-a', status: 'ready', authenticated: true, accountType: 'PERSONAL', availablePoints4: '1000.0000',
  currentKey: { enabled: true, kind: '媒体 Key', relationship: 'Owner' } };
const modules = [
  ['WHITE_BACKGROUND', '白底图'], ['SELLING_POINTS', '核心卖点'], ['FEATURES', '多功能信息图'],
  ['DETAILS', '细节拼图'], ['MODEL_SCENE', '模特场景'], ['OVERVIEW', '搭配总览'],
].map(([id, name]) => ({ id, name, description: `${name}内容` }));
const apparelModules = [['WHITE_BACKGROUND', '白底精修'], ['VOLUME_3D', '3D 立体效果'], ['MANNEQUIN', '人台图'], ['DETAILS', '细节展示'], ['SELLING_POINTS', '卖点展示']].map(([id, name]) => ({ id, name, description: name }));
const detailModules = [['WHITE_BACKGROUND', '首屏主视觉'], ['SELLING_POINTS', '核心卖点'], ['MODEL_SCENE', '使用场景'], ['MULTI_ANGLE', '多角度图'], ['BRAND_STORY', '品牌故事'], ['COMPARISON', '效果对比'], ['CRAFT', '工艺'], ['SIZE', '尺寸'], ['SPECS', '规格'], ['ACCESSORIES', '配件'], ['SERIES', '系列'], ['INGREDIENTS', '成分'], ['AFTER_SALES', '售后'], ['USAGE_ADVICE', '使用建议']].map(([id, name]) => ({ id, name, description: name }));
const request = { productName: '格纹套装', sellingPoints: '', language: '中文', fileIds: ['product-file'], modules: modules.map(m => m.id), templateId: 'streetwear', templateVersion: 1, model: 'image-model', aspectRatio: '1:1', resolution: '1K' };
const result = { id: 'suite-one', createdAt: '2026-09-23T00:00:00Z', request, status: 'SUCCEEDED',
  items: [{ module: 'WHITE_BACKGROUND', name: '白底图', task: { id: 'image-one', type: 'IMAGE', status: 'SUCCEEDED', results: [] }, previousResult: null }] };
const submit = vi.mocked(api.mediaSubmitPostApiV1MediaProductSuites);
const quote = vi.mocked(api.mediaQuotePostApiV1MediaProductSuitesQuotes);
async function fill() {
  await screen.findByRole('option', { name: '可用图片模型' });
  fireEvent.change(screen.getByLabelText('商品名称'), { target: { value: '格纹套装' } });
  fireEvent.change(screen.getByLabelText('上传商品图片'), { target: { files: [new File(['x'], 'product.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: /生成套图/ })).toBeEnabled());
}
async function showWorks() {
  fireEvent.click(screen.getByRole('button', { name: '创作结果' }));
  await screen.findByText('任务 image-one');
}
beforeEach(() => {
  vi.resetAllMocks(); localStorage.clear();
  vi.mocked(resolvePreview).mockResolvedValue('https://example.test/product.png');
  vi.mocked(session.useOptionalUserSession).mockReturnValue(owner as never);
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({ models: [{ type: 'IMAGE', model: 'image-model', displayName: '可用图片模型', maxReferenceImages: 8, maxCount: 1, aspectRatios: ['1:1', '4:3'], resolutions: ['1K'] }] }));
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'streetwear', version: 1, name: '复古服装套图', style: '复古', modules }], copyAvailable: false }));
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValue(reply([]));
  vi.mocked(api.mediaGetGetApiV1MediaProductSuitesSuiteId).mockResolvedValue(reply(result));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).mockResolvedValue(reply({ fileId: 'product-file' }));
  quote.mockResolvedValue(reply({ points: '60.0000', modules: modules.map(m => ({ module: m.id, points: '10.0000' })) }));
  vi.mocked(api.mediaQuotePostApiV1MediaQuotes).mockResolvedValue(reply({ points: '10.0000' }));
  submit.mockResolvedValue(reply(result));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('restores unsent product content, uploaded files and module choices without submitting again', async () => {
  const first = render(<ProductSuitePage />); await fill();
  fireEvent.change(screen.getByLabelText('商品卖点'), { target: { value: '透气舒适' } });
  fireEvent.change(screen.getByLabelText('画面比例'), { target: { value: '4:3' } });
  fireEvent.click(screen.getByRole('checkbox', { name: /核心卖点/ }));
  first.unmount();
  const saved = readSuiteDraft('owner-a', 'product-suite');
  expect(saved?.request.sellingPoints).toBe('透气舒适');
  const next = render(<ProductSuitePage />);
  await screen.findByRole('option', { name: '可用图片模型' });
  expect(screen.getByLabelText('商品名称')).toHaveValue('格纹套装');
  expect(screen.getByLabelText('商品卖点')).toHaveValue('透气舒适');
  expect(screen.getByLabelText('画面比例')).toHaveValue('4:3');
  expect(screen.getByRole('checkbox', { name: /核心卖点/ })).not.toBeChecked();
  await waitFor(() => expect(resolvePreview).toHaveBeenCalledWith('product-file'));
  expect(api.storageUploadReferenceImagePostApiV1FilesReferenceImages).toHaveBeenCalledOnce();
  expect(submit).not.toHaveBeenCalled();
  next.unmount();
  vi.mocked(session.useOptionalUserSession).mockReturnValue({ ...owner, userId: 'owner-b' } as never);
  render(<ProductSuitePage />);
  await screen.findByRole('option', { name: '可用图片模型' });
  expect(screen.getByLabelText('商品名称')).toHaveValue('');
  expect(readSuiteDraft('owner-a', 'product-suite')?.request.productName).toBe('格纹套装');
});
it('starts with tools collapsed and expands from the current tool button', async () => {
  render(<ProductSuitePage />);
  await screen.findByRole('option', { name: '可用图片模型' });
  const toggle = screen.getByRole('button', { name: '展开电商工具' });
  const panel = document.getElementById(toggle.getAttribute('aria-controls')!)!;
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  expect(panel).toHaveAttribute('inert');
  expect(screen.queryByRole('navigation', { name: '电商工具' })).not.toBeInTheDocument();
  fireEvent.click(toggle);
  const navigation = screen.getByRole('navigation', { name: '电商工具' });
  expect(within(navigation).getAllByRole('link')).toHaveLength(8);
  expect(within(navigation).getByRole('link', { name: '商品套图' })).toHaveAttribute('aria-current', 'page');
  expect(within(navigation).getByRole('region', { name: '图片创作' })).toBeInTheDocument();
  expect(within(navigation).getByRole('region', { name: '视频创作' })).toBeInTheDocument();
  expect(toggle).toHaveTextContent('商品套图');
  expect(toggle.parentElement?.firstElementChild).toBe(toggle);
  expect(toggle).toHaveAttribute('aria-expanded', 'true');

  fireEvent.click(toggle);
  expect(toggle).toHaveAccessibleName('展开电商工具');
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  expect(panel).toHaveAttribute('aria-hidden', 'true');
  expect(panel).toHaveAttribute('inert');
  expect(screen.queryByRole('navigation', { name: '电商工具' })).not.toBeInTheDocument();
  expect(toggle).toHaveTextContent('商品套图');
  expect(toggle).toHaveAttribute('aria-current', 'page');

  fireEvent.click(toggle);
  expect(panel).not.toHaveAttribute('inert');
  expect(within(screen.getByRole('navigation', { name: '电商工具' })).getAllByRole('link')).toHaveLength(8);
  expect(screen.getByLabelText('商品名称')).toHaveValue('');
  fireEvent.change(screen.getByLabelText('商品名称'), { target: { value: '保留的商品草稿' } });
  fireEvent.click(within(screen.getByRole('navigation', { name: '电商工具' })).getByRole('link', { name: '商品套图' }));
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  expect(toggle).toHaveFocus();
  expect(screen.getByLabelText('商品名称')).toHaveValue('保留的商品草稿');
  expect(submit).not.toHaveBeenCalled();
  fireEvent.click(toggle);
  fireEvent.click(screen.getByRole('button', { name: '关闭工具切换' }));
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  expect(toggle).toHaveFocus();
});
it('closes the tool dropdown on Escape and outside clicks', async () => {
  render(<ProductSuitePage />);
  await screen.findByRole('option', { name: '可用图片模型' });
  const toggle = screen.getByRole('button', { name: '展开电商工具' });
  fireEvent.click(toggle);
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  expect(toggle).toHaveFocus();
  fireEvent.click(toggle);
  fireEvent.pointerDown(screen.getByLabelText('商品名称'));
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
});
it('switches the example area to generated works without submitting or losing inputs', async () => {
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValue(reply([result]));
  render(<ProductSuitePage />);
  await screen.findByRole('option', { name: '可用图片模型' });
  expect(screen.queryByRole('region', { name: '创作结果' })).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('商品名称'), { target: { value: '待创作商品' } });
  const create = screen.getByRole('button', { name: '创作结果' });
  const examples = screen.getByRole('button', { name: '功能示例' });
  expect(examples).toHaveAttribute('aria-pressed', 'true');
  expect(create).toHaveAttribute('aria-pressed', 'false');
  fireEvent.click(create);
  expect(create).toHaveAttribute('aria-pressed', 'true');
  expect(screen.queryByRole('region', { name: '商品套图效果示例' })).not.toBeInTheDocument();
  const works = screen.getByRole('region', { name: '创作结果' });
  expect(works).toHaveAttribute('data-inline', 'true');
  expect(within(works).getByText('任务 image-one')).toBeInTheDocument();
  expect(screen.getByLabelText('商品名称')).toHaveValue('待创作商品');
  expect(submit).not.toHaveBeenCalled();
  fireEvent.click(examples);
  expect(screen.getByRole('region', { name: '商品套图效果示例' })).toBeInTheDocument();
  expect(screen.queryByRole('region', { name: '创作结果' })).not.toBeInTheDocument();
  expect(create).toHaveAttribute('aria-pressed', 'false');
  expect(examples).toHaveAttribute('aria-pressed', 'true');
});
it('shows an empty works state when no generations exist', async () => {
  render(<ProductSuitePage />);
  await screen.findByRole('option', { name: '可用图片模型' });
  fireEvent.click(screen.getByRole('button', { name: '创作结果' }));
  expect(within(screen.getByRole('region', { name: '创作结果' })).getByText('暂无套图记录')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '按项目' }));
  expect(screen.getByRole('combobox', { name: '选择生成项目' })).toBeDisabled();
  expect(screen.getByRole('combobox', { name: '选择生成项目' })).toHaveTextContent('暂无项目');
});
it('groups results by tool or generation project without the old history block or changing the creation draft', async () => {
  const second = { ...result, id: 'suite-two', createdAt: '2026-09-24T00:00:00Z', items: [{ ...result.items[0], task: { ...result.items[0].task, id: 'image-two' } }] };
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValue(reply([second, result]));
  render(<ProductSuitePage />); await showWorks();
  const draft = screen.getByLabelText('商品名称');
  fireEvent.change(draft, { target: { value: '正在编辑的商品' } });
  expect(screen.queryByRole('heading', { name: '我的套图' })).not.toBeInTheDocument();
  expect(document.querySelector('.suite-history-list')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '按当前功能' })).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByText('任务 image-two')).toBeInTheDocument();
  expect(screen.getByText('任务 image-one')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '按项目' }));
  const projects = screen.getByRole('combobox', { name: '选择生成项目' });
  fireEvent.click(projects);
  const projectOptions = within(screen.getByRole('listbox', { name: '选择生成项目' })).getAllByRole('option');
  expect(projectOptions).toHaveLength(2);
  expect(projectOptions[0]).toHaveAttribute('aria-selected', 'true');
  expect(screen.queryByText('任务 image-one')).not.toBeInTheDocument();
  fireEvent.click(projectOptions[1]);
  expect(screen.getByText('任务 image-one')).toBeInTheDocument();
  expect(screen.queryByText('任务 image-two')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '功能示例' }));
  expect(screen.queryByRole('group', { name: '创作结果分组' })).not.toBeInTheDocument();
  await showWorks();
  fireEvent.click(screen.getByRole('combobox', { name: '选择生成项目' }));
  expect(within(screen.getByRole('listbox')).getAllByRole('option')[1]).toHaveAttribute('aria-selected', 'true');
  fireEvent.click(screen.getByRole('button', { name: '按当前功能' }));
  expect(screen.getByText('任务 image-two')).toBeInTheDocument();
  expect(draft).toHaveValue('正在编辑的商品');
  expect(quote).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
});
it('explains the missing reference and keeps composition selectable before quoting', async () => {
  render(<ProductSuitePage />);
  await screen.findByRole('option', { name: '可用图片模型' });
  expect(screen.getByText('请先上传商品图片。')).toBeVisible();
  const composition = screen.getByRole('region', { name: '图片内容配置' });
  expect(screen.getByRole('region', { name: '套图配置' })).toContainElement(composition);
  expect(screen.getByRole('region', { name: '套图配置' })).toContainElement(screen.getByRole('button', { name: /生成套图/ }));
  const choices = within(composition).getAllByRole('checkbox');
  expect(choices).toHaveLength(6);
  await waitFor(() => expect(choices.every(choice => (choice as HTMLInputElement).checked)).toBe(true));
  expect(within(composition).queryByRole('button', { name: /增加.*数量/ })).not.toBeInTheDocument();
  choices.forEach(choice => fireEvent.click(choice));
  expect(screen.getByText('至少选择一种图片内容。')).toBeVisible();
  expect(quote).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
});
it('offers account recovery when the settled owner cannot load their account', async () => {
  const refresh = vi.fn();
  vi.mocked(session.useOptionalUserSession).mockReturnValue({ ...owner, status: 'error', refresh } as never);
  render(<ProductSuitePage />);
  await screen.findByRole('option', { name: '可用图片模型' });
  expect(screen.getByText('账户读取失败')).toBeVisible();
  fireEvent.change(screen.getByLabelText('商品名称'), { target: { value: '离线准备的商品' } });
  expect(screen.getByLabelText('商品名称')).toHaveValue('离线准备的商品');
  fireEvent.click(screen.getByRole('button', { name: '刷新账户' }));
  expect(refresh).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('button', { name: /生成套图/ })).toBeDisabled();
  expect(quote).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
});
it.each(['not-open', 'pending-activation', 'awaiting-credit', 'disabled'] as const)(
  'shows the actual %s account blocker instead of a quote spinner', async status => {
    vi.mocked(session.useOptionalUserSession).mockReturnValue({ ...owner, status } as never);
    render(<ProductSuitePage />);
    await screen.findByRole('option', { name: '可用图片模型' });
    fireEvent.change(screen.getByLabelText('上传商品图片'), { target: { files: [new File(['x'], 'product.png', { type: 'image/png' })] } });
    await screen.findByText('product.png');
    expect(screen.getByText(session.accountStatusLabels[status])).toBeVisible();
    expect(screen.queryByText('正在计算积分…')).not.toBeInTheDocument();
    expect(quote).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /生成套图/ })).toBeDisabled();
  },
);
it.each([
  ['image-replica', '图片复刻', '上传商品图片', '上传参考效果图', 'OVERVIEW'],
  ['model-wear', '模特穿戴', '上传服装图片', '上传模特参考图', 'MODEL_SCENE'],
] as const)('requires both image roles for %s and keeps their order after replacement', async (tool, name, productLabel, referenceLabel, module) => {
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [
    { id: tool, version: 1, name, modules: [{ id: module, name, description: '生成图片' }] },
    { id: 'streetwear', version: 1, name: '复古服装套图', modules },
  ], copyAvailable: false }));
  const upload = vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages);
  upload.mockResolvedValueOnce(reply({ fileId: 'product-file' })).mockResolvedValueOnce(reply({ fileId: 'reference-file' })).mockResolvedValueOnce(reply({ fileId: 'replacement-product' }));
  render(<ProductSuitePage tool={tool} />);
  await screen.findByRole('option', { name: '可用图片模型' });
  expect(screen.getByRole('heading', { level: 1, name })).toBeVisible();
  expect(screen.queryByRole('option', { name: '复古服装套图' })).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('商品名称'), { target: { value: '商品' } });
  fireEvent.change(screen.getByLabelText(productLabel), { target: { files: [new File(['x'], 'product.png', { type: 'image/png' })] } });
  await screen.findByText('product.png');
  expect(quote).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText(referenceLabel), { target: { files: [new File(['x'], 'reference.png', { type: 'image/png' })] } });
  await waitFor(() => expect(quote).toHaveBeenCalledWith(expect.objectContaining({ templateId: tool, fileIds: ['product-file', 'reference-file'] })));
  fireEvent.click(screen.getByRole('button', { name: tool === 'model-wear' ? '移除服装图片 1' : '移除商品图片 1' }));
  expect(screen.getByText('reference.png')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: new RegExp(`生成${name}.*1 张`) })).toBeDisabled();
  fireEvent.change(screen.getByLabelText(productLabel), { target: { files: [new File(['x'], 'new.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: new RegExp(`生成${name}.*1 张`) })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`生成${name}.*1 张`) }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ templateId: tool, fileIds: ['replacement-product', 'reference-file'], modules: [module] }), expect.anything()));
});
it('restores only the active tool request and keeps both references on an uncertain retry', async () => {
  const replicaRequest = { ...request, templateId: 'image-replica', modules: ['OVERVIEW'], fileIds: ['product-file', 'reference-file'] };
  savePendingSuite('owner-a', { key: 'suite-pending', request: request as never });
  savePendingSuite('owner-a', { key: 'replica-pending', request: replicaRequest as never }, 'image-replica');
  submit.mockResolvedValue(reply({ ...result, request: replicaRequest }));
  const view = render(<ProductSuitePage tool="image-replica" />);
  fireEvent.click(screen.getByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(replicaRequest, { headers: { 'Idempotency-Key': 'replica-pending' } }));
  expect(readPendingSuite('owner-a')?.key).toBe('suite-pending');
  expect(readPendingSuite('owner-a', 'image-replica')).toBeNull();
  view.rerender(<ProductSuitePage />);
  expect(screen.getByRole('heading', { name: '商品套图', level: 1 })).toBeVisible();
  expect(screen.getByRole('button', { name: '使用原请求重试' })).toBeEnabled();
});
it.each(['detail-page', 'product-retouch', 'apparel-set'] as const)('submits %s through its own template and scopes history', async tool => {
  const templateId = tool;
  const toolModules = tool === 'product-retouch' ? [modules[3]] : tool === 'apparel-set' ? apparelModules : modules;
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [
    { id: templateId, version: 1, name: tool, modules: toolModules },
  ], copyAvailable: false }));
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValue(reply([result, { ...result, id: 'tool-history', request: { ...request, templateId, productName: '当前工具历史' } }]));
  render(<ProductSuitePage tool={tool} />);
  await screen.findByRole('option', { name: '可用图片模型' });
  fireEvent.click(screen.getByRole('button', { name: '创作结果' }));
  expect(await screen.findByRole('button', { name: /当前工具历史/ })).toBeVisible();
  if (tool !== 'apparel-set') expect(screen.queryByRole('button', { name: /格纹套装.*已完成/ })).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('商品名称'), { target: { value: '商品' } });
  fireEvent.change(screen.getByLabelText(tool === 'apparel-set' ? '上传服装图片' : '上传商品图片'), { target: { files: [new File(['x'], 'product.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: /^生成.*·/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /^生成.*·/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ templateId, modules: tool === 'apparel-set' ? ['WHITE_BACKGROUND', 'VOLUME_3D', 'MANNEQUIN'] : toolModules.map(m => m.id) }), expect.anything()));
});
it('defaults to the first catalog template and submits the selected category modules after switching', async () => {
  const general = modules.map(item => item.id === 'MODEL_SCENE' ? { ...item, name: '使用场景图', description: '商品实际使用场景' } : item);
  const apparel = modules.filter(item => item.id !== 'SELLING_POINTS');
  const beauty = general.map(item => item.id === 'FEATURES' ? { ...item, name: '质地展示', description: '已有质地参考' } : item);
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [
    { id: 'general', version: 1, name: '通用商品套图', modules: general },
    { id: 'apparel', version: 1, name: '服装套图', modules: apparel },
    { id: 'beauty', version: 1, name: '美妆护肤套图', modules: beauty },
    { id: 'streetwear', version: 1, name: '复古服装套图', modules },
  ], copyAvailable: false }));
  render(<ProductSuitePage />); await fill();
  expect(screen.getByLabelText('套图模板')).toHaveValue('general');
  expect(screen.getByRole('checkbox', { name: /使用场景图/ })).toBeChecked();
  fireEvent.change(screen.getByLabelText('套图模板'), { target: { value: 'beauty' } });
  expect(screen.getByRole('checkbox', { name: /质地展示/ })).toBeChecked();
  fireEvent.change(screen.getByLabelText('套图模板'), { target: { value: 'apparel' } });
  expect(screen.getAllByRole('checkbox')).toHaveLength(5);
  expect(screen.queryByRole('checkbox', { name: /核心卖点/ })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: /生成套图/ })).toBeDisabled();
  await waitFor(() => expect(screen.getByRole('button', { name: '生成套图 · 5 张' })).toBeEnabled());
  const selected = { templateId: 'apparel', templateVersion: 1, modules: apparel.map(item => item.id) };
  expect(quote).toHaveBeenLastCalledWith(expect.objectContaining(selected));
  fireEvent.click(screen.getByRole('button', { name: '生成套图 · 5 张' }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining(selected), expect.anything()));
});
it('quotes server modules, invalidates changed settings and prevents duplicate clicks', async () => {
  render(<ProductSuitePage />); await fill();
  expect(screen.getAllByRole('checkbox')).toHaveLength(6);
  expect(screen.getByRole('button', { name: 'AI 帮写' })).toBeDisabled();
  let quoteDone!: (value: never) => void;
  quote.mockReturnValue(new Promise(resolve => { quoteDone = resolve; }));
  fireEvent.change(screen.getByLabelText('画面比例'), { target: { value: '4:3' } });
  expect(screen.getByRole('button', { name: /生成套图/ })).toBeDisabled();
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ aspectRatio: '4:3' })));
  await act(async () => quoteDone(reply({ points: '60.0000', modules: [] })));
  let done!: (value: never) => void;
  submit.mockReturnValue(new Promise(resolve => { done = resolve; }));
  fireEvent.click(screen.getByRole('button', { name: /生成套图/ }));
  fireEvent.click(screen.getByRole('button', { name: /提交中/ }));
  expect(submit).toHaveBeenCalledTimes(1);
  expect(submit.mock.calls[0][0]).toEqual({ ...request, aspectRatio: '4:3', options: { platform: '亚马逊', replicationMode: null, enhancements: [], garmentView: null, whiteBackground3d: null, whiteBackgroundMannequin: null, moduleCounts: [] } });
  await act(async () => done(reply(result)));
  expect(await screen.findByText('任务 image-one')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '创作结果' })).toHaveAttribute('aria-pressed', 'true');
});
it('keeps public effect examples independent from the selected generation modules', async () => {
  render(<ProductSuitePage />); await fill();
  const examples = screen.getByRole('region', { name: '商品套图效果示例' });
  expect(screen.getByRole('region', { name: '套图配置' })).toContainElement(screen.getByLabelText('商品名称'));
  const original = within(examples).getByRole('img', { name: '商品原图' });
  const source = original.getAttribute('src');
  expect(source).not.toBe('https://example.test/product.png');
  fireEvent.click(screen.getByRole('checkbox', { name: /核心卖点/ }));
  expect(original).toHaveAttribute('src', source);
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ modules: modules.filter(item => item.name !== '核心卖点').map(item => item.id) })));
  fireEvent.click(screen.getByRole('button', { name: '切换到第2个示例' }));
  expect(within(examples).getByRole('img', { name: '商品原图' }).getAttribute('src')).not.toBe(source);
});
it.each(['loading', 'empty', 'failed'])('keeps public examples available when the template catalog is %s', async state => {
  const catalog = vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates);
  if (state === 'loading') catalog.mockReturnValue(new Promise(() => {}));
  else if (state === 'empty') catalog.mockResolvedValue(reply({ templates: [], copyAvailable: false }));
  else catalog.mockRejectedValue(new TypeError('offline'));
  render(<ProductSuitePage />);
  if (state === 'empty') await screen.findByText('暂无可用的套图模板或图片模型。');
  if (state === 'failed') await screen.findByRole('alert');
  expect(screen.getByRole('region', { name: '商品套图效果示例' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /生成套图/ })).toBeDisabled();
  expect(submit).not.toHaveBeenCalled();
});
it('restores uncertain submission across remounts and retries the exact key and body', async () => {
  submit.mockRejectedValueOnce(new TypeError('offline'));
  const view = render(<ProductSuitePage />); await fill();
  fireEvent.click(screen.getByRole('button', { name: /生成套图/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  const first = submit.mock.calls[0]; view.unmount();
  render(<ProductSuitePage />);
  fireEvent.click(await screen.findByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
  expect(submit.mock.calls[1]).toEqual(first);
  await screen.findByText('任务 image-one');
});
it('loads server history without resubmission and isolates a changed owner', async () => {
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValueOnce(reply([result])).mockResolvedValue(reply([]));
  const view = render(<ProductSuitePage />);
  await showWorks();
  expect(screen.getByText('任务 image-one')).toBeInTheDocument();
  expect(submit).not.toHaveBeenCalled();
  vi.mocked(session.useOptionalUserSession).mockReturnValue({ ...owner, userId: 'owner-b' } as never);
  view.rerender(<ProductSuitePage />);
  expect(screen.queryByText('任务 image-one')).not.toBeInTheDocument();
  await waitFor(() => expect(api.mediaListGetApiV1MediaProductSuites).toHaveBeenCalledTimes(2));
});
it('accepts a file when the chooser returns during account refresh while generation stays locked', async () => {
  const view = render(<ProductSuitePage />);
  await screen.findByRole('option', { name: '可用图片模型' });
  fireEvent.change(screen.getByLabelText('商品名称'), { target: { value: '格纹套装' } });
  vi.mocked(session.useOptionalUserSession).mockReturnValue({ ...owner, status: 'loading', authenticated: false, userId: null } as never);
  view.rerender(<ProductSuitePage />);
  fireEvent.focus(window);
  expect(screen.getByLabelText('上传商品图片')).toBeEnabled();
  fireEvent.change(screen.getByLabelText('上传商品图片'), { target: { files: [new File(['x'], 'chooser.png', { type: 'image/png' })] } });
  expect(await screen.findByText('chooser.png')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /生成套图/ })).toBeDisabled();
  expect(submit).not.toHaveBeenCalled();
  vi.mocked(session.useOptionalUserSession).mockReturnValue(owner as never);
  view.rerender(<ProductSuitePage />);
  await waitFor(() => expect(screen.getByRole('button', { name: /生成套图/ })).toBeEnabled());
  vi.mocked(session.useOptionalUserSession).mockReturnValue({ ...owner, userId: 'owner-b' } as never);
  view.rerender(<ProductSuitePage />);
  expect(screen.queryByText('chooser.png')).not.toBeInTheDocument();
});
it('switches gallery columns without generating or quoting and retains the choice across views', async () => {
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValue(reply([result]));
  render(<ProductSuitePage />); await showWorks();
  const layout = screen.getByRole('slider', { name: '排列列数' });
  expect(layout).toHaveValue('4');
  expect(screen.queryByRole('button', { name: '重新生成白底图' })).not.toBeInTheDocument();
  fireEvent.change(layout, { target: { value: '3' } });
  expect(screen.getByText('任务 image-one').closest('.suite-results-grid')).toHaveStyle({ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' });
  fireEvent.click(screen.getByRole('button', { name: '功能示例' }));
  await showWorks();
  expect(screen.getByRole('slider', { name: '排列列数' })).toHaveValue('3');
  fireEvent.click(screen.getByRole('button', { name: '单列' }));
  expect(screen.getByRole('button', { name: '单列' })).toHaveAttribute('aria-pressed', 'true');
  expect(screen.getByText('任务 image-one').closest('.suite-results-grid')).toHaveStyle({ gridTemplateColumns: 'repeat(1, minmax(0, 1fr))' });
  fireEvent.click(screen.getByRole('button', { name: '网格排列' }));
  expect(screen.getByRole('slider', { name: '排列列数' })).toHaveValue('3');
  expect(screen.getByText('任务 image-one').closest('.suite-results-grid')).toHaveStyle({ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' });
  expect(quote).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
});
it('keeps gallery dimensions steady during a slider drag and applies the final columns on release', async () => {
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValue(reply([result]));
  render(<ProductSuitePage />); await showWorks();
  const slider = screen.getByRole('slider', { name: '排列列数' });
  const grid = screen.getByText('任务 image-one').closest('.suite-results-grid');
  expect(slider).toHaveValue('4');
  fireEvent.pointerDown(slider, { button: 0, pointerId: 1, isPrimary: true });
  for (const value of ['3', '1', '4', '2']) {
    fireEvent.change(slider, { target: { value } });
    expect(slider).toHaveValue(value);
    expect(slider.parentElement?.querySelector('output')).toHaveTextContent(value);
    expect(grid).toHaveStyle({ gridTemplateColumns: 'repeat(4, minmax(0, 1fr))' });
  }
  fireEvent.pointerUp(slider, { pointerId: 1 });
  expect(grid).toHaveStyle({ gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' });
  fireEvent.click(screen.getByRole('button', { name: '单列' }));
  fireEvent.click(screen.getByRole('button', { name: '网格排列' }));
  expect(slider).toHaveValue('2');
  expect(quote).not.toHaveBeenCalled();
  expect(submit).not.toHaveBeenCalled();
});
it('cancels interrupted drags and finishes a drag when pointer capture is lost', async () => {
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValue(reply([result]));
  render(<ProductSuitePage />); await showWorks();
  const slider = screen.getByRole('slider', { name: '排列列数' });
  const grid = screen.getByText('任务 image-one').closest('.suite-results-grid');
  fireEvent.pointerDown(slider, { button: 0, pointerId: 1, isPrimary: true });
  fireEvent.change(slider, { target: { value: '1' } });
  fireEvent.pointerCancel(slider, { pointerId: 1 });
  fireEvent.lostPointerCapture(slider, { pointerId: 1 });
  expect(slider).toHaveValue('4');
  expect(grid).toHaveStyle({ gridTemplateColumns: 'repeat(4, minmax(0, 1fr))' });
  fireEvent.pointerDown(slider, { button: 0, pointerId: 2, isPrimary: true });
  fireEvent.change(slider, { target: { value: '3' } });
  fireEvent.lostPointerCapture(slider, { pointerId: 2 });
  expect(grid).toHaveStyle({ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' });
  fireEvent.change(slider, { target: { value: '2' } });
  expect(grid).toHaveStyle({ gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' });
});
it('requires a fresh single-module quote before regenerating another tool and retains the previous result', async () => {
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValue(reply([result]));
  vi.mocked(api.mediaRegeneratePostApiV1MediaProductSuitesSuiteIdModulesModuleRegenerations).mockResolvedValue(reply({ ...result,
    items: [{ ...result.items[0], task: { ...result.items[0].task, id: 'image-two' }, previousResult: result.items[0].task }] }));
  render(<ProductSuitePage tool="apparel-set" />); await showWorks();
  fireEvent.click(screen.getByRole('button', { name: '重新生成白底图' }));
  expect(api.mediaRegeneratePostApiV1MediaProductSuitesSuiteIdModulesModuleRegenerations).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole('button', { name: /确认重做白底图/ }));
  await screen.findByText('任务 image-two');
  expect(api.mediaRegeneratePostApiV1MediaProductSuitesSuiteIdModulesModuleRegenerations).toHaveBeenCalledWith('suite-one', 'WHITE_BACKGROUND', { sourceTaskId: 'image-one' }, expect.objectContaining({ headers: { 'Idempotency-Key': expect.any(String) } }));
  expect(screen.getByText('上次成功结果')).toBeInTheDocument();
});
it('keeps the original regeneration key and source across reload even when balance and models change', async () => {
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValue(reply([result]));
  const regenerate = vi.mocked(api.mediaRegeneratePostApiV1MediaProductSuitesSuiteIdModulesModuleRegenerations);
  regenerate.mockRejectedValueOnce(new TypeError('network')).mockResolvedValue(reply(result));
  const view = render(<ProductSuitePage tool="apparel-set" />); await showWorks();
  fireEvent.click(screen.getByRole('button', { name: '重新生成白底图' }));
  fireEvent.click(await screen.findByRole('button', { name: /确认重做白底图/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  const first = regenerate.mock.calls[0]; view.unmount();
  vi.mocked(session.useOptionalUserSession).mockReturnValue({ ...owner, status: 'disabled', availablePoints4: '0.0000' } as never);
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({ models: [] }));
  render(<ProductSuitePage tool="apparel-set" />);
  const retry = await screen.findByRole('button', { name: '使用原请求重试' });
  expect(retry).toBeEnabled(); fireEvent.click(retry);
  await waitFor(() => expect(regenerate).toHaveBeenCalledTimes(2));
  expect(regenerate.mock.calls[1]).toEqual(first);
});
it('does not send a billable request when recovery storage cannot be written', async () => {
  render(<ProductSuitePage />); await fill();
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
  fireEvent.click(screen.getByRole('button', { name: /生成套图/ }));
  expect(await screen.findByRole('alert')).toHaveTextContent('浏览器无法保存本次请求');
  expect(submit).not.toHaveBeenCalled();
});
it('does not let an older history response erase a newly created suite', async () => {
  let finishHistory!: (value: never) => void;
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockReturnValue(new Promise(resolve => { finishHistory = resolve; }));
  render(<ProductSuitePage />); await fill();
  fireEvent.click(screen.getByRole('button', { name: /生成套图/ }));
  await screen.findByText('任务 image-one');
  await act(async () => finishHistory(reply([])));
  expect(screen.getByText('任务 image-one')).toBeInTheDocument();
});
it('fills product selling points through the configured image-aware copy operation', async () => {
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'streetwear', version: 1, name: '复古服装套图', style: '复古', modules }], copyAvailable: true }));
  vi.mocked(api.mediaCopyPostApiV1MediaProductSuitesCopy).mockResolvedValue(reply({ text: '红色格纹上衣与宽松牛仔裤搭配。' }));
  render(<ProductSuitePage />); await fill();
  fireEvent.click(screen.getByRole('button', { name: 'AI 帮写' }));
  await waitFor(() => expect(screen.getByLabelText('商品卖点')).toHaveValue('红色格纹上衣与宽松牛仔裤搭配。'));
  expect(api.mediaCopyPostApiV1MediaProductSuitesCopy).toHaveBeenCalledWith({ productName: '格纹套装', sellingPoints: '', language: '中文', fileIds: ['product-file'] });
});
it('clears the previous product after removing all its images and writes copy for the replacement', async () => {
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'streetwear', version: 1, name: '复古服装套图', modules }], copyAvailable: true }));
  const copy = vi.mocked(api.mediaCopyPostApiV1MediaProductSuitesCopy);
  copy.mockResolvedValueOnce(reply({ text: '旧商品的格纹套装卖点' })).mockResolvedValueOnce(reply({ text: '新商品的起泡出水口卖点' }));
  vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages)
    .mockResolvedValueOnce(reply({ fileId: 'product-file' }))
    .mockResolvedValueOnce(reply({ fileId: 'detail-file' }))
    .mockResolvedValueOnce(reply({ fileId: 'replacement-file' }));
  render(<ProductSuitePage />); await fill();
  fireEvent.click(screen.getByRole('button', { name: 'AI 帮写' }));
  await waitFor(() => expect(screen.getByLabelText('商品卖点')).toHaveValue('旧商品的格纹套装卖点'));
  fireEvent.change(screen.getByLabelText('上传商品图片'), { target: { files: [new File(['detail'], 'detail.png', { type: 'image/png' })] } });
  await screen.findByText('detail.png');
  await waitFor(() => expect(screen.getByRole('button', { name: '移除商品图片 1' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '移除商品图片 1' }));
  expect(screen.getByLabelText('商品名称')).toHaveValue('格纹套装');
  expect(screen.getByLabelText('商品卖点')).toHaveValue('旧商品的格纹套装卖点');
  fireEvent.click(screen.getByRole('button', { name: '移除商品图片 1' }));
  expect(screen.getByLabelText('商品名称')).toHaveValue('');
  expect(screen.getByLabelText('商品卖点')).toHaveValue('');
  expect(screen.getByRole('button', { name: 'AI 帮写' })).toBeDisabled();
  fireEvent.change(screen.getByLabelText('上传商品图片'), { target: { files: [new File(['replacement'], 'replacement.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: 'AI 帮写' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: 'AI 帮写' }));
  await waitFor(() => expect(screen.getByLabelText('商品卖点')).toHaveValue('新商品的起泡出水口卖点'));
  expect(copy).toHaveBeenLastCalledWith({ productName: '商品套图', sellingPoints: '', language: '中文', fileIds: ['replacement-file'] });
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith(expect.objectContaining({ fileIds: ['replacement-file'], sellingPoints: '新商品的起泡出水口卖点' })));
  expect(screen.getByRole('button', { name: /生成套图/ })).toBeEnabled();
});
it.each(['success', 'rejection'] as const)('preserves a newer pending request when an unmounted older request returns %s', async outcome => {
  let completeOld!: (value: never) => void;
  let rejectOld!: (reason: unknown) => void;
  submit.mockReturnValueOnce(new Promise((resolve, reject) => { completeOld = resolve; rejectOld = reject; }))
    .mockResolvedValueOnce(reply(result)).mockRejectedValueOnce(new TypeError('new request unknown')).mockResolvedValue(reply(result));
  const firstView = render(<ProductSuitePage />); await fill();
  fireEvent.click(screen.getByRole('button', { name: /生成套图/ }));
  firstView.unmount();
  const secondView = render(<ProductSuitePage />);
  fireEvent.click(await screen.findByRole('button', { name: '使用原请求重试' }));
  await screen.findByText('任务 image-one');
  fireEvent.change(screen.getByLabelText('商品名称'), { target: { value: '第二件商品' } });
  await waitFor(() => expect(screen.getByRole('button', { name: /生成套图/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /生成套图/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  const newerRequest = submit.mock.calls[2];
  await act(async () => {
    if (outcome === 'success') completeOld(reply(result));
    else rejectOld(new api.ApiError(new Response('', { status: 400 }), {}));
  });
  secondView.unmount(); render(<ProductSuitePage />);
  fireEvent.click(await screen.findByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(4));
  expect(submit.mock.calls[3]).toEqual(newerRequest);
});
it('unlocks editing after an unknown request is definitively rejected with 422 on confirmation', async () => {
  submit.mockRejectedValueOnce(new TypeError('unknown')).mockRejectedValueOnce(new api.ApiError(new Response('', { status: 422 }), { code: 'UNSUPPORTED_GENERATION_SELECTION' }));
  const view = render(<ProductSuitePage />); await fill();
  fireEvent.click(screen.getByRole('button', { name: /生成套图/ }));
  fireEvent.click(await screen.findByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(screen.getByLabelText('商品名称')).toBeEnabled());
  expect(screen.queryByRole('button', { name: '使用原请求重试' })).not.toBeInTheDocument();
  view.unmount(); render(<ProductSuitePage />);
  expect(screen.queryByRole('button', { name: '使用原请求重试' })).not.toBeInTheDocument();
});
it.each([400, 401, 403])('retains an unknown request when confirmation returns pre-replay HTTP %s', async status => {
  submit.mockRejectedValueOnce(new TypeError('unknown')).mockRejectedValueOnce(new api.ApiError(new Response('', { status }), {}));
  render(<ProductSuitePage />); await fill();
  fireEvent.click(screen.getByRole('button', { name: /生成套图/ }));
  fireEvent.click(await screen.findByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
  expect(await screen.findByRole('button', { name: '使用原请求重试' })).toBeInTheDocument();
  expect(screen.getByLabelText('商品名称')).toBeDisabled();
});
it('does not dispatch a stale retry over another tab’s newer pending request', async () => {
  submit.mockRejectedValueOnce(new TypeError('unknown'));
  render(<ProductSuitePage />); await fill();
  fireEvent.click(screen.getByRole('button', { name: /生成套图/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  const older = readPendingSuite('owner-a')!;
  clearPendingSuite('owner-a', older.key);
  const newer = { ...older, key: 'another-tab-newer-request' };
  expect(savePendingSuite('owner-a', newer)).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: '使用原请求重试' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('还有一笔未确认的套图请求');
  expect(submit).toHaveBeenCalledTimes(1);
  expect(readPendingSuite('owner-a')).toEqual(newer);
});
it('preserves multiple product images before the replica reference and restores actual options', async () => {
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'image-replica', version: 1, name: '图片复刻', modules: [modules[5]] }], copyAvailable: false }));
  const upload = vi.mocked(api.storageUploadReferenceImagePostApiV1FilesReferenceImages);
  upload.mockResolvedValueOnce(reply({ fileId: 'front' })).mockResolvedValueOnce(reply({ fileId: 'detail' })).mockResolvedValueOnce(reply({ fileId: 'layout' }));
  submit.mockRejectedValueOnce(new TypeError('unknown'));
  const view = render(<ProductSuitePage tool="image-replica" />);
  await screen.findByRole('option', { name: '可用图片模型' });
  for (const name of ['front', 'detail']) {
    fireEvent.change(screen.getByLabelText('上传商品图片'), { target: { files: [new File(['x'], `${name}.png`, { type: 'image/png' })] } });
    await screen.findByText(`${name}.png`);
  }
  fireEvent.change(screen.getByLabelText('上传参考效果图'), { target: { files: [new File(['x'], 'layout.png', { type: 'image/png' })] } });
  await screen.findByText('layout.png');
  fireEvent.click(screen.getByRole('radio', { name: /参考风格/ }));
  fireEvent.change(screen.getByLabelText('商品平台'), { target: { value: '抖音电商' } });
  fireEvent.change(screen.getByLabelText('文案语言'), { target: { value: '日文' } });
  await waitFor(() => expect(screen.getByRole('button', { name: /生成图片复刻.*1 张/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /生成图片复刻.*1 张/ }));
  await screen.findByRole('button', { name: '使用原请求重试' });
  const first = submit.mock.calls[0];
  expect(first[0]).toMatchObject({ fileIds: ['front', 'detail', 'layout'], language: '日文', options: { platform: '抖音电商', replicationMode: 'STYLE' } });
  view.unmount(); render(<ProductSuitePage tool="image-replica" />);
  expect(screen.getByRole('radio', { name: /参考风格/ })).toBeChecked();
  expect(screen.getAllByRole('button', { name: /移除商品图片/ })).toHaveLength(2);
  expect(screen.getAllByRole('button', { name: /移除参考效果图/ })).toHaveLength(1);
  fireEvent.click(screen.getByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2)); expect(submit.mock.calls[1]).toEqual(first);
});
it('sends only the selected retouch enhancements through real request options', async () => {
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'product-retouch', version: 1, name: '产品精修', modules: [modules[3]] }], copyAvailable: false }));
  render(<ProductSuitePage tool="product-retouch" />);
  await screen.findByRole('option', { name: '可用图片模型' });
  expect(screen.getByRole('checkbox', { name: '增强产品光泽' })).toBeChecked();
  fireEvent.click(screen.getByRole('checkbox', { name: '增强产品光泽' }));
  fireEvent.click(screen.getByRole('checkbox', { name: '修复划痕瑕疵' }));
  fireEvent.click(screen.getByRole('checkbox', { name: '色彩校正' }));
  fireEvent.change(screen.getByLabelText('上传商品图片'), { target: { files: [new File(['x'], 'retouch.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: /生成产品精修.*1 张/ })).toBeEnabled());
  expect(screen.getByLabelText('上传商品图片')).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: /生成产品精修.*1 张/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ options: expect.objectContaining({ enhancements: ['REPAIR', 'COLOR'] }) }), expect.anything()));
});
it('keeps mandatory apparel views and invalidates quotes when real module counts change', async () => {
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'apparel-set', version: 1, name: '服装组图', modules: apparelModules }], copyAvailable: false }));
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({ models: [{ type: 'IMAGE', model: 'image-model', displayName: '可用图片模型', maxReferenceImages: 8, maxCount: 4, aspectRatios: ['1:1'], resolutions: ['1K'] }] }));
  render(<ProductSuitePage tool="apparel-set" />);
  await screen.findByRole('option', { name: '可用图片模型' });
  for (const name of ['白底精修', '3D 立体效果', '人台图']) {
    const checkbox = screen.getByRole('checkbox', { name: `${name}${name}` });
    expect(checkbox).toBeChecked(); expect(checkbox).toBeDisabled();
  }
  fireEvent.change(screen.getByLabelText('上传服装图片'), { target: { files: [new File(['x'], 'shirt.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: /生成服装组图.*3 张/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('checkbox', { name: '细节展示细节展示' }));
  fireEvent.click(screen.getByRole('button', { name: '增加细节展示数量' }));
  expect(screen.getByRole('button', { name: /生成服装组图.*5 张/ })).toBeDisabled();
  fireEvent.click(screen.getByRole('radio', { name: '背面' }));
  fireEvent.click(screen.getByRole('checkbox', { name: '3D 立体效果图使用白底' }));
  fireEvent.click(screen.getByRole('checkbox', { name: '人台图使用白底' }));
  await waitFor(() => expect(screen.getByRole('button', { name: /生成服装组图.*5 张/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /生成服装组图.*5 张/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ templateId: 'apparel-set', modules: ['WHITE_BACKGROUND', 'VOLUME_3D', 'MANNEQUIN', 'DETAILS'], options: expect.objectContaining({ garmentView: 'BACK', whiteBackground3d: false, whiteBackgroundMannequin: true, moduleCounts: [{ module: 'DETAILS', count: 2 }] }) }), expect.anything()));
});
it('shows fourteen detail modules with five selected defaults and a locked hero', async () => {
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'detail-page', version: 2, name: 'A+/详情页', modules: detailModules }], copyAvailable: false }));
  render(<ProductSuitePage tool="detail-page" />); await screen.findByRole('option', { name: '可用图片模型' });
  expect(screen.getAllByRole('checkbox')).toHaveLength(14);
  await waitFor(() => expect(screen.getAllByRole('checkbox').filter(input => (input as HTMLInputElement).checked)).toHaveLength(5));
  expect(screen.getByRole('checkbox', { name: /首屏主视觉/ })).toBeDisabled();
  fireEvent.change(screen.getByLabelText('上传商品图片'), { target: { files: [new File(['x'], 'product.png', { type: 'image/png' })] } });
  await waitFor(() => expect(screen.getByRole('button', { name: /生成详情页.*5 张/ })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: /生成详情页.*5 张/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ templateVersion: 2, modules: ['WHITE_BACKGROUND', 'SELLING_POINTS', 'MODEL_SCENE', 'MULTI_ANGLE', 'COMPARISON'] }), expect.anything()));
});
it.each([1, 4])('applies image-aware plans within model maxCount %i', async maxCount => {
  const detailCount = Math.min(2, maxCount);
  const generateName = new RegExp(`生成套图.*${1 + detailCount} 张`);
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'streetwear', version: 1, name: '服装套图', modules }, { id: 'general', version: 1, name: '通用套图', modules }], copyAvailable: true }));
  vi.mocked(api.mediaOptionsGetApiV1MediaGenerationOptions).mockResolvedValue(reply({ models: [{ type: 'IMAGE', model: 'image-model', displayName: '可用图片模型', maxReferenceImages: 8, maxCount, aspectRatios: ['1:1'], resolutions: ['1K'] }] }));
  vi.mocked(api.mediaPlanPostApiV1MediaProductSuitesPlan).mockResolvedValue(reply({ modules: [{ module: 'WHITE_BACKGROUND', count: 1 }, { module: 'DETAILS', count: 2 }] }));
  render(<ProductSuitePage />); await fill();
  expect(api.mediaPlanPostApiV1MediaProductSuitesPlan).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '智能匹配' }));
  await waitFor(() => expect(screen.getByLabelText('套图模板')).toHaveValue('general'));
  await waitFor(() => expect(screen.getByRole('button', { name: generateName })).toBeEnabled());
  expect(api.mediaPlanPostApiV1MediaProductSuitesPlan).toHaveBeenCalledWith({ productName: '格纹套装', sellingPoints: '', language: '中文', fileIds: ['product-file'] });
  fireEvent.click(screen.getByRole('button', { name: generateName }));
  await waitFor(() => expect(submit).toHaveBeenCalledWith(expect.objectContaining({ templateId: 'general', modules: ['WHITE_BACKGROUND', 'DETAILS'], options: expect.objectContaining({ moduleCounts: detailCount > 1 ? [{ module: 'DETAILS', count: detailCount }] : [] }) }), expect.anything()));
});
it('does not invoke image-aware planning when no vision supplier is available', async () => {
  render(<ProductSuitePage />); await fill();
  expect(screen.getByRole('button', { name: '智能匹配' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '智能匹配' }));
  expect(api.mediaPlanPostApiV1MediaProductSuitesPlan).not.toHaveBeenCalled();
});
it('quotes retries with only the selected module count and preserves other tool options', async () => {
  const counted = { ...result, request: { ...request, options: { platform: '抖音电商', moduleCounts: [{ module: 'WHITE_BACKGROUND', count: 2 }, { module: 'DETAILS', count: 3 }] } } };
  vi.mocked(api.mediaListGetApiV1MediaProductSuites).mockResolvedValue(reply([counted]));
  render(<ProductSuitePage tool="apparel-set" />); await showWorks();
  fireEvent.click(screen.getByRole('button', { name: '重新生成白底图' }));
  await waitFor(() => expect(quote).toHaveBeenLastCalledWith({ ...counted.request, modules: ['WHITE_BACKGROUND'], options: { platform: '抖音电商', moduleCounts: [{ module: 'WHITE_BACKGROUND', count: 2 }] } }));
});
it('initializes current detail defaults after confirming a legacy version-one pending request', async () => {
  const legacy = { ...request, templateId: 'detail-page', modules: ['WHITE_BACKGROUND', 'FEATURES', 'DETAILS', 'OVERVIEW'] };
  savePendingSuite('owner-a', { key: 'legacy-detail-pending', request: legacy as never }, 'detail-page');
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'detail-page', version: 2, name: 'A+/详情页', modules: detailModules }], copyAvailable: false }));
  submit.mockResolvedValueOnce(reply({ ...result, request: legacy })).mockResolvedValue(reply(result));
  render(<ProductSuitePage tool="detail-page" />);
  fireEvent.click(screen.getByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(screen.getByRole('button', { name: /生成详情页.*5 张/ })).toBeEnabled());
  expect(screen.getByRole('checkbox', { name: /首屏主视觉/ })).toBeChecked();
  expect(screen.getByRole('checkbox', { name: /首屏主视觉/ })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: /生成详情页.*5 张/ }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
  expect(submit.mock.calls[0][0]).toEqual(legacy);
  expect(submit.mock.calls[1][0]).toMatchObject({ templateVersion: 2, modules: ['WHITE_BACKGROUND', 'SELLING_POINTS', 'MODEL_SCENE', 'MULTI_ANGLE', 'COMPARISON'] });
});
it('initializes required apparel modules after confirming a pending regeneration', async () => {
  const apparelRequest = { ...request, templateId: 'apparel-set', modules: ['WHITE_BACKGROUND', 'VOLUME_3D', 'MANNEQUIN'] };
  savePendingSuite('owner-a', { key: 'apparel-retry', request: apparelRequest as never, regeneration: { suiteId: 'suite-one', module: 'WHITE_BACKGROUND', sourceTaskId: 'image-one' } }, 'apparel-set');
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'apparel-set', version: 1, name: '服装组图', modules: apparelModules }], copyAvailable: false }));
  vi.mocked(api.mediaRegeneratePostApiV1MediaProductSuitesSuiteIdModulesModuleRegenerations).mockResolvedValue(reply({ ...result, request: apparelRequest }));
  render(<ProductSuitePage tool="apparel-set" />);
  fireEvent.click(screen.getByRole('button', { name: '使用原请求重试' }));
  await waitFor(() => expect(screen.getByRole('button', { name: /生成服装组图.*3 张/ })).toBeInTheDocument());
  for (const name of ['白底精修', '3D 立体效果', '人台图']) {
    expect(screen.getByRole('checkbox', { name: `${name}${name}` })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: `${name}${name}` })).toBeDisabled();
  }
});
it('allows an authenticated disabled account to confirm its pending AI portrait inside model wear', async () => {
  const portrait = { key: 'model-pending-key', request: { type: 'IMAGE', count: 1, durationSeconds: 0, model: 'image-model', aspectRatio: '1:1', resolution: '1K', references: [], prompt: '明确已成年全身模特' } };
  localStorage.setItem('xzwl-model-portrait:owner-a', JSON.stringify(portrait));
  vi.mocked(session.useOptionalUserSession).mockReturnValue({ ...owner, status: 'disabled', availablePoints4: '0.0000' } as never);
  vi.mocked(api.mediaTemplatesGetApiV1MediaProductSuitesTemplates).mockResolvedValue(reply({ templates: [{ id: 'model-wear', version: 1, name: '模特穿戴', modules: [modules[4]] }], copyAvailable: false }));
  vi.mocked(api.mediaSubmitPostApiV1MediaTasks).mockResolvedValue(reply({ id: 'portrait-one', type: 'IMAGE', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'model-file' }] }));
  vi.mocked(api.mediaGetGetApiV1MediaTasksTaskId).mockResolvedValue(reply({ id: 'portrait-one', type: 'IMAGE', status: 'SUCCEEDED', results: [{ index: 0, fileId: 'model-file' }] }));
  render(<ProductSuitePage tool="model-wear" />);
  fireEvent.click(screen.getByRole('radio', { name: 'AI 生成' }));
  const retry = await screen.findByRole('button', { name: '使用原请求重试' });
  expect(retry).toBeEnabled(); fireEvent.click(retry);
  await waitFor(() => expect(api.mediaSubmitPostApiV1MediaTasks).toHaveBeenCalledWith(portrait.request, { headers: { 'Idempotency-Key': portrait.key } }));
  expect(submit).not.toHaveBeenCalled();
});
