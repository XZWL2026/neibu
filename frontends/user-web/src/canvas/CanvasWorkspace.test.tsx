import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ComponentType, ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as userSession from "../userSession";
import { CanvasWorkspace } from "./CanvasWorkspace";
import { copyGenerationDefaults } from "./generation-defaults";
import {
  addGenerationNode,
  addTextNode,
  connectNodes,
  createCanvasDocument,
  updateGenerationTask,
  updateGenerationPrompt,
  updateGenerationSettings,
  updateTextNode,
  updateViewport,
} from "./document";
import type { CanvasGenerationOption, MediaTaskPort } from "./media-task-port";
import { loadCanvasDraft, saveCanvasDraft } from "./persistence";

const controlledFlow = vi.hoisted(() => ({
  props: null as Record<string, unknown> | null,
}));

vi.mock("@xyflow/react", () => ({
  ReactFlow: (props: Record<string, unknown> & { children?: ReactNode }) => {
    controlledFlow.props = props;
    (props.onInit as ((instance: unknown) => void) | undefined)?.({
      fitView: vi.fn(),
      zoomIn: vi.fn(),
      zoomOut: vi.fn(),
      screenToFlowPosition: ({ x, y }: { x: number; y: number }) => {
        const viewport = props.defaultViewport as { x: number; y: number; zoom: number };
        return { x: (x - viewport.x) / viewport.zoom, y: (y - viewport.y) / viewport.zoom };
      },
    });
    const nodeTypes = props.nodeTypes as Record<string, ComponentType<{ data: unknown }>>;
    const nodes = props.nodes as Array<{ id: string; type: string; data: unknown }>;
    return (
      <div className="react-flow__pane" aria-label="受控画布适配器">
        {nodes.map((node) => {
          const NodeView = nodeTypes[node.type];
          return (
            <div className="react-flow__node" data-id={node.id} key={node.id} data-testid={`flow-node-${node.id}`}>
              <NodeView data={node.data} />
            </div>
          );
        })}
        {props.children}
      </div>
    );
  },
  ViewportPortal: ({ children }: { children: ReactNode }) => <>{children}</>, Background: () => null,
  MiniMap: () => null,
  Controls: () => null,
  Handle: () => null,
  PanOnScrollMode: { Vertical: "vertical" }, Position: { Left: "left", Right: "right" },
}));

// Exercise coordinator race handling directly; task polling is covered by the production-route test.
const readGeneration = () => {
  const nodes = controlledFlow.props?.nodes as { id: string; data: { onReadGeneration(id: string): void } }[];
  const node = nodes.find(item => item.id === "generation-1")!;
  act(() => node.data.onReadGeneration(node.id));
};

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it.each([
  { type: 'IMAGE', action: '图生图片', reference: 'IMAGE', count: 1 },
  { type: 'VIDEO', action: '图生视频', reference: 'IMAGE', count: 1 },
  { type: 'VIDEO', action: '首尾帧视频', reference: 'IMAGE', count: 2 },
  { type: 'VIDEO', action: '视频生视频', reference: 'VIDEO', count: 1 },
] as const)("$action 在左侧创建参考卡片并连线，当前卡片打开原生成编辑区", ({type, action, reference, count}) => {
  const initial = updateGenerationSettings(addGenerationNode(createCanvasDocument("entry", "t0"), "image-entry", { x: 500, y: 180 }, "t0"), 'image-entry', copyGenerationDefaults(type), 't0');
  const changed = vi.fn();
  render(<CanvasWorkspace projectId="entry" initialDocument={initial} storage={null} onDocumentChange={changed} />);
  const card = screen.getByTestId("node-image-entry");
  expect(within(card).getByRole("button", { name: type === 'IMAGE' ? '上传图片' : '上传视频' })).toBeInTheDocument();
  expect(within(card).getByRole("textbox", { name: "生成提示词" })).toBeInTheDocument();
  fireEvent.click(within(card).getByRole("button", { name: action }));
  const updated = changed.mock.lastCall![0];
  expect(updated.nodes).toHaveLength(count + 1);
  const references = updated.nodes.filter((node: { id: string }) => node.id !== "image-entry");
  for (const node of references) {
    expect(node.position.x).toBeLessThan(500);
    expect(node.settings.type).toBe(reference);
    expect(within(screen.getByTestId(`node-${node.id}`)).getByRole('button', { name: reference === 'IMAGE' ? '上传图片' : '上传视频' })).toBeInTheDocument();
  }
  if (count === 2) expect(references[0].position.y).toBeLessThan(references[1].position.y);
  else expect(references[0].position.y).toBe(180);
  expect(updated.edges).toEqual(references.map((node: {id: string}) => expect.objectContaining({ source: node.id, target: "image-entry" })));
  if (type === 'VIDEO') expect(updated.nodes[0].settings.referenceMode).toBe(count === 2 ? 'FIRST_LAST_FRAME' : 'OMNI');
  expect(card).toHaveAttribute("data-selected", "true");
  expect(within(card).getByRole("textbox", { name: "生成提示词" })).toBeInTheDocument();
  expect(within(card).queryByRole("button", { name: action })).not.toBeInTheDocument();
  for (let index = 0; index < count; index++) {
    fireEvent.click(within(card).getAllByRole('button', { name: /移除参考/ })[0]);
    expect(changed.mock.lastCall![0].nodes).toHaveLength(count + 1);
    expect(changed.mock.lastCall![0].edges).toHaveLength(count - index - 1);
  }
  expect(within(card).getByRole('button', { name: action })).toBeInTheDocument();
  expect(within(card).getByRole('textbox', { name: '生成提示词' })).toBeInTheDocument();
  act(() => (controlledFlow.props!.onConnect as Function)({ source: references[0].id, target: 'image-entry' }));
  expect(changed.mock.lastCall![0].nodes).toHaveLength(count + 1);
  expect(changed.mock.lastCall![0].edges).toHaveLength(1);
  expect(within(card).queryByRole('button', { name: action })).not.toBeInTheDocument();
});

it.each(['source', 'target'] as const)('从%s端拖到已有卡片主体建立相同引用，保留选择且不打开新增菜单', handleType => {
  let initial = addGenerationNode(createCanvasDocument('body-link', 't0'), 'image', { x: 0, y: 0 }, 't0');
  initial = updateGenerationSettings(addGenerationNode(initial, 'video', { x: 500, y: 0 }, 't0'), 'video', copyGenerationDefaults('VIDEO'), 't0');
  const changed = vi.fn();
  render(<CanvasWorkspace projectId="body-link" initialDocument={initial} storage={null} onDocumentChange={changed} />);
  fireEvent.keyDown(screen.getByTestId('node-image'), { key: 'Enter' });
  const before = screen.getByTestId('node-image').getAttribute('data-selected');
  const origin = handleType === 'source' ? 'image' : 'video';
  const target = screen.getByTestId(handleType === 'source' ? 'node-video' : 'node-image').querySelector('.canvas-generation-preview')!;
  act(() => (controlledFlow.props!.onConnectEnd as Function)(
    { target, clientX: 500, clientY: 150 }, { isValid: false, fromNode: { id: origin }, fromHandle: { type: handleType }, toNode: null },
  ));
  expect(changed.mock.lastCall![0].edges).toEqual([{ id: 'image->video', source: 'image', target: 'video', role: 'start' }]);
  expect(screen.getByTestId('node-image')).toHaveAttribute('data-selected', before);
  expect(screen.queryByRole('menu', { name: '添加节点' })).not.toBeInTheDocument();
});

class MemoryStorage implements Storage {
  protected values = new Map<string, string>();
  get length() { return this.values.size; }
  clear() { this.values.clear(); }
  getItem(key: string) { return this.values.get(key) ?? null; }
  key(index: number) { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string) { this.values.delete(key); }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

class FailingWriteStorage extends MemoryStorage {
  override setItem() { throw new DOMException("full", "QuotaExceededError"); }
}

const connectedStorage = (projectId = "project-1") => {
  const storage = new MemoryStorage();
  saveConnectedProject(storage, projectId);
  return storage;
};

const saveConnectedProject = (storage: Storage, projectId: string) => {
  let document = addTextNode(
    createCanvasDocument(projectId, "t0"),
    "text-1",
    { x: 0, y: 0 },
    "t0",
  );
  document = updateTextNode(document, "text-1", "雨夜黑猫", "t0");
  document = addGenerationNode(document, "generation-1", { x: 300, y: 0 }, "t0");
  document = connectNodes(document, "text-1", "generation-1", "t0");
  saveCanvasDraft(storage, document);
};

const defer = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return { promise, resolve, reject };
};

const generationOptions: CanvasGenerationOption[] = [
  { type: "IMAGE", model: "seedream-5.0-lite", displayName: "Seedream 5.0 Lite", aspectRatios: ["1:1", "16:9"], resolutions: ["2K", "4K"], maxCount: 6, minDurationSeconds: 0, maxDurationSeconds: 0 },
  { type: "IMAGE", model: "image-alternative", displayName: "图片备选", aspectRatios: ["1:1"], resolutions: ["2K"], maxCount: 2, minDurationSeconds: 0, maxDurationSeconds: 0 },
  { type: "VIDEO", model: "seedance-2.5", displayName: "Seedance 2.5", aspectRatios: ["16:9", "1:1"], resolutions: ["720p", "1080p"], maxCount: 1, minDurationSeconds: 3, maxDurationSeconds: 10 },
];
const generationPort = (overrides: Partial<MediaTaskPort> = {}): MediaTaskPort => ({
  options: vi.fn(async () => generationOptions), quote: vi.fn(async () => ({ points: "12.3400" })),
  submit: vi.fn(async () => ({ taskRef: "task-1" })), read: vi.fn(async () => ({ state: "working" as const, taskRef: "task-1" })), ...overrides,
});
const waitForQuote = () => waitFor(() => expect(screen.getByRole("button", { name: "提交生成任务" })).toBeEnabled());


describe("CanvasWorkspace", () => {
  it.each(["图片", "视频"])("新增%s使用自身提示词提交并保存恢复，始终不附带文本节点", async (kind) => {
    const storage = new MemoryStorage();
    const port = generationPort();
    const view = render(<CanvasWorkspace projectId="visual" storage={storage} mediaTaskPort={port} />);
    fireEvent.click(screen.getByRole("button", { name: `新增${kind}` }));
    await waitForQuote();
    const prompt = screen.getByLabelText("生成提示词");
    prompt.focus();
    fireEvent.change(screen.getByLabelText("生成提示词"), { target: { value: "雨夜街道" } });
    expect(screen.getByLabelText("生成提示词")).toBe(prompt);
    expect(prompt).toHaveFocus();
    expect(screen.getByRole("button", { name: `上传${kind}` })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: `图生${kind}` })).toBeInTheDocument();
    expect(screen.queryByLabelText("提示词")).not.toBeInTheDocument();
    expect(loadCanvasDraft(storage, "visual", 0)).toMatchObject({ document: {
      nodes: [expect.objectContaining({ kind: "generation", prompt: "雨夜街道" })], edges: [],
    } });
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    await waitFor(() => expect(port.submit).toHaveBeenCalledWith(expect.objectContaining({
      prompt: "雨夜街道", settings: expect.objectContaining({ type: kind === "图片" ? "IMAGE" : "VIDEO" }),
    })));
    view.unmount();
    render(<CanvasWorkspace projectId="visual" storage={storage} mediaTaskPort={port} />);
    expect(screen.getByLabelText("生成提示词")).toHaveValue("雨夜街道");
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    expect(screen.queryByLabelText("提示词")).not.toBeInTheDocument();
  });

  it.each(["source", "target"] as const)("拖线从%s端创建三种节点，保留方向、选择、缩放坐标和保存", (handleType) => {
    const storage = new MemoryStorage();
    const initial = updateViewport(updateGenerationSettings(addGenerationNode(createCanvasDocument("drag", "t0"), "origin", { x: 0, y: 0 }, "t0"), "origin", copyGenerationDefaults(handleType === "target" ? "VIDEO" : "IMAGE"), "t0"), { x: 100, y: -40, zoom: 2 }, "t0");
    saveCanvasDraft(storage, initial);
    render(<CanvasWorkspace projectId="drag" storage={storage} />);
    for (const [index, label] of (handleType === "source" ? ["图片", "视频"] : ["图片", "视频", "文本"]).entries()) {
      const flow = controlledFlow.props as { onConnectEnd(event: unknown, state: unknown): void };
      act(() => flow.onConnectEnd({ clientX: 500, clientY: 300, target: screen.getByLabelText("受控画布适配器") }, {
        isValid: false, fromNode: { id: "origin" }, fromHandle: { type: handleType }, toNode: null,
      }));
      const menu = screen.getByRole("menu", { name: "添加节点" });
      expect(within(menu).getAllByRole("menuitem").map(item => item.textContent)).toEqual(handleType === "source" ? ["图片", "视频"] : ["图片", "视频", "文本"]);
      expect(screen.getAllByRole("article")).toHaveLength(index + 1);
      fireEvent.click(within(menu).getByRole("menuitem", { name: label }));
      expect(screen.queryByRole("menu")).not.toBeInTheDocument();
      const loaded = loadCanvasDraft(storage, "drag", 0);
      if (loaded.kind !== "loaded") throw new Error("not saved");
      expect(loaded.document.nodes).toHaveLength(index + 2);
      const node = loaded.document.nodes.at(-1)!;
      expect(node).toMatchObject({ position: { x: 200, y: 170 }, kind: label === "文本" ? "text" : "generation" });
      if (label !== "文本") expect(node).toMatchObject({ settings: { type: label === "图片" ? "IMAGE" : "VIDEO" } });
      expect(loaded.document.edges.at(-1)).toMatchObject(handleType === "source"
        ? { source: "origin", target: node.id } : { source: node.id, target: "origin" });
      expect(screen.getByTestId(`node-${node.id}`)).toHaveAttribute("data-selected", "true");
    }
  });

  it("拖线菜单 Esc/空白取消无副作用，已有节点正常连接且不打开菜单", () => {
    const storage = connectedStorage();
    render(<CanvasWorkspace storage={storage} projectId="project-1" />);
    const before = loadCanvasDraft(storage, "project-1", 0);
    const end = (extra = {}) => act(() => (controlledFlow.props!.onConnectEnd as Function)(
      { clientX: window.innerWidth - 1, clientY: window.innerHeight - 1, target: screen.getByLabelText("受控画布适配器") },
      { isValid: false, fromNode: { id: "text-1" }, fromHandle: { type: "source" }, toNode: null, ...extra },
    ));
    end();
    const menu = screen.getByRole("menu", { name: "添加节点" });
    expect(parseFloat(menu.style.left)).toBeLessThan(window.innerWidth - 200);
    expect(parseFloat(menu.style.top)).toBeLessThan(window.innerHeight - 180);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    end();
    fireEvent.pointerDown(document.body);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    end({ toNode: { id: "generation-1" } });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    act(() => (controlledFlow.props!.onConnect as Function)({ source: "generation-1", target: "text-1" }));
    end({ isValid: true, toNode: { id: "text-1" } });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    const after = loadCanvasDraft(storage, "project-1", 0);
    if (before.kind !== "loaded" || after.kind !== "loaded") throw new Error("not saved");
    expect(after.document.nodes).toEqual(before.document.nodes);
    expect(after.document.edges).toHaveLength(1);
  });

  it("切换选择保持各自提示词，主动文本独立保存且不会覆盖或重复拼接", async () => {
    const storage = connectedStorage();
    const port = generationPort();
    const view = render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);
    await waitForQuote();
    fireEvent.change(screen.getByLabelText("生成提示词"), { target: { value: "  自身提示词  " } });
    expect(screen.getByLabelText("提示词")).toHaveValue("雨夜黑猫");
    fireEvent.click(screen.getByRole("button", { name: "新增视频" }));
    const nodes = screen.getAllByRole("article");
    const video = nodes.at(-1)!;
    fireEvent.change(within(video).getByLabelText("生成提示词"), { target: { value: "视频提示词" } });
    fireEvent.keyDown(screen.getByTestId("node-generation-1"), { key: "Enter" });
    expect(within(screen.getByTestId("node-generation-1")).getByLabelText("生成提示词")).toHaveValue("  自身提示词  ");
    view.unmount();
    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);
    expect(screen.getAllByLabelText("生成提示词").map(element => (element as HTMLTextAreaElement).value)).toEqual(["  自身提示词  ", "视频提示词"]);
    expect(screen.getAllByRole("article")).toHaveLength(3);
    await waitFor(() => expect(within(screen.getByTestId("node-generation-1")).getByRole("button", { name: "提交生成任务" })).toBeEnabled());
    fireEvent.click(within(screen.getByTestId("node-generation-1")).getByRole("button", { name: "提交生成任务" }));
    await waitFor(() => expect(port.submit).toHaveBeenCalledWith(expect.objectContaining({ prompt: "自身提示词\n雨夜黑猫" })));
    expect(screen.getAllByRole("article")).toHaveLength(3);
  });

  it("生成节点显示图片默认控件、后端积分及视频默认值，逐字段重新试算", async () => {
    const port = generationPort();
    render(<CanvasWorkspace projectId="project-1" storage={connectedStorage()} mediaTaskPort={port} />);
    await waitForQuote();
    expect(screen.getByLabelText("模型")).toHaveValue("seedream-5.0-lite");
    expect(screen.getByRole("button", { name: "比例与分辨率" })).toHaveTextContent("1:1");
    expect(screen.getByRole("button", { name: "比例与分辨率" })).toHaveTextContent("2K");
    expect(screen.getByLabelText("图片数量")).toHaveValue("1");
    expect(screen.getByText("预计 12.34 积分")).toBeVisible();
    expect(screen.queryByRole("option", { name: "5 张" })).not.toBeInTheDocument();
    for (const [label, value, field] of [["图片数量", "2", "count"], ["比例", "16:9", "aspectRatio"], ["分辨率", "4K", "resolution"], ["模型", "image-alternative", "model"]]) {
      if (label === "比例" || label === "分辨率") {
        fireEvent.click(screen.getByRole("button", { name: "比例与分辨率" }));
        fireEvent.click(within(screen.getByRole("dialog", { name: "比例调节" })).getByRole("button", { name: value }));
        expect(screen.getByRole("button", { name: value })).toHaveAttribute("aria-pressed", "true");
        fireEvent.click(screen.getByRole("button", { name: "关闭比例调节" }));
      } else if (label === "模型") chooseCanvasOption(screen.getByLabelText(label), value);
      else fireEvent.change(screen.getByLabelText(label), { target: { value } });
      await waitFor(() => expect(port.quote).toHaveBeenLastCalledWith(expect.objectContaining({ [field]: field === "count" ? Number(value) : value })));
      await waitForQuote();
    }
    chooseCanvasOption(screen.getByLabelText("生成类型"), "VIDEO");
    await waitForQuote();
    expect(screen.getByLabelText("模型")).toHaveValue("seedance-2.5");
    expect(screen.getByRole("button", { name: "比例与分辨率" })).toHaveTextContent("16:9");
    expect(screen.getByRole("button", { name: "比例与分辨率" })).toHaveTextContent("720p");
    expect(screen.getByLabelText("时长设置")).toHaveTextContent("5s");
    expect(screen.queryByText("固定 1 条视频")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "同步任务状态" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "把生成节点设为连接起点" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("图片数量")).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("时长设置"));
    const slider = screen.getByRole("slider", { name: "视频时长" });
    expect(slider).toHaveAttribute("min", "3");
    expect(slider).toHaveAttribute("max", "10");
    fireEvent.change(slider, { target: { value: "8" } });
    expect(screen.getByLabelText("视频时长（秒）")).toHaveValue(8);
    fireEvent.change(screen.getByLabelText("视频时长（秒）"), { target: { value: "6" } });
    expect(slider).toHaveValue("6");
    fireEvent.change(slider, { target: { value: "8" } });
    fireEvent.click(screen.getByLabelText("关闭时长设置"));
    await waitFor(() => expect(port.quote).toHaveBeenLastCalledWith(expect.objectContaining({ durationSeconds: 8, count: 1 })));
    chooseCanvasOption(screen.getByLabelText("生成类型"), "IMAGE");
    await waitForQuote();
    expect(screen.getByLabelText("图片数量")).toHaveValue("1");
    expect(port.options).toHaveBeenCalledTimes(1);
  });

  it("报价加载与失败禁用提交，过期结果不能解锁新参数且可修改参数重试", async () => {
    const first = defer<{ points: string }>();
    const second = defer<{ points: string }>();
    const quote = vi.fn().mockImplementationOnce(() => first.promise).mockImplementationOnce(() => second.promise).mockResolvedValue({ points: "8.0000" });
    const port = generationPort({ quote });
    render(<CanvasWorkspace projectId="project-1" storage={connectedStorage()} mediaTaskPort={port} />);
    await waitFor(() => expect(quote).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("图片数量"), { target: { value: "2" } });
    await waitFor(() => expect(quote).toHaveBeenCalledTimes(2));
    await act(async () => { first.resolve({ points: "1.0000" }); await first.promise; });
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    expect(screen.queryByText("预计 1 积分")).not.toBeInTheDocument();
    await act(async () => { second.reject(new Error("quote failed")); });
    expect(await screen.findByText("积分试算失败，请调整参数重试")).toBeVisible();
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("图片数量"), { target: { value: "3" } });
    await waitForQuote();
    expect(screen.getByText("预计 8 积分")).toBeVisible();
  });

  it("首选模型不可用时采用同类型兼容模型，空目录与加载错误分别提示", async () => {
    const view = render(<CanvasWorkspace projectId="project-1" storage={connectedStorage()} mediaTaskPort={generationPort({ options: async () => [generationOptions[1]] })} />);
    await waitForQuote();
    expect(screen.getByLabelText("模型")).toHaveValue("image-alternative");
    view.rerender(<CanvasWorkspace projectId="project-1" storage={connectedStorage()} mediaTaskPort={generationPort({ options: async () => [] })} />);
    expect(await screen.findByText("当前无可用生成模型")).toBeVisible();
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    view.rerender(<CanvasWorkspace projectId="project-1" storage={connectedStorage()} mediaTaskPort={generationPort({ options: async () => { throw new Error("catalog failed"); } })} />);
    expect(await screen.findByText("生成模型加载失败")).toBeVisible();
  });

  it.each([
    { label: "比例与分辨率", type: "IMAGE" as const, incompatible: { aspectRatios: ["16:9"], resolutions: ["4K"] }, count: 1 },
    { label: "图片数量", type: "IMAGE" as const, incompatible: { maxCount: 1 }, count: 2 },
    { label: "视频时长", type: "VIDEO" as const, incompatible: { minDurationSeconds: 6 }, count: 1 },
  ])("默认模型缺失时跳过不支持当前$label的首项，选择后续兼容模型", async ({ type, incompatible, count }) => {
    const storage = connectedStorage();
    const loaded = loadCanvasDraft(storage, "project-1", 0);
    if (loaded.kind !== "loaded") throw new Error("fixture missing");
    const settings = type === "IMAGE"
      ? { type, model: "seedream-5.0-lite", aspectRatio: "1:1", resolution: "2K", count, durationSeconds: 0 }
      : { type, model: "seedance-2.5", aspectRatio: "16:9", resolution: "720p", count, durationSeconds: 5 };
    saveCanvasDraft(storage, updateGenerationSettings(loaded.document, "generation-1", settings, "t1"));
    const source = type === "IMAGE" ? generationOptions[0] : generationOptions[2];
    const compatible = { ...source, model: "compatible-model", displayName: "兼容模型" };
    const port = generationPort({ options: async () => [
      { ...source, ...incompatible, model: "incompatible-model", displayName: "不兼容模型" }, compatible,
    ] });
    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);
    await waitForQuote();
    expect(screen.getByLabelText("模型")).toHaveValue("compatible-model");
    expect(port.quote).toHaveBeenCalledExactlyOnceWith({ ...settings, model: "compatible-model" });
  });

  it("默认模型缺失且所有候选不兼容时保留选择、显示无可用模型并禁止试算提交", async () => {
    const storage = connectedStorage();
    const port = generationPort({ options: async () => [{ ...generationOptions[0], model: "incompatible-model", aspectRatios: ["16:9"], resolutions: ["4K"] }] });
    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);
    expect(await screen.findByText("当前无可用生成模型")).toBeVisible();
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    expect(port.quote).not.toHaveBeenCalled();
    expect(loadCanvasDraft(storage, "project-1", 0)).toMatchObject({
      kind: "loaded", document: { nodes: expect.arrayContaining([expect.objectContaining({
        id: "generation-1", settings: expect.objectContaining({ model: "seedream-5.0-lite", aspectRatio: "1:1", resolution: "2K" }),
      })]) },
    });
  });

  it("活跃生成控件通过可访问性检查且 Delete 不删除已选择节点", async () => {
    const view = render(<CanvasWorkspace projectId="project-1" storage={connectedStorage()} mediaTaskPort={generationPort()} />);
    await waitForQuote();
    fireEvent.keyDown(screen.getByTestId("node-generation-1"), { key: "Enter" });
    const select = screen.getByLabelText("模型");
    expect(select.closest(".nodrag.nowheel.nopan")).not.toBeNull();
    fireEvent.keyDown(select, { key: "Delete" });
    fireEvent.keyDown(select, { key: "Backspace" });
    expect(screen.getByTestId("node-generation-1")).toBeVisible();
    const canvasContext = vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    try {
      expect((await axe(view.container)).violations).toEqual([]);
    } finally {
      canvasContext.mockRestore();
    }
  });

  it("有效的已保存参数不被默认值覆盖且提交后参数冻结，积分不写入文档", async () => {
    const storage = connectedStorage();
    const loaded = loadCanvasDraft(storage, "project-1", 0);
    if (loaded.kind !== "loaded") throw new Error("fixture missing");
    saveCanvasDraft(storage, updateGenerationSettings(loaded.document, "generation-1", {
      type: "IMAGE", model: "seedream-5.0-lite", aspectRatio: "16:9", resolution: "4K", count: 3, durationSeconds: 0,
    }, "t1"));
    const port = generationPort();
    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);
    await waitForQuote();
    expect(screen.getByLabelText("图片数量")).toHaveValue("3");
    expect(screen.getByRole("button", { name: "比例与分辨率" })).toHaveTextContent("16:9");
    expect(screen.getByRole("button", { name: "比例与分辨率" })).toHaveTextContent("4K");
    expect(JSON.stringify(loadCanvasDraft(storage, "project-1", 0))).not.toContain("12.34");
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    expect(screen.getByLabelText("图片数量")).toBeDisabled();
    expect(screen.getByRole("button", { name: "比例与分辨率" })).toBeDisabled();
    expect((await screen.findAllByText("排队中"))[0]).toBeVisible();
    fireEvent.change(screen.getByLabelText("图片数量"), { target: { value: "1" } });
    expect(screen.getByLabelText("图片数量")).toHaveValue("3");
    expect(port.submit).toHaveBeenCalledWith(expect.objectContaining({ settings: expect.objectContaining({ count: 3, resolution: "4K" }) }));
  });

  it("迟到报价不能跨项目或端口作用域启用同名节点", async () => {
    const storage = connectedStorage();
    saveConnectedProject(storage, "project-2");
    const oldQuote = defer<{ points: string }>();
    const nextQuote = defer<{ points: string }>();
    const port = generationPort({ quote: vi.fn().mockImplementationOnce(() => oldQuote.promise).mockImplementationOnce(() => nextQuote.promise) });
    const view = render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);
    await waitFor(() => expect(port.quote).toHaveBeenCalledTimes(1));
    view.rerender(<CanvasWorkspace projectId="project-2" storage={storage} mediaTaskPort={port} />);
    await waitFor(() => expect(port.quote).toHaveBeenCalledTimes(2));
    await act(async () => { oldQuote.resolve({ points: "1.0000" }); await oldQuote.promise; });
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    const replacementQuote = defer<{ points: string }>();
    const replacementPort = generationPort({ quote: vi.fn(() => replacementQuote.promise) });
    view.rerender(<CanvasWorkspace projectId="project-2" storage={storage} mediaTaskPort={replacementPort} />);
    await waitFor(() => expect(replacementPort.quote).toHaveBeenCalledTimes(1));
    await act(async () => { nextQuote.resolve({ points: "2.0000" }); await nextQuote.promise; });
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    await act(async () => { replacementQuote.resolve({ points: "3.0000" }); await replacementQuote.promise; });
    await waitForQuote();
    expect(screen.getByText("预计 3 积分")).toBeVisible();
    expect(screen.queryByText("预计 2 积分")).not.toBeInTheDocument();
  });

  it("两张图片从试算、一次提交、运行到部分成功只回填成功图片", async () => {
    const port = generationPort({ read: vi.fn().mockResolvedValueOnce({ state: "working", taskRef: "task-1" }).mockResolvedValueOnce({
      state: "partial_success", taskRef: "task-1", message: "部分成功", outputs: [{ assetRef: "result-1", kind: "image", alt: "成功图片", previewRef: "https://cdn.example.test/result.png" }],
    }) });
    render(<CanvasWorkspace projectId="project-1" storage={connectedStorage()} mediaTaskPort={port} />);
    await waitForQuote();
    fireEvent.change(screen.getByLabelText("图片数量"), { target: { value: "2" } });
    await waitForQuote();
    expect(screen.getByText("预计 12.34 积分")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    expect((await screen.findAllByText("排队中"))[0]).toBeVisible();
    readGeneration();
    expect((await screen.findAllByText("正在生成"))[0]).toBeVisible();
    readGeneration();
    expect(await screen.findByText("部分成功")).toBeVisible();
    expect(screen.getAllByRole("img")).toHaveLength(1);
    expect(screen.getByRole("img", { name: "成功图片" })).toBeVisible();
    expect(port.submit).toHaveBeenCalledTimes(1);
    expect(port.submit).toHaveBeenCalledWith(expect.objectContaining({ settings: expect.objectContaining({ count: 2 }) }));
  });

  it("原生按钮单次新增唯一节点并在文本输入聚焦时保护 Delete", () => {
    render(<CanvasWorkspace projectId="project-1" storage={null} />);
    const addText = screen.getByRole("button", { name: "新增文本节点" });

    expect(addText.tagName).toBe("BUTTON");
    fireEvent.click(addText);
    fireEvent.click(addText);
    expect(screen.getAllByLabelText("提示词")).toHaveLength(2);
    const nodeIds = screen.getAllByTestId(/^node-text-/).map((node) => node.dataset.testid);
    expect(new Set(nodeIds).size).toBe(2);

    const input = screen.getAllByLabelText("提示词")[0];
    fireEvent.change(input, { target: { value: "雨夜黑猫" } });
    fireEvent.keyDown(input, { key: "Delete" });
    expect(screen.getByDisplayValue("雨夜黑猫")).toBeVisible();
  });

  it("通过受控选择与 Delete 删除节点和关联边并保存", async () => {
    const storage = connectedStorage();
    render(<CanvasWorkspace projectId="project-1" storage={storage} />);

    fireEvent.keyDown(screen.getByTestId("node-text-1"), { key: "Enter" });
    fireEvent.keyDown(screen.getByTestId("canvas-workspace"), { key: "Delete" });

    await waitFor(() => expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: {
        nodes: [expect.objectContaining({ id: "generation-1" })],
        edges: [],
      },
    }));
  });

  it("文本节点已选中时 textarea 的 Delete 与 Backspace 不删除节点或关联边", async () => {
    const storage = connectedStorage();
    render(<CanvasWorkspace projectId="project-1" storage={storage} />);

    fireEvent.keyDown(screen.getByTestId("node-text-1"), { key: "Enter" });
    const input = screen.getByLabelText("提示词");
    input.focus();
    fireEvent.keyDown(input, { key: "Delete" });
    fireEvent.keyDown(input, { key: "Backspace" });

    expect(screen.getByTestId("node-text-1")).toBeVisible();
    await waitFor(() => expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: {
        nodes: expect.arrayContaining([
          expect.objectContaining({ id: "text-1", text: "雨夜黑猫" }),
          expect.objectContaining({ id: "generation-1" }),
        ]),
        edges: [expect.objectContaining({ source: "text-1", target: "generation-1" })],
      },
    }));
  });

  it("没有端口时禁用生成并说明合同未接入", () => {
    render(<CanvasWorkspace projectId="project-1" storage={null} />);
    fireEvent.click(screen.getByRole("button", { name: "新增生成占位节点" }));
    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    expect(screen.getByRole("status", { name: "画布保存状态" }))
      .toHaveTextContent("生产生成合同待接入");
    expect(screen.getByRole("status", { name: "画布保存状态" }))
      .toHaveTextContent("会话草稿");
  });

  it("拒绝没有直连非空提示词的提交", async () => {
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async () => ({ taskRef: "unused" })),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "unused" })),
    };
    render(<CanvasWorkspace projectId="project-1" storage={null} mediaTaskPort={port} />);
    fireEvent.click(screen.getByRole("button", { name: "新增生成占位节点" }));
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));

    expect(port.submit).not.toHaveBeenCalled();
    expect(await screen.findByText("请填写自身提示词或连接文本节点")).toBeVisible();
  });

  it.each([
    { taskState: "accepted" as const, taskRef: "task-1" },
    { taskState: "working" as const, taskRef: "task-1" },
    { taskState: "succeeded" as const, taskRef: "task-1" },
    { taskState: "failed" as const, taskRef: "task-1" },
  ])("工作区入口拒绝 $taskState 或已绑定节点二次提交", async ({ taskState, taskRef }) => {
    const storage = connectedStorage();
    const loaded = loadCanvasDraft(storage, "project-1", 1000);
    if (loaded.kind !== "loaded") throw new Error("fixture not loaded");
    saveCanvasDraft(storage, updateGenerationTask(
      loaded.document,
      "generation-1",
      { taskRef, taskState, message: taskState === "failed" ? "terminal" : null },
      "t1",
    ));
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async () => ({ taskRef: "unexpected" })),
      read: vi.fn(async () => ({ state: "working" as const, taskRef })),
    };
    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);
    const generation = (controlledFlow.props as {
      nodes: Array<{ id: string; data: { onSubmitGeneration(id: string): void } }>;
    }).nodes.find((node) => node.id === "generation-1");
    if (!generation) throw new Error("generation node missing");

    expect(screen.getByRole("button", { name: "提交生成任务" })).toBeDisabled();
    generation.data.onSubmitGeneration("generation-1");
    await Promise.resolve();
    expect(port.submit).not.toHaveBeenCalled();
    expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([expect.objectContaining({
        id: "generation-1",
        taskRef,
        taskState,
      })]) },
    });
  });

  it("动态节点与提交引用在卸载重挂后不复用", async () => {
    const requests: string[] = [];
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async (request) => {
        requests.push(request.requestRef);
        return { taskRef: `task-${requests.length}` };
      }),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "unused" })),
    };
    const submitFreshWorkspace = async () => {
      const view = render(
        <CanvasWorkspace projectId="project-1" storage={null} mediaTaskPort={port} />,
      );
      fireEvent.click(screen.getByRole("button", { name: "新增文本节点" }));
      fireEvent.click(screen.getByRole("button", { name: "新增生成占位节点" }));
      fireEvent.change(screen.getByLabelText("提示词"), { target: { value: "雨夜黑猫" } });
      const textNode = screen.getByTestId(/^node-text-/);
      const generationNode = screen.getByTestId(/^node-generation-/);
      const nodeIds = [textNode.dataset.testid, generationNode.dataset.testid];
      act(() => (controlledFlow.props?.onConnect as (connection: { source: string; target: string }) => void)({
        source: textNode.dataset.testid!.replace("node-", ""), target: generationNode.dataset.testid!.replace("node-", ""),
      }));
      await waitForQuote();
      fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
      view.unmount();
      return nodeIds;
    };

    const firstIds = await submitFreshWorkspace();
    const secondIds = await submitFreshWorkspace();

    expect(new Set([...firstIds, ...secondIds]).size).toBe(4);
    expect(requests).toHaveLength(2);
    expect(requests[0]).not.toBe(requests[1]);
  });

  it("动态节点删除后再次新增不会复用节点标识", () => {
    render(<CanvasWorkspace projectId="project-1" storage={null} />);
    fireEvent.click(screen.getByRole("button", { name: "新增生成占位节点" }));
    const firstNode = screen.getByTestId(/^node-generation-/);
    const firstId = firstNode.dataset.testid;
    expect(firstNode).toHaveAttribute("data-selected", "true");
    fireEvent.click(screen.getByRole("button", { name: "删除所选节点" }));

    fireEvent.click(screen.getByRole("button", { name: "新增生成占位节点" }));
    const secondId = screen.getByTestId(/^node-generation-/).dataset.testid;

    expect(secondId).not.toBe(firstId);
  });

  it("crypto.randomUUID 缺失时 fallback 跨挂载生成唯一节点且不抛", () => {
    const cryptoObject = globalThis.crypto as unknown as { randomUUID?: () => string };
    const previous = Object.getOwnPropertyDescriptor(cryptoObject, "randomUUID");
    Object.defineProperty(cryptoObject, "randomUUID", { configurable: true, value: undefined });
    try {
      const first = render(<CanvasWorkspace projectId="project-1" storage={null} />);
      fireEvent.click(screen.getByRole("button", { name: "新增生成占位节点" }));
      const firstId = screen.getByTestId(/^node-generation-/).dataset.testid;
      first.unmount();
      render(<CanvasWorkspace projectId="project-1" storage={null} />);
      fireEvent.click(screen.getByRole("button", { name: "新增生成占位节点" }));
      expect(screen.getByTestId(/^node-generation-/).dataset.testid).not.toBe(firstId);
    } finally {
      if (previous) Object.defineProperty(cryptoObject, "randomUUID", previous);
      else delete cryptoObject.randomUUID;
    }
  });

  it("crypto.randomUUID 抛错时 fallback 连续生成唯一节点且不抛", () => {
    const cryptoObject = globalThis.crypto as unknown as { randomUUID?: () => string };
    const previous = Object.getOwnPropertyDescriptor(cryptoObject, "randomUUID");
    Object.defineProperty(cryptoObject, "randomUUID", {
      configurable: true,
      value: () => { throw new DOMException("blocked", "SecurityError"); },
    });
    try {
      render(<CanvasWorkspace projectId="project-1" storage={null} />);
      expect(() => {
        fireEvent.click(screen.getByRole("button", { name: "新增生成占位节点" }));
        fireEvent.click(screen.getByRole("button", { name: "新增生成占位节点" }));
      }).not.toThrow();
      const ids = screen.getAllByTestId(/^node-generation-/).map((node) => node.dataset.testid);
      expect(new Set(ids).size).toBe(2);
    } finally {
      if (previous) Object.defineProperty(cryptoObject, "randomUUID", previous);
      else delete cryptoObject.randomUUID;
    }
  });

  it("同一生成节点同步提交只调用一次并持久化 submitting 到 accepted", async () => {
    const pending = defer<{ taskRef: string }>();
    const storage = connectedStorage();
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(() => pending.promise),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "task-1" })),
    };
    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);
    await waitForQuote();
    const submit = screen.getByRole("button", { name: "提交生成任务" });

    fireEvent.click(submit);
    fireEvent.click(submit);
    expect(port.submit).toHaveBeenCalledTimes(1);
    expect(port.submit).toHaveBeenCalledWith(expect.objectContaining({
      prompt: "雨夜黑猫",
      inputAssetRefs: [],
    }));
    await waitFor(() => expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([
        expect.objectContaining({ id: "generation-1", taskState: "submitting" }),
      ]) },
    }));

    pending.resolve({ taskRef: "task-1" });
    await waitFor(() => expect(screen.getAllByText("排队中")[0]).toBeVisible());
    await waitFor(() => expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([
        expect.objectContaining({
          id: "generation-1",
          taskRef: "task-1",
          taskState: "accepted",
        }),
      ]) },
    }));
  });

  it("accepted 后重放旧渲染的提交回调也不会二次提交", async () => {
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async () => ({ taskRef: "task-1" })),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "task-1" })),
    };
    render(
      <CanvasWorkspace projectId="project-1" storage={connectedStorage()} mediaTaskPort={port} />,
    );
    const generation = (controlledFlow.props as {
      nodes: Array<{ id: string; data: { onSubmitGeneration(id: string): void } }>;
    }).nodes.find((node) => node.id === "generation-1");
    if (!generation) throw new Error("generation node missing");
    const staleSubmit = generation.data.onSubmitGeneration;

    await waitForQuote();
    staleSubmit("generation-1");
    await waitFor(() => expect(screen.getAllByText("排队中")[0]).toBeVisible());
    staleSubmit("generation-1");
    await Promise.resolve();

    expect(port.submit).toHaveBeenCalledTimes(1);
  });

  it("提交异常写入失败状态且允许再次尝试", async () => {
    const requestRefs: string[] = [];
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async (request) => {
        requestRefs.push(request.requestRef);
        if (requestRefs.length === 1) throw new Error("no");
        return { taskRef: "task-2" };
      }),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "task-2" })),
    };
    render(<CanvasWorkspace projectId="project-1" storage={connectedStorage()} mediaTaskPort={port} />);

    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    expect(await screen.findByText("任务提交失败")).toBeVisible();
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    expect((await screen.findAllByText("排队中"))[0]).toBeVisible();
    expect(port.submit).toHaveBeenCalledTimes(2);
    expect(requestRefs).toHaveLength(2); expect(requestRefs.every(ref => ref.startsWith("canvas-run-"))).toBe(true); expect(requestRefs[0]).not.toBe(requestRefs[1]);
  });

  it.each(["", "   "])("端口返回空白任务引用 %j 时持久化失败并允许重试", async (taskRef) => {
    const storage = connectedStorage();
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn()
        .mockResolvedValueOnce({ taskRef })
        .mockResolvedValueOnce({ taskRef: "task-2" }),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "task-2" })),
    };
    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);

    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    expect(await screen.findByText("任务提交未返回有效引用，请重试")).toBeVisible();
    expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([expect.objectContaining({
        id: "generation-1",
        taskRef: null,
        taskState: "failed",
        message: "任务提交未返回有效引用，请重试",
      })]) },
    });

    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    expect((await screen.findAllByText("排队中"))[0]).toBeVisible();
    expect(port.submit).toHaveBeenCalledTimes(2);
  });

  it("持久化的 submitting 空句柄在加载时恢复为可重试失败状态", async () => {
    const storage = connectedStorage();
    const loaded = loadCanvasDraft(storage, "project-1", 1000);
    if (loaded.kind !== "loaded") throw new Error("fixture not loaded");
    saveCanvasDraft(storage, updateGenerationTask(
      loaded.document,
      "generation-1",
      { taskRef: null, taskState: "submitting", message: null },
      "t1",
    ));

    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={{
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async () => ({ taskRef: "task-1" })),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "task-1" })),
    }} />);

    expect(await screen.findByText("上次提交已中断，请重试")).toBeVisible();
    await waitForQuote();
    expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([expect.objectContaining({
        id: "generation-1",
        taskRef: null,
        taskState: "failed",
        message: "上次提交已中断，请重试",
      })]) },
    });
  });

  it("切换并切回同名项目后忽略旧项目迟到提交结果", async () => {
    const pending = defer<{ taskRef: string }>();
    const storage = connectedStorage();
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(() => pending.promise),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "task-1" })),
    };
    const view = render(
      <CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />,
    );
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    view.rerender(<CanvasWorkspace projectId="project-2" storage={storage} mediaTaskPort={port} />);
    view.rerender(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);

    pending.resolve({ taskRef: "late-task" });
    await waitFor(() => expect(screen.getByTestId("canvas-workspace"))
      .toHaveAttribute("data-project-id", "project-1"));
    expect(screen.queryByText("排队中")).not.toBeInTheDocument();
    expect(screen.getByText("上次提交已中断，请重试")).toBeVisible();
  });

  it("项目切换后忽略旧项目迟到读取快照，即使切回同名项目也不串线", async () => {
    const storage = connectedStorage();
    const loaded = loadCanvasDraft(storage, "project-1", 1000);
    if (loaded.kind !== "loaded") throw new Error("fixture not loaded");
    saveCanvasDraft(storage, updateGenerationTask(
      loaded.document,
      "generation-1",
      { taskRef: "task-1", taskState: "working", message: null },
      "t1",
    ));
    const pending = defer<{
      state: "succeeded";
      taskRef: string;
      outputs: readonly [];
    }>();
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async () => ({ taskRef: "unused" })),
      read: vi.fn(() => pending.promise),
    };
    const view = render(
      <CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />,
    );
    readGeneration();
    view.rerender(<CanvasWorkspace projectId="project-2" storage={storage} mediaTaskPort={port} />);
    view.rerender(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);
    pending.resolve({ state: "succeeded", taskRef: "task-1", outputs: [] });

    await waitFor(() => expect(screen.getAllByText("正在生成")[0]).toBeVisible());
    expect(screen.queryByText("生成完成")).not.toBeInTheDocument();
  });

  it("成功快照落库后忽略更早读取的迟到异常并保持终态", async () => {
    const storage = connectedStorage();
    const loaded = loadCanvasDraft(storage, "project-1", 1000);
    if (loaded.kind !== "loaded") throw new Error("fixture not loaded");
    saveCanvasDraft(storage, updateGenerationTask(
      loaded.document,
      "generation-1",
      { taskRef: "task-1", taskState: "working", message: null },
      "t1",
    ));
    const first = defer<never>();
    const read = vi.fn()
      .mockImplementationOnce(() => first.promise)
      .mockResolvedValueOnce({
        state: "succeeded",
        taskRef: "task-1",
        outputs: [{
          assetRef: "asset-1",
          kind: "image",
          alt: "成功结果",
          previewRef: "https://cdn.example.test/result.png",
        }],
      });
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async () => ({ taskRef: "unused" })),
      read,
    };
    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);

    readGeneration();
    readGeneration();
    await waitFor(() => expect(screen.getByRole("img", { name: "成功结果" })).toBeVisible());
    first.reject(new Error("late read failure"));
    await waitFor(() => expect(screen.getByText("生成完成")).toBeVisible());
    expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: {
        nodes: expect.arrayContaining([
          expect.objectContaining({ id: "generation-1", taskState: "succeeded" }),
        ]),
      },
    });
  });

  it("活动项目读取异常经协调器显示并持久化失败快照", async () => {
    const storage = connectedStorage();
    const loaded = loadCanvasDraft(storage, "project-1", 1000);
    if (loaded.kind !== "loaded") throw new Error("fixture not loaded");
    saveCanvasDraft(storage, updateGenerationTask(
      loaded.document,
      "generation-1",
      { taskRef: "task-1", taskState: "working", message: null },
      "t1",
    ));
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async () => ({ taskRef: "unused" })),
      read: vi.fn(async () => { throw new Error("read failed"); }),
    };
    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />);

    readGeneration();

    expect(await screen.findByText("任务状态读取失败")).toBeVisible();
    expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: {
        nodes: expect.arrayContaining([expect.objectContaining({
          id: "generation-1",
          taskRef: "task-1",
          taskState: "failed",
          message: "任务状态读取失败",
        })]),
      },
    });
  });

  it("旧项目迟到提交清理不会释放新项目同节点的提交锁", async () => {
    const storage = connectedStorage("project-1");
    saveConnectedProject(storage, "project-2");
    const first = defer<{ taskRef: string }>();
    const second = defer<{ taskRef: string }>();
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn()
        .mockImplementationOnce(() => first.promise)
        .mockImplementationOnce(() => second.promise)
        .mockResolvedValue({ taskRef: "unexpected-third" }),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "unused" })),
    };
    const view = render(
      <CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} />,
    );

    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    view.rerender(
      <CanvasWorkspace projectId="project-2" storage={storage} mediaTaskPort={port} />,
    );
    await waitFor(() => expect(screen.getByText("待生成")).toBeVisible());
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    expect(port.submit).toHaveBeenCalledTimes(2);

    await act(async () => {
      first.resolve({ taskRef: "old-task" });
      await first.promise;
    });
    const generation = (controlledFlow.props as {
      nodes: Array<{
        id: string;
        data: { onSubmitGeneration(id: string): void };
      }>;
    }).nodes.find((node) => node.id === "generation-1");
    if (!generation) throw new Error("generation node missing");
    generation.data.onSubmitGeneration("generation-1");
    expect(port.submit).toHaveBeenCalledTimes(2);
    expect(loadCanvasDraft(storage, "project-2", 1000)).not.toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([
        expect.objectContaining({ taskRef: "old-task" }),
      ]) },
    });

    await act(async () => {
      second.resolve({ taskRef: "new-task" });
      await second.promise;
    });
    await waitFor(() => expect(screen.getAllByText("排队中")[0]).toBeVisible());
    expect(loadCanvasDraft(storage, "project-2", 1000)).toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([expect.objectContaining({
        id: "generation-1",
        taskRef: "new-task",
        taskState: "accepted",
      })]) },
    });
  });

  it("存储热替换后旧提交结果不能写入新存储作用域", async () => {
    const oldStorage = connectedStorage();
    const newStorage = connectedStorage();
    const pending = defer<{ taskRef: string }>();
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(() => pending.promise),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "unused" })),
    };
    const view = render(
      <CanvasWorkspace projectId="project-1" storage={oldStorage} mediaTaskPort={port} />,
    );
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    view.rerender(
      <CanvasWorkspace projectId="project-1" storage={newStorage} mediaTaskPort={port} />,
    );
    await waitFor(() => expect(screen.getByText("待生成")).toBeVisible());

    pending.resolve({ taskRef: "old-task" });
    await act(async () => { await pending.promise; });

    expect(screen.getByText("待生成")).toBeVisible();
    expect(loadCanvasDraft(newStorage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([expect.objectContaining({
        id: "generation-1",
        taskRef: null,
        taskState: "idle",
      })]) },
    });
  });

  it("项目和同项目存储作用域切换时重挂画布并恢复各自默认视口", async () => {
    const firstStorage = connectedStorage("project-1");
    saveConnectedProject(firstStorage, "project-2");
    for (const [project, viewport] of [
      ["project-1", { x: 10, y: 20, zoom: 1.1 }],
      ["project-2", { x: -80, y: 40, zoom: 1.6 }],
    ] as const) {
      const loaded = loadCanvasDraft(firstStorage, project, 1000);
      if (loaded.kind !== "loaded") throw new Error("viewport fixture missing");
      saveCanvasDraft(firstStorage, updateViewport(loaded.document, viewport, `viewport-${project}`));
    }
    const secondStorage = connectedStorage("project-2");
    const secondLoaded = loadCanvasDraft(secondStorage, "project-2", 1000);
    if (secondLoaded.kind !== "loaded") throw new Error("replacement viewport fixture missing");
    saveCanvasDraft(secondStorage, updateViewport(
      secondLoaded.document,
      { x: 220, y: -120, zoom: 0.75 },
      "viewport-replacement",
    ));

    const view = render(<CanvasWorkspace projectId="project-1" storage={firstStorage} />);
    await waitFor(() => expect(controlledFlow.props).toMatchObject({
      defaultViewport: { x: 10, y: 20, zoom: 1.1 },
    }));
    const projectOneSurface = screen.getByLabelText("受控画布适配器");

    view.rerender(<CanvasWorkspace projectId="project-2" storage={firstStorage} />);
    await waitFor(() => expect(controlledFlow.props).toMatchObject({
      defaultViewport: { x: -80, y: 40, zoom: 1.6 },
    }));
    const projectTwoSurface = screen.getByLabelText("受控画布适配器");
    expect(projectTwoSurface).not.toBe(projectOneSurface);

    view.rerender(<CanvasWorkspace projectId="project-2" storage={secondStorage} />);
    await waitFor(() => expect(controlledFlow.props).toMatchObject({
      defaultViewport: { x: 220, y: -120, zoom: 0.75 },
    }));
    expect(screen.getByLabelText("受控画布适配器")).not.toBe(projectTwoSurface);
  });

  it("端口热替换会恢复当前 submitting 并允许用稳定引用重试", async () => {
    const storage = connectedStorage();
    const pending = defer<{ taskRef: string }>();
    const requestRefs: string[] = [];
    const oldPort: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn((request) => {
        requestRefs.push(request.requestRef);
        return pending.promise;
      }),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "unused" })),
    };
    const newPort: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async (request) => {
        requestRefs.push(request.requestRef);
        return { taskRef: "new-task" };
      }),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "new-task" })),
    };
    const view = render(
      <CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={oldPort} />,
    );
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    await waitFor(() => expect(screen.getAllByText("正在提交")[0]).toBeVisible());
    view.rerender(
      <CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={newPort} />,
    );

    expect(await screen.findByText("上次提交已中断，请重试")).toBeVisible();
    pending.resolve({ taskRef: "old-task" });
    await act(async () => { await pending.promise; });
    expect(screen.queryByText("排队中")).not.toBeInTheDocument();
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    expect((await screen.findAllByText("排队中"))[0]).toBeVisible();
    expect(requestRefs).toHaveLength(2); expect(requestRefs.every(ref => ref.startsWith("canvas-run-"))).toBe(true); expect(requestRefs[0]).not.toBe(requestRefs[1]);
  });

  it("持久草稿卸载重挂时恢复 pending submit 且忽略旧实例结果", async () => {
    const storage = connectedStorage();
    const pending = defer<{ taskRef: string }>();
    const oldPort: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(() => pending.promise),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "unused" })),
    };
    const first = render(
      <CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={oldPort} />,
    );
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    await waitFor(() => expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([expect.objectContaining({
        id: "generation-1",
        taskState: "submitting",
      })]) },
    }));
    first.unmount();

    const newPort: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async () => ({ taskRef: "new-task" })),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "new-task" })),
    };
    render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={newPort} />);
    expect(await screen.findByText("上次提交已中断，请重试")).toBeVisible();
    pending.resolve({ taskRef: "old-task" });
    await act(async () => { await pending.promise; });
    expect(screen.queryByText("排队中")).not.toBeInTheDocument();
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    expect((await screen.findAllByText("排队中"))[0]).toBeVisible();
  });

  it("A 到 B 再回 A 时恢复 A 的 pending submit 并忽略迟到结果", async () => {
    const storage = connectedStorage("project-a");
    saveConnectedProject(storage, "project-b");
    const pending = defer<{ taskRef: string }>();
    const port: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn()
        .mockImplementationOnce(() => pending.promise)
        .mockResolvedValueOnce({ taskRef: "retry-task" }),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "unused" })),
    };
    const view = render(
      <CanvasWorkspace projectId="project-a" storage={storage} mediaTaskPort={port} />,
    );
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    await waitFor(() => expect(screen.getAllByText("正在提交")[0]).toBeVisible());
    view.rerender(<CanvasWorkspace projectId="project-b" storage={storage} mediaTaskPort={port} />);
    await waitFor(() => expect(screen.getByText("待生成")).toBeVisible());
    view.rerender(<CanvasWorkspace projectId="project-a" storage={storage} mediaTaskPort={port} />);

    expect(await screen.findByText("上次提交已中断，请重试")).toBeVisible();
    pending.resolve({ taskRef: "late-task" });
    await act(async () => { await pending.promise; });
    expect(screen.queryByText("排队中")).not.toBeInTheDocument();
    await waitForQuote();
    fireEvent.click(screen.getByRole("button", { name: "提交生成任务" }));
    expect((await screen.findAllByText("排队中"))[0]).toBeVisible();
  });

  it("端口热替换后旧读取结果不能写入新端口作用域", async () => {
    const storage = connectedStorage();
    const loaded = loadCanvasDraft(storage, "project-1", 1000);
    if (loaded.kind !== "loaded") throw new Error("fixture not loaded");
    saveCanvasDraft(storage, updateGenerationTask(
      loaded.document,
      "generation-1",
      { taskRef: "task-1", taskState: "working", message: null },
      "t1",
    ));
    const pending = defer<{
      state: "succeeded";
      taskRef: string;
      outputs: readonly [];
    }>();
    const oldPort: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async () => ({ taskRef: "unused" })),
      read: vi.fn(() => pending.promise),
    };
    const newPort: MediaTaskPort = {
      options: async () => generationOptions,
      quote: async () => ({ points: "12.3400" }),
      submit: vi.fn(async () => ({ taskRef: "unused" })),
      read: vi.fn(async () => ({ state: "working" as const, taskRef: "task-1" })),
    };
    const view = render(
      <CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={oldPort} />,
    );
    readGeneration();
    view.rerender(
      <CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={newPort} />,
    );

    pending.resolve({ state: "succeeded", taskRef: "task-1", outputs: [] });
    await act(async () => { await pending.promise; });

    expect(screen.getAllByText("正在生成")[0]).toBeVisible();
    expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([expect.objectContaining({
        id: "generation-1",
        taskRef: "task-1",
        taskState: "working",
      })]) },
    });
  });

  it("项目切换不会把旧文档写入新项目草稿", async () => {
    const storage = connectedStorage("project-1");
    const view = render(<CanvasWorkspace projectId="project-1" storage={storage} />);
    fireEvent.change(screen.getByLabelText("提示词"), { target: { value: "项目一修改" } });

    view.rerender(<CanvasWorkspace projectId="project-2" storage={storage} />);
    await waitFor(() => expect(screen.queryByLabelText("提示词")).not.toBeInTheDocument());
    expect(loadCanvasDraft(storage, "project-1", 1000)).toMatchObject({
      kind: "loaded",
      document: { nodes: expect.arrayContaining([
        expect.objectContaining({ id: "text-1", text: "项目一修改" }),
      ]) },
    });
    expect(loadCanvasDraft(storage, "project-2", 1000)).toMatchObject({
      kind: "loaded",
      document: { projectId: "project-2", nodes: [] },
    });
  });

  it("保存失败播报本地草稿未保存", async () => {
    render(<CanvasWorkspace projectId="project-1" storage={new FailingWriteStorage()} />);

    fireEvent.click(screen.getByRole("button", { name: "新增文本节点" }));
    expect(await screen.findByRole("alert", { name: "画布保存状态" }))
      .toHaveTextContent("本地草稿未保存");
  });

  it("localStorage getter 抛 SecurityError 时生产组件进入会话草稿", () => {
    const previous = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
    Object.defineProperty(globalThis, "localStorage", {
      configurable: true,
      get: () => { throw new DOMException("blocked", "SecurityError"); },
    });
    try {
      expect(() => render(<CanvasWorkspace projectId="project-1" />)).not.toThrow();
      expect(screen.getByRole("status", { name: "画布保存状态" }))
        .toHaveTextContent("会话草稿");
    } finally {
      if (previous) Object.defineProperty(globalThis, "localStorage", previous);
      else delete (globalThis as { localStorage?: Storage }).localStorage;
    }
  });
});

function chooseCanvasOption(trigger: HTMLElement, value: string) {
  fireEvent.click(trigger);
  const option = within(screen.getByRole('dialog')).getAllByRole('button').find(button => button.getAttribute('value') === value);
  expect(option).toBeDefined();
  fireEvent.click(option!);
}

it.each(['source', 'target', 'handle'] as const)('rejects video to image via %s without hiding the image entry', direction => {
  let doc = addGenerationNode(createCanvasDocument('types', 't0'), 'image', { x: 0, y: 0 }, 't0');
  doc = updateGenerationSettings(addGenerationNode(doc, 'video', { x: 500, y: 0 }, 't0'), 'video', copyGenerationDefaults('VIDEO'), 't0');
  const changed = vi.fn();
  render(<CanvasWorkspace projectId="types" initialDocument={doc} storage={null} onDocumentChange={changed} />);
  if (direction === 'handle') act(() => (controlledFlow.props!.onConnect as Function)({ source: 'video', target: 'image' }));
  else act(() => (controlledFlow.props!.onConnectEnd as Function)(
    { target: screen.getByTestId(direction === 'source' ? 'node-image' : 'node-video'), clientX: 500, clientY: 150 },
    { isValid: false, fromNode: { id: direction === 'source' ? 'video' : 'image' }, fromHandle: { type: direction }, toNode: null },
  ));
  expect(changed).not.toHaveBeenCalled();
  expect(within(screen.getByTestId('node-image')).getByRole('button', { name: '图生图片' })).toBeInTheDocument();
});

it.each(['source', 'target'] as const)('hides incompatible new references from the %s drag menu', direction => {
  const doc = updateGenerationSettings(addGenerationNode(createCanvasDocument('types', 't0'), 'origin', { x: 0, y: 0 }, 't0'), 'origin', copyGenerationDefaults(direction === 'source' ? 'VIDEO' : 'IMAGE'), 't0');
  render(<CanvasWorkspace projectId="types" initialDocument={doc} storage={null} />);
  act(() => (controlledFlow.props!.onConnectEnd as Function)(
    { target: screen.getByLabelText('受控画布适配器'), clientX: 500, clientY: 150 },
    { isValid: false, fromNode: { id: 'origin' }, fromHandle: { type: direction }, toNode: null },
  ));
  expect(within(screen.getByRole('menu', { name: '添加节点' })).getAllByRole('menuitem').map(item => item.textContent)).toEqual(direction === 'source' ? ['视频'] : ['图片', '文本']);
});

it.each(['IMAGE', 'VIDEO'] as const)('API账户画布%s禁用生成但仍可读取已有任务', async type => {
  vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ accountType: 'API' } as never);
  let initial = updateGenerationSettings(addGenerationNode(createCanvasDocument('api-canvas', 't0'), 'generation-1', { x: 0, y: 0 }, 't0'), 'generation-1', copyGenerationDefaults(type), 't0');
  initial = updateGenerationTask(initial, 'generation-1', { taskRef: 'existing-task', taskState: 'working', message: null }, 't0');
  initial = updateGenerationPrompt(updateGenerationSettings(addGenerationNode(initial, 'new-task', { x: 500, y: 0 }, 't0'), 'new-task', copyGenerationDefaults(type), 't0'), 'new-task', '不应提交的生成', 't0');
  const port = generationPort();
  render(<CanvasWorkspace projectId="api-canvas" initialDocument={initial} storage={null} mediaTaskPort={port} />);
  expect((await screen.findAllByText(`当前用户为api用户，可在查看媒体记录，但不能生成${type === 'IMAGE' ? '图片' : '视频'}。`))[0]).toBeVisible();
  await waitFor(() => expect(port.quote).toHaveBeenCalled());
  for (const button of screen.getAllByRole('button', { name: '提交生成任务' })) expect(button).toBeDisabled();
  readGeneration();
  await waitFor(() => expect(port.read).toHaveBeenCalled());
  const nodes = controlledFlow.props!.nodes as { id: string; data: { onSubmitGeneration(id: string): void } }[];
  act(() => nodes.find(node => node.id === 'new-task')!.data.onSubmitGeneration('new-task'));
  await act(async () => {});
  expect(port.submit).not.toHaveBeenCalled();
});

it('API账户刷新期间仍禁止画布未确认请求重试', async () => {
  const session = vi.spyOn(userSession, 'useOptionalUserSession').mockReturnValue({ accountType: 'PERSONAL', status: 'ready', authenticated: true } as never);
  const port = generationPort({ submit: vi.fn().mockRejectedValue(new TypeError('disconnected')) });
  const storage = connectedStorage();
  const view = render(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} production />);
  await waitForQuote();
  fireEvent.click(screen.getByRole('button', { name: '提交生成任务' }));
  const retry = await screen.findByRole('button', { name: '使用原请求重试 generation-1' });
  expect(port.submit).toHaveBeenCalledTimes(1);
  for (const account of [{ accountType: 'API', status: 'ready', authenticated: true }, { accountType: null, status: 'loading', authenticated: false }, { accountType: 'API', status: 'ready', authenticated: true }]) {
    session.mockReturnValue(account as never);
    view.rerender(<CanvasWorkspace projectId="project-1" storage={storage} mediaTaskPort={port} production />);
    expect(retry).toBeDisabled();
    const nodes = controlledFlow.props!.nodes as { id: string; data: { onSubmitGeneration(id: string): void } }[];
    act(() => nodes.find(node => node.id === 'generation-1')!.data.onSubmitGeneration('generation-1'));
    await act(async () => {});
    expect(port.submit).toHaveBeenCalledTimes(1);
  }
});
