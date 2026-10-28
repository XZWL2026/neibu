import type { ReactNode } from 'react';
import Prism from './Prism';
import './canvas-prism.css';

type Props = { theme: 'light' | 'dark'; children?: ReactNode };

export function CanvasFeatureBackdrop({ theme, children }: Props) {
  return <div className="canvas-feature-backdrop canvas-prism-backdrop" data-prism-theme={theme}>
    <div className="canvas-prism-viewport" aria-hidden="true">
      <Prism height={3.5} baseWidth={5.5} scale={3.6} hueShift={0} colorFrequency={1}
        glow={1} timeScale={0.25} animationType="rotate" lightMode={theme === 'light'}
        interactive intro />
    </div>
    {children}
  </div>;
}
