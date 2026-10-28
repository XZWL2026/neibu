// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";

import { act, cleanup, render } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { LoginSpace } from './LoginSpace';

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it.each([[false,1000,710], [false,366,680], [false,1000,400], [true,1000,710]] as const)('renders independent planet motion and cleans up (reduced=%s, %sx%s)', (reduced,width,height) => {
  const methods = ['activeTexture','createProgram','createShader','createBuffer','createTexture','shaderSource','compileShader','getShaderParameter','attachShader','linkProgram','getProgramParameter','useProgram','bindBuffer','bufferData','getAttribLocation','enableVertexAttribArray','vertexAttribPointer','getUniformLocation','uniform1i','uniform1f','uniform2f','uniform3f','bindTexture','texParameteri','texImage2D','viewport','drawArrays','deleteTexture','deleteBuffer','deleteShader','deleteProgram'];
  const gl = Object.fromEntries(methods.map(name => [name, vi.fn<(...args: unknown[]) => unknown>(() => true)]));
  gl.getUniformLocation.mockImplementation((...args: unknown[]) => args[1]);
  vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue(gl as never);
  vi.stubGlobal('matchMedia',vi.fn(() => ({matches:reduced,addEventListener:vi.fn(),removeEventListener:vi.fn()})));
  vi.spyOn(performance,'now').mockReturnValue(0);
  const images: Array<{onload: null | (() => void)}> = [];
  vi.stubGlobal('Image',class { onload = null; constructor() {images.push(this);} });
  const disconnect=vi.fn();
  vi.stubGlobal('ResizeObserver',class {observe() {} disconnect=disconnect;});
  let tick: FrameRequestCallback = () => {};
  const request=vi.fn((callback: FrameRequestCallback) => {tick=callback; return 7;});
  const cancel=vi.fn(); vi.stubGlobal('requestAnimationFrame',request); vi.stubGlobal('cancelAnimationFrame',cancel);
  vi.spyOn(HTMLCanvasElement.prototype,'clientWidth','get').mockReturnValue(width);
  vi.spyOn(HTMLCanvasElement.prototype,'clientHeight','get').mockReturnValue(height);
  const {container,unmount}=render(<LoginSpace />);
  expect(container.firstChild).not.toHaveClass('is-animated');
  if (reduced) {
    expect(images).toHaveLength(0);
    expect(request).not.toHaveBeenCalled();
  } else {
    act(() => images.slice(0,3).forEach(image => image.onload?.()));
    expect(container.firstChild).not.toHaveClass('is-animated');
    act(() => images[3].onload?.());
    expect(container.firstChild).toHaveClass('is-animated');
    const start=gl.uniform3f.mock.calls.find(call => call[0]==='moon')!;
    act(() => tick(10000));
    const later=gl.uniform3f.mock.calls.filter(call => call[0]==='moon').at(-1)!;
    expect(Number(later[1])).toBeGreaterThan(Number(start[1]));
    expect(Number(later[2])).toBeGreaterThan(Number(start[2]));
    expect(gl.uniform1f).toHaveBeenCalledWith('moonSpin',expect.closeTo(2*Math.PI*10000/120000));
    expect(gl.drawArrays).toHaveBeenCalledTimes(2);
    // Cover crops the source image: a loop must return inside the real window,
    // not spend seconds travelling from an off-screen source-image starting point.
    const scale=Math.max(width/1672,height/941);
    const left=836-width/scale/2, right=836+width/scale/2;
    const top=470.5-height/scale/2, bottom=470.5+height/scale/2;
    let previousX=Number(later[1]), returns=0;
    for (let time=10500; time<=180000; time+=500) {
      act(() => tick(time));
      const position=gl.uniform3f.mock.calls.at(-1)!;
      const x=Number(position[1]), y=Number(position[2]), radius=Number(position[3]);
      expect(x+radius).toBeGreaterThan(left);
      expect(x-radius).toBeLessThan(right);
      expect(y+radius).toBeGreaterThan(top);
      expect(y-radius).toBeLessThan(bottom);
      if (x<previousX) {
        returns++;
        expect(x-radius).toBeGreaterThanOrEqual(left);
        expect(x).toBeLessThan((left+right)/2);
        expect(y).toBeLessThan((top+bottom)/2);
      }
      previousX=x;
    }
    expect(returns).toBeGreaterThanOrEqual(2);
    expect(gl.uniform1f).toHaveBeenCalledWith('spin',expect.closeTo(2*Math.PI*10000/600000));
    // The slower Earth angle still crosses a full-turn boundary continuously.
    act(() => tick(599980));
    const before=Number(gl.uniform1f.mock.calls.filter(call=>call[0]==='spin').at(-1)![1]);
    act(() => tick(600020));
    const after=Number(gl.uniform1f.mock.calls.filter(call=>call[0]==='spin').at(-1)![1]);
    expect(Math.cos(after)).toBeCloseTo(Math.cos(before),4);
    expect(Math.abs(Math.sin(after)-Math.sin(before))).toBeLessThan(.01);
    vi.spyOn(document,'hidden','get').mockReturnValue(true);
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    expect(cancel).toHaveBeenCalledWith(7);
    act(() => container.querySelector("canvas")!.dispatchEvent(new Event("webglcontextlost",{cancelable:true})));
    expect(container.firstChild).not.toHaveClass("is-animated");
  }
  unmount();
  if (!reduced) {expect(gl.deleteTexture).toHaveBeenCalledTimes(3); expect(gl.deleteProgram).toHaveBeenCalled();expect(disconnect).toHaveBeenCalled();}
});

it('keeps the original background when WebGL is unavailable', () => {
  vi.stubGlobal('matchMedia',vi.fn(() => ({matches:false,addEventListener:vi.fn(),removeEventListener:vi.fn()})));
  vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue(null);
  const {container}=render(<LoginSpace />);
  expect(container.firstChild).toHaveClass('auth-scenery');
  expect(container.firstChild).not.toHaveClass('is-animated');
});
