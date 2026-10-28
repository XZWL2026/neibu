import { readPromptInput, editPromptInput } from './mention-test-helpers';
import { mentionCaret, placeMentionCaret, readMentionText, selectedMentionText } from './RichMaterialInput';
import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useState } from 'react';
import type { ComposerMaterial } from './ComposerMaterials';
import { MaterialPrompt, useMaterialMentions } from './MaterialPrompt';
import { promptCaretRect } from './prompt-caret';
const files = [{ fileId: 'file-a', name: '猫.png', preview: 'https://assets.example/a.png' }, { fileId: 'file-b', name: '房间.png', preview: 'https://assets.example/b.png' }];
afterEach(cleanup);
afterEach(() => vi.restoreAllMocks());
it('maps textarea caret and scroll offsets through a scaled canvas ancestor', () => {
  const input = document.createElement('textarea');
  input.value = '参考 @'; input.selectionStart = input.value.length;
  Object.defineProperties(input, { offsetWidth: { value: 400 }, offsetHeight: { value: 200 },
    clientWidth: { value: 396 }, clientLeft: { value: 2 }, clientTop: { value: 2 },
    scrollTop: { value: 20 }, scrollLeft: { value: 10 } });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    return this === input ? new DOMRect(100, 100, 200, 100) : new DOMRect(182, 162, 0, 24);
  });
  const rect = promptCaretRect(input);
  expect({ x: rect.x, y: rect.y, height: rect.height }).toEqual({ x: 136, y: 121, height: 12 });
});
function Editor({ materials = files, issues = ['', ''], kind = 'IMAGE' }: { materials?: ComposerMaterial[]; issues?: string[]; kind?: 'IMAGE' | 'VIDEO' | 'OMNI' }) {
  const [prompt, setPrompt] = useState('');
  const mentions = useMaterialMentions(prompt, materials, issues, kind);
  return <><MaterialPrompt label="提示词" placeholder="描述" value={prompt} onChange={setPrompt} mentions={mentions} disabled={false} /><output aria-label="提交提示词">{mentions.compiled}</output></>;
}
it('keeps unmatched mentions quiet on click and reopens only when candidates match', () => {
  const view = render(<Editor materials={[]} />);
  const input = screen.getByRole('textbox');
  editPromptInput(input, '@');
  fireEvent.click(input);
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  expect(screen.queryByText(/暂无匹配的已上传素材/)).not.toBeInTheDocument();
  expect(input).not.toHaveAttribute('aria-controls');
  expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(true);
  view.rerender(<Editor />);
  expect(screen.getAllByRole('option')).toHaveLength(2);
  editPromptInput(input, '@不存在的素材');
  fireEvent.click(input);
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  editPromptInput(input, '@猫');
  expect(screen.getAllByRole('option')).toHaveLength(1);
});
it('anchors the top-layer list to a wrapped textarea caret and follows scrolling and viewport edges', () => {
  let caretTop = 180;
  const rect = (x: number, y: number, width: number, height: number) => new DOMRect(x, y, width, height);
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    if (this.tagName === 'TEXTAREA') return rect(100, 100, 900, 620);
    if (this.tagName === 'SPAN') return rect(820, caretTop, 0, 24);
    return rect(0, 0, 360, 120);
  });
  render(<Editor />);
  const input = screen.getByRole('textbox');
  editPromptInput(input, '多行提示词\n换行后 @');
  const menu = screen.getByRole('listbox');
  expect(menu).toHaveAttribute('popover', 'manual');
  expect(menu.style.top).toBe('210px');
  expect(parseFloat(menu.style.left) + 360).toBeLessThanOrEqual(window.innerWidth - 8);
  caretTop = 680;
  fireEvent.scroll(input);
  expect(menu.style.top).toBe('554px');
  fireEvent.keyDown(input, { key: 'Escape' });
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
});
it('anchors another mention after a chip to the live rich-text selection without changing the caret', () => {
  const original = Object.getOwnPropertyDescriptor(Range.prototype, 'getBoundingClientRect');
  let x = 250;
  Object.defineProperty(Range.prototype, 'getBoundingClientRect', { configurable: true, value: () => new DOMRect(x, 200, 0, 24) });
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(100, 100, 600, 500));
  try {
    render(<Editor />);
    editPromptInput(screen.getByRole('textbox'), '@');
    fireEvent.click(screen.getByRole('option', { name: /猫.png/ }));
    const field = screen.getByRole('textbox');
    const text = readPromptInput(field) + '\n接着 @';
    editPromptInput(field, text);
    expect(screen.getByRole('listbox').style.left).toBe('250px');
    expect(screen.getByRole('listbox').style.top).toBe('230px');
    expect(mentionCaret(field)).toBe(text.length);
    x = 320;
    fireEvent(window, new Event('resize'));
    expect(screen.getByRole('listbox').style.left).toBe('320px');
    fireEvent.keyDown(field, { key: 'Enter' });
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(screen.getByLabelText('提交提示词')).toHaveTextContent('图1 接着 图1');
  } finally {
    if (original) Object.defineProperty(Range.prototype, 'getBoundingClientRect', original);
    else Reflect.deleteProperty(Range.prototype, 'getBoundingClientRect');
  }
});
it('inserts at the caret, navigates without submitting, and binds reverse mentions to unchanged attachment order', () => {
  render(<Editor />);
  const input = screen.getByRole('textbox');
  fireEvent.change(input, { target: { value: '把@放在背景', selectionStart: 2, selectionEnd: 2 } });
  expect(screen.getAllByRole('option')).toHaveLength(2);
  fireEvent.keyDown(input, { key: 'ArrowDown' });
  fireEvent.select(input);
  fireEvent.keyUp(input, { key: 'ArrowDown' });
  expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(false);
  const rich = screen.getByRole('textbox');
  expect(readPromptInput(rich)).toBe('把@【房间.png · 1】放在背景');
  editPromptInput(rich, '把@【房间.png · 1】放在背景 @');
  fireEvent.click(screen.getByRole('option', { name: /猫.png/ }));
  expect(screen.getByLabelText('提交提示词')).toHaveTextContent('把图2放在背景 图1');
  expect(screen.getByLabelText('提交提示词')).not.toHaveTextContent('@【');
});
it('blocks stale, replaced, incompatible and unknown mentions without silently rebinding names', () => {
  const view = render(<Editor />); const input = screen.getByRole('textbox');
  fireEvent.change(input, { target: { value: '@', selectionStart: 1 } });
  fireEvent.keyDown(input, { key: 'Enter' });
  view.rerender(<Editor materials={[{ ...files[0], fileId: 'replacement' }, files[1]]} />);
  expect(screen.getByRole('alert')).toHaveTextContent('失效');
  view.rerender(<Editor issues={['当前模型不支持', '']} />);
  expect(screen.getByRole('alert')).toHaveTextContent('当前模型不支持');
  editPromptInput(screen.getByRole('textbox'), '@【伪造 · 9】');
  expect(screen.getByRole('alert')).toHaveTextContent('失效');
});
it('keeps video first/last positions, closes with Escape and does not offer incompatible frames', () => {
  render(<Editor kind="VIDEO" issues={['', '不支持尾帧']} />);
  const input = screen.getByRole('textbox');
  fireEvent.change(input, { target: { value: '@', selectionStart: 1 } });
  expect(screen.getAllByRole('option')).toHaveLength(1);
  fireEvent.keyDown(input, { key: 'Escape' }); expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  fireEvent.change(input, { target: { value: '从@', selectionStart: 2 } });
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(screen.getByLabelText('提交提示词')).toHaveTextContent('从首帧图片');
});
it('tracks keyboard caret movement instead of replacing the previous mention range', () => {
  render(<Editor />); const input = screen.getByRole('textbox') as HTMLTextAreaElement;
  fireEvent.change(input, { target: { value: '前文 @ 后文', selectionStart: 4 } });
  expect(screen.getByRole('listbox')).toBeVisible();
  // Keyboard navigation changes selection without firing change/click.
  input.setSelectionRange(7, 7); fireEvent.select(input);
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(true);
  expect(input).toHaveValue('前文 @ 后文');
  input.setSelectionRange(4, 4); fireEvent.select(input);
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(readPromptInput(screen.getByRole('textbox'))).toBe('前文 @【猫.png · 1】 后文');
  expect(mentionCaret(screen.getByRole('textbox'))).toBe('前文 @【猫.png · 1】'.length);
});

it('compiles omni mentions by real file binding and ordinal within each media type', () => {
  const media: ComposerMaterial[] = [
    { ...files[0], kind: 'VIDEO' }, { ...files[1], kind: 'IMAGE' },
    { fileId: 'sound', name: '声音.wav', preview: '', kind: 'AUDIO' },
    { fileId: 'video-two', name: '运动.mp4', preview: '', kind: 'VIDEO' },
  ];
  render(<Editor kind="OMNI" materials={media} issues={['', '', '', '']} />);
  for (const name of ['运动.mp4', '声音.wav', '房间.png', '猫.png']) {
    const current = screen.getByRole('textbox');
    const value = readPromptInput(current) + ' @';
    editPromptInput(current, value);
    fireEvent.click(screen.getByRole('option', { name: new RegExp(name) }));
  }
  expect(screen.getByLabelText('提交提示词')).toHaveTextContent('视频2 音频1 图片1 视频1');
});


it('renders a bound image as a thumbnail chip with its attachment ordinal', () => {
  render(<Editor />);
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '@', selectionStart: 1 } });
  fireEvent.click(screen.getByRole('option', { name: /房间.png/ }));
  const chip = screen.getByText('图像2').closest('[data-mention-token]')!;
  expect(chip).toHaveAttribute('contenteditable', 'false');
  expect(chip.querySelector('img')).toHaveAttribute('src', files[1].preview);
  expect(screen.getByLabelText('提交提示词')).toHaveTextContent('图2');
});

it('keeps Chinese composition intact, copies bound tokens, and returns to plain text after deleting a chip', () => {
  render(<Editor />);
  editPromptInput(screen.getByRole('textbox'), '@');
  fireEvent.click(screen.getByRole('option', { name: /猫.png/ }));
  const input = screen.getByRole('textbox');
  const chip = input.querySelector('[data-mention-token]')!;
  const text = document.createTextNode('镜');
  input.append(text);
  fireEvent.compositionStart(input);
  fireEvent.input(input);
  expect(screen.getByLabelText('提交提示词')).toHaveTextContent(/^图1$/);
  text.textContent = '镜头移动';
  fireEvent.compositionEnd(input);
  expect(screen.getByLabelText('提交提示词')).toHaveTextContent('图1镜头移动');
  expect(input.querySelector('[data-mention-token]')).toBe(chip);
  const range = document.createRange(); range.selectNodeContents(input);
  window.getSelection()!.removeAllRanges(); window.getSelection()!.addRange(range);
  const copied: Record<string, string> = {};
  fireEvent.copy(input, { clipboardData: { setData: (type: string, value: string) => { copied[type] = value; } } });
  expect(copied['text/plain']).toBe('@【猫.png · 1】镜头移动');
  chip.remove(); fireEvent.input(input);
  expect(screen.getByRole('textbox')).toHaveValue('镜头移动');
  expect(screen.getByLabelText('提交提示词')).toHaveTextContent(/^镜头移动$/);
});


it('preserves native block line breaks and restores the caret after a pasted token', () => {
  render(<Editor />);
  const plain = screen.getByRole('textbox'); plain.focus();
  editPromptInput(plain, '@【unknown】');
  const input = screen.getByRole('textbox');
  expect(input).toHaveFocus();
  expect(mentionCaret(input)).toBe('@【unknown】'.length);
  const line = document.createElement('div'); line.append(document.createElement('br')); input.append(line);
  const range = document.createRange(); range.selectNodeContents(line); range.collapse(false);
  window.getSelection()!.removeAllRanges(); window.getSelection()!.addRange(range);
  fireEvent.input(input);
  expect(readPromptInput(input)).toBe('@【unknown】\n');
  expect(input.querySelector('div')).toBe(line);
  expect(mentionCaret(input)).toBe('@【unknown】\n'.length);
});


it('treats the native trailing break as an empty editor rather than an extra prompt line', () => {
  render(<Editor />);
  editPromptInput(screen.getByRole('textbox'), '@');
  fireEvent.click(screen.getByRole('option', { name: /猫.png/ }));
  const input = screen.getByRole('textbox');
  input.replaceChildren(document.createTextNode(''), document.createElement('br'), document.createElement('br'));
  fireEvent.input(input, { inputType: 'deleteContentBackward' });
  expect(screen.getByRole('textbox')).toHaveValue('');
  expect(screen.getByLabelText('提交提示词')).toBeEmptyDOMElement();
});


it('counts a real BR before the next text from the live DOM for caret and copy', () => {
  const root = document.createElement('div');
  root.append(document.createTextNode('a'), document.createElement('br'), document.createTextNode('b'));
  document.body.append(root);
  expect(readMentionText(root)).toBe('a\nb');
  placeMentionCaret(root, 2);
  expect(mentionCaret(root)).toBe(2);
  const range = document.createRange(); range.setStart(root, 0); range.setEnd(root, 2);
  window.getSelection()!.removeAllRanges(); window.getSelection()!.addRange(range);
  expect(selectedMentionText(root)).toBe('a\n');
  root.remove();
});
