import { t, formatMessage, useLanguage } from '../i18n';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { NodeQuoteState } from "./CanvasNodes";
import { CanvasIcon } from "./CanvasIcon";
import { copyGenerationDefaults } from "./generation-defaults";
import { CanvasSurface, type CanvasSurfaceProps } from "./CanvasSurface";
import {
  addGenerationNode,
  addTextNode,
  canSubmitGenerationNode,
  connectNodes,
  connectNodesInDirection,
  connectionIssue,
  createCanvasDocument,
  moveNode,
  removeNodes,
  removeEdges,
  updateGenerationTask,
  updateGenerationPrompt,
  updateGenerationSettings,
  updateTextNode,
  updateViewport,
} from "./document";
import { collectCanvasInputs, depthResolutions, canvasQuoteParameters, referenceIssue, buildMediaTaskRequest, type CanvasGenerationOption, type CanvasMediaTaskRequest, type MediaTaskPort } from "./media-task-port";
import type { CanvasDocument, CanvasGenerationSettings, CanvasPoint } from "./model";
import { getCanvasStorage, loadCanvasDraft, saveCanvasDraft } from "./persistence";
import { apiGenerationNotice, useOptionalUserSession } from "../userSession";
import { ApiError } from "../api";
import { canvasError } from "./production-api";
import { reconcileMediaTask } from "./task-reconciler";
import { beginRun, syncRunState, selectOutput, changeReference, normalizeWorkflow, restoreDraft, runtimeChanged, taskBusy, copySelection, pasteSelection } from './workflow';
import { createGroup, moveNodes, normalizeGroups, removeFromGroups, renameGroup } from './groups';

export type CanvasWorkspaceProps = {
  projectId?: string;
  initialDocument?: CanvasDocument;
  onDocumentChange?: (document: CanvasDocument) => void;
  onUploadMedia?: (nodeId: string) => void;
  onUploadAt?: (position: CanvasPoint) => void;
  onBack?: () => void;
  toolbar?: (document: CanvasDocument, update: (transform: (document: CanvasDocument) => CanvasDocument) => void, options: readonly CanvasGenerationOption[], optionsLoading: boolean) => ReactNode;
  saveStatus?: string;
  generationEnabled?: boolean;
  beforeGenerationSubmit?: () => Promise<void>;
  production?: boolean;
  locked?: boolean;
  storage?: Storage | null;
  mediaTaskPort?: MediaTaskPort;
};

type WorkspaceScope = {
  projectId: string;
  storage: Storage | null;
  mediaTaskPort: MediaTaskPort | undefined;
  epoch: number;
};

const compatibleSettings = (
  settings: CanvasGenerationSettings,
  options: readonly CanvasGenerationOption[],
  fallbackToFirst = false,
): CanvasGenerationSettings | null => {
  const matching = options.filter((option) => option.type === settings.type && (option.operation ?? "GENERATION") === (settings.operation ?? "GENERATION"));
  const option = matching.find((item) => item.model === settings.model) ?? matching.find((item) => (
    item.aspectRatios.includes(settings.aspectRatio)
    && item.resolutions.includes(settings.resolution)
    && (settings.type === "IMAGE"
      ? settings.count >= 1 && settings.count <= Math.min(item.maxCount, 4)
      : settings.count === 1
        && settings.durationSeconds >= item.minDurationSeconds
        && settings.durationSeconds <= item.maxDurationSeconds)
  )) ?? (fallbackToFirst ? matching[0] : undefined);
  if (!option) return null;
  if (settings.operation === "DEPTH") return { ...settings, model: option.model, aspectRatio: "source", durationSeconds: 0, count: 1,
    resolution: option.resolutions.includes(settings.resolution) ? settings.resolution : option.resolutions[0] };
  return {
    ...settings,
    model: option.model,
    aspectRatio: option.aspectRatios.includes(settings.aspectRatio) ? settings.aspectRatio : option.aspectRatios[0],
    resolution: option.resolutions.includes(settings.resolution) ? settings.resolution : option.resolutions[0],
    count: settings.type === "VIDEO" ? 1 : Math.max(1, Math.min(settings.count, option.maxCount, 4)),
    durationSeconds: settings.type === "IMAGE" ? 0 : Math.max(option.minDurationSeconds, Math.min(settings.durationSeconds, option.maxDurationSeconds)),
  };
};

const now = () => new Date().toISOString();

let fallbackUniqueSequence = 0;

const createLocalUniqueRef = (prefix: string): string => {
  try {
    const value = globalThis.crypto?.randomUUID?.();
    if (value) {
      return `${prefix}-${value}`;
    }
  } catch {
    // A local fallback keeps the canvas usable in restricted browser contexts.
  }
  fallbackUniqueSequence += 1;
  const randomPart = Math.random().toString(36).slice(2);
  return [
    prefix,
    Date.now().toString(36),
    fallbackUniqueSequence.toString(36),
    randomPart,
  ].join("-");
};

const recoverInterruptedSubmissions = (document: CanvasDocument, production = false): CanvasDocument => {
  let recovered = false;
  const nodes = document.nodes.map((node) => {
    if (node.kind !== "generation" || node.taskState !== "submitting" || node.taskRef !== null) {
      return node;
    }
    if (production && node.runs?.at(-1)?.request) return node;
    recovered = true;
    return {
      ...node,
      taskState: "failed" as const,
      message: "上次提交已中断，请重试",
    };
  });
  return recovered ? { ...document, nodes, updatedAt: now() } : document;
};

const loadDocument = (storage: Storage | null, projectId: string, production = false): CanvasDocument => {
  const loaded = loadCanvasDraft(storage, projectId, Date.now());
  const document = loaded.kind === "loaded"
    ? loaded.document
    : createCanvasDocument(projectId, now());
  return recoverInterruptedSubmissions(document, production);
};

const isEditingTarget = (target: EventTarget | null): boolean => target instanceof HTMLElement
  && (
    target.matches("input,textarea,select,[contenteditable='true']")
    || Boolean(target.closest("input,textarea,select,[contenteditable='true']"))
  );

export function CanvasWorkspace({
  projectId = "default",
  initialDocument,
  onDocumentChange,
  onUploadMedia,
  onUploadAt,
  onBack,
  toolbar,
  saveStatus,
  generationEnabled: configuredGenerationEnabled = true,
  beforeGenerationSubmit,
  production = false,
  locked = false,
  storage,
  mediaTaskPort,
}: CanvasWorkspaceProps) {
  useLanguage();
  const account = useOptionalUserSession();
  const accountAllowsGeneration = !account || (account.status === "ready" && account.authenticated && account.accountType !== "API");
  const generationEnabled = configuredGenerationEnabled && accountAllowsGeneration;
  const generationAccess = useRef({ allowed: accountAllowsGeneration, account });
  generationAccess.current = { allowed: accountAllowsGeneration, account };
  const resolvedStorage = useMemo(
    () => storage === undefined ? getCanvasStorage() : storage,
    [storage],
  );
  const [document, setDocument] = useState<CanvasDocument>(() => (
    initialDocument ?? loadDocument(resolvedStorage, projectId, production)
  ));
  const documentRef = useRef(document);
  documentRef.current = document;
  const [selectedNodeIds, setSelectedNodeIds] = useState<readonly string[]>([]);
  const [surfaceEpoch, setSurfaceEpoch] = useState(0);
  const [saveMessage, setSaveMessage] = useState(
    resolvedStorage ? "本地草稿" : "会话草稿",
  );
  const changeListener = useRef(onDocumentChange);
  changeListener.current = onDocumentChange;
  const readLocks = useRef(new Set<string>());
  const submitLocks = useRef(new Map<string, symbol>());
  const frozenRequests = useRef(new Map<string, { request: CanvasMediaTaskRequest; uncertain: boolean }>(document.nodes.flatMap(node => {
    const run = node.kind === 'generation' ? node.runs?.at(-1) : undefined;
    return run?.request && node.kind === 'generation' && node.taskState === 'submitting' && !node.taskRef ? [[node.id, { request: run.request, uncertain: true }] as const] : [];
  })));
  const history = useRef<{ past: CanvasDocument[]; future: CanvasDocument[]; lastKey: string; lastAt: number }>({ past: [], future: [], lastKey: '', lastAt: 0 });
  const clipboard = useRef<CanvasDocument | null>(null);
  const [workflowError, setWorkflowError] = useState('');
  const [clipboardReady, setClipboardReady] = useState(false);
  const newGenerationNodeIds = useRef(new Set<string>());
  const lifecycleToken = useRef(Symbol("canvas-workspace"));
  const previousMediaTaskPort = useRef(mediaTaskPort);
  const activeScope = useRef<WorkspaceScope>({
    projectId,
    storage: resolvedStorage,
    mediaTaskPort,
    epoch: 0,
  });

  if (
    activeScope.current.projectId !== projectId
    || activeScope.current.storage !== resolvedStorage
    || activeScope.current.mediaTaskPort !== mediaTaskPort
  ) {
    activeScope.current = {
      projectId,
      storage: resolvedStorage,
      mediaTaskPort,
      epoch: activeScope.current.epoch + 1,
    };
    submitLocks.current.clear();
    frozenRequests.current.clear();
  }
  const renderScope = activeScope.current;
  const [accountRevision, setAccountRevision] = useState(0);
  const accountRevisionRef = useRef(0);
  useEffect(() => {
    const refresh = () => {
      accountRevisionRef.current += 1;
      quoteEntries.current.clear();
      setAccountRevision(accountRevisionRef.current);
    };
    window.addEventListener("xzwl-account-changed", refresh);
    window.addEventListener("focus", refresh);
    return () => { window.removeEventListener("xzwl-account-changed", refresh); window.removeEventListener("focus", refresh); };
  }, []);
  const [catalog, setCatalog] = useState<{
    port: MediaTaskPort | undefined;
    state: "idle" | "loading" | "ready" | "failed";
    options: CanvasGenerationOption[];
  }>({ port: mediaTaskPort, state: mediaTaskPort ? "loading" : "idle", options: [] });
  const quoteEntries = useRef(new Map<string, {
    scope: WorkspaceScope;
    settings: CanvasGenerationSettings;
    inputKey: string;
    result: NodeQuoteState;
  }>());
  const [, refreshQuotes] = useState(0);
  const generationOptions = catalog.port === mediaTaskPort ? catalog.options : [];
  const generationOptionsStatus = catalog.port === mediaTaskPort ? catalog.state : mediaTaskPort ? "loading" : "idle";
  const quoteInputKey = (doc: CanvasDocument, id: string) => {
    const node = doc.nodes.find(item => item.id === id);
    const option = node?.kind === "generation" ? generationOptions.find(item => item.model === node.settings.model && item.type === node.settings.type) : undefined;
    return JSON.stringify({ parameters: canvasQuoteParameters(doc, id), issue: referenceIssue(doc, id, option) });
  };
  const nodeQuotes: Record<string, NodeQuoteState> = {};
  for (const node of document.nodes) {
    if (node.kind !== "generation") continue;
    const entry = quoteEntries.current.get(node.id);
    nodeQuotes[node.id] = entry?.scope === renderScope && entry.settings === node.settings && entry.inputKey === quoteInputKey(document, node.id)
      ? entry.result : { state: "idle" };
  }

  const commitDocument = useCallback((
    update: (current: CanvasDocument) => CanvasDocument,
  ) => {
    const previous = documentRef.current;
    let next = normalizeGroups(update(previous));
    // A possibly accepted request must keep its saved payload, including connected sources.
    for (const [id, frozen] of frozenRequests.current) {
      try {
        if (JSON.stringify(buildMediaTaskRequest(next, id, frozen.request.requestRef)) !== JSON.stringify(frozen.request)) {
          next = documentRef.current; break;
        }
      } catch { next = documentRef.current; break; }
    }
    if (next === previous) return;
    const runtime = runtimeChanged(previous, next);
    if (runtime) { next = normalizeWorkflow(syncRunState(next)); history.current.lastKey = ''; }
    if (!runtime && (previous.nodes !== next.nodes || previous.edges !== next.edges || previous.groups !== next.groups)) {
      const changed = next.nodes.filter(node => previous.nodes.find(item => item.id === node.id) !== node);
      const key = changed.length === 1 && previous.nodes.length === next.nodes.length && previous.edges === next.edges && previous.groups === next.groups
        ? `${changed[0].id}:${changed[0].kind === 'text' ? 'text' : changed[0].kind === 'generation' ? 'draft' : 'media'}` : '';
      if (!key || history.current.lastKey !== key || Date.now() - history.current.lastAt > 700) history.current.past = [...history.current.past.slice(-99), previous];
      history.current.future = []; history.current.lastKey = key; history.current.lastAt = Date.now();
    }
    documentRef.current = next;
    setDocument(next);
    changeListener.current?.(next);
  }, []);

  useEffect(() => {
    if (initialDocument && documentRef.current === initialDocument) return;
    const loaded = initialDocument ?? loadDocument(resolvedStorage, projectId, production);
    documentRef.current = loaded;
    setDocument(loaded);
    setSelectedNodeIds([]);
    history.current = { past: [], future: [], lastKey: '', lastAt: 0 };
    setSurfaceEpoch((current) => current + 1);
    submitLocks.current.clear();
    newGenerationNodeIds.current.clear();
  }, [projectId, resolvedStorage, initialDocument]);

  useEffect(() => {
    let cancelled = false;
    setCatalog({ port: mediaTaskPort, state: mediaTaskPort ? "loading" : "idle", options: [] });
    if (mediaTaskPort) {
      void Promise.resolve().then(() => mediaTaskPort.options()).then((options) => {
        if (!cancelled) setCatalog({ port: mediaTaskPort, state: "ready", options: options.filter((option) => (
          option.resolutions.length > 0 && option.aspectRatios.length > 0 && option.maxCount >= 1
          && (option.operation === "DEPTH" || option.type === "IMAGE" || option.minDurationSeconds >= 1 && option.maxDurationSeconds >= option.minDurationSeconds)
        )) });
      }).catch(() => {
        if (!cancelled) setCatalog({ port: mediaTaskPort, state: "failed", options: [] });
      });
    }
    return () => { cancelled = true; };
  }, [mediaTaskPort, accountRevision]);

  useEffect(() => {
    if (!mediaTaskPort || generationOptionsStatus !== "ready" || documentRef.current !== document || document.projectId !== projectId) return;
    for (const [id] of quoteEntries.current) {
      if (!document.nodes.some((node) => node.id === id)) quoteEntries.current.delete(id);
    }
    for (const node of document.nodes) {
      if (node.kind !== "generation" || !canSubmitGenerationNode(node)) continue;
      const settings = compatibleSettings(node.settings, generationOptions, newGenerationNodeIds.current.has(node.id));
      if (!settings) continue;
      if (settings.operation === "DEPTH") {
        const resolutions = depthResolutions(collectCanvasInputs(document, node.id), generationOptions.find(item => item.operation === "DEPTH"));
        if (resolutions.length && !resolutions.includes(settings.resolution)) settings.resolution = resolutions[0];
      }
      newGenerationNodeIds.current.delete(node.id);
      if (JSON.stringify(settings) !== JSON.stringify(node.settings)) {
        commitDocument((current) => updateGenerationSettings(current, node.id, settings, now()));
        continue;
      }
      const inputKey = quoteInputKey(document, node.id);
      const previous = quoteEntries.current.get(node.id);
      if (previous?.scope === renderScope && previous.settings === node.settings && previous.inputKey === inputKey) continue;
      const issue = referenceIssue(document, node.id, generationOptions.find(item => item.model === node.settings.model && item.type === node.settings.type));
      const entry = { scope: renderScope, settings: node.settings, inputKey, result: (issue ? { state: "failed", message: issue } : { state: "loading" }) as NodeQuoteState };
      quoteEntries.current.set(node.id, entry);
      refreshQuotes((value) => value + 1);
      if (issue) continue;
      const lifecycle = lifecycleToken.current;
      const publish = (result: NodeQuoteState) => {
        const latest = documentRef.current.nodes.find((item) => item.id === node.id);
        if (activeScope.current !== renderScope || lifecycleToken.current !== lifecycle
          || quoteEntries.current.get(node.id) !== entry || latest?.kind !== "generation" || latest.settings !== entry.settings
          || entry.inputKey !== quoteInputKey(documentRef.current, node.id)) return;
        entry.result = result;
        refreshQuotes((value) => value + 1);
      };
      if (node.settings.operation === "DEPTH") {
        publish({ state: "ready", points: "", localDepth: true });
        continue;
      }
      void Promise.resolve().then(() => mediaTaskPort.quote(canvasQuoteParameters(document, node.id))).then(
        ({ points }) => publish({ state: "ready", points }),
        () => publish({ state: "failed", message: "积分试算失败，请调整参数重试" }),
      );
    }
  }, [document, projectId, mediaTaskPort, generationOptions, generationOptionsStatus, renderScope, commitDocument]);

  const changeGenerationSettings = useCallback((id: string, settings: CanvasGenerationSettings) => {
    if (activeScope.current !== renderScope) return;
    commitDocument((current) => {
      const node = current.nodes.find((item) => item.id === id);
      if (!node || !canSubmitGenerationNode(node)) return current;
      return updateGenerationSettings(current, id, compatibleSettings(settings, generationOptions, true) ?? settings, now());
    });
  }, [commitDocument, generationOptions, renderScope]);

  useEffect(() => {
    if (previousMediaTaskPort.current === mediaTaskPort) {
      return;
    }
    previousMediaTaskPort.current = mediaTaskPort;
    commitDocument(document => recoverInterruptedSubmissions(document, production));
  }, [commitDocument, mediaTaskPort]);

  useEffect(() => {
    if (!production) return;
    // The persisted pre-submit document is the original request snapshot. The reader
    // marks interrupted submissions as failed, but they are not definitive refusals.
    for (const node of documentRef.current.nodes) {
      if (node.kind !== "generation" || node.taskRef || frozenRequests.current.has(node.id)) continue;
      const interrupted = node.taskState === "submitting" || node.taskState === "failed" && [
        "上次提交中断，请保留原参数重试", "上次提交已中断，请重试",
      ].includes(node.message ?? "");
      if (!interrupted) continue;
      const current = documentRef.current;
      frozenRequests.current.set(node.id, {
        request: node.runs?.at(-1)?.request ?? buildMediaTaskRequest(current, node.id, `canvas-${current.projectId}-${node.id}`), uncertain: true,
      });
      commitDocument(document => updateGenerationTask(document, node.id, {
        taskRef: null, taskState: "submitting", message: "上次提交中断，请使用原请求重试",
      }, now()));
    }
  }, [production, initialDocument, projectId, mediaTaskPort, commitDocument]);

  useEffect(() => () => {
    lifecycleToken.current = Symbol("canvas-workspace-unmounted");
    submitLocks.current.clear();
  }, []);

  useEffect(() => {
    if (document.projectId !== projectId || documentRef.current !== document) {
      return;
    }
    if (production) return;
    const result = saveCanvasDraft(resolvedStorage, document);
    setSaveMessage(
      result.saved
        ? "本地草稿已保存"
        : result.reason === "unavailable"
          ? "会话草稿"
          : "本地草稿未保存",
    );
  }, [document, projectId, resolvedStorage, production]);

  const uniqueId = useCallback((current: CanvasDocument, prefix: string) => {
    let id = createLocalUniqueRef(prefix);
    while (current.nodes.some((node) => node.id === id)) {
      id = createLocalUniqueRef(prefix);
    }
    return id;
  }, []);

  const addText = useCallback(() => {
    commitDocument((current) => addTextNode(
      current,
      uniqueId(current, "text"),
      { x: 80, y: 80 },
      now(),
    ));
  }, [commitDocument, uniqueId]);

  const addGeneration = useCallback((type: CanvasGenerationSettings["type"] = "IMAGE") => {
    const current = documentRef.current;
    const id = uniqueId(current, "generation");
    newGenerationNodeIds.current.add(id);
    const offset = current.nodes.filter(node => node.kind === "generation").length % 4 * 48;
    const position = {
      x: (Math.max(10, (window.innerWidth - 300) / 2) - current.viewport.x) / current.viewport.zoom + offset,
      y: ((window.innerWidth <= 760 ? 150 : Math.max(95, Math.min(140, window.innerHeight - 600))) - current.viewport.y) / current.viewport.zoom,
    };
    commitDocument((document) => updateGenerationSettings(
      addGenerationNode(document, id, position, now()), id, copyGenerationDefaults(type), now(),
    ));
    setSelectedNodeIds([id]);
  }, [commitDocument, uniqueId]);

  const addConnectedNode: NonNullable<CanvasSurfaceProps["onAddConnectedNode"]> = useCallback((kind, position, originId, handleType) => {
    const current = documentRef.current;
    if (!current.nodes.some(node => node.id === originId)) return;
    const id = uniqueId(current, kind === "text" ? "text" : "generation");
    if (kind !== "text") newGenerationNodeIds.current.add(id);
    commitDocument(document => {
      const next = kind === "text" ? addTextNode(document, id, position, now())
        : updateGenerationSettings(addGenerationNode(document, id, position, now()), id, copyGenerationDefaults(kind), now());
      const source = handleType === 'source' ? originId : id, target = handleType === 'source' ? id : originId;
      const issue = connectionIssue(next, source, target);
      if (issue) { setWorkflowError(issue); return document; }
      setWorkflowError('');
      return connectNodesInDirection(next, source, target, now());
    });
    setSelectedNodeIds([id]);
  }, [commitDocument, uniqueId]);

  const addDepth = useCallback((sourceId: string) => {
    if (!generationEnabled || !generationOptions.some(option => option.operation === "DEPTH")) return;
    const current = documentRef.current;
    const source = current.nodes.find(node => node.id === sourceId);
    if (!source) return;
    const inputIds = source.kind === "generation" && source.settings.operation === "DEPTH"
      ? current.edges.filter(edge => edge.target === sourceId).map(edge => edge.source) : [sourceId];
    if (source.kind !== "video" && !(source.kind === "generation" && source.settings.operation === "DEPTH")) return;
    const id = uniqueId(current, "depth");
    commitDocument(document => {
      let next = updateGenerationSettings(addGenerationNode(document, id, { x: source.position.x + 380, y: source.position.y + 40 }, now()), id, {
        operation: "DEPTH", type: "VIDEO", model: "depth-anything-v2-small", aspectRatio: "source", resolution: "480p", count: 1, durationSeconds: 0,
      }, now());
      for (const input of inputIds) next = connectNodesInDirection(next, input, id, now());
      return next;
    });
    setSelectedNodeIds([id]);
  }, [commitDocument, uniqueId, generationEnabled, generationOptions]);

  const submitGeneration = useCallback(async (id: string) => {
    if (
      !mediaTaskPort
      || !generationEnabled || !generationAccess.current.allowed
      || activeScope.current !== renderScope
      || submitLocks.current.has(id)
    ) {
      return;
    }

    const currentDocument = documentRef.current;
    const generation = currentDocument.nodes.find((node) => node.id === id);
    const frozen = frozenRequests.current.get(id);
    const retry = Boolean(frozen?.uncertain);
    const quote = quoteEntries.current.get(id);
    const revision = accountRevisionRef.current;
    if (!generation || generation.kind !== "generation"
      || (!retry && (!canSubmitGenerationNode(generation)
        || quote?.scope !== renderScope || quote.settings !== generation.settings || quote.inputKey !== quoteInputKey(currentDocument, id) || quote.result.state !== "ready"))) return;

    const request = frozen?.request ?? buildMediaTaskRequest(currentDocument, id, createLocalUniqueRef('canvas-run'));
    if (request.settings.operation !== "DEPTH" && !request.prompt) {
      commitDocument((current) => updateGenerationTask(
        current,
        id,
        { taskRef: null, taskState: "failed", message: "请填写自身提示词或连接文本节点" },
        now(),
      ));
      return;
    }

    const operation = renderScope;
    const operationLifecycle = lifecycleToken.current;
    const lock = Symbol(id);
    submitLocks.current.set(id, lock);
    if (production && !frozen) frozenRequests.current.set(id, { request, uncertain: false });
    let attempted = false;
    if (!retry) commitDocument(current => beginRun(current, id, request, now()));

    try {
      if (!retry && beforeGenerationSubmit) {
        await beforeGenerationSubmit();
        if (activeScope.current !== operation || lifecycleToken.current !== operationLifecycle
          || !documentRef.current.nodes.some((node) => node.id === id && node.kind === "generation"
            && node.taskState === "submitting" && node.settings === generation.settings)) return;
      }
      if (!retry && (quoteEntries.current.get(id) !== quote || quote?.inputKey !== quoteInputKey(documentRef.current, id) || accountRevisionRef.current !== revision)) {
        throw new Error("账户或报价已变化，请确认最新报价后重新提交");
      }
      if (!generationAccess.current.allowed) throw new Error(apiGenerationNotice(generationAccess.current.account, request.settings.type)
        ?? "账户或报价已变化，请确认最新报价后重新提交");
      attempted = true;
      const handle = await mediaTaskPort.submit(request);
      if (!handle.taskRef?.trim()) throw new Error("任务提交未返回有效引用");
      if (
        activeScope.current === operation
        && lifecycleToken.current === operationLifecycle
      ) {
        frozenRequests.current.delete(id);
        commitDocument((current) => {
          if (current.projectId !== operation.projectId) {
            return current;
          }
          return reconcileMediaTask(
            current,
            id,
            { state: "accepted", taskRef: handle.taskRef },
            now(),
          );
        });
      }
    } catch (error) {
      if (
        activeScope.current === operation
        && lifecycleToken.current === operationLifecycle
      ) {
        const uncertain = production && attempted && (retry || !(error instanceof ApiError)
          || ![400, 401, 403, 404, 422].includes(error.status));
        if (uncertain) frozenRequests.current.set(id, { request, uncertain: true });
        else frozenRequests.current.delete(id);
        commitDocument((current) => current.projectId === operation.projectId
          ? updateGenerationTask(current, id, {
              taskRef: null, taskState: uncertain ? "submitting" : "failed",
              message: uncertain ? `${error instanceof TypeError ? "网络连接失败；" : ""}提交结果未确认，请使用原请求重试`
                : error instanceof Error && error.message === "任务提交未返回有效引用"
                  ? "任务提交未返回有效引用，请重试"
                  : production ? canvasError(error) : "任务提交失败",
            }, now())
          : current);
      }
    } finally {
      if (submitLocks.current.get(id) === lock) {
        submitLocks.current.delete(id);
        refreshQuotes(value => value + 1);
      }
    }
  }, [commitDocument, mediaTaskPort, renderScope, generationEnabled, production, beforeGenerationSubmit, generationOptions]);

  const readGeneration = useCallback(async (id: string) => {
    if (!mediaTaskPort || activeScope.current !== renderScope || (production && readLocks.current.has(id))) {
      return;
    }
    const node = documentRef.current.nodes.find((item) => (
      item.id === id && item.kind === "generation"
    ));
    if (!node || node.kind !== "generation" || !node.taskRef) {
      return;
    }
    const taskRef = node.taskRef;
    if (production) readLocks.current.add(id);

    const operation = renderScope;
    const operationLifecycle = lifecycleToken.current;
    try {
      const snapshot = await mediaTaskPort.read({ taskRef });
      if (
        activeScope.current === operation
        && lifecycleToken.current === operationLifecycle
      ) {
        let settled = false;
        commitDocument((current) => {
          if (current.projectId !== operation.projectId) return current;
          const previous = current.nodes.find(node => node.id === id);
          const next = reconcileMediaTask(current, id, snapshot, now());
          const updated = next.nodes.find(node => node.id === id);
          const final = ["succeeded", "partial_success", "failed", "timed_out"];
          settled = previous?.kind === "generation" && updated?.kind === "generation"
            && previous.taskRef === snapshot.taskRef && !final.includes(previous.taskState) && final.includes(updated.taskState);
          return next;
        });
        if (production && settled && node.settings.operation !== "DEPTH") window.dispatchEvent(new Event("xzwl-account-changed"));
      }
    } catch (error) {
      if (
        activeScope.current === operation
        && lifecycleToken.current === operationLifecycle
      ) {
        if (production) {
          commitDocument((current) => {
            const latest = current.nodes.find(item => item.id === id);
            return latest?.kind === "generation" && ["accepted", "working", "refund_pending"].includes(latest.taskState)
              ? updateGenerationTask(current, id, { taskRef, taskState: latest.taskState, message: canvasError(error) }, now())
              : current;
          });
          return;
        }
        commitDocument((current) => current.projectId === operation.projectId
          ? reconcileMediaTask(
              current,
              id,
              {
                state: "failed",
                taskRef,
                message: "任务状态读取失败",
              },
              now(),
            )
          : current);
      }
    } finally {
      readLocks.current.delete(id);
    }
  }, [commitDocument, mediaTaskPort, renderScope, production]);

  useEffect(() => {
    if (!production || !mediaTaskPort) return;
    const timer = window.setInterval(() => {
      for (const node of documentRef.current.nodes) {
        if (node.kind === "generation" && ["accepted", "working", "refund_pending"].includes(node.taskState)) {
          void readGeneration(node.id);
        }
      }
    }, 3000);
    return () => window.clearInterval(timer);
  }, [production, mediaTaskPort, readGeneration]);

  const deleteSelection = useCallback(() => {
    if (selectedNodeIds.length === 0) {
      return;
    }
    if (documentRef.current.nodes.some(node => selectedNodeIds.includes(node.id) && taskBusy(node))) { setWorkflowError('请等待任务完成后再删除节点'); return; }
    commitDocument((current) => removeNodes(current, selectedNodeIds, now()));
    setSelectedNodeIds([]);
  }, [commitDocument, selectedNodeIds]);

  const historyLocked = document.nodes.some(taskBusy);
  const groupSelection = () => commitDocument(current => createGroup(current, selectedNodeIds, createLocalUniqueRef('group'), now()));
  const ungroupSelection = () => commitDocument(current => removeFromGroups(current, selectedNodeIds, now()));
  const selectionGrouped = document.groups?.some(group => group.nodeIds.some(id => selectedNodeIds.includes(id)));
  const selectionIsGroup = document.groups?.some(group => group.nodeIds.length === selectedNodeIds.length && group.nodeIds.every(id => selectedNodeIds.includes(id)));
  const travel = (redo: boolean) => {
    if (documentRef.current.nodes.some(taskBusy)) return;
    const from = redo ? history.current.future : history.current.past;
    const snapshot = from.pop(); if (!snapshot) return;
    (redo ? history.current.past : history.current.future).push(documentRef.current);
    const next = restoreDraft(documentRef.current, snapshot);
    history.current.lastKey = ''; documentRef.current = next; setDocument(next); changeListener.current?.(next);
    setSelectedNodeIds(ids => ids.filter(id => next.nodes.some(node => node.id === id))); setWorkflowError('');
  };
  const copy = () => { if (selectedNodeIds.length) { clipboard.current = copySelection(documentRef.current, selectedNodeIds); setClipboardReady(true); } };
  const paste = async (position?: CanvasPoint) => {
    if (!clipboard.current) return;
    const lifecycle = lifecycleToken.current;
    const result = pasteSelection(documentRef.current, clipboard.current, () => createLocalUniqueRef('node'));
    const copied = clipboard.current;
    if (position && copied.nodes.length) {
      const x = Math.min(...copied.nodes.map(node => node.position.x)) + 48, y = Math.min(...copied.nodes.map(node => node.position.y)) + 48;
      const pastedIds = new Set(result.mapping.values());
      result.document = { ...result.document, nodes: result.document.nodes.map(node => pastedIds.has(node.id)
        ? { ...node, position: { x: node.position.x + position.x - x, y: node.position.y + position.y - y } } : node) };
    }
    if (lifecycleToken.current !== lifecycle || documentRef.current.projectId !== copied.projectId) return;
    commitDocument(current => {
      const nodes = [...current.nodes, ...result.document.nodes.filter(node => [...result.mapping.values()].includes(node.id))];
      const ids = new Set(nodes.map(node => node.id));
      return { ...current, nodes, edges: [...current.edges, ...result.document.edges.filter(edge => [...result.mapping.values()].includes(edge.target) && ids.has(edge.source))], ...(result.groups.length ? { groups: [...(current.groups ?? []), ...result.groups] } : {}), updatedAt: now() };
    });
    setSelectedNodeIds([...result.mapping.values()]);
  };

  const duplicate = () => { if (!selectedNodeIds.length) return; copy(); void paste(); };
  const addAtPosition = (kind: 'IMAGE' | 'VIDEO' | 'text', position: CanvasPoint) => {
    const id = uniqueId(documentRef.current, kind === 'text' ? 'text' : 'generation');
    if (kind !== 'text') newGenerationNodeIds.current.add(id);
    commitDocument(current => kind === 'text' ? addTextNode(current, id, position, now())
      : updateGenerationSettings(addGenerationNode(current, id, position, now()), id, copyGenerationDefaults(kind), now()));
    setSelectedNodeIds([id]);
  };
  useEffect(() => {
    const keydown = (event: KeyboardEvent) => {
        if (locked || event.defaultPrevented || (event.target instanceof Element && event.target.closest('dialog,[role="dialog"]'))) return;
        if (!isEditingTarget(event.target) && (event.ctrlKey || event.metaKey)) {
          const key = event.key.toLowerCase();
          if (['z', 'y', 'c', 'v', 'g', 'd'].includes(key)) {
            event.preventDefault();
            if (key === 'z' || key === 'y') travel(key === 'y' || event.shiftKey);
            if (key === 'c') copy();
            if (key === 'v') void paste();
            if (key === 'd') duplicate();
            if (key === 'g') { if (event.shiftKey) ungroupSelection(); else groupSelection(); }
            return;
          }
        }
        if (
          (event.key === "Delete" || event.key === "Backspace")
          && !isEditingTarget(event.target)
          && selectedNodeIds.length > 0
        ) {
          event.preventDefault();
          deleteSelection();
        }
    };
    window.addEventListener('keydown', keydown);
    return () => window.removeEventListener('keydown', keydown);
  });

  return (
    <section
      className="canvas-workspace"
      inert={locked}
      data-testid="canvas-workspace"
      data-project-id={projectId}
      aria-labelledby="canvas-title"
    >
      <header className="canvas-toolbar">
        <a className="canvas-brand" href={onBack ? '/canvas' : '/home'} aria-label={onBack ? `${t('返回画布首页')} · YOMIO` : t('YOMIO 首页')}
          aria-disabled={onBack && document.nodes.some(node => node.kind === 'generation' && node.taskState === 'submitting') || undefined}
          onClick={event => {
            if (!onBack) return;
            event.preventDefault();
            if (!document.nodes.some(node => node.kind === 'generation' && node.taskState === 'submitting')) onBack();
          }}><CanvasIcon name="back" /><strong>YOMIO</strong></a>
        <h1 id="canvas-title" className="sr-only">{t("创作画布")}</h1>
        <div className="canvas-project-tools">{toolbar?.(document, commitDocument, generationOptions, generationOptionsStatus === "loading")}</div>
        <div className="canvas-history-tools" role="group" aria-label={t("编辑历史")}>
          <button type="button" disabled={historyLocked || !history.current.past.length} onClick={() => travel(false)} title={t("撤销 Ctrl/⌘ Z")}>{t("撤销")}</button>
          <button type="button" disabled={historyLocked || !history.current.future.length} onClick={() => travel(true)} title={t("重做 Ctrl/⌘ Shift Z")}>{t("重做")}</button>
          <button type="button" disabled={!selectedNodeIds.length} onClick={copy} title={t("复制节点 Ctrl/⌘ C")}>{t("复制节点")}</button>
          <button type="button" disabled={!clipboardReady} onClick={() => void paste()} title={t("粘贴节点 Ctrl/⌘ V")}>{t("粘贴")}</button>
        </div>
      </header>
      {workflowError && <button type="button" className="canvas-workflow-error" role="alert" onClick={() => setWorkflowError('')}>{t(workflowError)}</button>}
      <div className="canvas-tool-rail" role="toolbar" aria-label={t("添加画布节点")}>
        <span className="canvas-rail-heading" aria-hidden="true"><CanvasIcon name="plus" /></span>
        <button type="button" title={t("新增图片")} onClick={() => addGeneration("IMAGE")}><CanvasIcon name="image" /><span>{t("新增图片")}</span></button>
        <button type="button" title={t("新增视频")} onClick={() => addGeneration("VIDEO")}><CanvasIcon name="video" /><span>{t("新增视频")}</span></button>
        <button type="button" title={t("新增文本节点")} aria-label={t("新增文本节点")} onClick={addText}><CanvasIcon name="text" /><span>{t("文本")}</span></button>
        <button type="button" title={t("新增生成节点")} aria-label={t("新增生成占位节点")} onClick={() => addGeneration()}><CanvasIcon name="plus" /><span>{t("生成")}</span></button>
        <span className="canvas-rail-divider" />
        <button type="button" title={t("选中至少两个节点后创建分组 · Ctrl/⌘ G")} aria-label={t("创建分组")} disabled={selectedNodeIds.length < 2 || selectionIsGroup} onClick={groupSelection}><CanvasIcon name="group" /><span>{t("分组")}</span></button>
        <button type="button" title={t("将所选节点移出分组 · Ctrl/⌘ Shift G")} aria-label={t("移出分组")} disabled={!selectionGrouped} onClick={ungroupSelection}><CanvasIcon name="ungroup" /><span>{t("移出分组")}</span></button>
        <button type="button" title={t("删除所选节点")} aria-label={t("删除所选节点")} disabled={selectedNodeIds.length === 0} onClick={deleteSelection}><CanvasIcon name="trash" /><span>{t("删除")}</span></button>
      </div>
      <span className="canvas-save-status" data-state={saveStatus?.includes('冲突') || saveStatus?.includes('失败') ? 'error' : saveStatus?.includes('未保存') || saveStatus?.includes('正在') ? 'pending' : 'saved'} aria-label={t("画布保存状态")} role={saveMessage === "本地草稿未保存" ? "alert" : "status"}>
        {t(saveStatus ?? (mediaTaskPort ? saveMessage : formatMessage("生产生成合同待接入 · {0}", saveMessage)))}
      </span>
      <div className={selectedNodeIds.length ? 'canvas-selection-feedback' : 'sr-only'}>
        <span role="status" aria-label={t("节点选择状态")} aria-live="polite" aria-atomic="true">
          {selectedNodeIds.length ? formatMessage('已选中 {0} 个节点', selectedNodeIds.length) : t('未选中节点')}
        </span>
        {selectedNodeIds.length > 0 && <>
          <span className="canvas-selection-hint">{t("Shift + 点击可追加或取消")}</span>
          <button type="button" onClick={() => setSelectedNodeIds([])} aria-label={t("取消节点选择")}>{t("取消选择")}</button>
        </>}
      </div>
      {[...frozenRequests.current].filter(([, entry]) => entry.uncertain).map(([id]) => <div key={id} className="canvas-api-error" role="group" aria-label={formatMessage("未确认的生成请求 {0}", id)}>
        <p>{t("提交结果未确认，原提示词、参数和引用已保留。")}</p>
        <button type="button" disabled={!generationEnabled || submitLocks.current.has(id)} aria-label={formatMessage("使用原请求重试 {0}", id)} onClick={() => { void submitGeneration(id); }}>{t("使用原请求重试")}</button>
      </div>)}
      <CanvasSurface
        key={`canvas-scope-${surfaceEpoch}`}
        document={document}
        locked={locked}
        clipboardReady={clipboardReady}
        selectionGrouped={selectionGrouped}
        selectionIsGroup={selectionIsGroup}
        selectionBusy={document.nodes.some(node => selectedNodeIds.includes(node.id) && taskBusy(node))}
        onCopySelection={copy}
        onPasteSelection={position => { void paste(position); }}
        onDuplicateSelection={duplicate}
        onGroupSelection={groupSelection}
        onUngroupSelection={ungroupSelection}
        onDeleteSelection={deleteSelection}
        onAddNode={addAtPosition}
        selectedNodeIds={selectedNodeIds}
        canSubmit={Boolean(mediaTaskPort) && generationEnabled}
        generationOptions={generationOptions}
        generationOptionsStatus={generationOptionsStatus}
        nodeQuotes={nodeQuotes}
        onGenerationSettingsChange={changeGenerationSettings}
        onCreateDepth={addDepth}
        onAddConnectedNode={addConnectedNode}
        onUploadMedia={onUploadMedia}
        onUploadAt={onUploadAt}
        onSelectOutput={(id, outputId) => commitDocument(current => selectOutput(current, id, outputId))}
        onChangeReference={(edgeId, patch) => commitDocument(current => changeReference(current, edgeId, patch))}
        onUseOutput={(id, outputId, type) => {
          const current = selectOutput(documentRef.current, id, outputId);
          const origin = current.nodes.find(node => node.id === id); if (!origin) return;
          const childId = uniqueId(current, 'generation'); newGenerationNodeIds.current.add(childId);
          commitDocument(() => connectNodes(updateGenerationSettings(addGenerationNode(current, childId, { x: origin.position.x + 520, y: origin.position.y }, now()), childId, copyGenerationDefaults(type), now()), id, childId, now()));
          setSelectedNodeIds([childId]);
        }}
        onStartReferenceGeneration={(id, kind) => {
          const node = documentRef.current.nodes.find(node => node.id === id);
          if (node?.kind !== 'generation' || node.settings.operation === 'DEPTH' || node.taskState !== 'idle') return;
          if (!documentRef.current.edges.some(edge => edge.target === id)) {
            const frames = kind === 'FIRST_LAST_FRAME';
            if (node.settings.type === 'VIDEO') changeGenerationSettings(id, { ...node.settings, referenceMode: frames ? 'FIRST_LAST_FRAME' : 'OMNI' });
            for (const offset of frames ? [-200, 200] : [0]) {
              addConnectedNode(kind === 'VIDEO' ? 'VIDEO' : 'IMAGE', { x: node.position.x - 460, y: node.position.y + offset }, id, 'target');
            }
          }
          setSelectedNodeIds([id]);
        }}
        onGenerationPromptChange={(id, prompt, bindings) => {
          commitDocument(current => {
            const next = updateGenerationPrompt(current, id, prompt, now());
            return next === current ? current : { ...next, nodes: next.nodes.map(node => node.id === id && node.kind === 'generation' ? { ...node, ...(bindings ? { promptBindings: bindings } : {}) } : node) };
          });
        }}
        onTextChange={(id, text) => {
          commitDocument((current) => updateTextNode(current, id, text, now()));
        }}
        onConnect={(source, target) => {
          const issue = connectionIssue(documentRef.current, source, target); setWorkflowError(issue); if (issue) return;
          commitDocument((current) => connectNodes(current, source, target, now()));
        }}
        onDisconnect={(ids) => {
          if (documentRef.current.edges.some(edge => ids.includes(edge.id) && documentRef.current.nodes.some(node => node.id === edge.target && taskBusy(node)))) { setWorkflowError('请等待任务完成后再修改引用'); return; }
          commitDocument(current => removeEdges(current, ids, now()));
        }}
        onMoveNode={(id, position) => {
          commitDocument((current) => moveNode(current, id, position, now()));
        }}
        onMoveNodes={positions => commitDocument(current => moveNodes(current, positions, now()))}
        onRenameGroup={(id, name) => commitDocument(current => renameGroup(current, id, name, now()))}
        onUngroup={id => commitDocument(current => removeFromGroups(current, current.groups?.find(group => group.id === id)?.nodeIds ?? [], now()))}
        onSelectionChange={setSelectedNodeIds}
        onViewportChange={(viewport) => {
          commitDocument((current) => updateViewport(current, viewport, now()));
        }}
        onSubmitGeneration={(id) => { void submitGeneration(id); }}
        onReadGeneration={(id) => { void readGeneration(id); }}
      />
    </section>
  );
}
