import { t, useLanguage } from '../i18n';
import {
  Background,
  BaseEdge,
  getBezierPath,
  Handle,
  MiniMap,
  Position,
  PanOnScrollMode,
  ReactFlow,
  ViewportPortal,
  type Connection,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeChange,
  type NodeProps,
  type ReactFlowInstance,
  type Viewport,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import "./connections.css";
import "./local-canvas.css";
import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { collectCanvasInputs } from "./media-task-port";
import { canConnectNodeKinds, connectionSourceKind, generationOutputs } from "./document";
import { CanvasIcon } from "./CanvasIcon";
import { MemoCanvasNodes, type CanvasFlowData, type NodeQuoteState } from "./CanvasNodes";
import type { CanvasDocument, CanvasGenerationSettings, CanvasPoint, CanvasViewport, CanvasEdge, GenerationCanvasNode } from "./model";

import type { CanvasGenerationOption } from "./media-task-port";
import { CanvasGroupFrame } from './CanvasGroups';
import { groupBounds } from './groups';
import { CanvasContextMenu, type CanvasMenuAction } from './CanvasContextMenu';

type CanvasFlowNode = Node<CanvasFlowData, CanvasDocument["nodes"][number]["kind"]>;

const TextNode = (props: NodeProps<CanvasFlowNode>) => { useLanguage(); return (
  <>
    <MemoCanvasNodes data={props.data} />
    <Handle id="output" type="source" position={Position.Right} />
  </>
); };

const ImageNode = (props: NodeProps<CanvasFlowNode>) => { useLanguage(); return (
  <>
    <MemoCanvasNodes data={props.data} />
    <Handle id="output" type="source" position={Position.Right} />
  </>
); };

const GenerationNode = (props: NodeProps<CanvasFlowNode>) => { useLanguage(); return (
  <>
    <Handle id="input" type="target" position={Position.Left} />
    <MemoCanvasNodes data={props.data} />
    <Handle id="output" type="source" position={Position.Right} />
  </>
); };

const nodeTypes = {
  text: TextNode,
  image: ImageNode,
  video: ImageNode,
  audio: ImageNode,
  generation: GenerationNode,
};

function ReferenceEdge(props: EdgeProps<Edge<{ onDelete(): void }>>) {
  useLanguage();
  const [path, x, y] = getBezierPath(props);
  const glowId = useId();
  const padding = Math.max(Math.abs(props.targetX - props.sourceX), Math.abs(props.targetY - props.sourceY), 100);
  return <g className="canvas-reference-edge">
    <BaseEdge id={props.id} path={path} style={props.style} markerEnd={props.markerEnd} interactionWidth={28} />
    {props.animated && <g className="canvas-edge-flow" aria-hidden="true" pointerEvents="none">
      <defs>
        <radialGradient id={`${glowId}-gradient`}><stop offset="0%" stopColor="white" /><stop offset="100%" stopColor="white" stopOpacity="0" /></radialGradient>
        <mask id={`${glowId}-mask`} maskUnits="userSpaceOnUse"
          x={Math.min(props.sourceX, props.targetX) - padding} y={Math.min(props.sourceY, props.targetY) - padding}
          width={Math.abs(props.targetX - props.sourceX) + padding * 2} height={Math.abs(props.targetY - props.sourceY) + padding * 2}>
          <circle r="18" fill={`url(#${glowId}-gradient)`}><animateMotion path={path} dur="1s" repeatCount="indefinite" /></circle>
        </mask>
      </defs>
      <path d={path} fill="none" stroke="white" strokeWidth="2" mask={`url(#${glowId}-mask)`} />
    </g>}
    <foreignObject x={x - 16} y={y - 16} width={32} height={32}>
      <button type="button" className="canvas-edge-delete nodrag nopan" aria-label={t("删除连线")} title={t("删除连线")}
        onClick={event => { event.stopPropagation(); props.data?.onDelete(); }}>
        <CanvasIcon name="trash" />
      </button>
    </foreignObject>
  </g>;
}

const edgeTypes = { reference: ReferenceEdge };

function Controls({ instance, onFit, hasSelection, onFitSelection }: { instance: React.RefObject<ReactFlowInstance<CanvasFlowNode, Edge> | null>; onFit(): void; hasSelection: boolean; onFitSelection(): void }) {
  useLanguage();
  return (
    <div className="canvas-view-controls" role="group" aria-label={t("画布视口控制")}>
      <button type="button" aria-label={t("适配视图")} title={t("适配全部节点 · Shift + F")} onClick={onFit}>
        <CanvasIcon name="expand" />
      </button>
      <button type="button" aria-label={t("定位所选节点")} title={t("定位所选节点 · F")} disabled={!hasSelection} onClick={onFitSelection}><CanvasIcon name="locate" /></button>
      <button type="button" aria-label={t("放大画布")} title={t("放大画布")} onClick={() => { void instance.current?.zoomIn(); }}>
        +
      </button>
      <button type="button" aria-label={t("缩小画布")} title={t("缩小画布")} onClick={() => { void instance.current?.zoomOut(); }}>
        −
      </button>
    </div>
  );
}

export type CanvasSurfaceProps = {
  document: CanvasDocument;
  selectedNodeIds: readonly string[];
  canSubmit: boolean;
  generationOptions: readonly CanvasGenerationOption[];
  generationOptionsStatus: "idle" | "loading" | "ready" | "failed";
  nodeQuotes: Readonly<Record<string, NodeQuoteState>>;
  onGenerationSettingsChange(id: string, settings: CanvasGenerationSettings): void;
  onTextChange(id: string, text: string): void;
  onCreateDepth?(sourceId: string): void;
  onUploadMedia?(id: string): void;
  onUploadAt?(position: CanvasPoint): void;
  onStartReferenceGeneration?(id: string, kind: "IMAGE" | "VIDEO" | "FIRST_LAST_FRAME"): void;
  onGenerationPromptChange?(id: string, text: string, bindings?: GenerationCanvasNode['promptBindings']): void;
  onSelectOutput?(id: string, outputId: string): void;
  onUseOutput?(id: string, outputId: string, type: 'IMAGE' | 'VIDEO'): void;
  onChangeReference?(edgeId: string, patch: Pick<CanvasEdge, 'outputId' | 'role'>): void;
  onAddConnectedNode?(kind: "IMAGE" | "VIDEO" | "text", position: CanvasPoint, originId: string, handleType: "source" | "target"): void;
  onConnect(source: string, target: string): void;
  onDisconnect?(ids: readonly string[]): void;
  onMoveNode(id: string, position: CanvasPoint): void;
  onMoveNodes?(positions: Readonly<Record<string, CanvasPoint>>): void;
  onRenameGroup?(id: string, name: string): void;
  onUngroup?(id: string): void;
  onSelectionChange(ids: readonly string[]): void;
  onViewportChange(viewport: CanvasViewport): void;
  onSubmitGeneration(id: string): void;
  onReadGeneration(id: string): void;
  locked?: boolean;
  clipboardReady?: boolean;
  selectionGrouped?: boolean;
  selectionIsGroup?: boolean;
  selectionBusy?: boolean;
  onCopySelection?(): void;
  onPasteSelection?(position?: CanvasPoint): void;
  onDuplicateSelection?(): void;
  onGroupSelection?(): void;
  onUngroupSelection?(): void;
  onDeleteSelection?(): void;
  onAddNode?(kind: 'IMAGE' | 'VIDEO' | 'text', position: CanvasPoint): void;
};

export function CanvasSurface(props: CanvasSurfaceProps) {
  useLanguage();
  const instance = useRef<ReactFlowInstance<CanvasFlowNode, Edge> | null>(null);
  const stage = useRef<HTMLDivElement>(null);
  const [panning, setPanning] = useState(false);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; position: CanvasPoint; node: boolean; returnFocus: HTMLElement | null } | null>(null);
  const [selectionOnlyIds, setSelectionOnlyIds] = useState<ReadonlySet<string>>(() => new Set());
  const pointerSelection = useRef<{ id: string; ids: readonly string[] } | null>(null);
  const suppressExistingComposers = useCallback(() => {
    setSelectionOnlyIds(new Set(props.document.nodes.map(node => node.id)));
  }, [props.document.nodes]);
  const selectionTarget = (target: EventTarget) => target instanceof Element && !target.closest('.canvas-composer,input,textarea,select,[contenteditable="true"]')
    ? target.closest<HTMLElement>('.canvas-node')?.dataset.nodeId : undefined;
  const menuRef = useRef<HTMLDivElement>(null);
  const [measurements, setMeasurements] = useState<Record<string, { width: number; height: number }>>({});
  const [dragPositions, setDragPositions] = useState<Record<string, CanvasPoint>>({});
  const [pendingConnection, setPendingConnection] = useState<{
    position: CanvasPoint; left: number; top: number; originId: string; handleType: "source" | "target";
  } | null>(null);
  useEffect(() => {
    if (!pendingConnection) return;
    menuRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
    const dismiss = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as globalThis.Node)) setPendingConnection(null);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); setPendingConnection(null); }
    };
    const resize = () => setPendingConnection(null);
    document.addEventListener("pointerdown", dismiss);
    document.addEventListener("keydown", escape);
    window.addEventListener("resize", resize);
    return () => {
      document.removeEventListener("pointerdown", dismiss);
      document.removeEventListener("keydown", escape);
      window.removeEventListener("resize", resize);
    };
  }, [pendingConnection]);
  const [keyboardSourceId, setKeyboardSourceId] = useState<string | null>(null);

  const keyboardConnect = useCallback((id: string) => {
    if (keyboardSourceId === null) {
      setKeyboardSourceId(id);
      return;
    }
    props.onConnect(keyboardSourceId, id);
    setKeyboardSourceId(null);
  }, [keyboardSourceId, props.onConnect]);

  const keyboardSelect = useCallback((id: string, additive: boolean) => {
    if (additive) suppressExistingComposers();
    else setSelectionOnlyIds(new Set());
    const selected = new Set(props.selectedNodeIds);
    if (additive) {
      if (selected.has(id)) {
        selected.delete(id);
      } else {
        selected.add(id);
      }
      props.onSelectionChange([...selected]);
      return;
    }
    props.onSelectionChange(selected.size === 1 && selected.has(id) ? [] : [id]);
  }, [props.onSelectionChange, props.selectedNodeIds, suppressExistingComposers]);

  const outputsByGeneration = useMemo(() => new Map(props.document.nodes
    .filter(node => node.kind === "generation")
    .map(node => [node.id, generationOutputs(props.document, node.id)])), [props.document.nodes, props.document.edges]);
  const outputOwners = useMemo(() => new Map([...outputsByGeneration].flatMap(([id, outputs]) =>
    outputs.map(output => [output.id, id] as const))), [outputsByGeneration]);
  const flowNodes: CanvasFlowNode[] = useMemo(() => props.document.nodes.filter(node => !outputOwners.has(node.id)).map((node) => ({
    id: node.id,
    type: node.kind,
    ariaRole: "group",
    position: node.position,
    measured: measurements[node.id],
    selected: props.selectedNodeIds.includes(node.id),
    data: {
      node,
      outputs: outputsByGeneration.get(node.id),
      inputs: node.kind === "generation" ? collectCanvasInputs(props.document, node.id) : undefined,
      onGenerationPromptChange: props.onGenerationPromptChange,
      onSelectOutput: props.onSelectOutput,
      onUseOutput: props.onUseOutput,
      onChangeReference: props.onChangeReference,
      onCreateDepth: props.onCreateDepth,
      onUploadMedia: props.onUploadMedia,
      onStartReferenceGeneration: props.onStartReferenceGeneration,
      onRemoveReference: props.onDisconnect ? (referenceId: string) => {
        const reference = props.document.edges.find(edge => edge.id === referenceId);
        const sourceId = reference?.source ?? referenceId;
        const assetId = (edge: CanvasEdge) => {
          const source = props.document.nodes.find(item => item.id === (edge.outputId ?? edge.source));
          if (source && source.kind !== 'generation' && source.kind !== 'text') return source.asset.assetRef;
          const outputs = outputsByGeneration.get(edge.source); return outputs?.length === 1 ? outputs[0].asset.assetRef : undefined;
        };
        props.onDisconnect?.(props.document.edges.filter(edge => (outputOwners.get(edge.source) ?? edge.source) === (outputOwners.get(sourceId) ?? sourceId)
          && edge.target === node.id && (!reference || assetId(edge) === assetId(reference))).map(edge => edge.id));
      } : undefined,
      selected: props.selectedNodeIds.includes(node.id),
      suppressComposer: selectionOnlyIds.has(node.id) || props.selectedNodeIds.length > 1,
      canSubmit: props.canSubmit,
      generationOptions: props.generationOptions,
      generationOptionsStatus: props.generationOptionsStatus,
      quote: props.nodeQuotes[node.id] ?? { state: "idle" },
      onGenerationSettingsChange: props.onGenerationSettingsChange,
      onTextChange: props.onTextChange,
      onSubmitGeneration: props.onSubmitGeneration,
      onReadGeneration: props.onReadGeneration,
      onSelect: keyboardSelect,
      onKeyboardConnect: keyboardConnect,
      keyboardSourceId,
    },
  })), [
    measurements,
    outputOwners,
    outputsByGeneration,
    keyboardConnect,
    keyboardSelect,
    keyboardSourceId,
    props.canSubmit,
    props.generationOptions,
    props.generationOptionsStatus,
    props.nodeQuotes,
    props.onGenerationSettingsChange,
    props.document.nodes,
    props.document.edges,
    props.onGenerationPromptChange,
    props.onSelectOutput,
    props.onUseOutput,
    props.onChangeReference,
    props.onCreateDepth,
    props.onUploadMedia,
    props.onStartReferenceGeneration,
    props.onDisconnect,
    props.onReadGeneration,
    props.onSubmitGeneration,
    props.onTextChange,
    props.selectedNodeIds,
    selectionOnlyIds,
  ]);
  const displayedNodes = useMemo(() => flowNodes.map(node => dragPositions[node.id]
    ? { ...node, position: dragPositions[node.id], dragging: true } : node), [flowNodes, dragPositions]);
  const edges: Edge[] = useMemo(() => {
    const selectedMedia = new Set(flowNodes.filter(node => node.selected && (node.type === "image" || node.type === "video" || node.type === "generation")).map(node => node.id));
    const mappedEdges = props.document.edges.map(edge => ({
    ...edge, source: outputOwners.get(edge.source) ?? edge.source, target: outputOwners.get(edge.target) ?? edge.target,
  }));
    return mappedEdges
    .filter((edge, index, all) => edge.source !== edge.target
      && all.findIndex(other => other.source === edge.source && other.target === edge.target) === index)
    .map(edge => ({ ...edge, type: "reference", ...(edge.role === 'annotation' ? { style: { strokeDasharray: '5 5' } } : {}), animated: edge.role !== 'annotation' && (selectedMedia.has(edge.source) || selectedMedia.has(edge.target)), data: {
      onDelete: () => props.onDisconnect?.(mappedEdges
        .filter(other => other.source === edge.source && other.target === edge.target).map(other => other.id)),
    } }));
  }, [props.document.edges, outputOwners, props.onDisconnect, flowNodes]);
  const onConnect = useCallback((connection: Connection) => {
    if (connection.source && connection.target) {
      props.onConnect(connection.source, connection.target);
    }
  }, [props.onConnect]);
  const onNodesChange = useCallback((changes: NodeChange<CanvasFlowNode>[]) => {
    const selected = new Set(props.selectedNodeIds);
    let selectionChanged = false;
    const finishedPositions: Record<string, CanvasPoint> = {};

    for (const change of changes) {
      if (change.type === "dimensions" && change.dimensions) {
        const dimensions = change.dimensions;
        setMeasurements(current => current[change.id]?.width === dimensions.width && current[change.id]?.height === dimensions.height
          ? current : { ...current, [change.id]: dimensions });
      }
      if (change.type === "position" && change.position) {
        if (change.dragging) {
          const position = change.position;
          setDragPositions(current => ({ ...current, [change.id]: position }));
        } else {
          finishedPositions[change.id] = change.position;
          setDragPositions(current => {
            if (!(change.id in current)) return current;
            const next = { ...current };
            delete next[change.id];
            return next;
          });
        }
      }
      if (change.type === "select") {
        selectionChanged = true;
        if (change.selected) {
          selected.add(change.id);
        } else {
          selected.delete(change.id);
        }
      }
    }

    if (Object.keys(finishedPositions).length) {
      if (props.onMoveNodes) props.onMoveNodes(finishedPositions);
      else Object.entries(finishedPositions).forEach(([id, position]) => props.onMoveNode(id, position));
    }
    if (selectionChanged) {
      props.onSelectionChange([...selected]);
    }
  }, [props.onMoveNode, props.onMoveNodes, props.onSelectionChange, props.selectedNodeIds]);
  const onSelectionChange = useCallback(({ nodes: selected }: { nodes: CanvasFlowNode[] }) => {
    props.onSelectionChange(selected.map((node) => node.id));
  }, [props.onSelectionChange]);
  const onViewportChange = useCallback((
    _event: MouseEvent | TouchEvent | null,
    viewport: Viewport,
  ) => {
    props.onViewportChange(viewport);
  }, [props.onViewportChange]);

  const fit = (selected = false) => {
    const flow = instance.current;
    if (!flow) return;
    const nodes = selected ? displayedNodes.filter(node => props.selectedNodeIds.includes(node.id)) : displayedNodes;
    if (!nodes.length) return;
    const frames = (props.document.groups ?? []).filter(group => !selected || group.nodeIds.every(id => props.selectedNodeIds.includes(id)))
      .map(group => groupBounds(group, nodes)).filter(frame => frame !== null);
    const bounds = [flow.getNodesBounds(nodes), ...frames];
    const x = Math.min(...bounds.map(bound => bound.x)), y = Math.min(...bounds.map(bound => bound.y));
    void flow.fitBounds({ x, y, width: Math.max(...bounds.map(bound => bound.x + bound.width)) - x, height: Math.max(...bounds.map(bound => bound.y + bound.height)) - y }, { padding: .25, duration: 200 });
  };
  useEffect(() => {
    const blocked = (event: KeyboardEvent) => props.locked || event.defaultPrevented || (event.target instanceof Element && Boolean(event.target.closest('input,textarea,select,[contenteditable="true"],[role="dialog"],dialog,[role="menu"]')));
    const down = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !props.locked && !event.defaultPrevented && !(event.target instanceof Element && event.target.closest('[role="dialog"],dialog,[role="menu"]'))) {
        setContextMenu(null); setPendingConnection(null); setKeyboardSourceId(null); suppressExistingComposers(); props.onSelectionChange([]);
        if (document.activeElement instanceof HTMLElement && stage.current?.contains(document.activeElement)) { document.activeElement.blur(); stage.current?.focus(); }
        return;
      }
      if (blocked(event)) return;
      if (event.code === 'Space' && !(event.target instanceof Element && event.target.closest('button,a'))) {
        event.preventDefault(); setPanning(true); setContextMenu(null); return;
      }
      if (event.key.toLowerCase() === 'f' && !event.ctrlKey && !event.metaKey && !event.altKey) { event.preventDefault(); fit(!event.shiftKey && props.selectedNodeIds.length > 0); }
    };
    const up = (event: KeyboardEvent) => { if (event.code === 'Space') setPanning(false); };
    const blur = () => { setPanning(false); setContextMenu(null); };
    window.addEventListener('keydown', down); window.addEventListener('keyup', up); window.addEventListener('blur', blur);
    return () => { window.removeEventListener('keydown', down); window.removeEventListener('keyup', up); window.removeEventListener('blur', blur); };
  });
  const uploadAction: CanvasMenuAction = { label: '上传图片或视频', icon: 'upload', disabled: !props.onUploadAt || props.generationOptionsStatus === 'loading', run: () => contextMenu && props.onUploadAt?.(contextMenu.position) };
  const menuActions: CanvasMenuAction[] = contextMenu?.node ? [
    uploadAction,
    { label: '复制节点', icon: 'copy', shortcut: 'Ctrl/⌘ C', run: () => props.onCopySelection?.() },
    { label: '创建副本', icon: 'copy', shortcut: 'Ctrl/⌘ D', run: () => props.onDuplicateSelection?.() },
    { label: '创建分组', icon: 'group', shortcut: 'Ctrl/⌘ G', disabled: props.selectedNodeIds.length < 2 || props.selectionIsGroup, run: () => props.onGroupSelection?.() },
    { label: '移出分组', icon: 'ungroup', shortcut: '⇧ Ctrl/⌘ G', disabled: !props.selectionGrouped, run: () => props.onUngroupSelection?.() },
    { label: '定位所选节点', icon: 'locate', shortcut: 'F', run: () => fit(true) },
    { label: '删除所选节点', icon: 'trash', shortcut: 'Delete', destructive: true, disabled: props.selectionBusy, run: () => props.onDeleteSelection?.() },
  ] : [
    ...(['IMAGE', 'VIDEO', 'text'] as const).map(kind => ({ label: kind === 'IMAGE' ? '新增图片' : kind === 'VIDEO' ? '新增视频' : '新增文本', icon: kind === 'IMAGE' ? 'image' as const : kind === 'VIDEO' ? 'video' as const : 'text' as const, run: () => contextMenu && props.onAddNode?.(kind, contextMenu.position) })),
    uploadAction,
    { label: '粘贴节点', icon: 'copy', shortcut: 'Ctrl/⌘ V', disabled: !props.clipboardReady, run: () => contextMenu && props.onPasteSelection?.(contextMenu.position) },
    { label: '适配全部节点', icon: 'expand', shortcut: '⇧ F', disabled: !displayedNodes.length, run: () => fit() },
  ];

  return (
    <div ref={stage} tabIndex={-1} className={`canvas-stage${panning ? ' canvas-stage-panning' : ''}`} role="region" aria-label={t("画布视口")}
      onContextMenu={event => {
        if (props.locked || !(event.target instanceof Element) || event.target.closest('input,textarea,select,[contenteditable="true"],.canvas-composer,button,.react-flow__handle,.react-flow__edge,.react-flow__minimap')) return;
        const node = event.target.closest<HTMLElement>('.canvas-node');
        if (!node && !event.target.closest('.react-flow__pane')) return;
        event.preventDefault(); event.stopPropagation(); setPendingConnection(null); suppressExistingComposers();
        const id = node?.dataset.nodeId;
        if (id && !props.selectedNodeIds.includes(id)) props.onSelectionChange([id]);
        setContextMenu({ x: event.clientX, y: event.clientY, position: instance.current?.screenToFlowPosition({ x: event.clientX, y: event.clientY }) ?? { x: 0, y: 0 }, node: Boolean(id), returnFocus: node ?? stage.current });
      }}
      onMouseDownCapture={event => {
        if (panning) return;
        const id = selectionTarget(event.target);
        if (!id || event.button !== 0) return;
        pointerSelection.current = { id, ids: props.selectedNodeIds };
        if (event.shiftKey) suppressExistingComposers();
        else setSelectionOnlyIds(new Set());
      }}
      onClickCapture={event => {
        if (panning) return;
        const id = selectionTarget(event.target);
        if (!id) return;
        if (!event.shiftKey) { setSelectionOnlyIds(new Set()); pointerSelection.current = null; return; }
        // Read the selection from before React Flow's drag-start selection changes.
        const previous = pointerSelection.current?.id === id ? pointerSelection.current.ids : props.selectedNodeIds;
        pointerSelection.current = null;
        event.preventDefault(); event.stopPropagation();
        suppressExistingComposers();
        props.onSelectionChange(previous.includes(id) ? previous.filter(nodeId => nodeId !== id) : [...previous, id]);
      }}>
      <Controls instance={instance} onFit={() => fit()} hasSelection={props.selectedNodeIds.length > 0} onFitSelection={() => fit(true)} />
      {props.document.nodes.length === 0 && <div className="canvas-empty-state">
        <span className="canvas-empty-mark"><CanvasIcon name="plus" /></span>
        <h2>{t("让灵感，在画布上发生")}</h2>
        <p>{t("从左侧添加图片、视频或文本，连接你的创意。")}</p>
        <span>{t("滚轮上下移动 · Ctrl + 滚轮缩放 · Shift 多选")}</span>
      </div>}
      <ReactFlow
        aria-label={t("创作画布交互区域")}
          colorMode="dark"
        minZoom={0.15}
        selectionOnDrag={!panning}
        nodesDraggable={!panning}
        elementsSelectable={!panning}
        panOnDrag={panning ? true : [1]}
        panActivationKeyCode={null}
        panOnScroll
        panOnScrollMode={PanOnScrollMode.Vertical}
        zoomOnScroll={false}
        zoomActivationKeyCode="Control"
        nodes={displayedNodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        defaultViewport={props.document.viewport}
        onInit={(value) => { instance.current = value; }}
        onConnect={onConnect}
        onConnectStart={() => { setPendingConnection(null); setContextMenu(null); }}
        onConnectEnd={(event, state) => {
          if (state.isValid || !state.fromNode || !state.fromHandle) return;
          const point = "changedTouches" in event ? event.changedTouches[0] : event;
          if (!point) return;
          const target = "changedTouches" in event ? document.elementFromPoint(point.clientX, point.clientY) : event.target;
          const targetId = state.toNode?.id ?? (target instanceof Element ? target.closest(".react-flow__node")?.getAttribute("data-id") : null);
          if (targetId) {
            if (state.fromHandle.type === "source") props.onConnect(state.fromNode.id, targetId);
            else props.onConnect(targetId, state.fromNode.id);
            return;
          }
          if (!props.onAddConnectedNode || !(target instanceof Element) || !target.closest(".react-flow__pane") || !instance.current) return;
          const screenPoint = { x: point.clientX, y: point.clientY };
          setPendingConnection({
            position: instance.current.screenToFlowPosition(screenPoint),
            left: Math.max(8, Math.min(point.clientX + 8, window.innerWidth - 248)),
            top: Math.max(8, Math.min(point.clientY + 8, window.innerHeight - 220)),
            originId: state.fromNode.id, handleType: state.fromHandle.type,
          });
        }}
        onNodeDoubleClick={(event, flowNode) => {
          const node = flowNode.data.node;
          if ((node.kind !== "image" && node.kind !== "video") || node.sourceTaskRef
            || !(event.target instanceof Element) || !event.target.matches(".canvas-media-preview img, .canvas-video-drag-area")) return;
          event.stopPropagation();
          void instance.current?.fitView({ nodes: [{ id: node.id }], padding: 0.15, duration: 250, maxZoom: 2 });
        }}
        onNodesChange={onNodesChange}
        onSelectionChange={onSelectionChange}
        onSelectionStart={suppressExistingComposers}
        onMoveEnd={onViewportChange}
        multiSelectionKeyCode="Shift"
        nodesFocusable={false}
        deleteKeyCode={null}
      >
        <ViewportPortal>
          {(props.document.groups ?? []).map(group => {
            const bounds = groupBounds(group, displayedNodes);
            if (!bounds) return null;
            const members = displayedNodes.filter(node => group.nodeIds.includes(node.id));
            const clearPreview = () => setDragPositions(current => Object.fromEntries(Object.entries(current).filter(([id]) => !group.nodeIds.includes(id))));
            return <CanvasGroupFrame key={group.id} group={group} bounds={bounds}
              positions={Object.fromEntries(members.map(node => [node.id, node.position]))}
              selected={group.nodeIds.every(id => props.selectedNodeIds.includes(id))}
              toCanvasPoint={point => instance.current?.screenToFlowPosition(point) ?? point}
              onSelect={() => { suppressExistingComposers(); props.onSelectionChange(group.nodeIds); }}
              onPreview={positions => setDragPositions(current => ({ ...current, ...positions }))}
              onFinish={positions => { props.onMoveNodes?.(positions); clearPreview(); }} onCancel={clearPreview}
              onRename={name => props.onRenameGroup?.(group.id, name)} onUngroup={() => props.onUngroup?.(group.id)} />;
          })}
        </ViewportPortal>
        <Background gap={24} size={1} color="#505252" />
        <MiniMap ariaLabel={t("画布小地图")} />
      </ReactFlow>
      {contextMenu && <CanvasContextMenu x={contextMenu.x} y={contextMenu.y} actions={menuActions} returnFocus={contextMenu.returnFocus} onClose={() => setContextMenu(null)} />}
      {pendingConnection && createPortal(
        <div ref={menuRef} className="canvas-add-node-menu" role="menu" aria-label={t("添加节点")}
          style={{ left: pendingConnection.left, top: pendingConnection.top }}
          onKeyDown={event => {
            if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
            event.preventDefault();
            const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>("button")];
            const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
            buttons[event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1
              : (index + (event.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length]?.focus();
          }}>
          <span className="canvas-add-node-heading">{t("添加节点")}</span>
          {([ ["IMAGE", "图片", "image"], ["VIDEO", "视频", "video"], ["text", "文本", "text"] ] as const).filter(([kind]) => {
            const origin = props.document.nodes.find(node => node.id === pendingConnection.originId);
            if (!origin) return false;
            if (pendingConnection.handleType === 'source' && kind === 'text') return false;
            if (pendingConnection.handleType === 'target' && origin.kind !== 'generation') return false;
            const originKind = origin.kind === "generation" ? origin.settings.type : origin.kind;
            return pendingConnection.handleType === "source"
              ? canConnectNodeKinds(connectionSourceKind(props.document, origin.id), kind) : canConnectNodeKinds(kind, originKind);
          }).map(([kind, label, icon]) => (
            <button type="button" role="menuitem" key={kind} onClick={() => {
              props.onAddConnectedNode?.(kind, pendingConnection.position, pendingConnection.originId, pendingConnection.handleType);
              setPendingConnection(null);
            }}><span><CanvasIcon name={icon} /></span>{t(label)}</button>
          ))}
        </div>, document.body,
      )}
    </div>
  );
}
