import { CanvasFeature, type CanvasFeatureProps } from './CanvasFeature';

export function DarkCanvasFeature(props: CanvasFeatureProps) {
  return <CanvasFeature {...props} theme="dark" />;
}
