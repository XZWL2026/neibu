// Textareas do not expose a caret Range. Mirror their text layout without moving focus.
export function promptCaretRect(input: HTMLTextAreaElement | HTMLDivElement): DOMRect {
  if (!(input instanceof HTMLTextAreaElement)) {
    const selection = window.getSelection();
    if (selection?.rangeCount && input.contains(selection.focusNode)) {
      const range = selection.getRangeAt(0).cloneRange();
      range.collapse(false);
      const rect = range.getBoundingClientRect?.();
      if (rect?.height) return rect;
    }
    return input.getBoundingClientRect();
  }
  const bounds = input.getBoundingClientRect();
  const styles = getComputedStyle(input);
  const mirror = document.createElement('div');
  for (const property of ['font-family', 'font-size', 'font-weight', 'font-style', 'font-stretch', 'font-variant',
    'line-height', 'letter-spacing', 'word-spacing', 'text-transform', 'text-indent', 'text-align', 'direction',
    'tab-size', 'padding-top', 'padding-right', 'padding-bottom', 'padding-left', 'word-break']) {
    mirror.style.setProperty(property, styles.getPropertyValue(property));
  }
  Object.assign(mirror.style, { position: 'fixed', visibility: 'hidden', pointerEvents: 'none',
    left: `${bounds.left + input.clientLeft}px`, top: `${bounds.top + input.clientTop}px`,
    width: `${input.clientWidth || bounds.width}px`, boxSizing: 'border-box',
    whiteSpace: input.wrap === 'off' ? 'pre' : 'pre-wrap', overflowWrap: 'break-word' });
  mirror.setAttribute('aria-hidden', 'true');
  const marker = document.createElement('span');
  marker.textContent = '\u200b';
  mirror.append(document.createTextNode(input.value.slice(0, input.selectionStart)), marker,
    document.createTextNode(input.value.slice(input.selectionStart)));
  document.body.append(mirror);
  try {
    const rect = marker.getBoundingClientRect();
    const scaleX = input.offsetWidth ? bounds.width / input.offsetWidth : 1;
    const scaleY = input.offsetHeight ? bounds.height / input.offsetHeight : 1;
    return new DOMRect(bounds.left + (rect.left - bounds.left - input.scrollLeft) * scaleX,
      bounds.top + (rect.top - bounds.top - input.scrollTop) * scaleY, 0,
      (rect.height || parseFloat(styles.lineHeight) || 24) * scaleY);
  } finally { mirror.remove(); }
}
