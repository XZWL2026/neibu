import type { CreatorMedia } from "./types";

const cyanPosterUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAACXBIWXMAAAABAAAAAQBPJcTWAAAAEklEQVR4nGOUO3mJgYGBhQEMABQwAb8imdECAAAAAElFTkSuQmCC";
const goldenFrameUrl = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjI4LjEwMgD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABMAAEBAAAAAAAAAAAAAAAAAAAABgEBAQAAAAAAAAAAAAAAAAAABQYQAQAAAAAAAAAAAAAAAAAAAAARAQAAAAAAAAAAAAAAAAAAAAD/wAARCAACAAIDASIAAhEAAxEA/9oADAMBAAIRAxEAPwCqARh9/9k=";
const paperTextureUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAACXBIWXMAAAABAAAAAQBPJcTWAAAAE0lEQVR4nGN8fvc4AwMDCwMYAAAe3AKRWY16DgAAAABJRU5ErkJggg==";

const inlineFixtureBlob = (previewUrl: string, mimeType: string): Blob => {
  const encoded = previewUrl.slice(previewUrl.indexOf(",") + 1);
  const bytes = Uint8Array.from(atob(encoded), (character) => character.charCodeAt(0));
  return new Blob([bytes], { type: mimeType });
};

const cyanPoster = inlineFixtureBlob(cyanPosterUrl, "image/png");
const goldenFrame = inlineFixtureBlob(goldenFrameUrl, "image/jpeg");
const paperTexture = inlineFixtureBlob(paperTextureUrl, "image/png");

export const publicCreatorMedia: readonly CreatorMedia[] = [
  {
    id: "public-cyan-poster",
    kind: "image",
    name: "青色海报.png",
    mimeType: "image/png",
    size: cyanPoster.size,
    content: cyanPoster,
    previewUrl: cyanPosterUrl,
    origin: "public",
  },
  {
    id: "public-golden-frame",
    kind: "image",
    name: "金色电影帧.jpg",
    mimeType: "image/jpeg",
    size: goldenFrame.size,
    content: goldenFrame,
    previewUrl: goldenFrameUrl,
    origin: "public",
  },
  {
    id: "public-paper-texture",
    kind: "image",
    name: "纸张纹理.png",
    mimeType: "image/png",
    size: paperTexture.size,
    content: paperTexture,
    previewUrl: paperTextureUrl,
    origin: "public",
  },
];
