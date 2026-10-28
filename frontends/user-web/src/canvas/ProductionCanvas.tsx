import { t, useLanguage } from '../i18n';
import { CanvasIcon } from './CanvasIcon';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  ApiError, canvasCreatePostApiV1Canvases, canvasReadGetApiV1CanvasesCanvasId,
  canvasCopyPostApiV1CanvasesCanvasIdCopies, canvasDeleteDeleteApiV1CanvasesCanvasId,
} from '../api';
import { CanvasMaterials } from './CanvasMaterials';
import { CanvasWorkspace } from './CanvasWorkspace';
import { createCanvasDocument } from './document';
import type { CanvasDocument, CanvasPoint } from './model';
import { canvasError, createProductionMediaPort, persistCanvas, readCanvasDocument, type CanvasResponse } from './production-api';
import { canvasContentChanged, readSessionView, saveSessionView } from './session-view';
import { taskBusy } from './workflow';

export function ProductionCanvas() {
  useLanguage();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const id = params.get('id') ?? '';
  return <CanvasSession key={id} id={id} open={next => setParams(next ? { id: next } : {})} home={() => navigate('/canvas', { replace: true })} />;
}

function CanvasSession({ id, open, home }: { id: string; open: (id: string) => void; home: () => void }) {
  useLanguage();
  const uploadMedia = useRef<(nodeId?: string, position?: CanvasPoint) => void>(null);
  const [loaded, setLoaded] = useState(() => ({
    document: id ? null : createCanvasDocument('draft', new Date().toISOString()), epoch: 0,
  }));
  const { document: initial, epoch } = loaded;
  const current = useRef(initial);
  const server = useRef<CanvasResponse | null>(null);
  const saved = useRef(initial);
  const alive = useRef(true);
  const running = useRef(false);
  const operationDone = useRef(Promise.resolve());
  const autoSaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clearAutoSave = () => { if (autoSaveTimer.current) clearTimeout(autoSaveTimer.current); autoSaveTimer.current = null; };
  const autoSavePending = useRef(false);
  const conflict = useRef(false);
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState(id ? '正在加载画布' : '未保存的会话草稿');
  const [name, setName] = useState('未命名画布');
  const currentName = useRef(name);
  const savedName = useRef(name);
  const hasChanges = () => canvasContentChanged(saved.current, current.current) || currentName.current.trim() !== savedName.current;

  const [confirm, setConfirm] = useState<'reload' | 'delete' | null>(null);
  const port = useMemo(() => createProductionMediaPort(), []);
  const accept = (record: CanvasResponse) => { server.current = record; };
  const save = async () => {
    clearAutoSave();
    if (conflict.current) throw new Error('版本冲突：请另存为新画布，或放弃编辑后重新加载');
    const document = current.current;
    if (!document || !server.current) return;
    const requestedName = currentName.current.trim();
    if (!requestedName) throw new Error('请填写画布名称');
    setNotice('正在保存…');
    await persistCanvas(server.current, document, accept, requestedName);
    if (!alive.current) return;
    saved.current = document;
    savedName.current = server.current.name;
    if (currentName.current.trim() === requestedName) {
      currentName.current = requestedName;
      setName(requestedName);
    }
    setDirty(hasChanges());
    setNotice(hasChanges() ? '有未保存的编辑' : `已保存 · 版本 ${server.current.version}`);
    try { localStorage.setItem(`xzwl-canvas-revision:${server.current.ownerId}:${server.current.id}`, String(server.current.version)); } catch { /* The server version remains authoritative. */ }
  };
  const saveRef = useRef(save);
  saveRef.current = save;

  const run = useCallback(async (operation: () => Promise<void>, background = false): Promise<boolean> => {
    while (running.current && alive.current) await operationDone.current;
    if (!alive.current) return false;
    running.current = true;
    let finish!: () => void;
    operationDone.current = new Promise<void>(resolve => { finish = resolve; });
    if (!background) setBusy(true);
    setError('');
    let failed = false;
    try { await operation(); }
    catch (cause) {
      failed = true;
      if (cause instanceof ApiError && cause.status === 409) { conflict.current = true; clearAutoSave(); setNotice('保存冲突 · 编辑已保留'); }
      if (alive.current) setError(canvasError(cause));
    } finally {
      running.current = false;
      finish();
      if (alive.current && !background) setBusy(false);
      const needsSave = autoSavePending.current;
      autoSavePending.current = false;
      if (!failed && needsSave && alive.current && !conflict.current && hasChanges()) void run(() => saveRef.current(), true);
    }
    return !failed;
  }, []);

  const load = async () => {
    clearAutoSave();
    if (!id) return;
    const record = (await canvasReadGetApiV1CanvasesCanvasId(id)).data;
    if (record.id !== id) throw new Error('画布响应 ID 不匹配，请重新加载');
    const document = await readCanvasDocument(record);
    if (!alive.current) return;
    document.viewport = readSessionView(record.ownerId, id, current.current?.viewport ?? document.viewport);
    server.current = record;
    current.current = document;
    saved.current = document;
    conflict.current = false;
    setLoaded(previous => ({ document, epoch: previous.epoch + 1 }));
    currentName.current = savedName.current = record.name;
    setName(record.name);
    setDirty(false);
    setConfirm(null);
    setNotice(`已加载 · 版本 ${record.version}`);
  };
  useEffect(() => {
    alive.current = true;
    if (id) void run(load);
    return () => { alive.current = false; clearAutoSave(); };
  }, []);
  const scheduleSave = () => {
    clearAutoSave();
    if (server.current && !conflict.current) autoSaveTimer.current = setTimeout(() => {
      if (hasChanges()) void run(() => saveRef.current(), true);
    }, 450);
  };
  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault(); if (server.current && !conflict.current) void run(() => saveRef.current());
      }
    };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, [run]);
  useEffect(() => {
    let checking = false;
    const refresh = async () => {
      const before = server.current;
      if (!before || checking || running.current || conflict.current || document.visibilityState === 'hidden') return;
      checking = true;
      try {
        const record = (await canvasReadGetApiV1CanvasesCanvasId(before.id)).data;
        if (!alive.current || server.current !== before || record.version === before.version) return;
        if (record.id !== before.id || record.ownerId !== before.ownerId) throw new Error('画布响应不匹配，请重新加载');
        const latest = await readCanvasDocument(record);
        if (!alive.current || server.current !== before) return;
        if (hasChanges() || running.current || current.current?.nodes.some(taskBusy)) {
          clearAutoSave(); conflict.current = true; setError('其他页面已更新画布，当前编辑已保留。请另存当前编辑或读取最新版本。'); setNotice('保存冲突 · 编辑已保留'); return;
        }
        latest.viewport = readSessionView(record.ownerId, record.id, current.current?.viewport ?? latest.viewport);
        server.current = record; current.current = saved.current = latest;
        currentName.current = savedName.current = record.name; setName(record.name); setDirty(false); setError('');
        setLoaded(previous => ({ document: latest, epoch: previous.epoch + 1 })); setNotice(`已加载 · 版本 ${record.version}`);
      } catch (cause) { if (alive.current) setError(canvasError(cause)); }
      finally { checking = false; }
    };
    const storage = (event: StorageEvent) => {
      if (server.current && event.key === `xzwl-canvas-revision:${server.current.ownerId}:${server.current.id}`) void refresh();
    };
    window.addEventListener('focus', refresh); window.addEventListener('storage', storage);
    return () => { window.removeEventListener('focus', refresh); window.removeEventListener('storage', storage); };
  }, []);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (hasChanges() || running.current) { event.preventDefault(); event.returnValue = ''; }
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, []);

  const changed = (document: CanvasDocument) => {
    const previous = current.current;
    current.current = document;
    if (server.current) saveSessionView(server.current.ownerId, server.current.id, document.viewport);
    const changed = hasChanges();
    setDirty(changed);
    if (changed) setNotice(server.current ? '有未保存的编辑' : '未保存的会话草稿');
    const taskChanged = document.nodes.some(node => node.kind === 'generation' && node.taskRef && (() => {
      const before = previous?.nodes.find(item => item.id === node.id);
      return before?.kind !== 'generation' || before.taskRef !== node.taskRef || before.taskState !== node.taskState;
    })());
    const materialChanged = document.nodes.some(node => {
      if (node.kind === 'generation' || node.kind === 'text') return false;
      const before = previous?.nodes.find(item => item.id === node.id);
      return !before || before.kind === 'generation' || before.kind !== 'text'
        && (before.asset.reviewId !== node.asset.reviewId || before.asset.reviewPending !== node.asset.reviewPending || before.asset.reviewStatus !== node.asset.reviewStatus);
    });
    if (server.current && (taskChanged || materialChanged) && !conflict.current) {
      if (running.current) autoSavePending.current = true;
      else void run(() => saveRef.current(), true);
    } else if (canvasContentChanged(previous, document)) {
      scheduleSave();
    }
  };

  const create = async (openEditor = true) => {
    clearAutoSave();
    const document = current.current;
    if (!document || !name.trim()) throw new Error('请填写画布名称');
    const record = (await canvasCreatePostApiV1Canvases({ name: name.trim() })).data;
    if (!alive.current) return;
    // Keep the new server ID even when its first save fails; retry never creates another canvas.
    server.current = record;
    conflict.current = false;
    saved.current = null;
    setNotice(`新画布 ${record.id} 已创建，正在保存草稿`);
    await persistCanvas(record, { ...document, projectId: record.id }, accept);
    if (alive.current && openEditor) open(record.id);
  };

  const leave = () => {
    void run(async () => {
      if (hasChanges()) {
        if (!server.current) await create(false);
        else await save();
      }
      if (alive.current) home();
    });
  };

  if (!initial) return <section className="canvas-workspace canvas-session-state" aria-label={t("创作画布")}>
    <button className="canvas-home-back" onClick={home}>{t("返回画布首页")}</button>
    <h1>{t("创作画布")}</h1>
    {error ? <><p role="alert">{t(error)}</p><button disabled={busy} onClick={() => { void run(load); }}>{t("重试加载")}</button></> : <p role="status">{t("正在加载画布")}</p>}
  </section>;

  return <>
    {busy && <p className="canvas-api-progress" role="status">{t("正在同步画布，请稍候…")}</p>}
    {error && <div className="canvas-api-error" role="alert"><p>{t(error)}</p>{conflict.current && <div className="canvas-conflict-actions">
      <button disabled={busy} onClick={() => void run(create)}>{t('另存当前编辑')}</button>
      <button disabled={busy} onClick={() => dirty ? setConfirm('reload') : void run(load)}>{t('读取最新版本')}</button>
    </div>}</div>}
    {confirm && <div role="group" aria-label={t("确认画布操作")} className="canvas-api-confirm">
      <p>{t(confirm === 'delete' ? '删除后无法继续打开此画布。' : '继续将放弃当前未保存的编辑。')}</p>
      <button disabled={busy} onClick={() => {
        setConfirm(null);
        if (confirm === 'reload') void run(load);
        else void run(async () => {
          const record = server.current!;
          await canvasDeleteDeleteApiV1CanvasesCanvasId(record.id, { expectedVersion: record.version });
          if (alive.current) open('');
        });
      }}>{t(confirm === 'delete' ? '确认删除画布' : '放弃编辑并继续')}</button>
      <button onClick={() => setConfirm(null)}>{t("取消")}</button>
    </div>}
    <CanvasWorkspace key={epoch} projectId={initial.projectId} initialDocument={initial} storage={null}
      production mediaTaskPort={server.current ? port : undefined} onDocumentChange={changed} locked={busy} onBack={leave}
      onUploadMedia={nodeId => uploadMedia.current?.(nodeId)}
      onUploadAt={position => uploadMedia.current?.(undefined, position)}
      beforeGenerationSubmit={async () => {
        if (!await run(() => saveRef.current())) throw new Error('画布保存失败，已停止生成提交');
      }}
      generationEnabled={Boolean(server.current) && !busy && !error}
      saveStatus={notice}
      toolbar={(_document, update, options, optionsLoading) => <>
        <button aria-label={t("返回画布首页")} className="canvas-home-back canvas-action" disabled={busy || _document.nodes.some(node => node.kind === 'generation' && node.taskState === 'submitting')} onClick={leave}><CanvasIcon name="home" /><span className="canvas-action-tip" aria-hidden="true">{t("返回画布首页")}</span></button>
        <label>{t("画布名称")}<input aria-label={t("画布名称")} maxLength={200} value={name} onChange={event => {
          currentName.current = event.target.value;
          setName(event.target.value);
          setDirty(hasChanges());
          setNotice(server.current ? hasChanges() ? '有未保存的编辑' : `已保存 · 版本 ${server.current.version}` : '未保存的会话草稿');
          scheduleSave();
        }} disabled={busy} /></label>
        {!server.current && <button disabled={busy || !name.trim()} onClick={() => { void run(create); }}>{t("创建画布")}</button>}
        {server.current && <>
          <button aria-label={t("保存画布")} className="canvas-action" disabled={busy || conflict.current} onClick={() => { void run(async () => { await save(); if (server.current?.id !== id && alive.current) open(server.current!.id); }); }}><CanvasIcon name="save" /><span className="canvas-action-tip" aria-hidden="true">{t("保存画布")}</span></button>
          <button aria-label={t("复制画布")} className="canvas-action" disabled={busy || conflict.current} onClick={() => { void run(async () => {
            await save();
            const copy = (await canvasCopyPostApiV1CanvasesCanvasIdCopies(server.current!.id, { name: `${name.slice(0,197)} 副本` })).data;
            if (alive.current) open(copy.id);
          }); }}><CanvasIcon name="copy" /><span className="canvas-action-tip" aria-hidden="true">{t("复制画布")}</span></button>
          <button aria-label={t("另存为新画布")} className="canvas-action" disabled={busy} onClick={() => { void run(create); }}><CanvasIcon name="save-as" /><span className="canvas-action-tip" aria-hidden="true">{t("另存为新画布")}</span></button>
          <button aria-label={t("重新加载")} className="canvas-action" disabled={busy || !id} onClick={() => dirty ? setConfirm('reload') : void run(load)}><CanvasIcon name="reload" /><span className="canvas-action-tip" aria-hidden="true">{t("重新加载")}</span></button>
          <button aria-label={t("删除画布")} className="canvas-action" disabled={busy || conflict.current} onClick={() => setConfirm('delete')}><CanvasIcon name="trash" /><span className="canvas-action-tip" aria-hidden="true">{t("删除画布")}</span></button>
        </>}
        <CanvasMaterials document={_document} disabled={busy || optionsLoading} options={options} update={update} uploadMediaRef={uploadMedia} />
      </>} />
  </>;
}
