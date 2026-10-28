import { t, useLanguage } from '../i18n';
import { useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react';
import { RichMaterialInput, placeMentionCaret, type MentionChip } from './RichMaterialInput';
import { typedMaterialLabel, type ComposerMaterial } from './ComposerMaterials';
import { promptCaretRect } from './prompt-caret';

type Binding = { token: string; fileId: string };
// Editor tokens never cross the SDK boundary. The existing ordered references
// carry real IDs; ordinary prompt descriptions refer to that same order.
export function useMaterialMentions(value: string, materials: readonly (ComposerMaterial | null)[], issues: readonly string[], kind: 'IMAGE' | 'VIDEO' | 'OMNI') {
  const materialLabel = (index: number) => kind === 'OMNI' ? typedMaterialLabel(materials, index)
    : kind === 'IMAGE' ? `图${index + 1}` : index === 0 ? '首帧图片' : '尾帧图片';
  const [bindings, setBindings] = useState<Binding[]>([]);
  const candidates = materials.flatMap((file, index) => file && !issues[index] ? [{ ...file, index }] : []);
  const errors: string[] = [];
  const used: string[] = [];
  const compiled = value.replace(/@【[^】]*】/g, token => {
    const binding = bindings.find(item => item.token === token);
    const index = binding ? materials.findIndex(file => file?.fileId === binding.fileId) : -1;
    used.push(token);
    if (index < 0 || issues[index]) {
      errors.push(`${token} 引用已失效${index >= 0 ? `：${issues[index]}` : '：素材已移除、替换或未绑定'}，请删除引用后重新 @ 选择。`);
      return '';
    }
    return materialLabel(index);
  });
  if (compiled.includes('@【')) errors.push('素材引用不完整，请删除后重新 @ 选择。');
  const chips: MentionChip[] = [...new Set(used)].map(token => {
    const binding = bindings.find(item => item.token === token);
    const index = binding ? materials.findIndex(file => file?.fileId === binding.fileId) : -1;
    const file = materials[index];
    const image = file && (!file.kind || file.kind === 'IMAGE');
    const ordinal = materials.slice(0, index + 1).filter(item => item && (!item.kind || item.kind === 'IMAGE')).length;
    return { token, label: image ? `图像${ordinal}` : index >= 0 ? materialLabel(index) : '失效引用',
      preview: image ? file.preview : undefined, invalid: index < 0 || Boolean(issues[index]) };
  });
  const bind = (file: ComposerMaterial) => {
    const existing = bindings.find(item => item.fileId === file.fileId);
    if (existing) return existing.token;
    const name = file.name.replace(/[【】\r\n]/g, ' ').slice(0, 80);
    const token = `@【${name} · ${bindings.length + 1}】`;
    setBindings(current => [...current, { token, fileId: file.fileId }]);
    return token;
  };
  return { compiled, chips, error: [...new Set(errors)].join(' '), candidates, bind,
    bindings, restore: setBindings, reset: () => setBindings([]), materialLabel, key: JSON.stringify({ used, compiled: used.length ? compiled : '', errors }), kind };
}

type Props = { label: string; placeholder: string; value: string; onChange(value: string): void; disabled: boolean; showHelp?: boolean; mentions: Pick<ReturnType<typeof useMaterialMentions>, 'candidates' | 'bind' | 'materialLabel' | 'kind' | 'error'> & { chips?: MentionChip[] } };
export function MaterialPrompt({ label, placeholder, value, onChange, disabled, mentions }: Props) {
  useLanguage();
  const input = useRef<HTMLTextAreaElement>(null);
  const richInput = useRef<HTMLDivElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const rich = Boolean(mentions.chips) && value.includes('@【');
  const id = useId();
  const pendingCaret = useRef<number | null>(null);
  useLayoutEffect(() => {
    if (pendingCaret.current === null) return;
    if (richInput.current) { richInput.current.focus(); placeMentionCaret(richInput.current, pendingCaret.current); }
    else { input.current?.focus(); input.current?.setSelectionRange(pendingCaret.current, pendingCaret.current); }
    pendingCaret.current = null;
  }, [value]);
  const [query, setQuery] = useState<{ start: number; end: number; text: string } | null>(null);
  const [selected, setSelected] = useState(0);
  const candidates = mentions.candidates.filter(file => !query?.text || file.name.toLowerCase().includes(query.text.toLowerCase()));
  const open = query !== null && !disabled && candidates.length > 0;
  const active = Math.min(selected, Math.max(0, candidates.length - 1));
  useLayoutEffect(() => {
    const list = menu.current;
    const field = richInput.current ?? input.current;
    if (!open || !list || !field) return;
    if (list.showPopover) list.showPopover();
    else list.style.display = 'block';
    const position = () => {
      const caret = promptCaretRect(field);
      const viewport = window.visualViewport;
      const left = (viewport?.offsetLeft ?? 0) + 8;
      const top = (viewport?.offsetTop ?? 0) + 8;
      const right = left + (viewport?.width ?? window.innerWidth) - 16;
      const bottom = top + (viewport?.height ?? window.innerHeight) - 16;
      const width = Math.min(360, right - left);
      list.style.width = `${width}px`;
      const height = Math.min(240, Math.max(list.scrollHeight + 2, list.getBoundingClientRect().height));
      const below = Math.max(0, bottom - caret.bottom - 6);
      const above = Math.max(0, caret.top - top - 6);
      const upwards = below < height && above > below;
      const available = upwards ? above : below;
      list.style.left = `${Math.max(left, Math.min(caret.left, right - width))}px`;
      list.style.top = `${Math.max(top, upwards ? caret.top - Math.min(height, available) - 6 : caret.bottom + 6)}px`;
      list.style.maxHeight = `${Math.min(240, available)}px`;
      const bounds = field.getBoundingClientRect();
      list.style.visibility = caret.height > 0 && bounds.height > 0
        && (caret.bottom < Math.max(bounds.top, top) || caret.top > Math.min(bounds.bottom, bottom)) ? 'hidden' : 'visible';
    };
    position();
    const scroll = (event: Event) => { if (!list.contains(event.target as Node)) position(); };
    document.addEventListener('scroll', scroll, true);
    window.addEventListener('resize', position);
    window.visualViewport?.addEventListener('resize', position);
    window.visualViewport?.addEventListener('scroll', position);
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(position);
    observer?.observe(field);
    return () => {
      document.removeEventListener('scroll', scroll, true);
      window.removeEventListener('resize', position);
      window.visualViewport?.removeEventListener('resize', position);
      window.visualViewport?.removeEventListener('scroll', position);
      observer?.disconnect();
    };
  }, [open, query, value, candidates.length, rich]);
  useLayoutEffect(() => {
    const list = menu.current;
    const option = list?.querySelector<HTMLElement>('[aria-selected="true"]');
    if (!list || !option) return;
    if (option.offsetTop < list.scrollTop) list.scrollTop = option.offsetTop;
    else if (option.offsetTop + option.offsetHeight > list.scrollTop + list.clientHeight)
      list.scrollTop = option.offsetTop + option.offsetHeight - list.clientHeight;
  }, [active, open]);
  const updateQuery = (text: string, caret: number) => {
    const match = /@([^@\s【】]*)$/.exec(text.slice(0, caret));
    const next = match ? { start: caret - match[0].length, end: caret, text: match[1] } : null;
    // React can emit select again on keyup even when ArrowUp/Down did not move
    // the caret. Preserve menu navigation when the query range is unchanged.
    if (next?.start === query?.start && next?.end === query?.end && next?.text === query?.text) return;
    setQuery(next); setSelected(0);
  };
  const choose = (file: ComposerMaterial) => {
    if (!query) return;
    // Reserve enough space before binding; never truncate the surrounding prompt.
    const token = mentions.bind(file);
    const next = value.slice(0, query.start) + token + value.slice(query.end);
    if (next.length > 16000) return;
    const caret = query.start + token.length;
    pendingCaret.current = caret;
    onChange(next); setQuery(null);
  };
  const keyDown = (event: KeyboardEvent<HTMLElement>) => {
        if (!open || event.nativeEvent.isComposing) return;
        if (['ArrowDown', 'ArrowUp', 'Enter', 'Escape'].includes(event.key)) { event.preventDefault(); event.stopPropagation(); }
        if (event.key === 'Escape') setQuery(null);
        if (event.key === 'ArrowDown') setSelected((active + 1) % Math.max(1, candidates.length));
        if (event.key === 'ArrowUp') setSelected((active + candidates.length - 1) % Math.max(1, candidates.length));
        if (event.key === 'Enter' && candidates[active]) choose(candidates[active]);

  };
  return <div className="material-prompt">
    {rich ? <RichMaterialInput inputRef={richInput} value={value} chips={mentions.chips ?? []} label={t(label)} placeholder={t(placeholder)} disabled={disabled}
      controls={open ? id : undefined} activeDescendant={open && candidates.length ? `${id}-${active}` : undefined}
      onChange={(text, caret) => { pendingCaret.current = caret; onChange(text); updateQuery(text, caret); }}
      onSelect={caret => { if (caret === null) setQuery(null); else updateQuery(value, caret); }}
      onKeyDown={keyDown} onBlur={() => setQuery(null)} /> : <textarea ref={input} aria-label={t(label)} aria-autocomplete="list" aria-controls={open ? id : undefined}
      aria-activedescendant={open && candidates.length ? `${id}-${active}` : undefined}
      maxLength={16000} disabled={disabled} value={value} placeholder={t(placeholder)}
      onChange={event => {
        if (mentions.chips && event.target.value.includes('@【')) pendingCaret.current = event.target.selectionStart;
        onChange(event.target.value); updateQuery(event.target.value, event.target.selectionStart);
      }}
      onClick={event => updateQuery(value, event.currentTarget.selectionStart)}
      onSelect={event => {
        const field = event.currentTarget;
        if (field.selectionStart !== field.selectionEnd) setQuery(null);
        else updateQuery(field.value, field.selectionStart);
      }} onBlur={() => setQuery(null)}
      onKeyDown={keyDown} />}
    {open && <div ref={menu} popover="manual" className="material-mentions" role="listbox" id={id} aria-label={t("引用已上传素材")}>
      {candidates.map((file, index) => <button type="button" role="option" id={`${id}-${index}`} key={file.fileId}
        aria-selected={active === index} onMouseDown={event => event.preventDefault()} onClick={() => choose(file)}>
        {file.preview && (!file.kind || file.kind === 'IMAGE') && <img src={file.preview} alt="" />}<span>{file.name}</span><small>{t(mentions.materialLabel(file.index))}</small>
      </button>)}
    </div>}
    {mentions.error && <p role="alert" className="composer-upload-error">{t(mentions.error)}</p>}
  </div>;
}
