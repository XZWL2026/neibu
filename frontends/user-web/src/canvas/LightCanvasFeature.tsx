import { CanvasFeature, type CanvasFeatureProps } from './CanvasFeature';

export function LightCanvasFeature(props: CanvasFeatureProps) {
  return <CanvasFeature {...props} theme="light" />;
}
