export function groupImageModels<T extends { model: string; displayName: string }>(models: T[]) {
  const groups = new Map<string, { key: string; displayName: string; variants: T[] }>();
  for (const option of models) {
    const family = ['gpt-image-2', 'gpt-image-2-2K', 'gpt-image-2-4K'].includes(option.model) ? 'gpt-image-2'
      : ['nano_banana_pro-1K', 'nano_banana_pro-2K', 'nano_banana_pro-4K', 'banana_pro-1K', 'banana_pro-2K', 'banana_pro-4K'].includes(option.model) ? 'nano-banana-pro' : null;
    const key = family ?? option.model;
    const group = groups.get(key) ?? { key, displayName: family ?? option.displayName, variants: [] };
    group.variants.push(option);
    groups.set(key, group);
  }
  return [...groups.values()];
}
