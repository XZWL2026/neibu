import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { t } from '../i18n';

export function SuiteResultLayout({ columns, gridColumns, onColumnsChange }: {
  columns: number;
  gridColumns: number;
  onColumnsChange: (columns: number) => void;
}) {
  const [draft, setDraft] = useState(columns);
  const draftRef = useRef(columns);
  const dragging = useRef(false);
  useEffect(() => {
    draftRef.current = columns;
    setDraft(columns);
  }, [columns]);
  const apply = (next: number) => {
    draftRef.current = next;
    setDraft(next);
    if (next !== columns) onColumnsChange(next);
  };
  const finishDrag = () => {
    if (!dragging.current) return;
    dragging.current = false;
    apply(draftRef.current);
  };
  return <div className="suite-result-layout">
    <div className="suite-layout-modes" role="group" aria-label={t("排列")}>
      <button type="button" aria-label={t("单列")} title={t("单列")} aria-pressed={columns === 1} onClick={() => apply(1)}>
        <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><rect x="5" y="4" width="14" height="16" rx="2" /><path d="M5 9h14M5 15h14" /></svg>
      </button>
      <button type="button" aria-label={t("网格排列")} title={t("网格排列")} aria-pressed={columns > 1} onClick={() => apply(gridColumns)}>
        <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><rect x="4" y="4" width="16" height="16" rx="2" /><path d="M12 4v16M4 12h16" /></svg>
      </button>
    </div>
    <label className="suite-column-slider"><span>{t("列数")}</span>
      <input type="range" min="1" max="4" step="1" value={draft} aria-label={t("排列列数")}
        style={{ '--column-fill': `${(draft - 1) / 3 * 100}%` } as CSSProperties}
        onPointerDown={event => {
          if (event.button > 0 || event.isPrimary === false) return;
          dragging.current = true;
          event.currentTarget.setPointerCapture?.(event.pointerId);
        }}
        onChange={event => {
          const next = Number(event.target.value);
          // Keep drag feedback local so the gallery only reflows once on release.
          if (dragging.current) { draftRef.current = next; setDraft(next); }
          else apply(next);
        }}
        onPointerUp={finishDrag} onLostPointerCapture={finishDrag} onBlur={finishDrag}
        onPointerCancel={() => {
          dragging.current = false;
          draftRef.current = columns;
          setDraft(columns);
        }} />
      <output aria-hidden="true">{draft}</output>
    </label>
  </div>;
}
