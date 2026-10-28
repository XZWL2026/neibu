export const isSafeCanvasPreviewRef = (value: unknown): value is string => {
  if (typeof value !== "string" || !value.startsWith("https://") || value.length > 2048 || /[\s\\]/.test(value)) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && Boolean(url.hostname) && !url.username && !url.password && !url.hash;
  } catch { return false; }
};
