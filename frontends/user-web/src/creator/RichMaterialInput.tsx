import { useLayoutEffect, useRef, useState, type RefObject, type KeyboardEvent, type FormEvent } from 'react';

export type MentionChip = { token: string; label: string; preview?: string; invalid: boolean };
export function readMentionText(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? '';
  if (node instanceof HTMLElement && node.dataset.mentionToken) return node.dataset.mentionToken;
  if (node.nodeName === 'BR') return node.nextSibling ? '\n' : '';
  if (/^(DIV|P)$/.test(node.nodeName) && node.childNodes.length === 1 && node.firstChild?.nodeName === 'BR') return '';
  return Array.from(node.childNodes).map((child, index) =>
    `${index > 0 && /^(DIV|P)$/.test(child.nodeName) ? '\n' : ''}${readMentionText(child)}`).join('');
}
// Count against the live tree: cloning a range can turn a real BR into a
// trailing placeholder and lose its newline or the selection's final line.
function mentionOffset(root: HTMLElement, target: Node, offset: number): number {
  let length = 0, found = false;
  const walk = (node: Node) => {
    if (found) return;
    const token = node instanceof HTMLElement ? node.dataset.mentionToken : undefined;
    if (token) {
      length += node.contains(target) ? (offset ? token.length : 0) : token.length;
      if (node.contains(target)) found = true;
    } else if (node.nodeType === Node.TEXT_NODE || node.nodeName === 'BR') {
      length += node === target ? offset : readMentionText(node).length;
      if (node === target) found = true;
    } else {
      const children = Array.from(node.childNodes);
      for (let index = 0; index < children.length; index += 1) {
        if (found || (node === target && index === offset)) break;
        if (index > 0 && /^(DIV|P)$/.test(children[index].nodeName)) length += 1;
        walk(children[index]);
      }
      if (node === target) found = true;
    }
  };
  walk(root); return length;
}
export function mentionCaret(root: HTMLElement): number | null {
  const selection = window.getSelection();
  return selection?.focusNode && root.contains(selection.focusNode)
    ? mentionOffset(root, selection.focusNode, selection.focusOffset) : null;
}
export function selectedMentionText(root: HTMLElement): string {
  const selection = window.getSelection();
  if (!selection?.rangeCount) return '';
  const range = selection.getRangeAt(0);
  const start = root.contains(range.startContainer) ? mentionOffset(root, range.startContainer, range.startOffset) : 0;
  const end = root.contains(range.endContainer) ? mentionOffset(root, range.endContainer, range.endOffset) : readMentionText(root).length;
  return readMentionText(root).slice(start, end);
}
export function placeMentionCaret(root: HTMLElement, offset: number) {
  const range = document.createRange();
  let remaining = offset, found = false;
  const walk = (node: Node, index: number) => {
    if (found) return;
    if (index > 0 && /^(DIV|P)$/.test(node.nodeName)) {
      if (remaining === 0) { range.setStartBefore(node); found = true; return; }
      remaining -= 1;
    }
    const token = node instanceof HTMLElement ? node.dataset.mentionToken : undefined;
    if (token || node.nodeType === Node.TEXT_NODE || node.nodeName === 'BR') {
      const placeholder = node.nodeName === 'BR' && /^(DIV|P)$/.test(node.parentNode?.nodeName ?? '') && node.parentNode?.childNodes.length === 1;
      const length = placeholder ? 0 : readMentionText(node).length;
      if (remaining <= length) {
        if (token || node.nodeName === 'BR') { if (remaining === 0) range.setStartBefore(node); else range.setStartAfter(node); }
        else range.setStart(node, remaining);
        found = true;
      } else remaining -= length;
    } else Array.from(node.childNodes).forEach(walk);
  };
  Array.from(root.childNodes).forEach(walk);
  if (!found) { range.selectNodeContents(root); range.collapse(false); }
  range.collapse(true); const selection = window.getSelection(); selection?.removeAllRanges(); selection?.addRange(range);
}

type Props = {
  inputRef: RefObject<HTMLDivElement | null>; value: string; chips: MentionChip[]; label: string; placeholder: string;
  disabled: boolean; controls?: string; activeDescendant?: string; describedBy?: string;
  onChange(value: string, caret: number): void; onSelect(caret: number | null): void;
  onKeyDown(event: KeyboardEvent<HTMLElement>): void; onBlur(): void;
};
export function RichMaterialInput({ inputRef, value, chips, label, placeholder, disabled, controls, activeDescendant, describedBy, onChange, onSelect, onKeyDown, onBlur }: Props) {
  const composing = useRef(false);
  const rendered = useRef('');
  const [revision, setRevision] = useState(0);
  const chipKey = JSON.stringify(chips);
  useLayoutEffect(() => {
    const root = inputRef.current;
    if (!root || composing.current) return;
    if (readMentionText(root) === value && rendered.current === chipKey
      && !Array.from(root.childNodes).some(node => node.nodeType === Node.TEXT_NODE && node.textContent?.includes('@【'))) return;
    const focused = document.activeElement === root;
    const caret = mentionCaret(root);
    const parts = value.split(/(@【[^】]*】)/g);
    const fragment = document.createDocumentFragment();
    for (const part of parts) {
      const chip = chips.find(item => item.token === part);
      if (!chip) { fragment.append(document.createTextNode(part)); continue; }
      const tag = document.createElement('span'); tag.className = `material-chip${chip.invalid ? ' is-invalid' : ''}`;
      tag.setAttribute('contenteditable', 'false'); tag.dataset.mentionToken = chip.token; tag.title = chip.token;
      if (chip.preview) { const image = document.createElement('img'); image.src = chip.preview; image.alt = ''; image.draggable = false; tag.append(image); }
      const text = document.createElement('span'); text.textContent = chip.label; tag.append(text); fragment.append(tag);
    }
    root.replaceChildren(fragment); rendered.current = chipKey;
    if (focused && caret !== null) placeMentionCaret(root, caret);
  }, [value, chipKey, revision, inputRef]);
  const edit = (event?: FormEvent<HTMLDivElement>) => {
    const root = inputRef.current;
    if (!root || disabled || composing.current) return;
    const deleting = (event?.nativeEvent as InputEvent | undefined)?.inputType?.startsWith('delete');
    const next = deleting && !root.textContent ? '' : readMentionText(root);
    if (next.length > 16000) { rendered.current = ''; setRevision(n => n + 1); return; }
    onChange(next, mentionCaret(root) ?? next.length);
  };
  const selectionText = () => inputRef.current ? selectedMentionText(inputRef.current) : '';
  return <div ref={inputRef} className="material-rich-input" role="textbox" aria-label={label} aria-multiline="true"
    aria-disabled={disabled} contentEditable={!disabled} suppressContentEditableWarning tabIndex={disabled ? -1 : 0}
    data-placeholder={placeholder} aria-autocomplete="list" aria-controls={controls} aria-activedescendant={activeDescendant} aria-describedby={describedBy}
    onInput={edit} onCompositionStart={() => { composing.current = true; }} onCompositionEnd={() => { composing.current = false; edit(); }}
    onClick={() => onSelect(mentionCaret(inputRef.current!))} onKeyUp={() => onSelect(mentionCaret(inputRef.current!))}
    onSelect={() => onSelect(window.getSelection()?.isCollapsed ? mentionCaret(inputRef.current!) : null)} onBlur={onBlur}
    onKeyDown={event => {
      if (disabled) return;
      onKeyDown(event);
    }}
    onPaste={event => {
      event.preventDefault();
      if (disabled) return;
      const capacity = 16000 - value.length + selectionText().length;
      document.execCommand('insertText', false, event.clipboardData.getData('text/plain').slice(0, Math.max(0, capacity)));
    }}
    onCopy={event => { event.preventDefault(); event.clipboardData.setData('text/plain', selectionText()); }}
    onCut={event => { event.preventDefault(); event.clipboardData.setData('text/plain', selectionText()); if (!disabled) document.execCommand('delete'); }}
    onDrop={event => event.preventDefault()} />;
}
