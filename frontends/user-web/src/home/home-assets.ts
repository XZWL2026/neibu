// Vite fingerprints these derivatives; original artwork remains in public/homepage-v2.
const images = import.meta.glob<string>('../assets/home-performance/**/*.webp', {
  eager: true, query: '?url', import: 'default'
});
export function homeImage(name: string): string {
  const key = `../assets/home-performance/${name.replace(/\.png$/, '.webp')}`;
  const image = images[key];
  if (!image) throw new Error(`Missing homepage image: ${name}`);
  return image;
}
