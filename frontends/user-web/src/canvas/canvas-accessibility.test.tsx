import "@testing-library/jest-dom/vitest";
import { readFileSync } from "node:fs";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import { createMemoryRouter } from "react-router-dom";
import { App } from "../App";
import { userRoutes } from "../router";
import {
  addGenerationNode,
  addTextNode,
  connectNodes,
  createCanvasDocument,
  updateGenerationTask,
  updateTextNode,
} from "./document";
import { canvasContent } from "./production-api";
import { configureGateway } from "../api";
import { ProductionCanvas } from "./ProductionCanvas";

expect.extend(matchers);

const canvasStyles = readFileSync("src/styles.css", "utf8");

const canvasRect = {
  x: 0,
  y: 0,
  top: 0,
  right: 1000,
  bottom: 640,
  left: 0,
  width: 1000,
  height: 640,
  toJSON: () => ({}),
};

beforeEach(() => {
  sessionStorage.clear();
  vi.stubGlobal("DOMMatrixReadOnly", class {
    readonly m22 = 1;
  });
  vi.stubGlobal("ResizeObserver", class {
    readonly callback: ResizeObserverCallback;
    constructor(callback: ResizeObserverCallback) {
      this.callback = callback;
    }
    observe(target: Element) {
      queueMicrotask(() => {
        this.callback([{
          target,
          contentRect: canvasRect,
          borderBoxSize: [],
          contentBoxSize: [],
          devicePixelContentBoxSize: [],
        }], this as unknown as ResizeObserver);
      });
    }
    unobserve() {}
    disconnect() {}
  });
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect")
    .mockImplementation(() => canvasRect);
  vi.spyOn(HTMLElement.prototype, "offsetWidth", "get").mockReturnValue(260);
  vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockReturnValue(180);
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(function (this: HTMLCanvasElement) {
    return {
      canvas: this,
      clearRect: () => undefined,
      fillText: () => undefined,
      getImageData: (_x: number, _y: number, width: number, height: number) => {
        const data = new Uint8ClampedArray(Math.max(4, Math.ceil(width * height * 4)));
        data[3] = 255;
        return { data };
      },
      measureText: (text: string) => ({ width: Math.max(10, text.length * 10) }),
    } as unknown as CanvasRenderingContext2D;
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("canvas production accessibility", () => {
  const showProductionCanvas = (draft = false, content = canvasContent(validDraft())) => {
    configureGateway({});
    vi.stubGlobal("fetch", vi.fn(async (url: string) => new Response(JSON.stringify(
      url.endsWith("/access-url") ? { url: "https://cdn.example.test/result.png", expiresAt: null } : {
        id: "default", name: "服务端画布", version: 1, ownerId: "user-1", deleted: false,
        createdAt: "2026-09-08", updatedAt: "2026-09-08", content,
      }
    ), { status: 200, headers: { "Content-Type": "application/json" } })));
    const router = createMemoryRouter(userRoutes, { initialEntries: [draft ? "/canvas" : "/canvas?id=default"] });
    return { router, view: render(<App router={router} />) };
  };

  const validDraft = () => {
    let document = addTextNode(
      createCanvasDocument("default", "t0"),
      "text-1",
      { x: 0, y: 0 },
      "t0",
    );
    document = updateTextNode(document, "text-1", "雨夜黑猫", "t0");
    document = addGenerationNode(document, "generation-1", { x: 300, y: 0 }, "t0");
    document = updateGenerationTask(document, "generation-1", {
      taskRef: "task-1",
      taskState: "succeeded",
      message: null,
    }, "t0");
    document = connectNodes(document, "text-1", "generation-1", "t0");
    return {
      ...document,
      nodes: [
        ...document.nodes,
        {
          id: "image-1",
          kind: "image" as const,
          version: 1 as const,
          position: { x: 600, y: 0 },
          sourceTaskRef: "task-1",
          asset: {
            assetRef: "asset-1",
            kind: "image" as const,
            alt: "本地结果",
            previewRef: "https://cdn.example.test/result.png",
          },
        },
      ],
      edges: [
        ...document.edges,
        { id: "generation-1->image-1", source: "generation-1", target: "image-1" },
      ],
    };
  };

  const audit = async (element: Element) => {
    expect(await axe(element)).toHaveNoViolations();
  };

  it("全屏画布保留移动端工具栏、键盘焦点和减少动画样式", () => {
    expect(canvasStyles).toMatch(/\.canvas-workspace\s*\{[^}]*height:\s*100dvh/);
    expect(canvasStyles).toMatch(/@media\s*\(max-width:\s*760px\)[\s\S]*?\.canvas-toolbar/);
    expect(canvasStyles).toMatch(/@media\s*\(prefers-reduced-motion:\s*reduce\)[\s\S]*?\.canvas-workspace/);
    expect(canvasStyles).toMatch(/\.canvas-node button:focus-visible[^}]*outline:\s*2px solid var\(--cyan\)/);
    expect(canvasStyles).toMatch(/\.canvas-stage \.react-flow__handle::after\s*\{[^}]*width:\s*44px[^}]*height:\s*44px/);
  });

  it("编辑器组件的未创建会话草稿零网络且无 axe 违规", async () => {
    const fetchSpy = vi.fn(() => { throw new Error("fetch disabled"); });
    const xhrSpy = vi.fn(function OfflineXmlHttpRequest() {
      return { open: () => { throw new Error("xhr disabled"); } };
    });
    const webSocketSpy = vi.fn(() => { throw new Error("websocket disabled"); });
    vi.stubGlobal("fetch", fetchSpy);
    vi.stubGlobal("XMLHttpRequest", xhrSpy);
    vi.stubGlobal("WebSocket", webSocketSpy);

    const router = createMemoryRouter([{ path: "/canvas", element: <ProductionCanvas /> }], { initialEntries: ["/canvas"] });
    const view = render(<App router={router} fixture />);
    fireEvent.click(screen.getByRole("button", { name: "新增生成占位节点" }));
    expect(await screen.findByRole("button", { name: "上传图片" })).toBeInTheDocument();

    const result = await axe(view.container);
    expect(result).toHaveNoViolations();
    expect(screen.getByRole("heading", { name: "创作画布" })).toBeVisible();
    expect(await screen.findByRole("button", { name: "提交生成任务" })).toBeDisabled();
    expect(screen.getByRole("status", { name: "画布保存状态" }))
      .toHaveTextContent("未保存的会话草稿");
    expect(router.state.location.pathname).toBe("/canvas");
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(xhrSpy).not.toHaveBeenCalled();
    expect(webSocketSpy).not.toHaveBeenCalled();
  });

  it("真实画布普通滚轮只上下移动，Ctrl滚轮缩放", async () => {
    const { view } = showProductionCanvas();
    await waitFor(() => expect(screen.getByLabelText("提示词")).toBeVisible());
    const pane = view.container.querySelector(".react-flow__pane")!;
    const viewport = view.container.querySelector<HTMLElement>(".react-flow__viewport")!;
    const before = viewport.style.transform;
    fireEvent.wheel(pane, { deltaY: 100, deltaX: 30, clientX: 500, clientY: 300 });
    await waitFor(() => expect(viewport.style.transform).not.toBe(before));
    expect(viewport.style.transform).toBe("translate(0px,-50px) scale(1)");
    fireEvent.keyDown(window, { key: "Control", code: "ControlLeft", ctrlKey: true });
    fireEvent.wheel(pane, { deltaY: -100, ctrlKey: true, clientX: 500, clientY: 300 });
    await waitFor(() => expect(viewport.style.transform).not.toContain("scale(1)"));
    fireEvent.keyUp(window, { key: "Control", code: "ControlLeft" });
    const zoomedScale = viewport.style.transform.match(/scale\(([^)]+)\)/)![1];
    fireEvent.wheel(pane, { deltaY: 100, clientX: 500, clientY: 300 });
    expect(viewport.style.transform).toContain(`scale(${zoomedScale})`);
  });

  it("真实路由从 Canvas 渲染三类节点和服务端版本且无 axe 违规", async () => {
    const { view } = showProductionCanvas();

    await waitFor(() => expect(screen.getByLabelText("提示词")).toBeVisible());
    expect(screen.getByRole("img", { name: "本地结果" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "图片生成" })).toBeVisible();
    await waitFor(() => expect(screen.getByRole("status", { name: "画布保存状态" }))
      .toHaveTextContent("已加载 · 版本 1"));
    await audit(view.container);
  });

  it("真实 React Flow 鼠标选择与追加多选会回写受控节点状态", async () => {
    showProductionCanvas();

    const textArticle = await screen.findByRole("article", {
      name: "文本节点 text-1",
      description: "未选择",
    });
    const textFlowNode = textArticle.closest(".react-flow__node");
    expect(textFlowNode).not.toBeNull();
    fireEvent.click(textFlowNode as Element);
    await screen.findByRole("article", {
      name: "文本节点 text-1",
      description: "已选择",
    });

    const generationArticle = screen.getByRole("article", {
      name: "生成节点 generation-1",
      description: "未选择",
    });
    const generationFlowNode = generationArticle.closest(".react-flow__node");
    expect(generationFlowNode).not.toBeNull();
    fireEvent.keyDown(document, { key: "Shift", code: "ShiftLeft" });
    fireEvent.click(generationFlowNode as Element, { shiftKey: true });
    fireEvent.keyUp(document, { key: "Shift", code: "ShiftLeft" });

    await screen.findByRole("article", {
      name: "文本节点 text-1",
      description: "已选择",
    });
    await screen.findByRole("article", {
      name: "生成节点 generation-1",
      description: "已选择",
    });
  });

  it.each(['IMAGE', 'VIDEO'] as const)('%s 入口卡片空白区域可真实拖动，按钮只处理点击', async type => {
    const initial = addGenerationNode(createCanvasDocument('default', 't0'), 'entry', { x: 300, y: 40 }, 't0');
    initial.nodes = initial.nodes.map(node => node.kind === 'generation' ? { ...node, settings: { ...node.settings, type } } : node);
    showProductionCanvas(false, canvasContent(initial));
    const card = await screen.findByTestId('node-entry');
    const flow = card.closest('.react-flow__node') as HTMLElement;
    await waitFor(() => expect(flow).toHaveStyle({ visibility: 'visible' }));
    const before = flow.style.transform;
    const drag = (target: Element) => {
      for (const [eventType, element, x, y, buttons] of [
        ['mousedown', target, 100, 100, 1], ['mousemove', window, 110, 110, 1],
        ['mousemove', window, 140, 150, 1], ['mouseup', window, 140, 150, 0],
      ] as const) {
        const event = new MouseEvent(eventType, { bubbles: true, button: 0, buttons, clientX: x, clientY: y });
        Object.defineProperty(event, 'view', { value: window });
        fireEvent(element, event);
      }
    };
    drag(card.querySelector('.canvas-image-entry')!);
    await waitFor(() => expect(flow.style.transform).not.toBe(before));
    const moved = flow.style.transform;
    for (const button of within(card).getAllByRole('button')) {
      drag(button);
      expect(flow.style.transform).toBe(moved);
    }
    fireEvent.click(within(card).getByRole('button', { name: type === 'IMAGE' ? '图生图片' : '图生视频' }));
    expect(within(card).getByRole('textbox', { name: '生成提示词' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(2));
  });

  it("左键框选多个节点后整体拖动并批量删除", async () => {
    vi.stubGlobal("PointerEvent", class extends MouseEvent {
      readonly isPrimary = true;
      readonly pointerId = 1;
    });
    let initial = validDraft();
    initial.nodes = initial.nodes.map(node => ({ ...node, position: { x: node.position.x + 40, y: 40 } }));
    initial = addTextNode(initial, "outside", { x: 760, y: 40 }, "t1");
    const { view } = showProductionCanvas(false, canvasContent(initial));
    const text = await screen.findByTestId("node-text-1");
    const generation = screen.getByTestId("node-generation-1");
    const outside = screen.getByTestId("node-outside");
    const pane = view.container.querySelector(".react-flow__pane")!;
    await waitFor(() => expect(text.closest(".react-flow__node")).toHaveStyle({ visibility: "visible" }));
    fireEvent.pointerDown(pane, { button: 0, buttons: 1, clientX: 10, clientY: 10 });
    fireEvent.pointerMove(pane, { buttons: 1, clientX: 620, clientY: 240 });
    expect(view.container.querySelector(".react-flow__selection")).not.toBeNull();
    fireEvent.pointerUp(pane, { button: 0, clientX: 620, clientY: 240 });
    await waitFor(() => {
      expect(text).toHaveAttribute("data-selected", "true");
      expect(generation).toHaveAttribute("data-selected", "true");
      expect(outside).toHaveAttribute("data-selected", "false");
    });
    const textFlow = text.closest(".react-flow__node") as HTMLElement;
    const generationFlow = generation.closest(".react-flow__node") as HTMLElement;
    const beforeText = textFlow.style.transform;
    const beforeGeneration = generationFlow.style.transform;
    const selection = view.container.querySelector(".react-flow__nodesselection-rect")!;
    for (const [type, target, x, y, buttons] of [
      ["mousedown", selection, 100, 100, 1], ["mousemove", window, 110, 110, 1], ["mousemove", window, 140, 150, 1], ["mouseup", window, 140, 150, 0],
    ] as const) {
      const event = new MouseEvent(type, { bubbles: true, button: 0, buttons, clientX: x, clientY: y });
      Object.defineProperty(event, "view", { value: window });
      fireEvent(target, event);
    }
    await waitFor(() => {
      expect(textFlow.style.transform).not.toBe(beforeText);
      expect(generationFlow.style.transform).not.toBe(beforeGeneration);
      expect(textFlow.style.transform).toBe("translate(70px,80px)");
      expect(generationFlow.style.transform).toBe("translate(370px,80px)");
      expect((outside.closest(".react-flow__node") as HTMLElement).style.transform).toBe("translate(760px,40px)");
    });
    fireEvent.keyDown(selection, { key: "Delete" });
    await waitFor(() => {
      expect(screen.queryByTestId("node-text-1")).not.toBeInTheDocument();
      expect(screen.queryByTestId("node-generation-1")).not.toBeInTheDocument();
      expect(screen.getByTestId("node-outside")).toBeInTheDocument();
    });
  });

  it("真实详情路由在 localStorage getter 抛错时加载服务端画布且无 axe 违规", async () => {
    const previous = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
    let view!: ReturnType<typeof render>;
    try {
      Object.defineProperty(globalThis, "localStorage", {
        configurable: true,
        get: () => { throw new DOMException("blocked", "SecurityError"); },
      });
      view = showProductionCanvas().view;
      await waitFor(() => expect(screen.getByRole("status", { name: "画布保存状态" }))
        .toHaveTextContent("已加载 · 版本 1"));
    } finally {
      if (previous) {
        Object.defineProperty(globalThis, "localStorage", previous);
      } else {
        delete (globalThis as { localStorage?: Storage }).localStorage;
      }
    }

    expect(screen.getByRole("status", { name: "画布保存状态" })).toHaveTextContent("已加载 · 版本 1");
    await audit(view.container);
  });

  it("真实路由加载失败显示可恢复告警且无 axe 违规", async () => {
    const { view } = showProductionCanvas();
    vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status: 502, headers: { "Content-Type": "application/json" } })));
    // Mount a new route scope to exercise the failing request.
    cleanup();
    const router = createMemoryRouter(userRoutes, { initialEntries: ["/canvas?id=unavailable"] });
    const failed = render(<App router={router} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("暂不可用");
    expect(screen.getByRole("button", { name: "重试加载" })).toBeVisible();
    await audit(failed.container);
  });
});
