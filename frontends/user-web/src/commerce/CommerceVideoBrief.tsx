import { t, formatMessage, useLanguage } from '../i18n';
import { commerceLanguages, commercePlatforms } from './ToolParameters';
import { videoBriefScript, type CommerceVideoTool, type VideoBrief } from './commerce-video';

export function CommerceVideoBrief({ tool, value, disabled, hasScript, onChange, onApply, onAnalyze, analyzing = false }: {
  tool: CommerceVideoTool; value: VideoBrief; disabled: boolean; hasScript: boolean;
  onAnalyze?: () => void; analyzing?: boolean;
  onChange(value: VideoBrief): void; onApply(script: string): void;
}) {
  useLanguage();
  const editShot = (index: number, field: 'visual' | 'narration', text: string) => onChange({ ...value,
    shots: value.shots.map((shot, position) => index === position ? { ...shot, [field]: text } : shot) });
  const script = videoBriefScript(tool, value);
  return <fieldset className="commerce-video-brief" disabled={disabled}>
    <legend>{t("商品与分镜")}</legend>
    <div className="commerce-video-fields">
      <label>{t("商品名称")}<input maxLength={200} value={value.product} onChange={event => onChange({ ...value, product: event.target.value })} /></label>
      <label>{t("视频风格")}<select value={value.style} onChange={event => onChange({ ...value, style: event.target.value })}>
        {['商品展示', '真人讲解', '使用演示', '生活场景', '开箱体验'].map(style => <option value={style} key={style}>{t(style)}</option>)}
      </select></label>
      <label>{t("发布平台")}<select value={value.platform} onChange={event => onChange({ ...value, platform: event.target.value })}>{commercePlatforms.map(platform => <option value={platform} key={platform}>{t(platform)}</option>)}</select></label>
      <label>{t("文案语言")}<select value={value.language} onChange={event => onChange({ ...value, language: event.target.value })}>{commerceLanguages.map(language => <option value={language} key={language}>{t(language)}</option>)}</select></label>
    </div>
    <label>{t("商品卖点")}<textarea maxLength={2000} rows={3} value={value.sellingPoints} onChange={event => onChange({ ...value, sellingPoints: event.target.value })} /></label>
    <div className="commerce-video-section-head"><h2>{t("分镜编辑")}</h2><button type="button" disabled={!onAnalyze || disabled} onClick={onAnalyze} title={onAnalyze ? undefined : t(tool === 'video-replica' ? '请上传参考视频并配置识图模型' : '视频理解模型尚未接入')}>{t(analyzing ? '拆解中…' : tool === 'video-replica' ? 'AI 拆解参考视频' : 'AI 帮写脚本')}</button></div>
    <ol className="commerce-video-shots">{value.shots.map((shot, index) => <li key={index}>
      <div className="commerce-video-section-head"><h3>{formatMessage("镜头 {0}", String(index + 1).padStart(2, '0'))}</h3><button type="button" disabled={value.shots.length === 1} aria-label={formatMessage("删除分镜 {0}", index + 1)} onClick={() => onChange({ ...value, shots: value.shots.filter((_, position) => position !== index) })}>{t("删除")}</button></div>
      <label>{t("画面与动作")}<textarea aria-label={formatMessage("分镜 {0} 画面", index + 1)} maxLength={600} rows={3} value={shot.visual} onChange={event => editShot(index, 'visual', event.target.value)} /></label>
      <label>{t("口播文案")}<textarea aria-label={formatMessage("分镜 {0} 口播", index + 1)} maxLength={300} rows={2} value={shot.narration} onChange={event => editShot(index, 'narration', event.target.value)} /></label>
    </li>)}</ol>
    <div className="commerce-video-section-head">
      <button type="button" disabled={value.shots.length >= 12} onClick={() => onChange({ ...value, shots: [...value.shots, { visual: '', narration: '' }] })}>{t("添加分镜")}</button>
      <button type="button" disabled={script.length > 16000 || ![value.product, value.sellingPoints, ...value.shots.flatMap(shot => [shot.visual, shot.narration])].some(text => text.trim())} onClick={() => onApply(script)}>{t(hasScript ? '替换当前视频脚本' : '应用到视频脚本')}</button>
    </div>
  </fieldset>;
}
