import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import type { ComponentType, ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  addGenerationNode,
  addTextNode,
  connectNodes,
  createCanvasDocument,
  updateGenerationTask,
  updateGenerationSettings,
  removeNodes,
} from "./document";
import { copyGenerationDefaults } from "./generation-defaults";
import { CanvasSurface } from "./CanvasSurface";
import { ThemeProvider, ThemeToggle } from '../theme';
import { reconcileMediaTask } from "./task-reconciler";
import { canonicalizeCanvasDocument } from "./persistence";
import { buildMediaTaskRequest } from "./media-task-port";
import { selectOutput } from './workflow';

const controlledFlow = vi.hoisted(() => ({
  props: null as Record<string, unknown> | null,
  controlsProps: null as Record<string, unknown> | null,
  fitView: vi.fn(),
  fitBounds: vi.fn(),
  zoomIn: vi.fn(),
  zoomOut: vi.fn(),
}));

vi.mock("@xyflow/react", () => ({
  ReactFlow: (props: Record<string, unknown> & { children?: ReactNode }) => {
    controlledFlow.props = props;
    (props.onInit as ((instance: unknown) => void) | undefined)?.({
      fitView: controlledFlow.fitView,
      fitBounds: controlledFlow.fitBounds,
      getNodesBounds: () => ({ x: 0, y: 0, width: 300, height: 200 }),
      zoomIn: controlledFlow.zoomIn,
      zoomOut: controlledFlow.zoomOut,
      screenToFlowPosition: ({ x, y }: { x: number; y: number }) => ({ x: x - 100, y: y - 50 }),
    });
    const nodeTypes = props.nodeTypes as Record<string, ComponentType<{ data: unknown }>>;
    const nodes = props.nodes as Array<{ id: string; type: string; data: unknown }>;
    return (
      <div className="react-flow__pane" aria-label="受控画布适配器">
        {nodes.map((node) => {
          const NodeView = nodeTypes[node.type];
          return (
            <div key={node.id} data-testid={`flow-node-${node.id}`}>
              <NodeView data={node.data} />
            </div>
          );
        })}
        {props.children}
      </div>
    );
  },
  ViewportPortal: ({ children }: { children: ReactNode }) => <>{children}</>, Background: () => null,
  BaseEdge: ({ path }: { path: string }) => <path d={path} />,
  getBezierPath: () => ["M 0 0 L 100 100", 50, 50],
  MiniMap: ({ ariaLabel }: { ariaLabel?: string }) => (
    <div className="react-flow__minimap" aria-label={ariaLabel} />
  ),
  Controls: (props: Record<string, unknown>) => {
    controlledFlow.controlsProps = props;
    return <div data-testid="react-flow-controls" />;
  },
  Handle: ({ id, type }: { id: string; type: string }) => (
    <span data-testid={`handle-${id}`} data-handle-type={type} />
  ),
  Position: { Left: "left", Right: "right" },
  PanOnScrollMode: { Vertical: "vertical" },
}));

afterEach(() => {
  cleanup();
  controlledFlow.fitView.mockClear();
  controlledFlow.fitBounds.mockClear();
  controlledFlow.zoomIn.mockClear();
  controlledFlow.zoomOut.mockClear();
  controlledFlow.controlsProps = null;
});

const generationProps = {
  generationOptions: [],
  generationOptionsStatus: "ready" as const,
  nodeQuotes: { "generation-1": { state: "ready" as const, points: "12.3400" } },
  onGenerationSettingsChange: vi.fn(),
};

const renderSurface = (
  document: ReturnType<typeof createCanvasDocument>,
  overrides: Partial<Parameters<typeof CanvasSurface>[0]> = {},
) => render(
  <CanvasSurface
    {...generationProps}
    document={document}
    selectedNodeIds={[]}
    canSubmit={false}
    onTextChange={vi.fn()}
    onConnect={vi.fn()}
    onMoveNode={vi.fn()}
    onSelectionChange={vi.fn()}
    onViewportChange={vi.fn()}
    onSubmitGeneration={vi.fn()}
    onReadGeneration={vi.fn()}
    {...overrides}
  />,
);

describe("CanvasSurface", () => {
  it.each(['pane', 'node'])('uploads from the %s context menu at the transformed canvas position', target => {
    const document = addTextNode(createCanvasDocument('upload-menu', 'now'), 'text-1', { x: 0, y: 0 }, 'now');
    const onUploadAt = vi.fn();
    renderSurface(document, { onUploadAt });
    fireEvent.contextMenu(target === 'pane' ? screen.getByLabelText('受控画布适配器') : screen.getByRole('article'), { clientX: 480, clientY: 290 });
    fireEvent.click(screen.getByRole('menuitem', { name: '上传图片或视频' }));
    expect(onUploadAt).toHaveBeenCalledExactlyOnceWith({ x: 380, y: 240 });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
  it('disables the context upload while the catalog loads', () => {
    const onUploadAt = vi.fn();
    renderSurface(createCanvasDocument('loading-upload', 'now'), { onUploadAt, generationOptionsStatus: 'loading' });
    fireEvent.contextMenu(screen.getByLabelText('受控画布适配器'), { clientX: 480, clientY: 290 });
    expect(screen.getByRole('menuitem', { name: '上传图片或视频' })).toBeDisabled();
    expect(onUploadAt).not.toHaveBeenCalled();
  });
  it('keeps the approved dark editor palette while the site theme changes', () => {
    localStorage.removeItem('yomio-theme');
    render(<ThemeProvider><ThemeToggle /><CanvasSurface {...generationProps}
      document={createCanvasDocument('theme-canvas', 't0')} selectedNodeIds={[]} canSubmit={false}
      onTextChange={vi.fn()} onConnect={vi.fn()} onMoveNode={vi.fn()} onSelectionChange={vi.fn()}
      onViewportChange={vi.fn()} onSubmitGeneration={vi.fn()} onReadGeneration={vi.fn()} /></ThemeProvider>);
    expect(controlledFlow.props).toMatchObject({ colorMode: 'dark' });
    fireEvent.click(screen.getByRole('button', { name: '切换至深色主题' }));
    expect(controlledFlow.props).toMatchObject({ colorMode: 'dark' });
    fireEvent.click(screen.getByRole('button', { name: '切换至浅色主题' }));
    expect(controlledFlow.props).toMatchObject({ colorMode: 'dark' });
    localStorage.removeItem('yomio-theme');
  });
  it("渲染项目节点并暴露视口控制和小地图", () => {
    let document = addTextNode(
      createCanvasDocument("project-1", "t0"),
      "text-1",
      { x: 0, y: 0 },
      "t0",
    );
    document = addGenerationNode(document, "generation-1", { x: 300, y: 0 }, "t0");
    document = updateGenerationTask(
      document,
      "generation-1",
      { taskRef: "task-1", taskState: "succeeded", message: null },
      "t0",
    );
    document = {
      ...document,
      nodes: [...document.nodes, {
        id: "image-1",
        kind: "image",
        version: 1,
        position: { x: 600, y: 0 },
        asset: {
          assetRef: "asset-1",
          kind: "image",
          alt: "结果",
          previewRef: "https://cdn.example.test/result.png",
        },
        sourceTaskRef: "task-1",
      }],
    };
    document = connectNodes(document, "text-1", "generation-1", "t0");
    document = {
      ...document,
      edges: [...document.edges, {
        id: "generation-1->image-1",
        source: "generation-1",
        target: "image-1",
      }],
    };

    const view = render(
      <CanvasSurface
        {...generationProps}
        document={document}
        selectedNodeIds={[]}
        canSubmit={false}
        onTextChange={vi.fn()}
        onConnect={vi.fn()}
        onMoveNode={vi.fn()}
        onSelectionChange={vi.fn()}
        onViewportChange={vi.fn()}
        onSubmitGeneration={vi.fn()}
        onReadGeneration={vi.fn()}
      />,
    );
    expect(screen.getByRole("region", { name: "画布视口" })).toBeVisible();
    expect(screen.getByRole("article", { name: "文本节点 text-1", description: "未选择" })).toBeVisible();
    expect(screen.getByRole("article", { name: "生成节点 generation-1", description: "未选择" })).toBeVisible();
    expect(screen.queryByTestId("flow-node-image-1")).not.toBeInTheDocument();
    expect(within(screen.getByTestId("node-generation-1")).getByRole("img", { name: "结果" })).toBeVisible();
    expect(screen.getByLabelText("提示词")).toBeVisible();
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    expect(view.container.querySelector(".react-flow__minimap")).not.toBeNull();
    expect(screen.getByLabelText("画布小地图")).toBeVisible();
    expect(within(screen.getByTestId("flow-node-text-1")).getAllByTestId("handle-output"))
      .toHaveLength(1);
    expect(within(screen.getByTestId("flow-node-generation-1")).getByTestId("handle-input"))
      .toHaveAttribute("data-handle-type", "target");
    expect(within(screen.getByTestId("flow-node-generation-1")).getByTestId("handle-output"))
      .toHaveAttribute("data-handle-type", "source");
    expect((controlledFlow.props as { edges: unknown[] }).edges).toEqual([
      expect.objectContaining({ source: "text-1", target: "generation-1" }),
    ]);
    expect(controlledFlow.props).toMatchObject({
      colorMode: "dark",
      minZoom: 0.15,
      panOnScroll: true,
      panOnScrollMode: "vertical",
      zoomOnScroll: false,
      zoomActivationKeyCode: "Control",
      nodesFocusable: false,
      defaultViewport: document.viewport,
    });
    expect(controlledFlow.props).not.toHaveProperty("fitView");
    expect(controlledFlow.controlsProps).toBeNull();
  });

  it.each(["image", "video"] as const)("上传 %s 隐藏操作按钮，双击画面只聚焦该节点", kind => {
    const document = createCanvasDocument("project-1", "t0");
    document.nodes.push({ id: "media", version: 1, kind, sourceTaskRef: null, position: { x: 42, y: 80 },
      asset: { kind, assetRef: "file", alt: "上传参考", previewRef: "https://cdn.example.test/media" } });
    renderSurface(document);
    const article = screen.getByTestId("node-media");
    expect(within(article).queryByText("上传参考")).not.toBeInTheDocument();
    expect(kind === "image" ? within(article).getByRole("img", { name: "上传参考" }) : within(article).getByLabelText("上传参考")).toBeInTheDocument();
    expect(within(article).queryByRole("button", { name: "放大预览" })).not.toBeInTheDocument();
    expect(within(article).queryByRole("button", { name: /连接/ })).not.toBeInTheDocument();
    expect(screen.getAllByTestId("handle-output")).toHaveLength(1);
    const flow = controlledFlow.props as unknown as {
      nodes: unknown[]; onNodeDoubleClick(event: unknown, node: unknown): void;
    };
    const stopPropagation = vi.fn();
    const picture = kind === "image" ? within(article).getByRole("img")
      : article.querySelector(".canvas-video-drag-area")!;
    act(() => flow.onNodeDoubleClick({ target: picture, stopPropagation }, flow.nodes[0]));
    expect(controlledFlow.fitView).toHaveBeenCalledExactlyOnceWith({ nodes: [{ id: "media" }], padding: 0.15, duration: 250, maxZoom: 2 });
    expect(stopPropagation).toHaveBeenCalledOnce();
    controlledFlow.fitView.mockClear();
    act(() => flow.onNodeDoubleClick({ target: article, stopPropagation }, flow.nodes[0]));
    if (kind === "video") {
      const player = within(article).getByLabelText("上传参考");
      expect(player).toHaveAttribute("controls");
      act(() => flow.onNodeDoubleClick({ target: player, stopPropagation }, flow.nodes[0]));
    }
    expect(controlledFlow.fitView).not.toHaveBeenCalled();
  });

  it.each(["image", "video"] as const)("%s 结果在原生成区域展示，恢复后保留多结果和下游引用", (kind) => {
    let document = addGenerationNode(createCanvasDocument("project-1", "t0"), "g", { x: 42, y: 80 }, "t0");
    document = updateGenerationSettings(document, "g", copyGenerationDefaults(kind === "video" ? "VIDEO" : "IMAGE"), "t0");
    document = updateGenerationTask(document, "g", { taskRef: "task", taskState: "working", message: null }, "t1");
    document = reconcileMediaTask(document, "g", { taskRef: "task", state: "partial_success", outputs: [0, 1].map(index => ({
      kind, assetRef: `file-${index}`, alt: `结果 ${index}`, previewRef: `https://cdn.example.test/${index}`,
    })) }, "t2");
    document = addGenerationNode(document, "next", { x: 500, y: 80 }, "t2");
    document = updateGenerationSettings(document, "next", copyGenerationDefaults(kind === "video" ? "VIDEO" : "IMAGE"), "t2");
    document = connectNodes(document, "task-output-0", "next", "t2");
    document = canonicalizeCanvasDocument(JSON.parse(JSON.stringify(document)), "project-1")!;
    renderSurface(document, { selectedNodeIds: ["g"] });
    expect(screen.getAllByRole("article")).toHaveLength(2);
    const original = screen.getByTestId("node-g");
    for (const index of [0, 1]) {
      const media = kind === "image" ? within(original).getByRole("img", { name: `结果 ${index}` })
        : within(original).getByLabelText(`结果 ${index}`);
      expect(media).toHaveAttribute("src", `https://cdn.example.test/${index}`);
      if (kind === "video") expect(media).toHaveAttribute("controls");
    }
    expect(within(original).getAllByRole("button", { name: "放大预览" })).toHaveLength(2);
    expect((controlledFlow.props as { edges: unknown[] }).edges).toEqual([
      expect.objectContaining({ source: "g", target: "next", animated: true }),
    ]);
    expect(buildMediaTaskRequest(document, "next", "r").inputAssetRefs).toEqual(["file-0"]);
    // A second explicit result creates another pinned reference, without retargeting the first.
    document = connectNodes(selectOutput(document, 'g', 'task-output-1'), "g", "next", "t3");
    expect(document.edges).toContainEqual(expect.objectContaining({ source: "g", target: "next", outputId: 'task-output-1' }));
    expect([...buildMediaTaskRequest(document, "next", "r").inputAssetRefs].sort()).toEqual(["file-0", "file-1"]);
    const removed = removeNodes(document, ["g"], "t4");
    expect(removed.nodes.map(node => node.id)).toEqual(["next"]);
    expect(canonicalizeCanvasDocument(removed, "project-1")).not.toBeNull();
  });

  it("文本输入只回传文本编辑", () => {
    const onTextChange = vi.fn();
    const document = addTextNode(
      createCanvasDocument("project-1", "t0"),
      "text-1",
      { x: 0, y: 0 },
      "t0",
    );
    renderSurface(document, { onTextChange });

    fireEvent.change(screen.getByLabelText("提示词"), { target: { value: "雨夜黑猫" } });
    expect(onTextChange).toHaveBeenCalledWith("text-1", "雨夜黑猫");
  });

  it("子控件键盘事件不会冒泡成节点选择", () => {
    const onSelectionChange = vi.fn();
    let document = addTextNode(
      createCanvasDocument("project-1", "t0"),
      "text-1",
      { x: 0, y: 0 },
      "t0",
    );
    document = addGenerationNode(document, "generation-1", { x: 300, y: 0 }, "t0");
    renderSurface(document, { canSubmit: true, onSelectionChange });
    fireEvent.click(screen.getByRole("button", { name: "图生图片" }));

    fireEvent.keyDown(screen.getByLabelText("提示词"), { key: "Enter" });
    fireEvent.keyDown(screen.getByRole("button", { name: "提交生成任务" }), { key: "Enter" });
    fireEvent.keyDown(
      screen.getByRole("button", { name: "把文本节点设为连接起点" }),
      { key: "Enter" },
    );

    expect(onSelectionChange).not.toHaveBeenCalled();
  });

  it("把 React Flow 节点变化中的拖动和追加选择写回项目回调", () => {
    const onConnect = vi.fn();
    const onMoveNode = vi.fn();
    const onSelectionChange = vi.fn();
    const onViewportChange = vi.fn();
    let document = addTextNode(
      createCanvasDocument("project-1", "t0"),
      "text-1",
      { x: 0, y: 0 },
      "t0",
    );
    document = addGenerationNode(document, "generation-1", { x: 300, y: 0 }, "t0");
    renderSurface(document, {
      selectedNodeIds: ["text-1"],
      onConnect,
      onMoveNode,
      onSelectionChange,
      onViewportChange,
    });

    const props = controlledFlow.props as {
      onConnect(value: { source: string; target: string }): void;
      onNodesChange(changes: Array<
        | { id: string; type: "position"; position: { x: number; y: number }; dragging: boolean }
        | { id: string; type: "select"; selected: boolean }
      >): void;
      onNodeDragStop(event: null, node: { id: string; position: { x: number; y: number } }): void;
      onSelectionChange(value: { nodes: Array<{ id: string }> }): void;
      onMoveEnd(event: null, viewport: { x: number; y: number; zoom: number }): void;
    };
    props.onConnect({ source: "text-1", target: "generation-1" });
    act(() => props.onNodesChange([
      { id: "text-1", type: "position", position: { x: 40, y: 80 }, dragging: true },
      { id: "generation-1", type: "select", selected: true },
    ]));
    act(() => props.onNodesChange([{ id: "text-1", type: "position", position: { x: 40, y: 80 }, dragging: false }]));
    props.onSelectionChange({ nodes: [{ id: "text-1" }, { id: "generation-1" }] });
    props.onMoveEnd(null, { x: 12, y: -8, zoom: 1.25 });

    expect(onConnect).toHaveBeenCalledWith("text-1", "generation-1");
    expect(onMoveNode).toHaveBeenCalledWith("text-1", { x: 40, y: 80 });
    expect(onSelectionChange).toHaveBeenCalledWith(["text-1", "generation-1"]);
    expect(onViewportChange).toHaveBeenCalledWith({ x: 12, y: -8, zoom: 1.25 });
  });

  it("键盘切换选择、多选，圆点连线回调与视口动作每次只调用一次", () => {
    const onConnect = vi.fn();
    const onSelectionChange = vi.fn();
    let document = addTextNode(
      createCanvasDocument("project-1", "t0"),
      "text-1",
      { x: 0, y: 0 },
      "t0",
    );
    document = addGenerationNode(document, "generation-1", { x: 300, y: 0 }, "t0");
    const surface = (selectedNodeIds: readonly string[]) => (
      <CanvasSurface
        {...generationProps}
        document={document}
        selectedNodeIds={selectedNodeIds}
        canSubmit={false}
        onTextChange={vi.fn()}
        onConnect={onConnect}
        onMoveNode={vi.fn()}
        onSelectionChange={onSelectionChange}
        onViewportChange={vi.fn()}
        onSubmitGeneration={vi.fn()}
        onReadGeneration={vi.fn()}
      />
    );
    const view = render(surface([]));

    expect(screen.getByRole("article", { name: "文本节点 text-1", description: "未选择" })).toBeVisible();
    fireEvent.keyDown(screen.getByTestId("node-text-1"), { key: "Enter" });
    expect(onSelectionChange).toHaveBeenLastCalledWith(["text-1"]);
    view.rerender(surface(["text-1"]));
    expect(screen.getByRole("article", { name: "文本节点 text-1", description: "已选择" })).toBeVisible();
    fireEvent.keyDown(screen.getByTestId("node-text-1"), { key: "Enter" });
    expect(onSelectionChange).toHaveBeenLastCalledWith([]);
    fireEvent.keyDown(screen.getByTestId("node-generation-1"), { key: "Enter", shiftKey: true });
    expect(onSelectionChange).toHaveBeenLastCalledWith(["text-1", "generation-1"]);
    view.rerender(surface(["text-1", "generation-1"]));
    expect(screen.getByRole("article", { name: "生成节点 generation-1", description: "已选择" })).toBeVisible();
    fireEvent.keyDown(screen.getByTestId("node-text-1"), { key: "Enter", shiftKey: true });
    expect(onSelectionChange).toHaveBeenLastCalledWith(["generation-1"]);

    act(() => (controlledFlow.props?.onConnect as (connection: { source: string; target: string }) => void)({ source: "text-1", target: "generation-1" }));
    expect(onConnect).toHaveBeenCalledWith("text-1", "generation-1");

    const fitView = screen.getByRole("button", { name: "适配视图" });
    const zoomIn = screen.getByRole("button", { name: "放大画布" });
    const zoomOut = screen.getByRole("button", { name: "缩小画布" });
    expect(fitView.tagName).toBe("BUTTON");
    expect(zoomIn.tagName).toBe("BUTTON");
    expect(zoomOut.tagName).toBe("BUTTON");
    fireEvent.click(fitView);
    fireEvent.click(zoomIn);
    fireEvent.click(zoomOut);
    expect(onConnect).toHaveBeenCalledTimes(1);
    expect(controlledFlow.fitBounds).toHaveBeenCalledExactlyOnceWith({ x: 0, y: 0, width: 300, height: 200 }, { padding: .25, duration: 200 });
    expect(controlledFlow.zoomIn).toHaveBeenCalledTimes(1);
    expect(controlledFlow.zoomOut).toHaveBeenCalledTimes(1);
  });

  it("只在端口可用且节点未提交时开放生成动作", () => {
    const onSubmitGeneration = vi.fn();
    let document = addGenerationNode(
      createCanvasDocument("project-1", "t0"),
      "generation-1",
      { x: 0, y: 0 },
      "t0",
    );
    const view = renderSurface(document, { canSubmit: true, onSubmitGeneration });
    fireEvent.click(screen.getByRole("button", { name: "图生图片" }));

    const submit = screen.getByRole("button", { name: "提交生成任务" });
    expect(submit).toBeEnabled();
    fireEvent.click(submit);
    expect(onSubmitGeneration).toHaveBeenCalledTimes(1);

    document = updateGenerationTask(
      document,
      "generation-1",
      { taskRef: null, taskState: "submitting", message: null },
      "t1",
    );
    view.rerender(
      <CanvasSurface
        {...generationProps}
        document={document}
        selectedNodeIds={[]}
        canSubmit
        onTextChange={vi.fn()}
        onConnect={vi.fn()}
        onMoveNode={vi.fn()}
        onSelectionChange={vi.fn()}
        onViewportChange={vi.fn()}
        onSubmitGeneration={onSubmitGeneration}
        onReadGeneration={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
  });

  it.each([
    { taskState: "accepted" as const, taskRef: "task-1" },
    { taskState: "working" as const, taskRef: "task-1" },
    { taskState: "refund_pending" as const, taskRef: "task-1" },
  ])("$taskState 或已绑定任务引用时不开放二次提交", ({ taskState, taskRef }) => {
    let document = addGenerationNode(
      createCanvasDocument("project-1", "t0"),
      "generation-1",
      { x: 0, y: 0 },
      "t0",
    );
    document = updateGenerationTask(
      document,
      "generation-1",
      { taskRef, taskState, message: null },
      "t1",
    );
    renderSurface(document, { canSubmit: true });

    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
  });

  it("未绑定任务引用的失败节点允许重新提交", () => {
    let document = addGenerationNode(
      createCanvasDocument("project-1", "t0"),
      "generation-1",
      { x: 0, y: 0 },
      "t0",
    );
    document = updateGenerationTask(
      document,
      "generation-1",
      { taskRef: null, taskState: "failed", message: "retry" },
      "t1",
    );
    renderSurface(document, { canSubmit: true });

    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeEnabled();
  });

  it("无关重渲染保持 React Flow 节点适配对象稳定", () => {
    const document = addTextNode(
      createCanvasDocument("project-1", "t0"),
      "text-1",
      { x: 0, y: 0 },
      "t0",
    );
    const callbacks = {
      onTextChange: vi.fn(),
      onConnect: vi.fn(),
      onMoveNode: vi.fn(),
      onSelectionChange: vi.fn(),
      onViewportChange: vi.fn(),
      onSubmitGeneration: vi.fn(),
      onReadGeneration: vi.fn(),
    };
    const selectedNodeIds: readonly string[] = [];
    const surface = (label: string) => (
      <div aria-label={label}>
        <CanvasSurface
          {...generationProps}
          document={document}
          selectedNodeIds={selectedNodeIds}
          canSubmit={false}
          {...callbacks}
        />
      </div>
    );
    const view = render(surface("第一次"));
    const firstNodes = (controlledFlow.props as { nodes: unknown[] }).nodes;

    view.rerender(surface("第二次"));

    expect((controlledFlow.props as { nodes: unknown[] }).nodes).toBe(firstNodes);
  });

  it("渲染层拒绝绕过上游的不安全图片预览", () => {
    const document = {
      ...createCanvasDocument("project-1", "t0"),
      nodes: [{
        id: "image-1",
        kind: "image" as const,
        version: 1 as const,
        position: { x: 0, y: 0 },
        sourceTaskRef: "task-1",
        asset: {
          assetRef: "asset-1",
          kind: "image" as const,
          alt: "结果",
          previewRef: "/untrusted-preview/1",
        },
      }],
    };
    renderSurface(document);

    expect(screen.getByText("预览不可用")).toBeVisible();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });
});

it('renders an accessible delete control for the visible reference edge without deleting its nodes', () => {
  let document = addTextNode(createCanvasDocument('edge-controls', 'now'), 'text', { x: 0, y: 0 }, 'now');
  document = addGenerationNode(document, 'target', { x: 300, y: 0 }, 'now');
  document = connectNodes(document, 'text', 'target', 'now');
  const onDisconnect = vi.fn();
  renderSurface(document, { onDisconnect });
  const props = controlledFlow.props as any;
  const edge = props.edges[0];
  const EdgeView = props.edgeTypes[edge.type];
  render(<svg><EdgeView {...edge} /></svg>);
  const button = screen.getByRole('button', { name: '删除连线' });
  fireEvent.click(button);
  expect(onDisconnect).toHaveBeenCalledWith(['text->target']);
  expect(document.nodes).toHaveLength(2);
});

it('keeps drag positions local without rebuilding other nodes and commits both final positions on release', () => {
  let document = addTextNode(createCanvasDocument('drag', 'now'), 'a', { x: 0, y: 0 }, 'now');
  document = addTextNode(document, 'b', { x: 200, y: 0 }, 'now');
  document = addTextNode(document, 'c', { x: 400, y: 0 }, 'now');
  const onMoveNode = vi.fn();
  renderSurface(document, { onMoveNode });
  const before = controlledFlow.props as any;
  const stationary = before.nodes[2];
  const movingData = before.nodes[0].data;
  act(() => before.onNodesChange([
    { id: 'a', type: 'position', position: { x: 20, y: 40 }, dragging: true },
    { id: 'b', type: 'position', position: { x: 220, y: 40 }, dragging: true },
  ]));
  expect(onMoveNode).not.toHaveBeenCalled();
  const during = controlledFlow.props as any;
  expect(during.nodes[0]).toMatchObject({ position: { x: 20, y: 40 }, dragging: true });
  expect(during.nodes[0].data).toBe(movingData);
  expect(during.nodes[2]).toBe(stationary);
  expect(during.edges).toBe(before.edges);
  act(() => during.onNodesChange([
    { id: 'a', type: 'position', position: { x: 30, y: 50 }, dragging: false },
    { id: 'b', type: 'position', position: { x: 230, y: 50 }, dragging: false },
  ]));
  expect(onMoveNode.mock.calls).toEqual([['a', { x: 30, y: 50 }], ['b', { x: 230, y: 50 }]]);
});

it('allows dragging reference images and video pictures while retaining native playback and action controls', () => {
  const document = createCanvasDocument('media-drag', 'now');
  document.nodes = (['image', 'video'] as const).map(kind => ({ id: kind, kind, version: 1, position: { x: 0, y: 0 }, sourceTaskRef: null,
    asset: { assetRef: kind, kind, alt: kind, previewRef: `https://cdn.example.test/${kind}` } }));
  const view = renderSurface(document);
  const img = screen.getByRole('img', { name: 'image' });
  expect(img.closest('.nodrag')).toBeNull();
  expect(img).toHaveAttribute('draggable', 'false');
  const dragArea = screen.getByTitle('拖动视频节点');
  expect(dragArea.closest('.nodrag')).toBeNull();
  expect(screen.getByLabelText('video')).toHaveAttribute('controls');
  expect(screen.getByLabelText('video')).toHaveClass('nodrag');
  expect(screen.queryByRole('link', { name: '打开视频并下载' })).not.toBeInTheDocument();
  // Position-only changes preserve the existing media element and its playback state.
  const video = screen.getByLabelText('video');
  act(() => (controlledFlow.props as any).onNodesChange([{ id: 'video', type: 'position', position: { x: 30, y: 20 }, dragging: true }]));
  expect(screen.getByLabelText('video')).toBe(video);
  view.unmount();
});

it('preserves measured dimensions throughout dragging and ignores identical measurement notifications', () => {
  const document = addTextNode(createCanvasDocument('measured', 'now'), 'a', { x: 0, y: 0 }, 'now');
  const onMoveNode = vi.fn();
  renderSurface(document, { onMoveNode });
  act(() => (controlledFlow.props as any).onNodesChange([{ id: 'a', type: 'dimensions', dimensions: { width: 300, height: 180 } }]));
  const measured = (controlledFlow.props as any).nodes[0];
  expect(measured.measured).toEqual({ width: 300, height: 180 });
  act(() => (controlledFlow.props as any).onNodesChange([{ id: 'a', type: 'dimensions', dimensions: { width: 300, height: 180 } }]));
  expect((controlledFlow.props as any).nodes[0]).toBe(measured);
  act(() => (controlledFlow.props as any).onNodesChange([{ id: 'a', type: 'position', position: { x: 90, y: 80 }, dragging: true }]));
  expect((controlledFlow.props as any).nodes[0]).toMatchObject({ measured: { width: 300, height: 180 }, position: { x: 90, y: 80 } });
  expect(onMoveNode).not.toHaveBeenCalled();
  act(() => (controlledFlow.props as any).onNodesChange([{ id: 'a', type: 'dimensions', dimensions: { width: 300, height: 240 } }]));
  expect((controlledFlow.props as any).nodes[0].measured).toEqual({ width: 300, height: 240 });
});

it('只让所选图片、视频或生成节点直接关联的连线流动，切换和取消选择即时更新', () => {
  let document = addTextNode(createCanvasDocument('edge-flow', 'now'), 'text', { x: 0, y: 0 }, 'now');
  document = addGenerationNode(document, 'g1', { x: 300, y: 0 }, 'now');
  document = addGenerationNode(document, 'g2', { x: 600, y: 0 }, 'now');
  for (const kind of ['image', 'video'] as const) document.nodes.push({ id: kind, kind, version: 1, position: { x: 0, y: 200 }, sourceTaskRef: null,
    asset: { kind, assetRef: kind, alt: kind, previewRef: `https://cdn.example.test/${kind}` } });
  document.edges = [{ id: 'a', source: 'image', target: 'g1' }, { id: 'b', source: 'video', target: 'g2' }, { id: 'c', source: 'g1', target: 'g2' }, { id: 'd', source: 'text', target: 'g1' }];
  const props = { ...generationProps, document, canSubmit: false, onTextChange: vi.fn(), onConnect: vi.fn(), onMoveNode: vi.fn(), onSelectionChange: vi.fn(), onViewportChange: vi.fn(), onSubmitGeneration: vi.fn(), onReadGeneration: vi.fn() };
  const view = render(<CanvasSurface {...props} selectedNodeIds={[]} />);
  for (const [selected, flowing] of [[[], []], [['image'], ['a']], [['video'], ['b']], [['g1'], ['a', 'c', 'd']], [['image', 'video'], ['a', 'b']], [['text'], []], [[], []]] as [string[], string[]][]) {
    view.rerender(<CanvasSurface {...props} selectedNodeIds={selected} />);
    expect((controlledFlow.props?.edges as { id: string; animated: boolean }[]).filter(edge => edge.animated).map(edge => edge.id)).toEqual(flowing);
  }
});

it('选中连线以独立遮罩中的白光沿路径移动，水平线也可见，取消选择移除高光', () => {
  renderSurface(createCanvasDocument('flow-glow', 'now'));
  const EdgeView = (controlledFlow.props as any).edgeTypes.reference;
  const props = { id: 'glow-edge', animated: true, sourceX: 0, sourceY: 50, targetX: 200, targetY: 50 };
  const view = render(<svg><EdgeView {...props} /><EdgeView {...props} id="second" /></svg>);
  const motions = view.container.querySelectorAll('animateMotion');
  expect(motions).toHaveLength(2);
  expect(motions[0]).toHaveAttribute('path', 'M 0 0 L 100 100');
  expect(motions[0]).toHaveAttribute('dur', '1s');
  const masks = view.container.querySelectorAll('mask');
  expect(masks[0].id).not.toBe(masks[1].id);
  expect(Number(masks[0].getAttribute('height'))).toBeGreaterThan(0);
  expect(view.container.querySelector('.canvas-edge-flow > path')).toHaveAttribute('mask', `url(#${masks[0].id})`);
  view.rerender(<svg><EdgeView {...props} animated={false} /></svg>);
  expect(view.container.querySelector('animateMotion')).toBeNull();
});
