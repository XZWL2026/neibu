import editorialAtlas from '../assets/commerce/optimized/editorial-atlas.webp';

export function EditorialArt({ panel }: { panel: number }) {
  return <svg className="commerce-art" viewBox={`${panel % 4} ${Math.floor(panel / 4)} 1 1`} preserveAspectRatio="xMidYMid slice" aria-hidden="true">
    <image href={editorialAtlas} width="4" height="2" />
  </svg>;
}
