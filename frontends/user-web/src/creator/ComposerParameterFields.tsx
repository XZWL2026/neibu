import { t, formatMessage } from '../i18n';
import type { CanvasGenerationSettings } from '../canvas/model';
import { RatioIcon, sortedResolutions } from './ComposerControls';

/** The same parameter controls are used by the home entry and both workspaces. */
export function ComposerParameterFields({ model, selection, onChange, ratioDisabled = false, resolutions = model.resolutions, onResolution, resolutionHint }: {
  model: { aspectRatios: string[]; resolutions: string[]; maxCount: number; minDurationSeconds: number; maxDurationSeconds: number; durationStepSeconds: number };
  selection: CanvasGenerationSettings; onChange(value: CanvasGenerationSettings): void;
  ratioDisabled?: boolean; resolutions?: string[]; onResolution?(value: string): void; resolutionHint?: string;
}) {
  return <>
    <fieldset disabled={ratioDisabled}><legend>{t('画面比例')}</legend><div className="video-ratios">{model.aspectRatios.map(ratio => <button type="button" key={ratio} aria-pressed={selection.aspectRatio === ratio} onClick={() => onChange({ ...selection, aspectRatio: ratio })}><RatioIcon ratio={ratio} /><span>{ratio}</span></button>)}</div></fieldset>
    {ratioDisabled && <p>{t('比例跟随首帧图片')}</p>}
    <fieldset><legend>{t('分辨率')}</legend><div className="video-resolutions">{sortedResolutions(resolutions).map(resolution => <button type="button" key={resolution} aria-pressed={selection.resolution === resolution} onClick={() => onResolution ? onResolution(resolution) : onChange({ ...selection, resolution })}>{resolution}</button>)}</div></fieldset>
    {resolutionHint && <p>{t(resolutionHint)}</p>}
    {selection.type === 'VIDEO' ? <fieldset><legend>{t('时长 ')}<small>{formatMessage('{0}–{1}秒 · 步长{2}秒', model.minDurationSeconds, model.maxDurationSeconds, model.durationStepSeconds)}</small></legend><div className="video-duration">
      <input type="range" aria-label={t('视频时长')} min={model.minDurationSeconds} max={model.maxDurationSeconds} step={model.durationStepSeconds} value={Number.isNaN(selection.durationSeconds) ? model.minDurationSeconds : selection.durationSeconds} onChange={event => onChange({ ...selection, durationSeconds: Number(event.target.value) })} />
      <label><input type="number" aria-label={t('时长秒数')} min={model.minDurationSeconds} max={model.maxDurationSeconds} step={model.durationStepSeconds} value={Number.isNaN(selection.durationSeconds) ? '' : selection.durationSeconds} onChange={event => onChange({ ...selection, durationSeconds: event.target.valueAsNumber })} /><span>{t('秒')}</span></label>
    </div></fieldset> : <fieldset className="image-parameters"><label>{t('生成数量')}<input aria-label={t('生成数量')} type="number" min={1} max={model.maxCount} step={1} value={Number.isNaN(selection.count) ? '' : selection.count} onChange={event => onChange({ ...selection, count: event.target.valueAsNumber })} /></label></fieldset>}
  </>;
}
