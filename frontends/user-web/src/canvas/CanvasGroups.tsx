import { t, formatMessage, useLanguage } from '../i18n';
import { useRef, useState, type PointerEvent } from 'react';
import type { CanvasGroup, CanvasPoint } from './model';
import { CanvasIcon } from './CanvasIcon';

type Props = {
  group: CanvasGroup;
  bounds: { x: number; y: number; width: number; height: number };
  positions: Record<string, CanvasPoint>;
  selected: boolean;
  toCanvasPoint(point: CanvasPoint): CanvasPoint;
  onSelect(): void;
  onPreview(positions: Record<string, CanvasPoint>): void;
  onFinish(positions: Record<string, CanvasPoint>): void;
  onCancel(): void;
  onRename(name: string): void;
  onUngroup(): void;
};

export function CanvasGroupFrame(props: Props) {
  useLanguage();
  const { group, bounds } = props;
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(group.name);
  const drag = useRef<{ pointer: number; start: CanvasPoint; positions: Record<string, CanvasPoint>; latest: Record<string, CanvasPoint>; moved: boolean } | null>(null);
  const cancelRename = useRef(false);
  const beginRename = () => { cancelRename.current = false; setName(group.name); setEditing(true); };
  const finishRename = () => { if (!cancelRename.current) props.onRename(name); setEditing(false); };
  const start = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 || editing || (event.target as Element).closest('button:not([data-group-drag]),input')) return;
    event.preventDefault(); event.stopPropagation();
    const start = props.toCanvasPoint({ x: event.clientX, y: event.clientY });
    drag.current = { pointer: event.pointerId, start, positions: props.positions, latest: props.positions, moved: false };
    event.currentTarget.setPointerCapture(event.pointerId);
    props.onSelect();
  };
  const move = (event: PointerEvent<HTMLDivElement>) => {
    const active = drag.current;
    if (!active || active.pointer !== event.pointerId) return;
    event.stopPropagation();
    const point = props.toCanvasPoint({ x: event.clientX, y: event.clientY });
    const dx = point.x - active.start.x, dy = point.y - active.start.y;
    if (!active.moved && Math.hypot(dx, dy) < 2) return;
    active.moved = true;
    active.latest = Object.fromEntries(Object.entries(active.positions).map(([id, position]) => [id, { x: position.x + dx, y: position.y + dy }]));
    props.onPreview(active.latest);
  };
  const end = (event: PointerEvent<HTMLDivElement>, cancelled = false) => {
    const active = drag.current;
    if (!active || active.pointer !== event.pointerId) return;
    drag.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    if (cancelled) props.onCancel();
    else props.onFinish(active.moved ? active.latest : {});
  };
  return <div className="canvas-group-frame" data-group-id={group.id} data-selected={props.selected}
    style={{ left: bounds.x, top: bounds.y, width: bounds.width, height: bounds.height }} role="group" aria-label={formatMessage('节点分组：{0}', group.name)}>
    <div className="canvas-group-header nopan nodrag" onPointerDown={start} onPointerMove={move} onPointerUp={event => end(event)} onPointerCancel={event => end(event, true)} onLostPointerCapture={event => end(event, true)}
      onDoubleClick={event => { if (!(event.target as Element).closest('button:not([data-group-drag]),input')) { event.stopPropagation(); beginRename(); } }}>
      <CanvasIcon name="group" />
      {editing ? <form onSubmit={event => { event.preventDefault(); finishRename(); }}>
        <input autoFocus aria-label={t("分组名称")} value={name} maxLength={80} onFocus={event => event.currentTarget.select()} onChange={event => setName(event.target.value)} onBlur={finishRename}
          onKeyDown={event => { event.stopPropagation(); if (event.key === 'Escape') { cancelRename.current = true; setEditing(false); } }} />
      </form> : <button className="canvas-group-name" data-group-drag type="button" title={t("拖动标题移动整组 · 双击重命名")} aria-label={formatMessage('选择分组：{0}', group.name)}
        onClick={props.onSelect} onDoubleClick={beginRename} onKeyDown={event => {
          if (event.key === 'F2') { event.preventDefault(); beginRename(); return; }
          if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
          event.preventDefault(); event.stopPropagation();
          const step = event.shiftKey ? 1 : 10;
          props.onFinish(Object.fromEntries(Object.entries(props.positions).map(([id, point]) => [id, { x: point.x + (event.key === 'ArrowRight' ? step : event.key === 'ArrowLeft' ? -step : 0), y: point.y + (event.key === 'ArrowDown' ? step : event.key === 'ArrowUp' ? -step : 0) }])));
        }}>{group.name}</button>}
      <span className="canvas-group-count">{formatMessage('{0} 个节点', group.nodeIds.length)}</span>
      <button type="button" className="canvas-group-action" aria-label={formatMessage('重命名分组：{0}', group.name)} title={t("重命名分组")} onClick={beginRename}><CanvasIcon name="edit" /></button>
      <button type="button" className="canvas-group-action" aria-label={formatMessage('取消分组：{0}', group.name)} title={t("取消分组，保留所有节点")} onClick={props.onUngroup}><CanvasIcon name="ungroup" /></button>
    </div>
  </div>;
}
