import type {
  CreatorMedia,
  LocalMediaRepository,
  LocalMediaRepositoryOpenState,
} from "./types";

const DATABASE_NAME = "xzwlab-business-media";
const DATABASE_VERSION = 1;
const STORE_NAME = "personal-media";

type LocalMediaRepositoryOptions =
  | { storageMode: "session" }
  | { storageMode: "persistent"; factory: IDBFactory };

const cloneMedia = (media: CreatorMedia): CreatorMedia => ({ ...media });

const createSessionRepository = (): LocalMediaRepository => {
  const mediaById = new Map<string, CreatorMedia>();

  return {
    async list(): Promise<readonly CreatorMedia[]> {
      return [...mediaById.values()].map(cloneMedia);
    },
    async put(media: CreatorMedia): Promise<void> {
      mediaById.set(media.id, cloneMedia(media));
    },
    async remove(id: string): Promise<void> {
      mediaById.delete(id);
    },
    close(): void {},
  };
};

const requestAsPromise = <T>(request: IDBRequest<T>): Promise<T> => new Promise((resolve, reject) => {
  request.onsuccess = () => resolve(request.result);
  request.onerror = () => reject(request.error ?? new Error("IndexedDB request failed"));
});

const transactionAsPromise = (transaction: IDBTransaction): Promise<void> => new Promise((resolve, reject) => {
  transaction.oncomplete = () => resolve();
  transaction.onerror = () => reject(transaction.error ?? new Error("IndexedDB transaction failed"));
  transaction.onabort = () => reject(transaction.error ?? new Error("IndexedDB transaction aborted"));
});

const transact = async <T>(
  database: IDBDatabase,
  mode: IDBTransactionMode,
  operation: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> => {
  const transaction = database.transaction(STORE_NAME, mode);
  const completed = transactionAsPromise(transaction);
  const requested = requestAsPromise(operation(transaction.objectStore(STORE_NAME)));
  const [value] = await Promise.all([requested, completed]);
  return value;
};

const createPersistentRepository = (database: IDBDatabase): LocalMediaRepository => {
  let closed = false;

  return {
    list(): Promise<readonly CreatorMedia[]> {
      return transact(database, "readonly", (store) => store.getAll() as IDBRequest<CreatorMedia[]>);
    },
    async put(media: CreatorMedia): Promise<void> {
      await transact(database, "readwrite", (store) => store.put(media));
    },
    async remove(id: string): Promise<void> {
      await transact(database, "readwrite", (store) => store.delete(id));
    },
    close(): void {
      if (!closed) {
        closed = true;
        database.close();
      }
    },
  };
};

const openDatabase = (factory: IDBFactory): Promise<IDBDatabase> => new Promise((resolve, reject) => {
  let request: IDBOpenDBRequest;
  let outcome: "pending" | "resolved" | "rejected" = "pending";
  try {
    request = factory.open(DATABASE_NAME, DATABASE_VERSION);
  } catch (error) {
    outcome = "rejected";
    reject(error);
    return;
  }

  request.onupgradeneeded = () => {
    try {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME, { keyPath: "id" });
      }
    } catch (error) {
      outcome = "rejected";
      try {
        request.transaction?.abort();
      } catch {
        // The upgrade is already failing; preserve the original error.
      }
      reject(error);
    }
  };
  request.onsuccess = () => {
    if (outcome === "rejected") {
      request.result.close();
      return;
    }
    if (outcome === "pending") {
      outcome = "resolved";
      resolve(request.result);
    }
  };
  request.onerror = () => {
    if (outcome === "pending") {
      outcome = "rejected";
      reject(request.error ?? new Error("IndexedDB open failed"));
    }
  };
});

export const openLocalMediaRepository = async (
  options: LocalMediaRepositoryOptions,
): Promise<LocalMediaRepositoryOpenState> => {
  if (options.storageMode === "session") {
    return {
      storageMode: "session",
      repository: createSessionRepository(),
      reason: "forced",
    };
  }

  try {
    const database = await openDatabase(options.factory);
    return {
      storageMode: "persistent",
      repository: createPersistentRepository(database),
    };
  } catch {
    return {
      storageMode: "session",
      repository: createSessionRepository(),
      reason: "open-failed",
    };
  }
};
