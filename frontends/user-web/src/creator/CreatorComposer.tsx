import { t, formatMessage, useLanguage } from '../i18n';
import { formatAmount } from '../../../shared/formatAmount';
import { useMemo, useState, type FormEvent } from "react";

import { CreatorSettingsPopover } from "./CreatorSettingsPopover";
import { IncompatibleModelDialog } from "./IncompatibleModelDialog";
import { useCreatorMedia } from "./CreatorMediaProvider";
import { estimateImagePoints } from "./creatorPricing";
import { MediaQueue } from "./MediaQueue";
import { MediaTrigger } from "./MediaTrigger";
import { getCapabilityState } from "./modelCapabilities";
import type {
  CreatorMedia,
  CreatorModeId,
  CreatorModelId,
  CreatorTask,
  MediaInputCapability,
  PendingCreatorSwitch,
} from "./types";

const unavailableInput: MediaInputCapability = {
  acceptedKinds: [],
  acceptedMimeTypes: [],
  acceptedSources: [],
  maxFileBytes: 0,
  maxItems: 0,
  verified: false,
};

const modelLabel: Readonly<Record<CreatorModelId, string>> = {
  "gpt-image-2": "GPT Image 2",
  "seedance-2.5": "Seedance 2.5",
  "Nano Banana Pro": "Nano Banana Pro",
  "Kling 2.1": "Kling 2.1",
};

export type CreatorComposerProps = {
  kind: "image" | "video";
  className?: string;
  testId?: string;
  initialModel?: CreatorModelId;
  initialMode?: CreatorModeId;
  initialPrompt?: string;
  initialSelectedMedia?: readonly CreatorMedia[];
  initialParameters?: readonly string[];
  injectedMediaCapability?: MediaInputCapability;
  priceOverride?: string | null;
  onCreate?(task: CreatorTask): void;
  onAccepted?(task: CreatorTask): void;
};

export function CreatorComposer({
  kind,
  className = "floating-composer",
  testId = "creator-composer",
  initialModel,
  initialMode,
  initialPrompt = "",
  initialSelectedMedia = [],
  initialParameters = [],
  injectedMediaCapability,
  priceOverride,
  onCreate,
  onAccepted,
}: CreatorComposerProps) {
  useLanguage();
  const {
    accountType,
    addTask,
    allocateTaskId,
    submissionLocks,
    tryAcquireSubmissionLock,
    releaseSubmissionLock,
    scheduleTaskTransitions,
  } = useCreatorMedia();
  const [model, setModel] = useState<CreatorModelId>(initialModel ?? (kind === "image" ? "gpt-image-2" : "seedance-2.5"));
  const [mode, setMode] = useState<CreatorModeId>(initialMode ?? (kind === "image" ? "image-reference" : "first-last-frame"));
  const [prompt, setPrompt] = useState(initialPrompt);
  const [selectedMedia, setSelectedMedia] = useState<readonly CreatorMedia[]>(initialSelectedMedia);
  const [frameSlots, setFrameSlots] = useState<readonly [CreatorMedia | null, CreatorMedia | null]>([null, null]);
  const [parameters, setParameters] = useState<readonly string[]>(initialParameters);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [pendingSwitch, setPendingSwitch] = useState<PendingCreatorSwitch | null>(null);
  const [pendingTitle, setPendingTitle] = useState("确认切换创作模式");
  const [openLibrarySignal, setOpenLibrarySignal] = useState(0);
  const [notice, setNotice] = useState("");

  const capabilityState = getCapabilityState(model);
  const capability = useMemo<MediaInputCapability>(() => {
    if (injectedMediaCapability && mode === "omni-reference") return injectedMediaCapability;
    if (!capabilityState.available) return unavailableInput;
    if (capabilityState.kind === "image") return capabilityState.input;
    return mode === "first-last-frame"
      ? capabilityState.modes.firstLastFrame
      : capabilityState.modes.omniReference.image;
  }, [capabilityState, injectedMediaCapability, mode]);
  const available = capabilityState.available
    && ((kind === "image" && capabilityState.kind === "image") || (kind === "video" && capabilityState.kind === "video"));
  const estimatedPrice = priceOverride !== undefined
    ? priceOverride
    : kind === "image" && model === "gpt-image-2"
      ? estimateImagePoints(accountType, model, "fixture-1k")
      : null;

  const computeSwitch = (nextModel: CreatorModelId, nextMode: CreatorModeId): PendingCreatorSwitch => {
    const nextState = getCapabilityState(nextModel);
    const nextCapability = nextState.available
      ? nextState.kind === "image"
        ? nextState.input
        : nextMode === "first-last-frame"
          ? nextState.modes.firstLastFrame
          : injectedMediaCapability ?? nextState.modes.omniReference.image
      : unavailableInput;
    const allSelected = [...selectedMedia, ...frameSlots.filter((item): item is CreatorMedia => item !== null)];
    const removedMedia = nextMode !== mode
      ? allSelected
      : allSelected.filter((item) => (
        !nextCapability.acceptedKinds.includes(item.kind)
        || !nextCapability.acceptedMimeTypes.includes(item.mimeType)
      ));
    return {
      nextModel,
      nextMode,
      removedMedia,
      resetParameters: nextMode === mode && nextModel === model ? [] : [...parameters],
    };
  };

  const applySwitch = (next: PendingCreatorSwitch): void => {
    const removed = new Set(next.removedMedia.map((item) => item.id));
    setModel(next.nextModel);
    setMode(next.nextMode);
    setSelectedMedia((items) => items.filter((item) => !removed.has(item.id)));
    setFrameSlots((items) => [
      items[0] && !removed.has(items[0].id) ? items[0] : null,
      items[1] && !removed.has(items[1].id) ? items[1] : null,
    ]);
    if (next.resetParameters.length > 0) setParameters([]);
  };

  const requestSwitch = (nextModel: CreatorModelId, nextMode: CreatorModeId, title: string): void => {
    const next = computeSwitch(nextModel, nextMode);
    if (next.removedMedia.length === 0 && next.resetParameters.length === 0) {
      applySwitch(next);
      return;
    }
    setPendingTitle(title);
    setPendingSwitch(next);
  };

  const submit = (event: FormEvent): void => {
    event.preventDefault();
    if (!tryAcquireSubmissionLock(kind)) return;
    const releaseImmediately = (): void => releaseSubmissionLock(kind);
    if (!available || estimatedPrice === null) {
      releaseImmediately();
      return;
    }
    const submittedPrompt = prompt.trim();
    if (!submittedPrompt) {
      setNotice("请输入创作提示词");
      releaseImmediately();
      return;
    }
    const taskId = allocateTaskId();
    const task: CreatorTask = kind === "image" ? {
      id: taskId,
      kind: "image",
      modelId: "gpt-image-2",
      mode: "image-reference",
      prompt: submittedPrompt,
      state: "pending",
    } : {
      id: taskId,
      kind: "video",
      modelId: "seedance-2.5",
      mode: mode === "omni-reference" ? "omni-reference" : "first-last-frame",
      prompt: submittedPrompt,
      state: "pending",
    };
    addTask(task);
    onCreate?.(task);
    scheduleTaskTransitions(task.id, () => releaseSubmissionLock(kind));
    onAccepted?.(task);
    setNotice(`本地${kind === "image" ? "图片" : "视频"}任务已创建`);
  };

  const label = kind === "image" ? "图片" : "视频";
  return <>
    <form data-testid={testId} className={className} onSubmit={submit}>
      {kind === "video" && mode === "omni-reference" && <div className="unverified-media-capabilities">
        <span>{t("视频能力未验证")}</span><button type="button" aria-label={t("上传视频")} disabled>{t("上传视频")}</button>
        <span>{t("音频能力未验证")}</span><button type="button" aria-label={t("上传音频")} disabled>{t("上传音频")}</button>
      </div>}
      <div className={`composer-main${kind === "video" && mode === "first-last-frame" ? " composer-main-first-last" : ""}`}>
        {kind === "video" && mode === "first-last-frame" ? <>
          <MediaTrigger capability={capability} label={t("添加首帧")} selectionMode="single" selected={frameSlots[0] ? [frameSlots[0]] : []} onValidated={(items) => setFrameSlots((current) => [items[0] ?? null, current[1]])} onOpenLibrary={() => {}} />
          <MediaTrigger capability={capability} label={t("添加尾帧")} selectionMode="single" selected={frameSlots[1] ? [frameSlots[1]] : []} onValidated={(items) => setFrameSlots((current) => [current[0], items[0] ?? null])} onOpenLibrary={() => {}} />
        </> : <MediaTrigger
          capability={capability}
          label={t("添加素材")}
          selectionMode="multiple"
          selected={selectedMedia}
          openLibrarySignal={openLibrarySignal}
          onValidated={setSelectedMedia}
          onOpenLibrary={() => {}}
        />}
        <textarea id={`${testId}-${kind}-prompt`} aria-label={formatMessage("{0}提示词", t(label))} value={prompt} onChange={(event) => setPrompt(event.target.value)} placeholder={t(kind === "image" ? "描述你想要生成的内容画面" : "描述画面变化、动作与镜头意图")} />
      </div>
      <MediaQueue
        mode={kind === "video" && mode === "first-last-frame" ? "first-last" : "omni"}
        items={kind === "video" && mode === "first-last-frame" ? frameSlots : selectedMedia}
        onRemove={(id) => {
          if (kind === "video" && mode === "first-last-frame") {
            setFrameSlots((items) => [items[0]?.id === id ? null : items[0], items[1]?.id === id ? null : items[1]]);
          } else {
            setSelectedMedia((items) => items.filter((item) => item.id !== id));
          }
        }}
        onManageOverflow={() => setOpenLibrarySignal((current) => current + 1)}
      />
      {parameters.length > 0 && <output aria-label={t("当前测试参数")}>{t(parameters.join("、"))}</output>}
      <div className="composer-toolbar" role="toolbar" aria-label={formatMessage("{0}创作工具栏", t(label))}>
        {kind === "video" && <div className="mode-row" role="group" aria-label={t("视频生成模式")}>
          <button type="button" aria-pressed={mode === "first-last-frame"} onClick={() => requestSwitch(model, "first-last-frame", "确认切换创作模式")}>{t("首尾帧")}</button>
          <button type="button" aria-pressed={mode === "omni-reference"} onClick={() => requestSwitch(model, "omni-reference", "确认切换创作模式")}>{t("全能参考")}</button>
        </div>}
        <button type="button" className="settings-trigger" aria-label={t("创作设置")} aria-haspopup="dialog" onClick={() => setSettingsOpen(true)}>
          <span>{t(modelLabel[model])}</span>
          {available ? null : <small>{t("本地能力未验证")}</small>}
        </button>
        {kind === "image" && <span>{t("图片参考")}</span>}
        <button type="button" disabled>{t("比例未验证")}</button>
        {kind === "image"
          ? <button type="button" disabled>{t("清晰度未验证")}</button>
          : <>
            <button type="button" disabled>{t("分辨率未验证")}</button>
            <button type="button" disabled>{t("时长未验证")}</button>
          </>}
        <span className="cost">{t(estimatedPrice === null ? "当前配置没有本地积分估算" : estimatedPrice === "0" || estimatedPrice === "0.0000" ? "免费" : formatMessage("预计 {0} 积分", formatAmount(estimatedPrice)))}</span>
        <button className="create-button" type="submit" disabled={!available || estimatedPrice === null || submissionLocks[kind]}>{t("开始生成")}</button>
      </div>
      {notice && <p role={notice.startsWith("请输入") ? "alert" : "status"} className="notice">{t(notice)}</p>}
    </form>
    <CreatorSettingsPopover
      open={settingsOpen}
      kind={kind}
      currentModel={model}
      onSelectModel={(nextModel) => {
        requestSwitch(nextModel, kind === "image" ? "image-reference" : mode, "确认切换创作模型");
        setSettingsOpen(false);
      }}
      onClose={() => setSettingsOpen(false)}
    />
    <IncompatibleModelDialog
      pending={pendingSwitch}
      title={t(pendingTitle)}
      onCancel={() => setPendingSwitch(null)}
      onConfirm={() => {
        if (pendingSwitch) applySwitch(pendingSwitch);
        setPendingSwitch(null);
      }}
    />
  </>;
}
