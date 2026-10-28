export type HomeCarousel = { select(index: number): void; step(delta: number): void; togglePaused(): boolean; dispose(): void };
export function mountCarousel(root: HTMLElement, items: readonly { label: string }[], onChange: (index: number) => void, onActivate: (index: number) => void): HomeCarousel;
