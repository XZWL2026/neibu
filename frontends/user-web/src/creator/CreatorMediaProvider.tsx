import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PropsWithChildren,
} from "react";

import { openLocalMediaRepository } from "./localMediaRepository";
import type {
  AccountType,
  CreatorMedia,
  CreatorNotice,
  CreatorTask,
  CreatorTaskState,
  LocalMediaRepository,
  LocalMediaRepositoryOpenState,
  ObjectUrlLifecycle,
} from "./types";

type CreatorMediaSessionSnapshot = {
  personal: readonly CreatorMedia[];
  notice: CreatorNotice;
  removed?: boolean;
  dispose(): void;
};

type CreatorMediaSession = {
  initialize(): Promise<CreatorMediaSessionSnapshot>;
  refresh(): Promise<CreatorMediaSessionSnapshot>;
  importMedia(media: CreatorMedia): Promise<CreatorMediaSessionSnapshot>;
  removeMedia(id: string): Promise<CreatorMediaSessionSnapshot>;
  dispose(): void;
};

const persistentMedia = (media: CreatorMedia): CreatorMedia => {
  const { previewUrl: _previewUrl, ...stored } = media;
  return { ...stored, origin: "personal", storageMode: "persistent" };
};

export const createCreatorMediaSession = (
  storage: LocalMediaRepositoryOpenState,
  objectUrls: ObjectUrlLifecycle,
): CreatorMediaSession => {
  let personal: CreatorMedia[] = [];
  let initialized = false;
  let disposed = false;
  let notice: CreatorNotice = storage.storageMode === "session" && storage.reason !== "forced"
    ? { kind: "session-fallback", message: "持久素材库不可用，当前素材仅保留在本次会话" }
    : null;
  const createdUrls = new Set<string>();
  const revokedUrls = new Set<string>();
  const sessionShadows = new Map<string, CreatorMedia>();
  let initialization: Promise<boolean> | null = null;
  let operationQueue: Promise<void> = Promise.resolve();

  const writeFailedNotice: Exclude<CreatorNotice, null> = {
    kind: "write-failed",
    message: "素材未写入持久库，已仅保留在本次会话",
  };

  const createPreview = (media: CreatorMedia): CreatorMedia => {
    if (!media.content) return { ...media };
    const previewUrl = objectUrls.create(media.content);
    createdUrls.add(previewUrl);
    return { ...media, previewUrl };
  };

  const release = (url: string | undefined): void => {
    if (!url || !createdUrls.has(url) || revokedUrls.has(url)) return;
    revokedUrls.add(url);
    objectUrls.revoke(url);
  };

  const releaseUnused = (previous: readonly CreatorMedia[], next: readonly CreatorMedia[]): void => {
    const active = new Set(next.map((item) => item.previewUrl).filter((url): url is string => Boolean(url)));
    previous.forEach((item) => {
      if (item.previewUrl && !active.has(item.previewUrl)) release(item.previewUrl);
    });
  };

  const hydrateStored = (records: readonly CreatorMedia[]): CreatorMedia[] => records.map((record) => {
    const existing = personal.find((item) => item.id === record.id && item.previewUrl);
    if (existing?.previewUrl) {
      return { ...record, origin: "personal", storageMode: storage.storageMode, previewUrl: existing.previewUrl };
    }
    return createPreview({ ...record, origin: "personal", storageMode: storage.storageMode, previewUrl: undefined });
  });

  const mergePersistentAndShadows = (records: readonly CreatorMedia[]): CreatorMedia[] => {
    const shadows = [...sessionShadows.values()];
    return [
      ...hydrateStored(records.filter((item) => !sessionShadows.has(item.id))),
      ...shadows,
    ];
  };

  const refreshWriteNotice = (): void => {
    notice = sessionShadows.size > 0 ? writeFailedNotice : null;
  };

  const snapshot = (removed?: boolean): CreatorMediaSessionSnapshot => ({
    personal: personal.map((item) => ({ ...item })),
    notice,
    removed,
    dispose,
  });

  const ensureInitialized = async (): Promise<boolean> => {
    if (disposed) return false;
    if (initialized) return true;
    if (!initialization) {
      initialization = (async () => {
        const records = await storage.repository.list();
        if (disposed) return false;
        personal = storage.storageMode === "persistent"
          ? mergePersistentAndShadows(records)
          : hydrateStored(records);
        initialized = true;
        return true;
      })();
    }
    return initialization;
  };

  const enqueue = <Value,>(operation: () => Promise<Value>): Promise<Value> => {
    const result = operationQueue.then(operation, operation);
    operationQueue = result.then(() => undefined, () => undefined);
    return result;
  };

  async function initializeInternal(): Promise<CreatorMediaSessionSnapshot> {
    await ensureInitialized();
    return snapshot();
  }

  async function importMediaInternal(media: CreatorMedia): Promise<CreatorMediaSessionSnapshot> {
    if (!await ensureInitialized() || disposed) return snapshot();
    const nextPreview = createPreview({ ...media, origin: "personal" });
    const previous = personal;

    if (storage.storageMode === "session") {
      const next = { ...nextPreview, storageMode: "session" as const };
      await storage.repository.put(next);
      if (disposed) return snapshot();
      personal = [...previous.filter((item) => item.id !== next.id), next];
      releaseUnused(previous, personal);
      return snapshot();
    }

    try {
      await storage.repository.put(persistentMedia(media));
      if (disposed) return snapshot();
      sessionShadows.delete(media.id);
      const next = { ...nextPreview, storageMode: "persistent" as const };
      personal = [...previous.filter((item) => item.id !== next.id), next];
      refreshWriteNotice();
    } catch {
      if (disposed) return snapshot();
      const shadow = { ...nextPreview, storageMode: "session" as const };
      sessionShadows.set(shadow.id, shadow);
      try {
        const records = await storage.repository.list();
        if (disposed) return snapshot();
        personal = mergePersistentAndShadows(records);
      } catch {
        if (disposed) return snapshot();
        personal = [...previous.filter((item) => item.id !== shadow.id), shadow];
      }
      notice = writeFailedNotice;
    }
    releaseUnused(previous, personal);
    return snapshot();
  }

  async function removeMediaInternal(id: string): Promise<CreatorMediaSessionSnapshot> {
    if (!await ensureInitialized() || disposed) return snapshot(false);
    const previous = personal;
    const removed = previous.find((item) => item.id === id);
    if (!removed) return snapshot(false);

    if (sessionShadows.has(id)) {
      sessionShadows.delete(id);
      personal = previous.filter((item) => item.id !== id);
      refreshWriteNotice();
      releaseUnused(previous, personal);
      return snapshot(true);
    }

    try {
      if (removed.storageMode === "persistent") await storage.repository.remove(id);
      else if (storage.storageMode === "session") await storage.repository.remove(id);
      if (disposed) return snapshot(false);
      personal = previous.filter((item) => item.id !== id);
      if (storage.storageMode === "persistent") refreshWriteNotice();
      releaseUnused(previous, personal);
    } catch {
      if (disposed) return snapshot(false);
      try {
        const records = await storage.repository.list();
        if (disposed) return snapshot(false);
        personal = storage.storageMode === "persistent"
          ? mergePersistentAndShadows(records)
          : previous;
      } catch {
        if (disposed) return snapshot(false);
        personal = previous;
      }
      notice = { kind: "write-failed", message: "素材删除未写入持久库，已恢复原有素材" };
      releaseUnused(previous, personal);
      return snapshot(false);
    }
    return snapshot(true);
  }

  function dispose(): void {
    if (disposed) return;
    disposed = true;
    createdUrls.forEach(release);
    storage.repository.close();
  }

  return {
    initialize: () => enqueue(initializeInternal),
    refresh: () => enqueue(async () => {
      if (!await ensureInitialized() || disposed) return snapshot();
      const records = await storage.repository.list();
      if (disposed) return snapshot();
      const previous = personal;
      personal = storage.storageMode === "persistent" ? mergePersistentAndShadows(records) : hydrateStored(records);
      releaseUnused(previous, personal);
      return snapshot();
    }),
    importMedia: (media) => enqueue(() => importMediaInternal(media)),
    removeMedia: (id) => enqueue(() => removeMediaInternal(id)),
    dispose,
  };
};

const createUnavailableSessionStorage = (
  reason: "unavailable" | "open-failed" = "unavailable",
): LocalMediaRepositoryOpenState => {
  const records = new Map<string, CreatorMedia>();
  const repository: LocalMediaRepository = {
    async list() { return [...records.values()].map((item) => ({ ...item })); },
    async put(media) { records.set(media.id, { ...media }); },
    async remove(id) { records.delete(id); },
    close() {},
  };
  return { storageMode: "session", repository, reason };
};

const defaultObjectUrls = (): ObjectUrlLifecycle => {
  let fallbackId = 0;
  return {
    create(value) {
      return typeof URL.createObjectURL === "function"
        ? URL.createObjectURL(value)
        : `blob:creator-${++fallbackId}`;
    },
    revoke(url) {
      if (typeof URL.revokeObjectURL === "function") URL.revokeObjectURL(url);
    },
  };
};

export type CreatorMediaContextValue = {
  accountType: AccountType;
  storage: LocalMediaRepositoryOpenState | null;
  publicMedia: readonly CreatorMedia[];
  personalMedia: readonly CreatorMedia[];
  notice: CreatorNotice;
  tasks: Readonly<Record<"image" | "video", readonly CreatorTask[]>>;
  submissionLocks: Readonly<Record<"image" | "video", boolean>>;
  tryAcquireSubmissionLock(kind: "image" | "video"): boolean;
  releaseSubmissionLock(kind: "image" | "video"): void;
  allocateTaskId(): string;
  addTask(task: CreatorTask): void;
  updateTask(id: string, state: CreatorTaskState): void;
  scheduleTaskTransitions(id: string, onGenerating?: () => void): void;
  importMedia(media: CreatorMedia): Promise<CreatorMedia | null>;
  removeMedia(id: string): Promise<boolean>;
  refreshMedia(): Promise<readonly CreatorMedia[]>;
};

const CreatorMediaContext = createContext<CreatorMediaContextValue | null>(null);

export type CreatorMediaProviderProps = PropsWithChildren<{
  accountType?: AccountType;
  storage?: LocalMediaRepositoryOpenState;
  openStorage?: () => Promise<LocalMediaRepositoryOpenState>;
  publicMedia?: readonly CreatorMedia[];
  objectUrls?: ObjectUrlLifecycle;
}>;

export function CreatorMediaProvider({
  accountType = "PERSONAL",
  storage: injectedStorage,
  openStorage,
  publicMedia = [],
  objectUrls,
  children,
}: CreatorMediaProviderProps) {
  const [storage, setStorage] = useState<LocalMediaRepositoryOpenState | null>(null);
  const [personalMedia, setPersonalMedia] = useState<readonly CreatorMedia[]>([]);
  const [notice, setNotice] = useState<CreatorNotice>(null);
  const [tasks, setTasks] = useState<Record<"image" | "video", CreatorTask[]>>({ image: [], video: [] });
  const [submissionLocks, setSubmissionLocks] = useState<Record<"image" | "video", boolean>>({ image: false, video: false });
  const submissionLockRef = useRef<Record<"image" | "video", boolean>>({ image: false, video: false });
  const nextTaskIdRef = useRef(1);
  const lifecycleRef = useRef(objectUrls ?? defaultObjectUrls());
  const sessionRef = useRef<CreatorMediaSession | null>(null);
  const generationRef = useRef(0);
  const taskTimersRef = useRef(new Map<string, readonly [ReturnType<typeof setTimeout>, ReturnType<typeof setTimeout>]>());
  const readySessionRef = useRef<Promise<{ session: CreatorMediaSession; token: number } | null>>(Promise.resolve(null));

  useEffect(() => {
    const token = ++generationRef.current;
    let active = true;
    let session: CreatorMediaSession | null = null;
    setStorage(null);
    setPersonalMedia([]);
    setNotice(null);

    const ready = (async (): Promise<{ session: CreatorMediaSession; token: number } | null> => {
      let opened: LocalMediaRepositoryOpenState;
      try {
        opened = injectedStorage
          ?? await (openStorage
            ? openStorage()
            : typeof globalThis.indexedDB === "undefined"
              ? Promise.resolve(createUnavailableSessionStorage())
              : openLocalMediaRepository({ storageMode: "persistent", factory: globalThis.indexedDB }));
      } catch {
        opened = createUnavailableSessionStorage("open-failed");
      }
      if (!active || generationRef.current !== token) {
        opened.repository.close();
        return null;
      }

      session = createCreatorMediaSession(opened, lifecycleRef.current);
      sessionRef.current = session;
      let state: CreatorMediaSessionSnapshot;
      try {
        state = await session.initialize();
      } catch {
        session.dispose();
        if (!active || generationRef.current !== token || sessionRef.current !== session) return null;
        opened = createUnavailableSessionStorage("open-failed");
        session = createCreatorMediaSession(opened, lifecycleRef.current);
        sessionRef.current = session;
        state = await session.initialize();
      }
      if (!active || generationRef.current !== token || sessionRef.current !== session) {
        session.dispose();
        return null;
      }
      setStorage(opened);
      setPersonalMedia(state.personal);
      setNotice(state.notice);
      return { session, token };
    })().catch(() => null);
    readySessionRef.current = ready;

    return () => {
      active = false;
      if (generationRef.current === token) generationRef.current += 1;
      session?.dispose();
      if (sessionRef.current === session) sessionRef.current = null;
    };
  }, [injectedStorage, openStorage]);

  const addTask = useCallback((task: CreatorTask) => {
    setTasks((current) => ({
      ...current,
      [task.kind]: current[task.kind].some((item) => item.id === task.id)
        ? current[task.kind]
        : [...current[task.kind], task],
    }));
  }, []);

  const allocateTaskId = useCallback((): string => `task-${nextTaskIdRef.current++}`, []);

  const tryAcquireSubmissionLock = useCallback((kind: "image" | "video"): boolean => {
    if (submissionLockRef.current[kind]) return false;
    submissionLockRef.current[kind] = true;
    setSubmissionLocks((current) => ({ ...current, [kind]: true }));
    return true;
  }, []);

  const releaseSubmissionLock = useCallback((kind: "image" | "video") => {
    if (!submissionLockRef.current[kind]) return;
    submissionLockRef.current[kind] = false;
    setSubmissionLocks((current) => ({ ...current, [kind]: false }));
  }, []);

  const updateTask = useCallback((taskId: string, state: CreatorTaskState) => {
    setTasks((current) => ({
      image: current.image.map((task) => task.id === taskId ? { ...task, state } : task),
      video: current.video.map((task) => task.id === taskId ? { ...task, state } : task),
    }));
  }, []);

  const scheduleTaskTransitions = useCallback((taskId: string, onGenerating?: () => void) => {
    if (taskTimersRef.current.has(taskId)) return;
    const generatingTimer = setTimeout(() => {
      updateTask(taskId, "generating");
      onGenerating?.();
    }, 500);
    const completedTimer = setTimeout(() => {
      updateTask(taskId, "completed");
      taskTimersRef.current.delete(taskId);
    }, 1000);
    taskTimersRef.current.set(taskId, [generatingTimer, completedTimer]);
  }, [updateTask]);

  useEffect(() => () => {
    taskTimersRef.current.forEach(([generating, completed]) => {
      clearTimeout(generating);
      clearTimeout(completed);
    });
    taskTimersRef.current.clear();
  }, []);

  const importMedia = useCallback(async (media: CreatorMedia): Promise<CreatorMedia | null> => {
    const ready = await readySessionRef.current;
    if (!ready) return null;
    const { session, token } = ready;
    const state = await session.importMedia(media);
    if (generationRef.current !== token || sessionRef.current !== session) return null;
    setPersonalMedia(state.personal);
    setNotice(state.notice);
    return state.personal.find((item) => item.id === media.id) ?? null;
  }, []);

  const refreshMedia = useCallback(async (): Promise<readonly CreatorMedia[]> => {
    const ready = await readySessionRef.current;
    if (!ready) return [];
    const { session, token } = ready;
    try {
      const state = await session.refresh();
      if (generationRef.current !== token || sessionRef.current !== session) return [];
      setPersonalMedia(state.personal); setNotice(state.notice);
      return state.personal;
    } catch { return []; }
  }, []);

  const removeMedia = useCallback(async (id: string): Promise<boolean> => {
    const ready = await readySessionRef.current;
    if (!ready) return false;
    const { session, token } = ready;
    const state = await session.removeMedia(id);
    if (generationRef.current !== token || sessionRef.current !== session) return false;
    setPersonalMedia(state.personal);
    setNotice(state.notice);
    return state.removed === true;
  }, []);

  const value = useMemo<CreatorMediaContextValue>(() => ({
    accountType,
    storage,
    publicMedia,
    personalMedia,
    notice,
    tasks,
    submissionLocks,
    tryAcquireSubmissionLock,
    releaseSubmissionLock,
    allocateTaskId,
    addTask,
    updateTask,
    scheduleTaskTransitions,
    importMedia,
    removeMedia,
    refreshMedia,
  }), [accountType, storage, publicMedia, personalMedia, notice, tasks, submissionLocks, tryAcquireSubmissionLock, releaseSubmissionLock, allocateTaskId, addTask, updateTask, scheduleTaskTransitions, importMedia, removeMedia, refreshMedia]);

  return <CreatorMediaContext.Provider value={value}>{children}</CreatorMediaContext.Provider>;
}

export const useCreatorMedia = (): CreatorMediaContextValue => {
  const value = useContext(CreatorMediaContext);
  if (!value) throw new Error("useCreatorMedia must be used inside CreatorMediaProvider");
  return value;
};

export const useOptionalCreatorMedia = (): CreatorMediaContextValue | null => useContext(CreatorMediaContext);
