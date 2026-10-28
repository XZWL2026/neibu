/** A bounded worker pool: preserve ordering without flooding the gateway. */
export async function mapPool<T, R>(items: readonly T[], concurrency: number, mapper: (item: T, index: number) => Promise<R>): Promise<R[]> {
  const result = new Array<R>(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(items.length, Math.max(1, concurrency)) }, async () => {
    while (next < items.length) {
      const index = next++;
      result[index] = await mapper(items[index]!, index);
    }
  }));
  return result;
}
