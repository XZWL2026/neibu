import { afterEach, vi } from 'vitest';
import { config } from '@vue/test-utils';
import {defineComponent,h} from 'vue';
// JSDOM has no layout; exercise table renderers with an unvirtualized adapter.
// The actual Naive UI virtual table is exercised by the browser suite.
config.global.stubs.NDataTable=defineComponent({props:['columns','data','loading'],setup(props){return()=>h('table',{},[h('thead',{},[h('tr',{},(props.columns??[]).map((column:any)=>h('th',{},typeof column.title==='function'?column.title():column.title)))]),h('tbody',{},(props.data??[]).map((row:any,index:number)=>h('tr',{},props.columns.map((column:any)=>h('td',{},column.render?column.render(row,index):row[column.key])))))]);}});
config.global.stubs.DataTable = config.global.stubs.NDataTable;
import { adminRequestCache } from './data/requestCache';
Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable:true, value() { this.setAttribute('open',''); } });
Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable:true, value() { this.removeAttribute('open'); } });
Object.defineProperty(window, 'matchMedia', { configurable:true, value: (query:string) => ({ matches:false, media:query, onchange:null, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent() { return false; } }) });
Object.defineProperty(window, 'scrollTo', { configurable:true, value() {} });
// JSDOM omits element scrolling; Naive UI scrolls the selected popup option.
Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable:true, value(options:ScrollToOptions|number, y?:number) {
  this.scrollLeft = typeof options === 'number' ? options : options.left ?? this.scrollLeft;
  this.scrollTop = typeof options === 'number' ? y ?? this.scrollTop : options.top ?? this.scrollTop;
} });
// axe only probes text measurement; chart rendering is covered in the real browser.
Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', { configurable:true, value() { return null; } });
vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
vi.stubGlobal('requestAnimationFrame', (callback:FrameRequestCallback) => setTimeout(() => callback(0),0));
vi.stubGlobal('cancelAnimationFrame', (handle:number) => clearTimeout(handle));
// Canvas output is exercised in Playwright; unit tests verify options and lifecycle calls.
vi.mock('./charts/engine', () => ({ init: () => ({ setOption:vi.fn(), resize:vi.fn(), dispose:vi.fn() }) }));
afterEach(() => adminRequestCache.clear());
