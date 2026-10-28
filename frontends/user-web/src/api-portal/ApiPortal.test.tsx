import '@testing-library/jest-dom/vitest';
import { render, cleanup, fireEvent, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { ApiPortal } from './ApiPortal';
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it.each(['', '#materials', '#models', '#api-account', '#keys'])('renders documentation in place at %s without redirect or API calls', hash => {
 const replace=vi.fn(),fetch=vi.fn();vi.stubGlobal('location',{replace});vi.stubGlobal('fetch',fetch);
 render(<MemoryRouter initialEntries={['/api-docs'+hash]}><ApiPortal/></MemoryRouter>);
 expect(screen.getByRole('heading',{name:'API 文档'})).toBeInTheDocument();
 expect(replace).not.toHaveBeenCalled();expect(fetch).not.toHaveBeenCalled();
 if(hash==='#models'){
  expect(screen.getByRole('heading',{name:'模型与能力'})).toBeInTheDocument();
  expect(screen.getByRole('table',{name:'请求参数'})).toHaveTextContent('models[].model');
  expect(screen.getByRole('table',{name:'请求参数'})).toHaveTextContent('models[].displayName');
  expect(screen.getByRole('region',{name:'请求示例'})).toHaveTextContent('https://aiyomio.cn/api/v1/media/generation-options');
 }
 if(hash==='#materials')expect(screen.getByRole('heading',{name:'人像库审核'})).toBeInTheDocument();
});
it('switches local topics and languages while keeping current callable paths',()=>{
 render(<MemoryRouter initialEntries={['/api-docs#materials']}><ApiPortal/></MemoryRouter>);
 expect(screen.getByRole('region',{name:'请求示例'})).toHaveTextContent('/api/v1/media/portraits');
 fireEvent.click(screen.getByRole('button',{name:'Python'}));
 expect(screen.getByRole('region',{name:'请求示例'})).toHaveTextContent('urllib.request');
 fireEvent.click(screen.getByRole('link',{name:'视频生成'}));
 expect(screen.getByRole('heading',{name:'视频生成'})).toBeInTheDocument();
 expect(screen.getByRole('region',{name:'请求示例'})).toHaveTextContent('durationSeconds');
 expect(document.body).not.toHaveTextContent('/api/external/v1/');
 fireEvent.click(screen.getByRole('button',{name:'Node.js'}));
 const nodeCode=screen.getByRole('region',{name:'请求示例'}).textContent!;
 expect(()=>new Function('return async () => {'+nodeCode+'}')).not.toThrow();
 fireEvent.click(screen.getByRole('link',{name:'人像库审核'}));
 fireEvent.change(screen.getByLabelText('示例操作'),{target:{value:'1'}});
 expect(screen.getByRole('region',{name:'请求示例'})).toHaveTextContent('method: "GET"');
 expect(screen.getByRole('region',{name:'请求示例'})).not.toHaveTextContent('JSON.stringify');
});
