// Own all resources created by the supplied standalone renderers for one React mount.
// No page-wide monkey patches or synthetic pagehide events are needed on navigation.
export function createEffectScope() {
  let alive = true;
  const frames = new Set(), cleanup = [], disposers = [];
  const observer = (Type, callback) => {
    const value = new Type((...args) => { if (alive) callback(...args); });
    cleanup.push(() => value.disconnect());
    return value;
  };
  const scope = {
    frame(callback) {
      if (!alive) return 0;
      const id = requestAnimationFrame(time => { frames.delete(id); if (alive) callback(time); });
      frames.add(id);
      return id;
    },
    cancelFrame(id) { cancelAnimationFrame(id); frames.delete(id); },
    listen(target, name, callback, options) {
      target.addEventListener(name, callback, options);
      cleanup.push(() => target.removeEventListener(name, callback, options));
    },
    resize: callback => observer(ResizeObserver, callback),
    intersection: callback => observer(IntersectionObserver, callback),
    mutation: callback => observer(MutationObserver, callback),
    onDispose(callback) { disposers.push(callback); },
    dispose() {
      if (!alive) return;
      alive = false;
      // Remove context-loss listeners before releasing WebGL contexts.
      cleanup.reverse().forEach(stop => stop());
      disposers.reverse().forEach(stop => stop({ persisted: false }));
      frames.forEach(cancelAnimationFrame);
      frames.clear();
    },
  };
  return scope;
}
