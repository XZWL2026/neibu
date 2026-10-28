import { describe, expect, it, vi } from "vitest";

import { createCreatorMediaSession } from "./CreatorMediaProvider";
import { openLocalMediaRepository } from "./localMediaRepository";
import type { CreatorMedia } from "./types";

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
};

type MutableRequest<T> = {
  result: T;
  error: DOMException | null;
  onsuccess: ((this: IDBRequest<T>, event: Event) => unknown) | null;
  onerror: ((this: IDBRequest<T>, event: Event) => unknown) | null;
};

type MutableOpenRequest = MutableRequest<IDBDatabase> & {
  onupgradeneeded: ((this: IDBOpenDBRequest, event: IDBVersionChangeEvent) => unknown) | null;
  transaction: IDBTransaction | null;
};

type MutableTransaction = {
  error: DOMException | null;
  oncomplete: ((this: IDBTransaction, event: Event) => unknown) | null;
  onerror: ((this: IDBTransaction, event: Event) => unknown) | null;
  onabort: ((this: IDBTransaction, event: Event) => unknown) | null;
  objectStore(name: string): IDBObjectStore;
};

const requestShell = <T>(result: T): MutableRequest<T> => ({
  result,
  error: null,
  onsuccess: null,
  onerror: null,
});

const cloneMedia = (media: CreatorMedia): CreatorMedia => ({
  ...media,
  content: media.content?.slice(0, media.content.size, media.content.type),
});

const createControlledIndexedDbFactory = () => {
  const records = new Map<string, CreatorMedia>();
  let storeCreated = false;
  let failNextWrite = false;
  const stats = {
    openCalls: [] as Array<{ name: string; version: number | undefined }>,
    createdStores: [] as Array<{ name: string; keyPath: string | string[] | null | undefined }>,
    transactionModes: [] as IDBTransactionMode[],
    closeCalls: 0,
  };

  const createTransaction = (mode: IDBTransactionMode): IDBTransaction => {
    const transaction: MutableTransaction = {
      error: null,
      oncomplete: null,
      onerror: null,
      onabort: null,
      objectStore(name: string): IDBObjectStore {
        if (name !== "personal-media" || !storeCreated) {
          throw new DOMException("missing object store", "NotFoundError");
        }

        return {
          getAll(): IDBRequest<CreatorMedia[]> {
            const request = requestShell<CreatorMedia[]>([]);
            queueMicrotask(() => {
              request.result = [...records.values()].map(cloneMedia);
              request.onsuccess?.call(request as unknown as IDBRequest<CreatorMedia[]>, new Event("success"));
              transaction.oncomplete?.call(transaction as unknown as IDBTransaction, new Event("complete"));
            });
            return request as unknown as IDBRequest<CreatorMedia[]>;
          },
          put(value: CreatorMedia): IDBRequest<IDBValidKey> {
            if (mode !== "readwrite") {
              throw new DOMException("readonly transaction", "ReadOnlyError");
            }
            const snapshot = cloneMedia(value);
            const request = requestShell<IDBValidKey>(value.id);
            queueMicrotask(() => {
              if (failNextWrite) {
                failNextWrite = false;
                const error = new DOMException("controlled write failure", "UnknownError");
                request.error = error;
                transaction.error = error;
                request.onerror?.call(request as unknown as IDBRequest<IDBValidKey>, new Event("error"));
                transaction.onerror?.call(transaction as unknown as IDBTransaction, new Event("error"));
                return;
              }
              records.set(snapshot.id, snapshot);
              request.onsuccess?.call(request as unknown as IDBRequest<IDBValidKey>, new Event("success"));
              transaction.oncomplete?.call(transaction as unknown as IDBTransaction, new Event("complete"));
            });
            return request as unknown as IDBRequest<IDBValidKey>;
          },
          delete(id: IDBValidKey): IDBRequest<undefined> {
            if (mode !== "readwrite") {
              throw new DOMException("readonly transaction", "ReadOnlyError");
            }
            const request = requestShell<undefined>(undefined);
            queueMicrotask(() => {
              records.delete(String(id));
              request.onsuccess?.call(request as unknown as IDBRequest<undefined>, new Event("success"));
              transaction.oncomplete?.call(transaction as unknown as IDBTransaction, new Event("complete"));
            });
            return request as unknown as IDBRequest<undefined>;
          },
        } as unknown as IDBObjectStore;
      },
    };
    return transaction as unknown as IDBTransaction;
  };

  const factory = {
    open(name: string, version?: number): IDBOpenDBRequest {
      stats.openCalls.push({ name, version });
      let closed = false;
      const request: MutableOpenRequest = {
        ...requestShell<IDBDatabase>(undefined as unknown as IDBDatabase),
        onupgradeneeded: null,
        transaction: null,
      };
      const database = {
        objectStoreNames: {
          contains(storeName: string): boolean {
            return storeName === "personal-media" && storeCreated;
          },
        },
        createObjectStore(storeName: string, options?: IDBObjectStoreParameters): IDBObjectStore {
          storeCreated = true;
          stats.createdStores.push({ name: storeName, keyPath: options?.keyPath });
          return {} as IDBObjectStore;
        },
        transaction(storeName: string, mode: IDBTransactionMode = "readonly"): IDBTransaction {
          if (closed) {
            throw new DOMException("database connection is closed", "InvalidStateError");
          }
          if (storeName !== "personal-media") {
            throw new DOMException("missing object store", "NotFoundError");
          }
          stats.transactionModes.push(mode);
          return createTransaction(mode);
        },
        close(): void {
          if (!closed) {
            closed = true;
            stats.closeCalls += 1;
          }
        },
      } as unknown as IDBDatabase;

      queueMicrotask(() => {
        request.result = database;
        if (!storeCreated) {
          request.onupgradeneeded?.call(
            request as unknown as IDBOpenDBRequest,
            new Event("upgradeneeded") as IDBVersionChangeEvent,
          );
        }
        request.onsuccess?.call(request as unknown as IDBRequest<IDBDatabase>, new Event("success"));
      });
      return request as unknown as IDBOpenDBRequest;
    },
  } as IDBFactory;

  return {
    factory,
    stats,
    failNextTransaction(): void {
      failNextWrite = true;
    },
  };
};

const openFailingFactory = (): IDBFactory => ({
  open(): IDBOpenDBRequest {
    const request: MutableOpenRequest = {
      ...requestShell<IDBDatabase>(undefined as unknown as IDBDatabase),
      onupgradeneeded: null,
      transaction: null,
    };
    queueMicrotask(() => {
      request.error = new DOMException("controlled open failure", "UnknownError");
      request.onerror?.call(request as unknown as IDBRequest<IDBDatabase>, new Event("error"));
    });
    return request as unknown as IDBOpenDBRequest;
  },
}) as unknown as IDBFactory;

const createUpgradeFailingFactory = () => {
  const stats = { abortCalls: 0, closeCalls: 0 };
  const factory = {
    open(): IDBOpenDBRequest {
      const upgradeTransaction = {
        abort(): void {
          stats.abortCalls += 1;
        },
      } as IDBTransaction;
      const request: MutableOpenRequest = {
        ...requestShell<IDBDatabase>(undefined as unknown as IDBDatabase),
        onupgradeneeded: null,
        transaction: upgradeTransaction,
      };
      const database = {
        objectStoreNames: { contains: () => false },
        createObjectStore(): IDBObjectStore {
          throw new DOMException("controlled upgrade failure", "UnknownError");
        },
        close(): void {
          stats.closeCalls += 1;
        },
      } as unknown as IDBDatabase;

      queueMicrotask(() => {
        request.result = database;
        request.onupgradeneeded?.call(
          request as unknown as IDBOpenDBRequest,
          new Event("upgradeneeded") as IDBVersionChangeEvent,
        );
        queueMicrotask(() => {
          request.onsuccess?.call(request as unknown as IDBRequest<IDBDatabase>, new Event("success"));
        });
      });
      return request as unknown as IDBOpenDBRequest;
    },
  } as unknown as IDBFactory;

  return { factory, stats };
};

const personalPngContent = new Blob(["persistent-png-binary"], { type: "image/png" });

const readBlobText = (blob: Blob): Promise<string> => new Promise((resolve, reject) => {
  const reader = new FileReader();
  reader.onload = () => resolve(String(reader.result));
  reader.onerror = () => reject(reader.error ?? new Error("Blob read failed"));
  reader.readAsText(blob);
});

const personalPng: CreatorMedia = {
  id: "personal-1",
  kind: "image",
  name: "personal.png",
  mimeType: "image/png",
  size: personalPngContent.size,
  origin: "personal",
  storageMode: "persistent",
  content: personalPngContent,
};

describe("local media repository", () => {
  it("initialize 在 dispose 后完成时不 hydrate 或创建 Object URL", async () => {
    const listed = deferred<readonly CreatorMedia[]>();
    const repository = {
      list: vi.fn(() => listed.promise),
      put: vi.fn(async () => {}),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    const create = vi.fn(() => "blob:late-list");
    const revoke = vi.fn();
    const session = createCreatorMediaSession({ storageMode: "persistent", repository }, { create, revoke });

    const initializing = session.initialize();
    session.dispose();
    listed.resolve([personalPng]);
    const state = await initializing;

    expect(state.personal).toEqual([]);
    expect(create).not.toHaveBeenCalled();
    expect(revoke).not.toHaveBeenCalled();
    expect(repository.close).toHaveBeenCalledTimes(1);
  });

  it("put 在 dispose 后完成时释放操作期间 URL 且不写入会话状态", async () => {
    const written = deferred<void>();
    const repository = {
      list: vi.fn(async () => [] as readonly CreatorMedia[]),
      put: vi.fn(() => written.promise),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    const create = vi.fn(() => "blob:pending-put");
    const revoke = vi.fn();
    const session = createCreatorMediaSession({ storageMode: "persistent", repository }, { create, revoke });
    await session.initialize();

    const importing = session.importMedia(personalPng);
    await vi.waitFor(() => expect(repository.put).toHaveBeenCalledTimes(1));
    session.dispose();
    written.resolve();
    const state = await importing;

    expect(state.personal).toEqual([]);
    expect(create).toHaveBeenCalledTimes(1);
    expect(revoke).toHaveBeenCalledTimes(1);
    expect(revoke).toHaveBeenCalledWith("blob:pending-put");
  });

  it("前一项写失败后后一项写成功仍保留 shadow 与 write-failed", async () => {
    const records = new Map<string, CreatorMedia>();
    let writes = 0;
    const repository = {
      list: vi.fn(async () => [...records.values()]),
      put: vi.fn(async (media: CreatorMedia) => {
        writes += 1;
        if (writes === 1) throw new Error("first write failed");
        records.set(media.id, media);
      }),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    const session = createCreatorMediaSession({ storageMode: "persistent", repository }, { create: (blob) => `blob:${blob.size}:${writes}`, revoke: vi.fn() });
    const first = { ...personalPng, id: "shadow-first", name: "shadow-first.png" };
    const second = { ...personalPng, id: "persistent-second", name: "persistent-second.png" };

    await session.importMedia(first);
    const state = await session.importMedia(second);

    expect(state.personal.map(({ id, storageMode }) => ({ id, storageMode }))).toEqual([
      { id: first.id, storageMode: "session" },
      { id: second.id, storageMode: "persistent" },
    ]);
    expect(state.notice).toMatchObject({ kind: "write-failed" });
    session.dispose();
  });

  it("连续写失败会累积全部 session shadows", async () => {
    const repository = {
      list: vi.fn(async () => [] as readonly CreatorMedia[]),
      put: vi.fn(async () => { throw new Error("write failed"); }),
      remove: vi.fn(async () => {}),
      close: vi.fn(),
    };
    const session = createCreatorMediaSession({ storageMode: "persistent", repository }, { create: (blob) => `blob:${blob.size}:${repository.put.mock.calls.length}`, revoke: vi.fn() });
    const first = { ...personalPng, id: "shadow-one" };
    const second = { ...personalPng, id: "shadow-two" };

    await session.importMedia(first);
    const state = await session.importMedia(second);

    expect(state.personal.map((item) => item.id)).toEqual([first.id, second.id]);
    expect(state.personal.every((item) => item.storageMode === "session")).toBe(true);
    expect(state.notice).toMatchObject({ kind: "write-failed" });
    session.dispose();
  });

  it("持久删除失败重读时仍合并既有 shadow", async () => {
    const records = new Map([[personalPng.id, personalPng]]);
    const repository = {
      list: vi.fn(async () => [...records.values()]),
      put: vi.fn(async () => { throw new Error("write failed"); }),
      remove: vi.fn(async () => { throw new Error("remove failed"); }),
      close: vi.fn(),
    };
    const session = createCreatorMediaSession({ storageMode: "persistent", repository }, { create: (blob) => `blob:${blob.size}:${repository.list.mock.calls.length}`, revoke: vi.fn() });
    const shadow = { ...personalPng, id: "existing-shadow" };
    await session.importMedia(shadow);

    const state = await session.removeMedia(personalPng.id);

    expect(state.personal.map((item) => item.id)).toEqual([personalPng.id, shadow.id]);
    expect(state.notice).toMatchObject({ kind: "write-failed" });
    session.dispose();
  });
  it("首次建库、写入、关闭后用同一驱动重开恢复，并可删除", async () => {
    const driver = createControlledIndexedDbFactory();
    const first = await openLocalMediaRepository({ storageMode: "persistent", factory: driver.factory });

    expect(first.storageMode).toBe("persistent");
    if (first.storageMode !== "persistent") {
      throw new Error("expected persistent repository");
    }
    expect(driver.stats.openCalls).toEqual([{ name: "xzwlab-business-media", version: 1 }]);
    expect(driver.stats.createdStores).toEqual([{ name: "personal-media", keyPath: "id" }]);
    expect("putWithPreview" in first.repository).toBe(false);

    await first.repository.put(personalPng);
    expect(driver.stats.transactionModes[0]).toBe("readwrite");
    expect(await first.repository.list()).toEqual([personalPng]);
    first.repository.close();
    expect(driver.stats.closeCalls).toBe(1);

    const reopened = await openLocalMediaRepository({ storageMode: "persistent", factory: driver.factory });
    expect(reopened.storageMode).toBe("persistent");
    if (reopened.storageMode !== "persistent") {
      throw new Error("expected reopened persistent repository");
    }
    expect(driver.stats.createdStores).toHaveLength(1);
    expect(await reopened.repository.list()).toEqual([personalPng]);
    const [restored] = await reopened.repository.list();
    expect(restored.content).toBeInstanceOf(Blob);
    if (!restored.content) {
      throw new Error("expected restored Blob content");
    }
    expect(restored.content.type).toBe("image/png");
    expect(await readBlobText(restored.content)).toBe("persistent-png-binary");

    await reopened.repository.remove(personalPng.id);
    expect(driver.stats.transactionModes.filter((mode) => mode === "readwrite")).toHaveLength(2);
    expect(await reopened.repository.list()).toEqual([]);
    reopened.repository.close();
  });

  it("open 失败只降级为会话仓库", async () => {
    const opened = await openLocalMediaRepository({
      storageMode: "persistent",
      factory: openFailingFactory(),
    });

    expect(opened).toMatchObject({ storageMode: "session", reason: "open-failed" });
    await opened.repository.put(personalPng);
    expect(await opened.repository.list()).toEqual([personalPng]);
  });

  it("升级建库异常时 abort，并关闭迟到 success 暴露的连接", async () => {
    const driver = createUpgradeFailingFactory();

    const opened = await openLocalMediaRepository({
      storageMode: "persistent",
      factory: driver.factory,
    });
    await new Promise<void>((resolve) => queueMicrotask(resolve));

    expect(opened).toMatchObject({ storageMode: "session", reason: "open-failed" });
    expect(driver.stats.abortCalls).toBe(1);
    expect(driver.stats.closeCalls).toBe(1);
  });

  it("put 立即快照，并让源对象、源 Blob 与每次 getAll 返回值相互隔离", async () => {
    const driver = createControlledIndexedDbFactory();
    const opened = await openLocalMediaRepository({ storageMode: "persistent", factory: driver.factory });
    if (opened.storageMode !== "persistent") {
      throw new Error("expected persistent repository");
    }
    const sourceContent = new Blob(["isolated-binary"], { type: "image/png" });
    const source: CreatorMedia = {
      ...personalPng,
      id: "isolated",
      name: "before-put.png",
      content: sourceContent,
    };

    const pendingPut = opened.repository.put(source);
    source.name = "mutated-after-put.png";
    await pendingPut;

    const [firstRead] = await opened.repository.list();
    expect(firstRead.name).toBe("before-put.png");
    expect(firstRead).not.toBe(source);
    expect(firstRead.content).not.toBe(sourceContent);
    if (!firstRead.content) {
      throw new Error("expected first isolated Blob");
    }
    expect(await readBlobText(firstRead.content)).toBe("isolated-binary");

    firstRead.name = "mutated-after-read.png";
    const [secondRead] = await opened.repository.list();
    expect(secondRead.name).toBe("before-put.png");
    expect(secondRead).not.toBe(firstRead);
    expect(secondRead.content).not.toBe(firstRead.content);
  });

  it("close 后受控驱动拒绝新事务", async () => {
    const driver = createControlledIndexedDbFactory();
    const opened = await openLocalMediaRepository({ storageMode: "persistent", factory: driver.factory });
    if (opened.storageMode !== "persistent") {
      throw new Error("expected persistent repository");
    }

    opened.repository.close();

    await expect(opened.repository.list()).rejects.toThrow();
  });

  it("显式 session 模式不读取或触发工厂", async () => {
    let factoryTouches = 0;
    const options = new Proxy({ storageMode: "session" } as const, {
      get(target, property, receiver) {
        if (property === "factory") {
          factoryTouches += 1;
        }
        return Reflect.get(target, property, receiver);
      },
    });

    const opened = await openLocalMediaRepository(options);
    expect(opened.storageMode).toBe("session");
    expect(opened).toMatchObject({ reason: "forced" });
    expect(factoryTouches).toBe(0);
  });

  it("write 失败保留旧持久素材并把新增素材降为会话影子", async () => {
    const writeFailingFactory = createControlledIndexedDbFactory();
    const opened = await openLocalMediaRepository({ storageMode: "persistent", factory: writeFailingFactory.factory });
    if (opened.storageMode !== "persistent") {
      throw new Error("expected persistent repository");
    }
    await opened.repository.put(personalPng);
    writeFailingFactory.failNextTransaction();
    const revoke = vi.fn();
    const session = createCreatorMediaSession(opened, {
      create: (content) => `blob:${content.size}`,
      revoke,
    });
    const newContent = new Blob(["new-session-shadow"], { type: "image/png" });
    const newPersonalPng: CreatorMedia = {
      id: "personal-new",
      kind: "image",
      name: "new.png",
      mimeType: "image/png",
      size: newContent.size,
      content: newContent,
      origin: "personal",
    };

    const state = await session.importMedia(newPersonalPng);

    expect(state.notice).toMatchObject({ kind: "write-failed" });
    expect(await opened.repository.list()).toEqual([personalPng]);
    expect(state.personal.map(({ id, storageMode }) => ({ id, storageMode }))).toEqual([
      { id: personalPng.id, storageMode: "persistent" },
      { id: newPersonalPng.id, storageMode: "session" },
    ]);
    expect(state.personal.every((item) => item.previewUrl === `blob:${item.content?.size}`)).toBe(true);
    state.dispose();
    expect(revoke).toHaveBeenCalledTimes(2);
    expect(new Set(revoke.mock.calls.map(([url]) => url))).toEqual(new Set([`blob:${personalPng.size}`, `blob:${newContent.size}`]));
  });

  it("相同 Object URL 在会话释放时只 revoke 一次", async () => {
    const driver = createControlledIndexedDbFactory();
    const opened = await openLocalMediaRepository({ storageMode: "persistent", factory: driver.factory });
    if (opened.storageMode !== "persistent") {
      throw new Error("expected persistent repository");
    }
    await opened.repository.put(personalPng);
    await opened.repository.put({ ...personalPng, id: "personal-2", name: "personal-2.png" });
    const revoke = vi.fn();
    const session = createCreatorMediaSession(opened, { create: () => "blob:shared", revoke });

    const state = await session.initialize();
    expect(state.personal).toHaveLength(2);
    state.dispose();
    state.dispose();

    expect(revoke).toHaveBeenCalledTimes(1);
    expect(revoke).toHaveBeenCalledWith("blob:shared");
  });

  it("open 失败的会话发布 session-fallback 通知", async () => {
    const opened = await openLocalMediaRepository({ storageMode: "persistent", factory: openFailingFactory() });
    const session = createCreatorMediaSession(opened, { create: () => "blob:fallback", revoke: vi.fn() });

    expect((await session.initialize()).notice).toMatchObject({ kind: "session-fallback" });
    session.dispose();
  });
});

it('refreshes material imports and deletions made by another session of the shared repository', async () => {
  const records = new Map<string, CreatorMedia>();
  const storage = { storageMode: 'persistent' as const, repository: {
    list: async () => [...records.values()], put: async (item: CreatorMedia) => { records.set(item.id, item); },
    remove: async (id: string) => { records.delete(id); }, close() {},
  } };
  const urls = { create: vi.fn(() => 'blob:shared'), revoke: vi.fn() };
  const canvas = createCreatorMediaSession(storage, urls);
  const video = createCreatorMediaSession(storage, urls);
  await canvas.initialize(); await video.initialize();
  await canvas.importMedia({ id: 'shared', kind: 'image', name: '狗.png', mimeType: 'image/png', size: 3, content: new Blob(['dog']) });
  expect((await video.refresh()).personal.map(item => item.name)).toEqual(['狗.png']);
  await canvas.removeMedia('shared');
  expect((await video.refresh()).personal).toEqual([]);
  canvas.dispose(); video.dispose();
});
