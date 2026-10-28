import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { CommerceWorkbench } from './CommerceWorkbench';

vi.mock('../UserFrame', () => ({ UserFrame: ({ children }: { children: React.ReactNode }) => <main>{children}</main> }));
vi.mock('../creator/ProductionImageComposer', () => ({ ProductionImageComposer: ({ active, inspiration }: { active: boolean; inspiration?: { prompt: string } }) => <label>图片描述<input data-active={active} value={inspiration?.prompt ?? ''} readOnly /></label> }));
vi.mock('../creator/ProductionVideoComposer', () => ({ ProductionVideoComposer: ({ active, inspiration }: { active: boolean; inspiration?: { prompt: string } }) => <label>视频描述<input data-active={active} value={inspiration?.prompt ?? ''} readOnly /></label> }));
beforeEach(() => { sessionStorage.clear(); vi.spyOn(window, 'scrollTo').mockImplementation(() => {}); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const show = () => render(<MemoryRouter><CommerceWorkbench /></MemoryRouter>);

it('keeps all eight working tool routes on the recommended carousel', () => {
  show();
  const tools = within(screen.getByRole('region', { name: '热门工具' }));
  const routes = ['product-retouch', 'detail-page', 'product-suite', 'model-wear', 'video-replica', 'seller-video', 'image-replica', 'apparel-set'];
  const links = tools.getAllByRole('link', { hidden: true });
  expect(links).toHaveLength(8);
  expect(links.map(link => link.getAttribute('href'))).toEqual(routes.map(route => `/commerce/${route}`));
  expect(tools.getByRole('button', { name: '查看商品套图' })).toHaveAttribute('aria-pressed', 'true');
  fireEvent.click(tools.getByRole('button', { name: '下一个工具' }));
  expect(tools.getByRole('button', { name: '查看模特穿戴' })).toHaveAttribute('aria-pressed', 'true');
});

it('shows seven reference products with a model preview action', () => {
  show();
  const tools = within(screen.getByRole('region', { name: '热门工具' }));
  fireEvent.click(tools.getByRole('button', { name: '商品' }));
  expect(screen.getByRole('region', { name: '热门工具' }).querySelectorAll('.commerce-product-card')).toHaveLength(7);
  expect(tools.getByRole('button', { name: '3D 实物预览 ↗' })).toBeEnabled();
  expect(screen.getByRole('region', { name: '热门工具' }).querySelector('.commerce-product-stage-copy strong')).toHaveTextContent('NiceDesign');
  fireEvent.click(tools.getByRole('button', { name: '查看AURUM ONE' }));
  expect(screen.getByRole('region', { name: '热门工具' }).querySelector('.commerce-product-stage-copy strong')).toHaveTextContent('AURUM ONE');
});

it('shows static image and video groups without changing the other filters', () => {
  show();
  const tools = within(screen.getByRole('region', { name: '热门工具' }));
  fireEvent.click(tools.getByRole('button', { name: '图片' }));
  expect(tools.getAllByRole('link')).toHaveLength(2);
  fireEvent.click(tools.getByRole('button', { name: '视频' }));
  expect(tools.getAllByRole('link')).toHaveLength(2);
  expect(screen.getByRole('button', { name: '全部' })).toHaveAttribute('aria-pressed', 'true');
});

it('previews a reference image and loads its prompt into the composer', () => {
  show();
  const gallery = within(screen.getByRole('region', { name: '创作灵感' }));
  expect(gallery.getAllByRole('button', { name: /·/ })).toHaveLength(120);
  fireEvent.click(gallery.getByRole('button', { name: '石上香氛 · 商品套图' }));
  const preview = screen.getByRole('dialog', { name: '石上香氛 · 商品套图' });
  expect(preview.querySelector('img')).toHaveAttribute('src', expect.stringContaining('/inspiration/'));
  expect(within(preview).getByRole('link', { name: '进入商品套图 ↗' })).toHaveAttribute('href', '/commerce/product-suite');
  fireEvent.click(within(preview).getByRole('button', { name: '使用这个灵感' }));
  expect((screen.getByLabelText('图片描述') as HTMLInputElement).value).toContain('石上香氛');
});

it('filters the full image catalogue and keeps every card after resizing', () => {
  vi.stubGlobal('innerWidth', 1280);
  const { container } = show();
  const gallery = within(screen.getByRole('region', { name: '创作灵感' }));
  fireEvent.click(gallery.getByRole('button', { name: '3C数码' }));
  expect(gallery.getAllByRole('button', { name: /·/ }).length).toBeGreaterThan(0);
  fireEvent.click(gallery.getByRole('button', { name: '全部' }));
  vi.stubGlobal('innerWidth', 390);
  fireEvent(window, new Event('resize'));
  expect(container.querySelectorAll('.commerce-inspiration-column')).toHaveLength(2);
  expect(gallery.getAllByRole('button', { name: /·/ })).toHaveLength(120);
});

it('restores safe browse defaults when stored preferences are invalid', () => {
  sessionStorage.setItem('xzwl:commerce-browse:v1', JSON.stringify({ category: 'unknown', industry: 'unknown', active: 99, scrollY: -1 }));
  show();
  expect(screen.getByRole('button', { name: '全部' })).toHaveAttribute('aria-pressed', 'true');
  expect(within(screen.getByRole('region', { name: '热门工具' })).getAllByRole('link', { hidden: true })).toHaveLength(8);
});

it('keeps the sidebar hidden until scrolling all the way back to the top', () => {
  const { container } = show();
  const page = container.querySelector('.commerce-workbench');
  vi.stubGlobal('scrollY', 240);
  fireEvent.scroll(window);
  expect(page).toHaveAttribute('data-nav-hidden', 'true');
  vi.stubGlobal('scrollY', 0);
  fireEvent.scroll(window);
  expect(page).toHaveAttribute('data-nav-hidden', 'false');
});
