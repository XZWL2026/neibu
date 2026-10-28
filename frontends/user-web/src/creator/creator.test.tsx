import "@testing-library/jest-dom/vitest";
import { readFileSync } from "node:fs";
import { act, cleanup, createEvent, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { StrictMode, useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import * as axeMatchers from "vitest-axe/matchers";
import { createMemoryRouter, MemoryRouter, RouterProvider } from "react-router-dom";

import { LocalPromotionProvider } from "../promotion/localPromotionViewState";
import { LocalInvitationProvider } from "../promotion/localInvitationProfile";
import { UserFixtureProvider } from "../userSession";
import { CreatorMediaProvider, useCreatorMedia } from "./CreatorMediaProvider";
import { CreatorComposer } from "./CreatorComposer";
import { MediaQueue } from "./MediaQueue";
import { MediaLibraryDialog } from "./MediaLibraryDialog";
import { MediaTrigger } from "./MediaTrigger";
import { publicCreatorMedia } from "./mediaFixtures";
import type { CreatorMedia, LocalMediaRepositoryOpenState, MediaInputCapability } from "./types";

expect.extend(axeMatchers);
const creatorStyles = readFileSync("src/styles.css", "utf8");
const creatorMediaProviderSource = readFileSync("src/creator/CreatorMediaProvider.tsx", "utf8");
const audit = async (node: Element) => (expect(await axe(node, { rules: { "color-contrast": { enabled: false } } })) as unknown as { toHaveNoViolations(): void }).toHaveNoViolations();

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
};

class OfflineXmlHttpRequest {
  open(): void {}
}

// Historical fixture pricing and local materials are tested outside production routes.
const renderLocalImage = (accountType?: "PERSONAL" | "ENTERPRISE") => render(
  <CreatorMediaProvider accountType={accountType} publicMedia={publicCreatorMedia}><CreatorComposer kind="image" /></CreatorMediaProvider>
);

// Historical local video behavior remains independent of the production route.
const renderLocalVideo = () => render(<CreatorMediaProvider publicMedia={publicCreatorMedia}><CreatorComposer kind="video" /></CreatorMediaProvider>);

describe("creator shared provider", () => {
  it("生产 Provider 泛型不使用 Result 后缀命名", () => {
    expect(creatorMediaProviderSource).not.toMatch(/\b[A-Za-z_$]*Result\b/);
  });

  it.each(["forced", "unavailable"] as const)("%s session 初始化按当前存储模式标记已有素材", async (reason) => {
    const repository = {
      list: vi.fn(async () => [{ ...oldPng, storageMode: "persistent" as const }]),
      put: vi.fn(async () => {}),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    function Probe() {
      const { storage, personalMedia } = useCreatorMedia();
      return <output aria-label="hydrate storage mode">{`${storage?.storageMode ?? "pending"}:${personalMedia[0]?.storageMode ?? "empty"}`}</output>;
    }

    render(<CreatorMediaProvider storage={{ storageMode: "session", reason, repository }}><Probe /></CreatorMediaProvider>);

    expect(await screen.findByText("session:session")).toBeVisible();
  });

  it("persistent 初始化才把已有素材标记为 persistent", async () => {
    const { storage, repository } = createTestStorage();
    repository.list.mockResolvedValue([{ ...oldPng, storageMode: "session" }]);
    function Probe() {
      const { personalMedia } = useCreatorMedia();
      return <output>{personalMedia[0]?.storageMode ?? "empty"}</output>;
    }

    render(<CreatorMediaProvider storage={storage}><Probe /></CreatorMediaProvider>);

    expect(await screen.findByText("persistent")).toBeVisible();
  });

  it("StrictMode 重放 effect 后上传队列仍可工作", async () => {
    const { storage, repository } = createTestStorage();
    render(<StrictMode><MediaHarness storage={storage} initial={[]} publicMedia={[]} /></StrictMode>);
    await waitFor(() => expect(repository.list).toHaveBeenCalled());

    fireEvent.change(screen.getByLabelText("添加素材文件"), {
      target: { files: [new File(["strict"], "strict.png", { type: "image/png" })] },
    });

    expect(await screen.findByLabelText("已选素材 strict.png")).toBeVisible();
  });

  it("持久存储 open 完成前的导入等待 ready session 且不会丢失", async () => {
    const opened = deferred<LocalMediaRepositoryOpenState>();
    const { storage, repository } = createTestStorage();
    const imported = vi.fn();
    function Probe() {
      const { importMedia, personalMedia } = useCreatorMedia();
      return <><button type="button" onClick={() => void importMedia(oldPng).then(imported)}>导入等待素材</button><output>{personalMedia.map((item) => item.name).join(",")}</output></>;
    }
    render(<CreatorMediaProvider openStorage={() => opened.promise}><Probe /></CreatorMediaProvider>);

    fireEvent.click(screen.getByRole("button", { name: "导入等待素材" }));
    expect(repository.put).not.toHaveBeenCalled();
    opened.resolve(storage);

    await waitFor(() => expect(repository.put).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("old.png")).toBeVisible();
    expect(imported).toHaveBeenCalledWith(expect.objectContaining({ id: oldPng.id }));
  });

  it("storage prop 切换后旧 session 的迟到 initialize 不覆盖新状态", async () => {
    const oldList = deferred<readonly CreatorMedia[]>();
    const oldRepository = {
      list: vi.fn(() => oldList.promise),
      put: vi.fn(async () => {}),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    const fresh = { ...oldPng, id: "fresh", name: "fresh.png", content: new Blob(["fresh"], { type: "image/png" }) };
    const freshStorage = createTestStorage();
    freshStorage.repository.list.mockResolvedValue([fresh]);
    function Probe() {
      const { personalMedia } = useCreatorMedia();
      return <output>{personalMedia.map((item) => item.name).join(",")}</output>;
    }
    function Switcher() {
      const [storage, setStorage] = useState<LocalMediaRepositoryOpenState>({ storageMode: "persistent", repository: oldRepository });
      return <><button type="button" onClick={() => setStorage(freshStorage.storage)}>切换存储</button><CreatorMediaProvider storage={storage}><Probe /></CreatorMediaProvider></>;
    }
    render(<Switcher />);
    await waitFor(() => expect(oldRepository.list).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: "切换存储" }));
    expect(await screen.findByText("fresh.png")).toBeVisible();

    oldList.resolve([{ ...oldPng, name: "late-old.png", content: new Blob(["old"], { type: "image/png" }) }]);
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(screen.getByText("fresh.png")).toBeVisible();
    expect(screen.queryByText("late-old.png")).not.toBeInTheDocument();
    expect(oldRepository.close).toHaveBeenCalledTimes(1);
  });

  it("persistent open 后 initialize 失败会关闭旧仓库并降级为可用 session", async () => {
    const repository = {
      list: vi.fn(async () => { throw new Error("controlled initialize failure"); }),
      put: vi.fn(async () => {}),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    function Probe() {
      const { storage, notice } = useCreatorMedia();
      const [selected, setSelected] = useState<readonly CreatorMedia[]>([]);
      const reason = storage?.storageMode === "session" ? storage.reason : "";
      return <>
        <output aria-label="存储状态">{storage ? `${storage.storageMode}:${reason}:${notice?.kind ?? "none"}` : "pending"}</output>
        <MediaTrigger capability={imageCapability} label="添加素材" selectionMode="multiple" selected={selected} onValidated={setSelected} onOpenLibrary={() => {}} />
        <MediaQueue mode="omni" items={selected} onRemove={() => {}} onManageOverflow={() => {}} />
      </>;
    }
    render(<CreatorMediaProvider openStorage={async () => ({ storageMode: "persistent", repository })}><Probe /></CreatorMediaProvider>);

    expect(await screen.findByText("session:open-failed:session-fallback")).toBeVisible();
    expect(repository.close).toHaveBeenCalledTimes(1);
    fireEvent.change(screen.getByLabelText("添加素材文件"), {
      target: { files: [new File(["fallback"], "fallback.png", { type: "image/png" })] },
    });
    expect(await screen.findByLabelText("已选素材 fallback.png")).toBeVisible();
  });

  it("默认个人账户与测试注入企业账户使用不同本地积分估算", async () => {
    const personal = renderLocalImage();
    expect(await personal.findByText("预计 1.2 积分")).toBeVisible();
    personal.unmount();

    renderLocalImage("ENTERPRISE");
    expect(await screen.findByText("预计 1 积分")).toBeVisible();
  });


});

const imageCapability: MediaInputCapability = {
  acceptedKinds: ["image"],
  acceptedMimeTypes: ["image/jpeg", "image/png"],
  acceptedSources: ["upload", "drop", "library"],
  maxFileBytes: 20 * 1024 * 1024,
  maxItems: null,
  verified: true,
};

const oldPng: CreatorMedia = {
  id: "old",
  kind: "image",
  name: "old.png",
  mimeType: "image/png",
  size: 3,
  origin: "personal",
  storageMode: "session",
  previewUrl: "blob:old",
};

const badLibraryMedia: CreatorMedia = {
  id: "bad-library",
  kind: "image",
  name: "bad.unsupported",
  mimeType: "image/png",
  size: 21 * 1024 * 1024,
  origin: "public",
};

const createTestStorage = (put = vi.fn(async () => {})) => {
  const remove = vi.fn(async () => {});
  const repository = {
    list: vi.fn(async () => [] as readonly CreatorMedia[]),
    put,
    remove,
    close: vi.fn(),
  };
  return {
    storage: { storageMode: "persistent", repository } as LocalMediaRepositoryOpenState,
    repository,
  };
};

function MediaHarness({
  storage,
  initial = [oldPng],
  publicMedia = [badLibraryMedia],
  capability = imageCapability,
  selectionMode = "multiple",
  createMediaId,
}: {
  storage: LocalMediaRepositoryOpenState;
  initial?: readonly CreatorMedia[];
  publicMedia?: readonly CreatorMedia[];
  capability?: MediaInputCapability;
  selectionMode?: "single" | "multiple";
  createMediaId?: () => string;
}) {
  const [selected, setSelected] = useState<readonly CreatorMedia[]>(initial);
  return (
    <CreatorMediaProvider storage={storage} publicMedia={publicMedia}>
      <MediaTrigger
        capability={capability}
        label="添加素材"
        selectionMode={selectionMode}
        createMediaId={createMediaId}
        selected={selected}
        onValidated={setSelected}
        onOpenLibrary={() => {}}
      />
      <MediaQueue mode="omni" items={selected} onRemove={(id) => setSelected((items) => items.filter((item) => item.id !== id))} onManageOverflow={() => {}} />
    </CreatorMediaProvider>
  );
}

const unsupportedFile = () => new File(["bad"], "bad.unsupported", { type: "application/x-test-unsupported" });

describe("creator media entry and queue", () => {
  it("公开素材只使用内嵌本地预览", () => {
    for (const item of publicCreatorMedia) {
      expect(item.previewUrl).toMatch(/^data:/);
      expect(item.previewUrl).not.toMatch(/^(?:https?:)?\/\//i);
    }
  });

  it("逐个公开图片 fixture 的 data URL MIME 与内容格式一致且具有有效图片边界", () => {
    for (const item of publicCreatorMedia) {
      const match = /^data:([^;,]+);base64,([A-Za-z0-9+/=]+)$/.exec(item.previewUrl ?? "");
      expect(match, item.name).not.toBeNull();
      if (!match) continue;
      expect(match[1], item.name).toBe(item.mimeType);
      const bytes = Uint8Array.from(atob(match[2]), (character) => character.charCodeAt(0));
      expect(bytes.byteLength, item.name).toBeGreaterThan(20);

      if (item.mimeType === "image/png") {
        expect([...bytes.slice(0, 8)], item.name).toEqual([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
        expect(String.fromCharCode(...bytes.slice(12, 16)), item.name).toBe("IHDR");
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        expect(view.getUint32(16), `${item.name} width`).toBeGreaterThan(0);
        expect(view.getUint32(20), `${item.name} height`).toBeGreaterThan(0);
        expect(String.fromCharCode(...bytes.slice(-8, -4)), item.name).toBe("IEND");
      } else {
        expect(item.mimeType, item.name).toBe("image/jpeg");
        expect([...bytes.slice(0, 3)], item.name).toEqual([0xff, 0xd8, 0xff]);
        expect([...bytes.slice(-2)], item.name).toEqual([0xff, 0xd9]);
      }
    }
  });

  it("素材队列与首尾帧槽按类型渲染可见本地预览并拒绝外部协议", () => {
    const image = { ...oldPng, previewUrl: "blob:queue-image" };
    const video: CreatorMedia = {
      ...oldPng,
      id: "queue-video",
      kind: "video",
      name: "queue.mp4",
      mimeType: "video/mp4",
      previewUrl: "data:video/mp4;base64,AA==",
    };
    const audio: CreatorMedia = {
      ...oldPng,
      id: "queue-audio",
      kind: "audio",
      name: "queue.mp3",
      mimeType: "audio/mpeg",
      previewUrl: "blob:queue-audio",
    };
    const unsafe = { ...oldPng, id: "unsafe", name: "unsafe.png", previewUrl: "https://example.invalid/unsafe.png" };
    const queue = render(<MediaQueue mode="omni" items={[image, video, audio, unsafe]} onRemove={() => {}} onManageOverflow={() => {}} />);

    expect(queue.getByRole("img", { name: "old.png" })).toHaveAttribute("src", "blob:queue-image");
    expect(queue.getByLabelText("queue.mp4 预览")).toHaveAttribute("src", "data:video/mp4;base64,AA==");
    expect(queue.getByLabelText("queue.mp3 预览")).toHaveAttribute("src", "blob:queue-audio");
    expect(queue.getByText("unsafe.png")).toBeVisible();
    expect(queue.queryByLabelText("unsafe.png 预览")).not.toBeInTheDocument();
    queue.unmount();

    render(<MediaQueue mode="first-last" items={[image, { ...image, id: "last", name: "last.png", previewUrl: "data:image/png;base64,AA==" }]} onRemove={() => {}} onManageOverflow={() => {}} />);
    expect(screen.getByRole("img", { name: "old.png" })).toHaveAttribute("src", "blob:queue-image");
    expect(screen.getByRole("img", { name: "last.png" })).toHaveAttribute("src", "data:image/png;base64,AA==");
  });

  it.each(["文件选择", "拖放", "素材库"] as const)("%s 校验失败保留原素材且不入库", async (entry) => {
    const { storage, repository } = createTestStorage();
    render(<MediaHarness storage={storage} />);
    const trigger = await screen.findByRole("button", { name: "添加素材" });

    if (entry === "文件选择") {
      fireEvent.click(trigger);
      fireEvent.change(screen.getByLabelText("添加素材文件"), { target: { files: [unsupportedFile()] } });
    } else if (entry === "拖放") {
      fireEvent.drop(trigger, { dataTransfer: { files: [unsupportedFile()] } });
    } else {
      fireEvent.click(trigger);
      fireEvent.click(screen.getByRole("menuitem", { name: "素材库" }));
      fireEvent.click(await screen.findByRole("option", { name: "bad.unsupported" }));
      expect(screen.getByText("素材大小超过当前模型限制", { selector: 'p[role="status"]' })).toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: "关闭素材库" }));
    }

    expect(await screen.findByLabelText("已选素材 old.png")).toBeVisible();
    expect(screen.queryByLabelText("已选素材 bad.unsupported")).not.toBeInTheDocument();
    if (entry !== "素材库") expect(await screen.findByRole("alert")).toBeVisible();
    expect(repository.put).not.toHaveBeenCalled();
  });

  it("Provider session-fallback notice 通过 status 播报", async () => {
    const { repository } = createTestStorage();
    const storage: LocalMediaRepositoryOpenState = {
      storageMode: "session",
      reason: "unavailable",
      repository,
    };
    render(<MediaHarness storage={storage} initial={[]} publicMedia={[]} />);

    expect(await screen.findByRole("status")).toHaveTextContent("持久素材库不可用，当前素材仅保留在本次会话");
  });

  it("合法文件拖放进入时归正并强调入口，离开或投放后恢复", async () => {
    const { storage } = createTestStorage();
    render(<MediaHarness storage={storage} initial={[]} publicMedia={[]} />);
    const trigger = screen.getByRole("button", { name: "添加素材" });
    const droppedFile = new File(["drop"], "dropped.png", { type: "image/png" });
    const fileTransfer = {
      types: ["Files"],
      items: [{ kind: "file", type: "image/png" }],
      files: [droppedFile],
    };

    fireEvent.dragEnter(trigger, { dataTransfer: fileTransfer });
    expect(trigger).toHaveClass("drag-active");
    const dragOver = createEvent.dragOver(trigger, { dataTransfer: fileTransfer });
    fireEvent(trigger, dragOver);
    expect(dragOver.defaultPrevented).toBe(true);
    fireEvent.dragLeave(trigger, { dataTransfer: fileTransfer });
    expect(trigger).not.toHaveClass("drag-active");

    fireEvent.dragEnter(trigger, { dataTransfer: fileTransfer });
    fireEvent.drop(trigger, { dataTransfer: fileTransfer });
    expect(trigger).not.toHaveClass("drag-active");
    expect(await screen.findByLabelText("已选素材 dropped.png")).toBeVisible();
  });

  it.each([
    ["文本", ["text/plain"], "text/plain"],
    ["链接", ["text/uri-list"], "text/uri-list"],
  ] as const)("%s拖放不激活素材入口且不接受 dragOver/drop", (label, types, type) => {
    const { storage } = createTestStorage();
    render(<MediaHarness storage={storage} initial={[]} publicMedia={[]} />);
    const trigger = screen.getByRole("button", { name: "添加素材" });
    const nonFileTransfer = {
      types: [...types],
      items: [{ kind: "string", type }],
      files: [],
    };

    fireEvent.dragEnter(trigger, { dataTransfer: nonFileTransfer });
    expect(trigger).not.toHaveClass("drag-active");
    const dragOver = createEvent.dragOver(trigger, { dataTransfer: nonFileTransfer });
    fireEvent(trigger, dragOver);
    expect(dragOver.defaultPrevented).toBe(false);
    const drop = createEvent.drop(trigger, { dataTransfer: nonFileTransfer });
    fireEvent(trigger, drop);
    expect(drop.defaultPrevented).toBe(false);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("持久写入 reject 时由 Provider 发布 write-failed 并保留会话预览", async () => {
    const put = vi.fn(async () => { throw new Error("controlled write failure"); });
    const { storage } = createTestStorage(put);
    render(<MediaHarness storage={storage} initial={[]} publicMedia={[]} />);
    fireEvent.click(await screen.findByRole("button", { name: "添加素材" }));
    fireEvent.change(screen.getByLabelText("添加素材文件"), {
      target: { files: [new File(["ok"], "ok.png", { type: "image/png" })] },
    });

    const selected = await screen.findByLabelText("已选素材 ok.png");
    expect(selected).toBeVisible();
    expect(selected).toHaveAttribute("data-preview-url", expect.stringContaining("blob:"));
    expect(screen.getByText(/素材未写入持久库/, { selector: 'p[role="status"]' })).toBeInTheDocument();
  });

  it("全能参考超过四项显示可管理的 +N", () => {
    const fiveImages = Array.from({ length: 5 }, (_, index): CreatorMedia => ({
      ...oldPng,
      id: `image-${index + 1}`,
      name: `image-${index + 1}.png`,
    }));
    const onManageOverflow = vi.fn();
    render(<MediaQueue mode="omni" items={fiveImages} onRemove={() => {}} onManageOverflow={onManageOverflow} />);
    const overflow = screen.getByRole("button", { name: "管理其余 1 项素材" });
    expect(overflow).toHaveTextContent("+1");
    fireEvent.click(overflow);
    expect(onManageOverflow).toHaveBeenCalledTimes(1);
  });

  it("素材删除先执行 240ms 退出过渡，完成后才提交移除", async () => {
    const finished = deferred<void>();
    const animate = vi.fn(() => ({ finished: finished.promise }));
    const originalAnimate = HTMLElement.prototype.animate;
    Object.defineProperty(HTMLElement.prototype, "animate", { configurable: true, value: animate });
    try {
      const onRemove = vi.fn();
      render(<MediaQueue mode="omni" items={[oldPng]} onRemove={onRemove} onManageOverflow={() => {}} />);
      fireEvent.click(screen.getByRole("button", { name: "移除 old.png" }));

      expect(animate).toHaveBeenCalledWith(expect.any(Array), expect.objectContaining({ duration: 240 }));
      expect(onRemove).not.toHaveBeenCalled();
      finished.resolve();
      await waitFor(() => expect(onRemove).toHaveBeenCalledWith(oldPng.id));
    } finally {
      if (originalAnimate) Object.defineProperty(HTMLElement.prototype, "animate", { configurable: true, value: originalAnimate });
      else delete (HTMLElement.prototype as { animate?: unknown }).animate;
    }
  });

  it.each(["resolve", "reject"] as const)("删除动画未完成时卸载会 cancel，finished %s 后也不回调", async (settlement) => {
    const finished = deferred<void>();
    const cancel = vi.fn();
    const animate = vi.fn(() => ({ finished: finished.promise, cancel }));
    const originalAnimate = HTMLElement.prototype.animate;
    Object.defineProperty(HTMLElement.prototype, "animate", { configurable: true, value: animate });
    try {
      const onRemove = vi.fn();
      const view = render(<MediaQueue mode="omni" items={[oldPng]} onRemove={onRemove} onManageOverflow={() => {}} />);
      fireEvent.click(screen.getByRole("button", { name: "移除 old.png" }));

      view.unmount();
      expect(cancel).toHaveBeenCalledTimes(1);
      if (settlement === "resolve") finished.resolve();
      else finished.reject(new DOMException("animation cancelled", "AbortError"));
      await act(async () => { await Promise.resolve(); });
      expect(onRemove).not.toHaveBeenCalled();
    } finally {
      if (originalAnimate) Object.defineProperty(HTMLElement.prototype, "animate", { configurable: true, value: originalAnimate });
      else delete (HTMLElement.prototype as { animate?: unknown }).animate;
    }
  });

  it("素材顺序变化时按位置差执行 240ms FLIP 过渡", () => {
    const second = { ...oldPng, id: "second", name: "second.png" };
    const positions = new Map([[oldPng.id, 0], [second.id, 100]]);
    const animate = vi.fn(() => ({ finished: Promise.resolve() }));
    const originalAnimate = HTMLElement.prototype.animate;
    const originalRect = HTMLElement.prototype.getBoundingClientRect;
    Object.defineProperty(HTMLElement.prototype, "animate", { configurable: true, value: animate });
    Object.defineProperty(HTMLElement.prototype, "getBoundingClientRect", { configurable: true, value: function(this: HTMLElement) {
      const left = positions.get(this.dataset.mediaId ?? "") ?? 0;
      return { x: left, y: 0, left, top: 0, right: left + 80, bottom: 44, width: 80, height: 44, toJSON: () => ({}) } as DOMRect;
    } });
    try {
      const view = render(<MediaQueue mode="omni" items={[oldPng, second]} onRemove={() => {}} onManageOverflow={() => {}} />);
      positions.set(oldPng.id, 100);
      positions.set(second.id, 0);
      view.rerender(<MediaQueue mode="omni" items={[second, oldPng]} onRemove={() => {}} onManageOverflow={() => {}} />);

      expect(animate).toHaveBeenCalledWith(expect.any(Array), expect.objectContaining({ duration: 240 }));
    } finally {
      if (originalAnimate) Object.defineProperty(HTMLElement.prototype, "animate", { configurable: true, value: originalAnimate });
      else delete (HTMLElement.prototype as { animate?: unknown }).animate;
      Object.defineProperty(HTMLElement.prototype, "getBoundingClientRect", { configurable: true, value: originalRect });
    }
  });

  it.each(["视频", "音频"] as const)("本地全能参考的%s子能力未验证且不可上传", async (kind) => {
    renderLocalVideo();
    fireEvent.click(await screen.findByRole("button", { name: "全能参考" }));
    expect(screen.getByText(`${kind}能力未验证`)).toBeVisible();
    expect(screen.getByRole("button", { name: `上传${kind}` })).toBeDisabled();
    cleanup();
  });

  it("素材菜单精确只有本地上传和素材库", async () => {
    const { storage } = createTestStorage();
    render(<MediaHarness storage={storage} />);
    fireEvent.click(await screen.findByRole("button", { name: "添加素材" }));
    const menu = screen.getByRole("menu", { name: "素材来源" });
    expect(Array.from(menu.querySelectorAll("button")).map((button) => button.textContent)).toEqual(["本地上传", "素材库"]);
  });

  it("本地首尾帧提供两个独立单选入口并只替换对应槽", async () => {
    renderLocalVideo();
    const firstInput = await screen.findByLabelText("添加首帧文件");
    const lastInput = screen.getByLabelText("添加尾帧文件");
    expect(firstInput).not.toHaveAttribute("multiple");
    expect(lastInput).not.toHaveAttribute("multiple");

    fireEvent.change(firstInput, { target: { files: [new File(["a"], "A.png", { type: "image/png" })] } });
    expect(await screen.findByLabelText("已选素材 A.png")).toBeVisible();
    fireEvent.change(firstInput, { target: { files: [new File(["b"], "B.png", { type: "image/png" })] } });
    expect(await screen.findByLabelText("已选素材 B.png")).toBeVisible();
    expect(screen.queryByLabelText("已选素材 A.png")).not.toBeInTheDocument();

    fireEvent.change(lastInput, { target: { files: [new File(["c"], "C.png", { type: "image/png" })] } });
    expect(await screen.findByLabelText("已选素材 C.png")).toBeVisible();
    expect(screen.getByLabelText("已选素材 B.png")).toBeVisible();
    fireEvent.change(firstInput, { target: { files: [new File(["d"], "D.png", { type: "image/png" })] } });
    expect(await screen.findByLabelText("已选素材 D.png")).toBeVisible();
    expect(screen.getByLabelText("已选素材 C.png")).toBeVisible();
    expect(screen.queryByLabelText("已选素材 B.png")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /管理其余/ })).not.toBeInTheDocument();
  });

  it.each(["添加首帧", "添加尾帧"] as const)("生产%s槽一次拖放多个文件时在持久写入前原子失败", async (label) => {
    const { storage, repository } = createTestStorage();
    render(<UserFixtureProvider><LocalPromotionProvider><LocalInvitationProvider><CreatorMediaProvider storage={storage} publicMedia={publicCreatorMedia}>
      <MemoryRouter initialEntries={["/create/video"]}><CreatorComposer kind="video" /></MemoryRouter>
    </CreatorMediaProvider></LocalInvitationProvider></LocalPromotionProvider></UserFixtureProvider>);
    await waitFor(() => expect(repository.list).toHaveBeenCalledTimes(1));

    fireEvent.drop(screen.getByRole("button", { name: label }), { dataTransfer: { files: [
      new File(["a"], "A.png", { type: "image/png" }),
      new File(["b"], "B.png", { type: "image/png" }),
    ] } });

    expect(await screen.findByRole("alert")).toHaveTextContent("最多选择 1 项素材");
    expect(repository.put).not.toHaveBeenCalled();
    expect(screen.queryByLabelText(/已选素材/)).not.toBeInTheDocument();
  });

  it("本地 +N 打开同一素材库并在关闭后恢复焦点", async () => {
    renderLocalImage();
    const input = await screen.findByLabelText("添加素材文件");
    const files = Array.from({ length: 5 }, (_, index) => new File([String(index)], `queue-${index + 1}.png`, { type: "image/png" }));
    fireEvent.change(input, { target: { files } });
    const overflow = await screen.findByRole("button", { name: "管理其余 1 项素材" });
    fireEvent.click(overflow);
    const dialog = await screen.findByRole("dialog", { name: "素材库" });
    expect(within(dialog).getByText("已选：5")).toBeVisible();
    fireEvent.click(within(dialog).getByRole("button", { name: "关闭素材库" }));
    await waitFor(() => expect(document.activeElement).toBe(overflow));
  });

  it("混合批次验证通过后保留成功持久项并将失败项标为 session 影子", async () => {
    const records = new Map<string, CreatorMedia>();
    let writes = 0;
    const repository = {
      list: vi.fn(async () => [...records.values()]),
      put: vi.fn(async (media: CreatorMedia) => {
        writes += 1;
        if (writes === 2) throw new Error("controlled second write failure");
        records.set(media.id, media);
      }),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    const storage = { storageMode: "persistent", repository } as LocalMediaRepositoryOpenState;
    render(<MediaHarness storage={storage} initial={[]} publicMedia={[]} />);
    const input = await screen.findByLabelText("添加素材文件");
    fireEvent.change(input, { target: { files: [
      new File(["one"], "one.png", { type: "image/png" }),
      new File(["two"], "two.png", { type: "image/png" }),
    ] } });

    const first = await screen.findByLabelText("已选素材 one.png");
    const second = await screen.findByLabelText("已选素材 two.png");
    expect(first).toHaveAttribute("data-storage-mode", "persistent");
    expect(second).toHaveAttribute("data-storage-mode", "session");
    expect([...records.values()].map((item) => item.name)).toEqual(["one.png"]);
    expect(screen.getByText(/素材未写入持久库/, { selector: 'p[role="status"]' })).toBeInTheDocument();
  });

  it("上传使用注入的稳定唯一 ID 且不会覆盖旧 local-media ID", async () => {
    const oldRecord = { ...oldPng, id: "local-media-1", storageMode: "persistent" as const };
    const records = new Map([[oldRecord.id, oldRecord as CreatorMedia]]);
    const repository = {
      list: vi.fn(async () => [...records.values()]),
      put: vi.fn(async (media: CreatorMedia) => { records.set(media.id, media); }),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    const createMediaId = vi.fn(() => "local-media-stable-uuid");
    render(<MediaHarness
      storage={{ storageMode: "persistent", repository }}
      initial={[]}
      publicMedia={[]}
      createMediaId={createMediaId}
    />);
    await waitFor(() => expect(repository.list).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByLabelText("添加素材文件"), {
      target: { files: [new File(["new"], "new.png", { type: "image/png" })] },
    });

    expect(await screen.findByLabelText("已选素材 new.png")).toBeVisible();
    expect(createMediaId).toHaveBeenCalledTimes(1);
    expect([...records.keys()]).toEqual(["local-media-1", "local-media-stable-uuid"]);
  });

  it("两次交付即使 deferred 按逆序 resolve 也串行合并最新选择", async () => {
    const firstWrite = deferred<void>();
    const secondWrite = deferred<void>();
    const repository = {
      list: vi.fn(async () => [] as readonly CreatorMedia[]),
      put: vi.fn()
        .mockImplementationOnce(() => firstWrite.promise)
        .mockImplementationOnce(() => secondWrite.promise),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    render(<MediaHarness storage={{ storageMode: "persistent", repository }} initial={[]} publicMedia={[]} />);
    await waitFor(() => expect(repository.list).toHaveBeenCalledTimes(1));
    const input = screen.getByLabelText("添加素材文件");
    fireEvent.change(input, { target: { files: [new File(["a"], "A.png", { type: "image/png" })] } });
    fireEvent.drop(screen.getByRole("button", { name: "添加素材" }), { dataTransfer: { files: [new File(["b"], "B.png", { type: "image/png" })] } });

    secondWrite.resolve();
    firstWrite.resolve();

    expect(await screen.findByLabelText("已选素材 A.png")).toBeVisible();
    expect(await screen.findByLabelText("已选素材 B.png")).toBeVisible();
    expect(repository.put).toHaveBeenCalledTimes(2);
  });

  it("两个帧 Trigger 并发导入由 session 全局串行且保留全部个人素材与 URL", async () => {
    const records = new Map<string, CreatorMedia>();
    const firstWrite = deferred<void>();
    const secondWrite = deferred<void>();
    const writes = [firstWrite, secondWrite];
    const repository = {
      list: vi.fn(async () => [...records.values()]),
      put: vi.fn(async (media: CreatorMedia) => {
        const pending = writes[repository.put.mock.calls.length - 1];
        await pending?.promise;
        records.set(media.id, media);
      }),
      remove: vi.fn(async (id: string) => { records.delete(id); }),
      close: vi.fn(),
    };
    let previewId = 0;
    const create = vi.fn(() => `blob:global-${++previewId}`);
    const revoke = vi.fn();
    function PersonalProbe() {
      const { personalMedia } = useCreatorMedia();
      return <output aria-label="个人素材状态">{personalMedia.map((item) => item.name).join(",")}</output>;
    }
    const view = render(<UserFixtureProvider><LocalPromotionProvider><LocalInvitationProvider><CreatorMediaProvider
      storage={{ storageMode: "persistent", repository }}
      objectUrls={{ create, revoke }}
      publicMedia={publicCreatorMedia}
    ><MemoryRouter initialEntries={["/create/video"]}><CreatorComposer kind="video" /><PersonalProbe /></MemoryRouter></CreatorMediaProvider></LocalInvitationProvider></LocalPromotionProvider></UserFixtureProvider>);
    await waitFor(() => expect(repository.list).toHaveBeenCalledTimes(1));

    fireEvent.drop(screen.getByRole("button", { name: "添加首帧" }), { dataTransfer: { files: [new File(["a"], "frame-A.png", { type: "image/png" })] } });
    fireEvent.drop(screen.getByRole("button", { name: "添加尾帧" }), { dataTransfer: { files: [new File(["b"], "frame-B.png", { type: "image/png" })] } });
    await waitFor(() => expect(repository.put).toHaveBeenCalledTimes(1));
    firstWrite.resolve();
    await waitFor(() => expect(repository.put).toHaveBeenCalledTimes(2));
    secondWrite.resolve();

    expect(await screen.findByLabelText("已选素材 frame-A.png")).toBeVisible();
    expect(await screen.findByLabelText("已选素材 frame-B.png")).toBeVisible();
    expect(await screen.findByText("frame-A.png,frame-B.png")).toBeVisible();
    expect(records.size).toBe(2);
    expect(create).toHaveBeenCalledTimes(2);
    expect(revoke).not.toHaveBeenCalled();
    view.unmount();
    expect(revoke).toHaveBeenCalledTimes(2);
  });

  it("导入等待期间移除旧选择后完成时不会复活旧项", async () => {
    const written = deferred<void>();
    const repository = {
      list: vi.fn(async () => [] as readonly CreatorMedia[]),
      put: vi.fn(() => written.promise),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    render(<MediaHarness storage={{ storageMode: "persistent", repository }} initial={[oldPng]} publicMedia={[]} />);
    await waitFor(() => expect(repository.list).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("添加素材文件"), {
      target: { files: [new File(["new"], "new-after-remove.png", { type: "image/png" })] },
    });
    await waitFor(() => expect(repository.put).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: "移除 old.png" }));
    written.resolve();

    expect(await screen.findByLabelText("已选素材 new-after-remove.png")).toBeVisible();
    expect(screen.queryByLabelText("已选素材 old.png")).not.toBeInTheDocument();
  });

  it("MediaTrigger 卸载后 deferred 交付不再 onValidated 或 setState", async () => {
    const written = deferred<void>();
    const repository = {
      list: vi.fn(async () => [] as readonly CreatorMedia[]),
      put: vi.fn(() => written.promise),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    const onValidated = vi.fn();
    const view = render(<CreatorMediaProvider storage={{ storageMode: "persistent", repository }}>
      <MediaTrigger capability={imageCapability} label="添加素材" selectionMode="multiple" selected={[]} onValidated={onValidated} onOpenLibrary={() => {}} />
    </CreatorMediaProvider>);
    await waitFor(() => expect(repository.list).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("添加素材文件"), {
      target: { files: [new File(["late"], "late.png", { type: "image/png" })] },
    });
    await waitFor(() => expect(repository.put).toHaveBeenCalledTimes(1));
    view.unmount();
    written.resolve();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(onValidated).not.toHaveBeenCalled();
  });
});

const multimodalTestCapability: MediaInputCapability = {
  acceptedKinds: ["image", "audio"],
  acceptedMimeTypes: ["image/png", "audio/mpeg"],
  acceptedSources: ["upload", "drop", "library"],
  maxFileBytes: 50 * 1024 * 1024,
  maxItems: 6,
  verified: true,
};

const publicPng: CreatorMedia = {
  id: "public-png",
  kind: "image",
  name: "public-poster.png",
  mimeType: "image/png",
  size: 0,
  origin: "public",
};

const publicSecondPng: CreatorMedia = {
  ...publicPng,
  id: "public-second",
  name: "public-second.png",
};

const personalPngContent = new Blob(["personal-image"], { type: "image/png" });
const personalPng: CreatorMedia = {
  id: "personal-png",
  kind: "image",
  name: "personal.png",
  mimeType: "image/png",
  size: personalPngContent.size,
  content: personalPngContent,
  origin: "personal",
  storageMode: "persistent",
};

const personalAudioContent = new Blob(["personal-audio"], { type: "audio/mpeg" });
const personalAudio: CreatorMedia = {
  id: "personal-audio",
  kind: "audio",
  name: "personal.mp3",
  mimeType: "audio/mpeg",
  size: personalAudioContent.size,
  content: personalAudioContent,
  origin: "personal",
  storageMode: "persistent",
};

const createLibraryStorage = (records: readonly CreatorMedia[] = [personalPng, personalAudio]) => {
  const current = new Map(records.map((item) => [item.id, item]));
  const repository = {
    list: vi.fn(async () => [...current.values()]),
    put: vi.fn(async (media: CreatorMedia) => { current.set(media.id, media); }),
    remove: vi.fn(async (id: string) => { current.delete(id); }),
    close: vi.fn(),
  };
  return {
    storage: { storageMode: "persistent", repository } as LocalMediaRepositoryOpenState,
    repository,
  };
};

function ControlledLibrary({
  storage,
  selectionMode = "multiple",
  initialSelection = [],
  onConfirm,
  objectUrls,
}: {
  storage: LocalMediaRepositoryOpenState;
  selectionMode?: "single" | "multiple";
  initialSelection?: readonly string[];
  onConfirm(ids: readonly string[]): void;
  objectUrls?: { create(value: Blob): string; revoke(url: string): void };
}) {
  const [open, setOpen] = useState(false);
  return <CreatorMediaProvider
    storage={storage}
    publicMedia={[publicPng, publicSecondPng]}
    objectUrls={objectUrls}
  >
    <button type="button" onClick={() => setOpen(true)}>打开测试素材库</button>
    <MediaLibraryDialog
      open={open}
      capability={multimodalTestCapability}
      selectionMode={selectionMode}
      initialSelection={initialSelection}
      onConfirm={onConfirm}
      onClose={() => setOpen(false)}
    />
  </CreatorMediaProvider>;
}

describe("creator media library", () => {
  it("浏览不支持的分类但禁止选择，数量上限与取消选择实时生效", async () => {
    const confirm = vi.fn();
    render(<CreatorMediaProvider storage={createLibraryStorage([]).storage}
      publicMedia={[publicPng, publicSecondPng, personalAudio]}>
      <MediaLibraryDialog open capability={{ ...imageCapability, maxItems: 1 }} selectionMode="multiple"
        initialSelection={[]} onConfirm={confirm} onClose={() => {}} />
    </CreatorMediaProvider>);
    fireEvent.click(await screen.findByRole("option", { name: publicPng.name }));
    fireEvent.click(screen.getByRole("option", { name: publicSecondPng.name }));
    expect(screen.getByText("已选：1")).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent("最多选择 1 项");
    expect(screen.getByRole("option", { name: publicSecondPng.name })).toHaveAttribute("aria-selected", "false");
    fireEvent.click(screen.getByRole("tab", { name: "音频" }));
    const audio = screen.getByRole("option", { name: personalAudio.name });
    expect(audio).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(audio);
    expect(screen.getByRole("status")).toHaveTextContent("不支持音频");
    fireEvent.click(screen.getByRole("tab", { name: "视频" }));
    expect(screen.getByText("没有匹配的素材")).toBeVisible();
    fireEvent.click(screen.getByRole("tab", { name: "图片" }));
    fireEvent.click(screen.getByRole("option", { name: publicPng.name }));
    expect(screen.getByText("已选：0")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "确定" }));
    expect(confirm).toHaveBeenCalledWith([]);
  });

  it("逐卡删除只作用于当前素材，等待时不能确认，成功后清空待选且不提交表单", async () => {
    const removal = deferred<void>();
    const storage = createLibraryStorage([personalPng, { ...personalPng, id: "other", name: "other.png" }]);
    storage.repository.remove.mockImplementation(async () => { await removal.promise; });
    const confirm = vi.fn(); const submit = vi.fn(event => event.preventDefault());
    render(<CreatorMediaProvider storage={storage.storage}><form onSubmit={submit}>
      <MediaLibraryDialog open initialSource="personal" capability={imageCapability} selectionMode="multiple"
        initialSelection={[personalPng.id]} onConfirm={confirm} onClose={() => {}} />
    </form></CreatorMediaProvider>);
    const button = await screen.findByRole("button", { name: `删除 ${personalPng.name}` });
    expect(button.parentElement).toContainElement(screen.getByRole("option", { name: personalPng.name }));
    fireEvent.keyDown(button, { key: "Enter" }); fireEvent.click(button);
    expect(screen.getByRole("button", { name: "确定" })).toBeDisabled();
    expect(screen.getByText("已选：1")).toBeVisible();
    await act(async () => { removal.resolve(); await removal.promise; });
    expect(storage.repository.remove).toHaveBeenCalledExactlyOnceWith(personalPng.id);
    expect(screen.queryByRole("option", { name: personalPng.name })).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "other.png" })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByText("已选：0")).toBeVisible();
    expect(confirm).not.toHaveBeenCalled(); expect(submit).not.toHaveBeenCalled();
  });

  it("共用回填保留点击顺序，不按仓库顺序重排", async () => {
    const onValidated = vi.fn();
    render(<CreatorMediaProvider storage={createLibraryStorage([]).storage} publicMedia={[publicPng, publicSecondPng]}>
      <MediaTrigger capability={imageCapability} selected={[]} label="添加素材" selectionMode="multiple"
        onValidated={onValidated} onOpenLibrary={() => {}} />
    </CreatorMediaProvider>);
    fireEvent.click(screen.getByRole("button", { name: "添加素材" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "素材库" }));
    fireEvent.click(await screen.findByRole("option", { name: publicSecondPng.name }));
    fireEvent.click(screen.getByRole("option", { name: publicPng.name }));
    fireEvent.click(screen.getByRole("button", { name: "确定" }));
    await waitFor(() => expect(onValidated).toHaveBeenCalledWith([publicSecondPng, publicPng]));
  });

  it("素材库按分类展示真实预览且不使用 Base64 或外部协议", async () => {
    const publicPreviewMedia: readonly CreatorMedia[] = [
      { ...publicPng, name: "library.png", previewUrl: "data:image/png;base64,AA==" },
      { ...publicPng, id: "library-video", kind: "video", name: "library.mp4", mimeType: "video/mp4", previewUrl: "blob:library-video" },
      { ...publicPng, id: "library-audio", kind: "audio", name: "library.mp3", mimeType: "audio/mpeg", previewUrl: "data:audio/mpeg;base64,AA==" },
      { ...publicPng, id: "library-unsafe", name: "library-unsafe.png", previewUrl: "//example.invalid/unsafe.png" },
    ];
    const previewCapability: MediaInputCapability = {
      ...multimodalTestCapability,
      acceptedKinds: ["image", "video", "audio"],
      acceptedMimeTypes: ["image/png", "video/mp4", "audio/mpeg"],
    };
    const { storage } = createLibraryStorage([]);
    render(<CreatorMediaProvider storage={storage} publicMedia={publicPreviewMedia}>
      <MediaLibraryDialog
        open
        capability={previewCapability}
        selectionMode="multiple"
        initialSelection={[]}
        onConfirm={() => {}}
        onClose={() => {}}
      />
    </CreatorMediaProvider>);

    expect(await screen.findByRole("option", { name: "library.png" })).toBeVisible();
    expect(screen.queryByRole("img", { name: "library.png" })).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "library-unsafe.png" })).toHaveAttribute("title", "library-unsafe.png");
    fireEvent.click(screen.getByRole("tab", { name: "视频" }));
    expect(screen.getByLabelText("library.mp4 预览")).toHaveAttribute("src", "blob:library-video");
    expect(screen.getByLabelText("library.mp4 预览")).not.toHaveAttribute("controls");
    fireEvent.click(screen.getByRole("tab", { name: "音频" }));
    expect(screen.getByRole("option", { name: "library.mp3" })).toHaveTextContent("library.mp3");

  });

  it("StrictMode 重放 effect 后个人删除仍同步生产选择队列", async () => {
    const { storage, repository } = createLibraryStorage([personalPng]);
    render(<StrictMode><MediaHarness storage={storage} initial={[personalPng]} publicMedia={[]} /></StrictMode>);
    await waitFor(() => expect(repository.list).toHaveBeenCalled());
    fireEvent.click(screen.getByRole("button", { name: "添加素材" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "素材库" }));
    fireEvent.click(await screen.findByRole("tab", { name: "个人" }));
    fireEvent.click(await screen.findByRole("button", { name: "删除 personal.png" }));
    await waitFor(() => expect(repository.remove).toHaveBeenCalledWith(personalPng.id));

    expect(screen.queryByLabelText("已选素材 personal.png")).not.toBeInTheDocument();
  });

  it("个人删除失败保留 option 与已选 draft 且不抛出未处理 rejection", async () => {
    const repository = {
      list: vi.fn(async () => [personalPng] as readonly CreatorMedia[]),
      put: vi.fn(async () => {}),
      remove: vi.fn(async () => { throw new Error("controlled remove failure"); }),
      close: vi.fn(),
    };
    render(<ControlledLibrary
      storage={{ storageMode: "persistent", repository }}
      initialSelection={[personalPng.id]}
      onConfirm={() => {}}
    />);
    fireEvent.click(screen.getByRole("button", { name: "打开测试素材库" }));
    fireEvent.click(await screen.findByRole("tab", { name: "个人" }));
    const option = await screen.findByRole("option", { name: "personal.png" });
    expect(option).toHaveAttribute("aria-selected", "true");

    const dialog = screen.getByRole("dialog", { name: "素材库" });
    fireEvent.click(within(dialog).getByRole("button", { name: "删除 personal.png" }));
    await waitFor(() => expect(repository.remove).toHaveBeenCalledWith(personalPng.id));

    expect(screen.getByRole("option", { name: "personal.png" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("已选：1")).toBeVisible();
    expect(within(dialog).getByRole("alert")).toHaveTextContent("素材删除未写入持久库");
  });

  it("个人删除成功后关闭素材库不会在生产选择队列保留已撤销项", async () => {
    const { storage, repository } = createLibraryStorage([personalPng]);
    render(<MediaHarness storage={storage} initial={[personalPng]} publicMedia={[]} />);
    fireEvent.click(screen.getByRole("button", { name: "添加素材" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "素材库" }));
    fireEvent.click(await screen.findByRole("tab", { name: "个人" }));
    fireEvent.click(await screen.findByRole("button", { name: "删除 personal.png" }));
    await waitFor(() => expect(repository.remove).toHaveBeenCalledWith(personalPng.id));
    fireEvent.click(screen.getByRole("button", { name: "关闭素材库" }));

    expect(screen.queryByLabelText("已选素材 personal.png")).not.toBeInTheDocument();
  });
  it("公开/个人与类型真实过滤并删除个人素材和预览", async () => {
    const { storage, repository } = createLibraryStorage();
    let previewId = 0;
    const revoke = vi.fn();
    render(<ControlledLibrary
      storage={storage}
      onConfirm={() => {}}
      objectUrls={{ create: () => `blob:personal-${++previewId}`, revoke }}
    />);
    fireEvent.click(screen.getByRole("button", { name: "打开测试素材库" }));
    expect(screen.queryByRole("searchbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "全部" })).not.toBeInTheDocument();
    expect(await screen.findByRole("option", { name: "public-second.png" })).toBeVisible();
    fireEvent.click(screen.getByRole("tab", { name: "个人" }));
    expect(await screen.findByRole("option", { name: "personal.png" })).toBeVisible();
    expect(screen.queryByRole("option", { name: "personal.mp3" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "音频" }));
    expect(screen.getByRole("option", { name: "personal.mp3" })).toBeVisible();
    fireEvent.click(screen.getByRole("tab", { name: "图片" }));

    fireEvent.click(screen.getByRole("button", { name: "删除 personal.png" }));
    await waitFor(() => expect(repository.remove).toHaveBeenCalledWith(personalPng.id));
    expect(screen.queryByRole("option", { name: "personal.png" })).not.toBeInTheDocument();
    expect(revoke).toHaveBeenCalledTimes(1);
  });

  it("单选替换、多选确认一次回填，关闭后丢弃未确认草稿", async () => {
    const { storage } = createLibraryStorage([]);
    const onConfirm = vi.fn();
    const view = render(<ControlledLibrary storage={storage} onConfirm={onConfirm} />);
    fireEvent.click(screen.getByRole("button", { name: "打开测试素材库" }));
    fireEvent.click(await screen.findByRole("option", { name: "public-poster.png" }));
    fireEvent.click(screen.getByRole("option", { name: "public-second.png" }));
    expect(screen.getByText("已选：2")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "关闭素材库" }));

    fireEvent.click(screen.getByRole("button", { name: "打开测试素材库" }));
    expect(await screen.findByText("已选：0")).toBeVisible();
    fireEvent.click(screen.getByRole("option", { name: "public-poster.png" }));
    fireEvent.click(screen.getByRole("option", { name: "public-second.png" }));
    fireEvent.click(screen.getByRole("button", { name: "确定" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith([publicPng.id, publicSecondPng.id]);
    view.unmount();

    const singleConfirm = vi.fn();
    const secondStorage = createLibraryStorage([]).storage;
    render(<ControlledLibrary storage={secondStorage} selectionMode="single" onConfirm={singleConfirm} />);
    fireEvent.click(screen.getByRole("button", { name: "打开测试素材库" }));
    fireEvent.click(await screen.findByRole("option", { name: "public-poster.png" }));
    fireEvent.click(screen.getByRole("option", { name: "public-second.png" }));
    fireEvent.click(screen.getByRole("button", { name: "确定" }));
    expect(singleConfirm).toHaveBeenCalledWith([publicSecondPng.id]);
  });

  it("素材库确认以 draft 为最终选择且公开/个人回填都不写仓库", async () => {
    const publicStorage = createLibraryStorage([]);
    render(<MediaHarness
      storage={publicStorage.storage}
      initial={[publicPng]}
      publicMedia={[publicPng, publicSecondPng]}
    />);
    fireEvent.click(screen.getByRole("button", { name: "添加素材" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "素材库" }));
    fireEvent.click(await screen.findByRole("option", { name: "public-poster.png" }));
    fireEvent.click(screen.getByRole("option", { name: "public-second.png" }));
    fireEvent.click(screen.getByRole("button", { name: "确定" }));
    expect(await screen.findByLabelText("已选素材 public-second.png")).toHaveAttribute("data-origin", "public");
    expect(screen.queryByLabelText("已选素材 public-poster.png")).not.toBeInTheDocument();
    expect(publicStorage.repository.put).not.toHaveBeenCalled();
    cleanup();

    const singleStorage = createLibraryStorage([]);
    render(<MediaHarness
      storage={singleStorage.storage}
      initial={[publicPng]}
      publicMedia={[publicPng, publicSecondPng]}
      selectionMode="single"
      capability={{ ...imageCapability, maxItems: 1 }}
    />);
    fireEvent.click(screen.getByRole("button", { name: "添加素材" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "素材库" }));
    fireEvent.click(await screen.findByRole("option", { name: "public-second.png" }));
    fireEvent.click(screen.getByRole("button", { name: "确定" }));
    expect(await screen.findByLabelText("已选素材 public-second.png")).toHaveAttribute("data-origin", "public");
    expect(screen.queryByLabelText("已选素材 public-poster.png")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(singleStorage.repository.put).not.toHaveBeenCalled();
    cleanup();

    const personalStorage = createLibraryStorage([personalPng]);
    render(<MediaHarness storage={personalStorage.storage} initial={[]} publicMedia={[]} />);
    fireEvent.click(await screen.findByRole("button", { name: "添加素材" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "素材库" }));
    fireEvent.click(screen.getByRole("tab", { name: "个人" }));
    fireEvent.click(await screen.findByRole("option", { name: "personal.png" }));
    fireEvent.click(screen.getByRole("button", { name: "确定" }));
    expect(await screen.findByLabelText("已选素材 personal.png")).toHaveAttribute("data-origin", "personal");
    expect(personalStorage.repository.put).not.toHaveBeenCalled();
  });

  it("Enter/Space 选择、方向键移动，Esc 关闭并恢复触发器", async () => {
    const { storage } = createLibraryStorage([]);
    const triggerView = render(<ControlledLibrary storage={storage} onConfirm={() => {}} />);
    const trigger = triggerView.getByRole("button", { name: "打开测试素材库" });
    trigger.focus();
    fireEvent.click(trigger);
    const firstOption = await screen.findByRole("option", { name: "public-poster.png" });
    const secondOption = screen.getByRole("option", { name: "public-second.png" });
    expect(firstOption.tagName).toBe("DIV");
    expect(firstOption).toHaveAttribute("tabindex", "0");
    expect(secondOption).toHaveAttribute("tabindex", "-1");
    firstOption.focus();
    fireEvent.keyDown(firstOption, { key: "Enter" });
    fireEvent.keyUp(firstOption, { key: "Enter" });
    expect(firstOption).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(firstOption, { key: "ArrowDown" });
    expect(document.activeElement).toBe(secondOption);
    expect(firstOption).toHaveAttribute("tabindex", "-1");
    expect(secondOption).toHaveAttribute("tabindex", "0");
    fireEvent.keyDown(secondOption, { key: " " });
    expect(secondOption).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(secondOption, { key: "Escape" });
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it("切换分类后 Tab/Shift+Tab 在实际可聚焦首尾项循环", async () => {
    render(<ControlledLibrary storage={createLibraryStorage([]).storage} onConfirm={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "打开测试素材库" }));
    const dialog = await screen.findByRole("dialog", { name: "素材库" });
    const audio = screen.getByRole("tab", { name: "音频" });
    fireEvent.click(audio);
    audio.focus();
    // Empty audio category: the selected public scope tab is the last actual tab stop.
    const last = within(dialog).getByRole("tab", { name: "公开" });
    fireEvent.keyDown(audio, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(last);
    fireEvent.keyDown(last, { key: "Tab" });
    expect(document.activeElement).toBe(audio);
  });

  it("两个 tablist 使用 roving tabindex、方向键切换并关联同一 panel", async () => {
    const { storage } = createLibraryStorage([]);
    render(<ControlledLibrary storage={storage} onConfirm={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "打开测试素材库" }));
    const scopeTabs = within(await screen.findByRole("tablist", { name: "素材库范围" }));
    const publicTab = scopeTabs.getByRole("tab", { name: "公开" });
    const personalTab = scopeTabs.getByRole("tab", { name: "个人" });
    expect(publicTab).toHaveAttribute("tabindex", "0");
    expect(personalTab).toHaveAttribute("tabindex", "-1");
    publicTab.focus();
    fireEvent.keyDown(publicTab, { key: "ArrowRight" });
    expect(personalTab).toHaveFocus();
    expect(personalTab).toHaveAttribute("aria-selected", "true");
    expect(personalTab).toHaveAttribute("aria-controls");
    const panelId = personalTab.getAttribute("aria-controls");
    expect(document.getElementById(String(panelId))).toHaveAttribute("role", "tabpanel");

    const typeTabs = within(screen.getByRole("tablist", { name: "素材类型" }));
    const allTab = typeTabs.getByRole("tab", { name: "图片" });
    const audioTab = typeTabs.getByRole("tab", { name: "音频" });
    allTab.focus();
    fireEvent.keyDown(allTab, { key: "End" });
    expect(audioTab).toHaveFocus();
    expect(audioTab).toHaveAttribute("aria-selected", "true");
    expect(audioTab).toHaveAttribute("aria-controls", panelId);
  });

  it("个人逐项删除按钮位于 listbox 语义容器之外", async () => {
    const { storage } = createLibraryStorage([personalPng]);
    render(<ControlledLibrary storage={storage} onConfirm={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "打开测试素材库" }));
    fireEvent.click(await screen.findByRole("tab", { name: "个人" }));

    const remove = await screen.findByRole("button", { name: "删除 personal.png" });
    expect(remove.closest("[role='listbox']")).toBeNull();
  });
});

describe("creator settings and verified pricing", () => {
  const renderComposer = (props: React.ComponentProps<typeof CreatorComposer>) => {
    const { storage } = createTestStorage();
    return render(<CreatorMediaProvider storage={storage}><CreatorComposer {...props} /></CreatorMediaProvider>);
  };

  const expectDocumentOrder = (items: readonly Element[]) => {
    for (let index = 0; index < items.length - 1; index += 1) {
      expect(items[index]?.compareDocumentPosition(items[index + 1] as Node) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    }
  };

  it("取消不兼容模式切换后完整保留模型、模式、参数、素材和提示词", async () => {
    renderComposer({
      kind: "video",
      initialMode: "omni-reference",
      initialPrompt: "保留这段提示词",
      initialSelectedMedia: [personalAudio],
      initialParameters: ["测试专用混音参数"],
      injectedMediaCapability: multimodalTestCapability,
      priceOverride: "0",
    });

    fireEvent.click(screen.getByRole("button", { name: "首尾帧" }));
    const dialog = screen.getByRole("dialog", { name: "确认切换创作模式" });
    expect(dialog).toHaveTextContent("personal.mp3");
    expect(dialog).toHaveTextContent("测试专用混音参数");
    fireEvent.click(within(dialog).getByRole("button", { name: "取消切换" }));

    expect(screen.getByRole("button", { name: "全能参考" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("Seedance 2.5")).toBeVisible();
    expect(screen.getByLabelText("已选素材 personal.mp3")).toBeVisible();
    expect(screen.getByLabelText("当前测试参数")).toHaveTextContent("测试专用混音参数");
    expect(screen.getByLabelText("视频提示词")).toHaveValue("保留这段提示词");
  });

  it("确认不兼容模式切换后才移除素材并重置参数", () => {
    renderComposer({
      kind: "video",
      initialMode: "omni-reference",
      initialSelectedMedia: [personalAudio],
      initialParameters: ["测试专用混音参数"],
      injectedMediaCapability: multimodalTestCapability,
      priceOverride: "0",
    });
    fireEvent.click(screen.getByRole("button", { name: "首尾帧" }));
    fireEvent.click(within(screen.getByRole("dialog", { name: "确认切换创作模式" })).getByRole("button", { name: "确认切换" }));

    expect(screen.getByRole("button", { name: "首尾帧" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByLabelText("已选素材 personal.mp3")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("当前测试参数")).not.toBeInTheDocument();
  });

  it("全能参考切到首尾帧时兼容图片也进入确认事务，取消保留而确认移除", () => {
    renderComposer({
      kind: "video",
      initialMode: "omni-reference",
      initialPrompt: "保留全能参考提示词",
      initialSelectedMedia: [oldPng],
      priceOverride: "0",
    });

    fireEvent.click(screen.getByRole("button", { name: "首尾帧" }));
    let dialog = screen.getByRole("dialog", { name: "确认切换创作模式" });
    expect(dialog).toHaveTextContent("old.png");
    fireEvent.click(within(dialog).getByRole("button", { name: "取消切换" }));
    expect(screen.getByRole("button", { name: "全能参考" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByLabelText("已选素材 old.png")).toBeVisible();
    expect(screen.getByLabelText("视频提示词")).toHaveValue("保留全能参考提示词");

    fireEvent.click(screen.getByRole("button", { name: "首尾帧" }));
    dialog = screen.getByRole("dialog", { name: "确认切换创作模式" });
    fireEvent.click(within(dialog).getByRole("button", { name: "确认切换" }));
    expect(screen.getByRole("button", { name: "首尾帧" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByLabelText("已选素材 old.png")).not.toBeInTheDocument();
  });

  it("首尾帧切到全能参考时兼容图片也进入确认事务，取消保留而确认移除", async () => {
    renderComposer({ kind: "video", initialMode: "first-last-frame", priceOverride: "0" });
    fireEvent.change(screen.getByLabelText("添加首帧文件"), {
      target: { files: [new File(["frame"], "first-frame.png", { type: "image/png" })] },
    });
    expect(await screen.findByLabelText("已选素材 first-frame.png")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "全能参考" }));
    let dialog = screen.getByRole("dialog", { name: "确认切换创作模式" });
    expect(dialog).toHaveTextContent("first-frame.png");
    fireEvent.click(within(dialog).getByRole("button", { name: "取消切换" }));
    expect(screen.getByRole("button", { name: "首尾帧" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByLabelText("已选素材 first-frame.png")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "全能参考" }));
    dialog = screen.getByRole("dialog", { name: "确认切换创作模式" });
    fireEvent.click(within(dialog).getByRole("button", { name: "确认切换" }));
    expect(screen.getByRole("button", { name: "全能参考" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByLabelText("已选素材 first-frame.png")).not.toBeInTheDocument();
  });

  it.each([
    ["image", "Nano Banana Pro"],
    ["video", "Kling 2.1"],
  ] as const)("%s 的 %s 未配置时不可提交", (kind, model) => {
    renderComposer({ kind, initialModel: model, priceOverride: "0" });
    expect(screen.getByText("本地能力未验证")).toBeVisible();
    expect(screen.getByRole("button", { name: "开始生成" })).toBeDisabled();
  });

  it("0 积分是免费而不是缺价，图片估算保持四位", () => {
    const free = renderComposer({ kind: "image", priceOverride: "0" });
    expect(screen.getByText("免费")).toBeVisible();
    expect(screen.getByRole("button", { name: "开始生成" })).toBeEnabled();
    free.unmount();

    renderComposer({ kind: "image" });
    expect(screen.getByText("预计 1.2 积分")).toBeVisible();
  });

  it("本地视频只提供已验证模式，缺失精确价格时禁用提交并可打开设置", async () => {
    renderLocalVideo();

    expect(await screen.findByRole("button", { name: "首尾帧" })).toBeVisible();
    expect(screen.getByRole("button", { name: "全能参考" })).toBeVisible();
    expect(screen.queryByText("文生视频")).not.toBeInTheDocument();
    expect(screen.getByText("当前配置没有本地积分估算")).toBeVisible();
    expect(screen.getByRole("button", { name: "开始生成" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "创作设置" }));
    expect(screen.getByRole("dialog", { name: "创作设置" })).toBeVisible();
  });

  it("本地首尾帧提交真实创建 first-last-frame 任务", () => {
    const onCreate = vi.fn();
    renderComposer({
      kind: "video",
      initialMode: "first-last-frame",
      initialPrompt: "从首帧运动到尾帧",
      priceOverride: "0",
      onCreate,
    });

    fireEvent.submit(screen.getByTestId("creator-composer"));

    expect(screen.getByRole("button", { name: "首尾帧" })).toHaveAttribute("aria-pressed", "true");
    expect(onCreate).toHaveBeenCalledWith(expect.objectContaining({
      id: "task-1",
      mode: "first-last-frame",
      state: "pending",
    }));
  });

  it("本地首尾帧主区固定两个素材槽，提示词占据剩余宽度并适配窄屏", () => {
    const view = renderLocalVideo();
    const composer = view.getByTestId("creator-composer");
    const main = composer.querySelector(".composer-main-first-last");

    expect(main).toBeInTheDocument();
    expect(main?.children).toHaveLength(3);
    expect(main?.children[0]).toContainElement(view.getByRole("button", { name: "添加首帧" }));
    expect(main?.children[1]).toContainElement(view.getByRole("button", { name: "添加尾帧" }));
    expect(main?.children[2]).toBe(view.getByLabelText("视频提示词"));
    expect(creatorStyles).toMatch(/\.composer-main-first-last\s*\{[^}]*grid-template-columns:\s*80px 80px minmax\(0, 1fr\)/s);
    expect(creatorStyles).toMatch(/@media \(max-width: 760px\)[\s\S]*\.composer-main-first-last\s*\{[^}]*grid-template-columns:\s*80px 80px minmax\(0, 1fr\)/);
  });

  it("图片工具栏按模型、参考模式、未知参数、积分、生成顺序渲染", () => {
    renderComposer({ kind: "image" });
    const toolbar = screen.getByRole("toolbar", { name: "图片创作工具栏" });
    const ratio = within(toolbar).getByRole("button", { name: "比例未验证" });
    const clarity = within(toolbar).getByRole("button", { name: "清晰度未验证" });
    expect(ratio).toBeDisabled();
    expect(clarity).toBeDisabled();
    expectDocumentOrder([
      within(toolbar).getByText("GPT Image 2"),
      within(toolbar).getByText("图片参考"),
      ratio,
      clarity,
      within(toolbar).getByText("预计 1.2 积分"),
      within(toolbar).getByRole("button", { name: "开始生成" }),
    ]);
  });

  it("视频工具栏按参考模式、模型、未知参数、积分、生成顺序渲染", () => {
    renderComposer({ kind: "video", priceOverride: "0" });
    const toolbar = screen.getByRole("toolbar", { name: "视频创作工具栏" });
    const ratio = within(toolbar).getByRole("button", { name: "比例未验证" });
    const resolution = within(toolbar).getByRole("button", { name: "分辨率未验证" });
    const duration = within(toolbar).getByRole("button", { name: "时长未验证" });
    expect(ratio).toBeDisabled();
    expect(resolution).toBeDisabled();
    expect(duration).toBeDisabled();
    expectDocumentOrder([
      within(toolbar).getByRole("group", { name: "视频生成模式" }),
      within(toolbar).getByText("Seedance 2.5"),
      ratio,
      resolution,
      duration,
      within(toolbar).getByText("免费"),
      within(toolbar).getByRole("button", { name: "开始生成" }),
    ]);
  });
});

describe("creator deterministic submit state machine", () => {

  it("按钮与表单双入口在 pending 阶段只创建一个任务，500ms 解锁且 1000ms 完成", async () => {
    vi.useFakeTimers();
    const onCreate = vi.fn();
    const { storage } = createTestStorage();
    function TaskProbe() {
      const { tasks } = useCreatorMedia();
      return <div>{tasks.image.map((task) => <article data-testid={`state-task-${task.id}`} key={task.id}>{task.prompt}:{task.state === "pending" ? "待提交" : task.state === "generating" ? "生成中" : "已完成"}</article>)}</div>;
    }
    render(<CreatorMediaProvider storage={storage}>
      <CreatorComposer kind="image" initialPrompt="锁定任务" onCreate={onCreate} />
      <TaskProbe />
    </CreatorMediaProvider>);
    const form = screen.getByTestId("creator-composer");
    const button = screen.getByRole("button", { name: "开始生成" });

    fireEvent.click(button);
    fireEvent.submit(form);
    expect(onCreate).toHaveBeenCalledTimes(1);
    expect(screen.getAllByTestId("state-task-task-1")).toHaveLength(1);
    expect(screen.getByTestId("state-task-task-1")).toHaveTextContent("待提交");
    expect(button).toBeDisabled();

    await act(async () => { await vi.advanceTimersByTimeAsync(499); });
    fireEvent.click(button);
    fireEvent.submit(form);
    expect(onCreate).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(screen.getByTestId("state-task-task-1")).toHaveTextContent("生成中");
    expect(button).toBeEnabled();

    fireEvent.submit(form);
    expect(onCreate).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("state-task-task-2")).toHaveTextContent("待提交");
    await act(async () => { await vi.advanceTimersByTimeAsync(500); });
    expect(screen.getByTestId("state-task-task-1")).toHaveTextContent("已完成");
    expect(screen.getByTestId("state-task-task-2")).toHaveTextContent("生成中");
  });

});

describe("local composer fixtures stay offline and accessible", () => {
  it("本地 CreatorComposer 测试路由同时绑定零网络与 axe 断言", async () => {
    const fetchSpy = vi.fn(() => { throw new Error("fetch disabled"); });
    const xhrSpy = vi.spyOn(OfflineXmlHttpRequest.prototype, "open");
    xhrSpy.mockImplementation(() => { throw new Error("xhr disabled"); });
    const webSocketSpy = vi.fn(() => { throw new Error("websocket disabled"); });
    vi.stubGlobal("fetch", fetchSpy);
    vi.stubGlobal("XMLHttpRequest", OfflineXmlHttpRequest);
    vi.stubGlobal("WebSocket", webSocketSpy);
    const { storage } = createTestStorage();
    const router = createMemoryRouter([{
      path: "/create/image",
      element: <CreatorMediaProvider storage={storage}><CreatorComposer kind="image" /></CreatorMediaProvider>,
    }], { initialEntries: ["/create/image"] });
    const view = render(<RouterProvider router={router} />);

    expect(view.getByTestId("creator-composer")).toBeVisible();
    const axeResult = await axe(view.container, { rules: { "color-contrast": { enabled: false } } });
    expect(axeResult).toHaveNoViolations();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(xhrSpy).not.toHaveBeenCalled();
    expect(webSocketSpy).not.toHaveBeenCalled();
  });

  it.each(["image", "video"])("本地 %s 完成素材库交互时三条网络通道仍为零调用", async (path) => {
    const fetchSpy = vi.fn(() => { throw new Error("fetch disabled"); });
    const xhrSpy = vi.spyOn(OfflineXmlHttpRequest.prototype, "open").mockImplementation(() => { throw new Error("xhr disabled"); });
    const webSocketSpy = vi.fn(() => { throw new Error("websocket disabled"); });
    vi.stubGlobal("fetch", fetchSpy);
    vi.stubGlobal("XMLHttpRequest", OfflineXmlHttpRequest);
    vi.stubGlobal("WebSocket", webSocketSpy);
    const view = path === "video" ? renderLocalVideo() : renderLocalImage();
    await view.findByTestId("creator-composer");
    const triggerName = path === "video" ? "添加首帧" : "添加素材";
    fireEvent.click(view.getByRole("button", { name: triggerName }));
    fireEvent.click(view.getByRole("menuitem", { name: "素材库" }));
    const library = await view.findByRole("dialog", { name: "素材库" });
    fireEvent.click(within(library).getByRole("button", { name: "关闭素材库" }));

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(xhrSpy).not.toHaveBeenCalled();
    expect(webSocketSpy).not.toHaveBeenCalled();
  });

  it("逐项拒绝公开 fixture 的外部 url/src/preview 字段", () => {
    for (const media of publicCreatorMedia) {
      for (const [field, value] of Object.entries(media)) {
        if (/url|src|preview/i.test(field) && typeof value === "string") {
          expect(value).not.toMatch(/^(?:https?:)?\/\//i);
        }
      }
    }
  });

  it.each(["image", "video"])("本地 %s 组件与素材库均无 axe 违规", async (path) => {
    const view = path === "video" ? renderLocalVideo() : renderLocalImage();
    await view.findByTestId("creator-composer");
    await audit(view.container);
    fireEvent.click(view.getByRole("button", { name: path === "video" ? "添加首帧" : "添加素材" }));
    fireEvent.click(view.getByRole("menuitem", { name: "素材库" }));
    expect(await view.findByRole("dialog", { name: "素材库" })).toBeVisible();
    await audit(view.container);
  });

  it("设置层与不兼容确认框均无 axe 违规，错误使用 alert 播报", async () => {
    const settings = renderLocalImage();
    await settings.findByTestId("creator-composer");
    fireEvent.click(settings.getByRole("button", { name: "创作设置" }));
    expect(settings.getByRole("dialog", { name: "创作设置" })).toBeVisible();
    await audit(settings.container);
    settings.unmount();

    const { storage } = createTestStorage();
    const incompatible = render(<CreatorMediaProvider storage={storage}><CreatorComposer
      kind="video"
      initialMode="omni-reference"
      initialSelectedMedia={[personalAudio]}
      injectedMediaCapability={multimodalTestCapability}
      priceOverride="0"
    /></CreatorMediaProvider>);
    fireEvent.click(incompatible.getByRole("button", { name: "首尾帧" }));
    expect(incompatible.getByRole("dialog", { name: "确认切换创作模式" })).toBeVisible();
    await audit(incompatible.container);
    incompatible.unmount();

    const error = render(<CreatorMediaProvider storage={createTestStorage().storage}><CreatorComposer kind="image" /></CreatorMediaProvider>);
    fireEvent.submit(error.getByTestId("creator-composer"));
    expect(error.getByRole("alert")).toHaveTextContent("请输入创作提示词");
  });

  it("设置层和不兼容确认框均正反向循环焦点并在关闭后恢复触发器", async () => {
    const settings = renderLocalImage();
    const settingsTrigger = settings.getByRole("button", { name: "创作设置" });
    settingsTrigger.focus();
    fireEvent.click(settingsTrigger);
    const settingsDialog = settings.getByRole("dialog", { name: "创作设置" });
    const settingsFocusable = Array.from(settingsDialog.querySelectorAll<HTMLElement>("button:not([disabled])"));
    const settingsFirst = settingsFocusable[0];
    const settingsLast = settingsFocusable[settingsFocusable.length - 1];
    settingsFirst.focus();
    fireEvent.keyDown(settingsFirst, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(settingsLast);
    fireEvent.keyDown(settingsLast, { key: "Tab" });
    expect(document.activeElement).toBe(settingsFirst);
    fireEvent.click(settings.getByRole("button", { name: "关闭创作设置" }));
    await waitFor(() => expect(document.activeElement).toBe(settingsTrigger));
    settings.unmount();

    const { storage } = createTestStorage();
    const incompatible = render(<CreatorMediaProvider storage={storage}><CreatorComposer
      kind="video"
      initialMode="omni-reference"
      initialSelectedMedia={[personalAudio]}
      injectedMediaCapability={multimodalTestCapability}
      priceOverride="0"
    /></CreatorMediaProvider>);
    const modeTrigger = incompatible.getByRole("button", { name: "首尾帧" });
    modeTrigger.focus();
    fireEvent.click(modeTrigger);
    const confirmDialog = incompatible.getByRole("dialog", { name: "确认切换创作模式" });
    const confirmFocusable = Array.from(confirmDialog.querySelectorAll<HTMLElement>("button:not([disabled])"));
    const confirmFirst = confirmFocusable[0];
    const confirmLast = confirmFocusable[confirmFocusable.length - 1];
    confirmFirst.focus();
    fireEvent.keyDown(confirmFirst, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(confirmLast);
    fireEvent.keyDown(confirmLast, { key: "Tab" });
    expect(document.activeElement).toBe(confirmFirst);
    fireEvent.click(confirmFirst);
    await waitFor(() => expect(document.activeElement).toBe(modeTrigger));
  });

  it("CSS 固定悬浮尺寸、素材动效、层动效、触控尺寸与 reduced-motion 显式取消变换", () => {
    expect(creatorStyles).toMatch(/\.floating-composer\s*\{[^}]*position:\s*fixed[^}]*bottom:\s*24px[^}]*width:\s*clamp\(760px,\s*72vw,\s*1120px\)[^}]*min-height:\s*176px[^}]*border-radius:\s*12px[^}]*backdrop-filter:\s*blur\(10px\)/s);
    expect(creatorStyles).toMatch(/\.media-trigger\s*\{[^}]*width:\s*80px[^}]*height:\s*80px[^}]*transform:\s*rotate\(-8deg\)[^}]*transition:[^}]*200ms/s);
    expect(creatorStyles).toMatch(/\.media-trigger:hover[\s\S]*\.media-trigger:focus-visible\s*\{[^}]*transform:\s*rotate\(0(?:deg)?\)\s*scale\(1\.03\)/);
    expect(creatorStyles).toMatch(/\.media-trigger\.drag-active\s*\{[^}]*border-style:\s*solid[^}]*border-color:\s*var\(--cyan\)[^}]*transform:\s*rotate\(0(?:deg)?\)\s*scale\(1\.03\)/s);
    expect(creatorStyles).toMatch(/\.media-trigger-wrap\s*\[role="menu"\][^}]*transition:[^;}]*160ms/);
    expect(creatorStyles).toMatch(/\.media-queue[^}]*transition:[^;}]*240ms/);
    expect(creatorStyles).toMatch(/\.composer-toolbar\s+\.create-button\s*\{[^}]*min-height:\s*44px/);
    expect(creatorStyles).toMatch(/\.incompatible-model-backdrop\s*\{[^}]*display:\s*grid[^}]*place-items:\s*center/);
    expect(creatorStyles).toContain("@starting-style");
    expect(creatorStyles).toContain("@media (prefers-reduced-motion: reduce)");
    expect(creatorStyles).toMatch(/prefers-reduced-motion:\s*reduce[\s\S]*transition:\s*none/);
    expect(creatorStyles).toMatch(/prefers-reduced-motion:\s*reduce[\s\S]*transform:\s*none/);
  });
});
