export type ClipboardAdapter = { copy: (text: string) => Promise<void> };
export type CopyLifecycle = {
  isActive: () => boolean;
  success: () => void;
  failure: () => void;
  settled: () => void;
};

export async function copyPromotionWithLifecycle(clipboard: ClipboardAdapter, text: string, lifecycle: CopyLifecycle) {
  try {
    await clipboard.copy(text);
    if (lifecycle.isActive()) lifecycle.success();
  } catch {
    if (lifecycle.isActive()) lifecycle.failure();
  } finally {
    if (lifecycle.isActive()) lifecycle.settled();
  }
}

export function createClipboardAdapter(): ClipboardAdapter {
  return {
    async copy(text: string) {
      if (!navigator.clipboard?.writeText) throw new Error("clipboard unavailable");
      await navigator.clipboard.writeText(text);
    }
  };
}
