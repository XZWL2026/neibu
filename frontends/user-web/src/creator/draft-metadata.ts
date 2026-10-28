/** Reopening an unchanged draft must not make it newer than the user's last edit. */
export function draftTimestamp(current: object, previous: { updatedAt?: number } | null): number {
  if (previous) {
    const { updatedAt, ...content } = previous;
    if (JSON.stringify(content) === JSON.stringify(current)) return Number.isFinite(updatedAt) ? updatedAt! : 0;
  }
  return Date.now();
}
