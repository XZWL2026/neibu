import { t, useLanguage } from '../i18n';
import type { CommerceTool } from './commerce-tools';
export const commercePlatforms = ['亚马逊', '淘宝天猫', '1688', 'Temu', 'TikTok Shop', '拼多多', '抖音电商', 'OZON', '独立站', 'Shopee', '阿里国际站', '速卖通', 'SHEIN', '京东', '美客多', 'Coupang', 'Wayfair', 'Lazada', 'eBay'];
export const commerceLanguages = ['中文', 'English', '日文', '韩文', '西班牙文', '俄语', '泰语', '马来语'];
export const retouchChoices = [['GLOSS', '增强产品光泽'], ['REPAIR', '修复划痕瑕疵'], ['SHARPNESS', '提升整体清晰度'], ['COLOR', '色彩校正'], ['PERSPECTIVE', '修正透视变形']] as const;
export function ToolParameters({ tool, disabled, replicationMode, onReplicationMode, enhancements, onEnhancements, garmentView, onGarmentView, white3d, onWhite3d, whiteMannequin, onWhiteMannequin }: {
  tool: CommerceTool; disabled: boolean; replicationMode: string; onReplicationMode(value: string): void;
  enhancements: string[]; onEnhancements(value: string[]): void; garmentView: string; onGarmentView(value: string): void;
  white3d: boolean; onWhite3d(value: boolean): void; whiteMannequin: boolean; onWhiteMannequin(value: boolean): void;
}) {
  useLanguage();
  if (tool === 'image-replica') return <fieldset className="suite-option-group" disabled={disabled}><legend>{t("复刻程度")}</legend>
    {([['STYLE', '参考风格', '参考构图和氛围，重新组织场景与配色'], ['HIGH', '高度复刻', '尽量保持参考图的布局和光线，替换为你的商品']] as const).map(([value, label, detail]) =>
      <label className="suite-option-choice" key={value}><input type="radio" name="replication-mode" value={value} checked={replicationMode === value} onChange={() => onReplicationMode(value)} /><span>{t(label)}<small>{t(detail)}</small></span></label>)}
  </fieldset>;
  if (tool === 'product-retouch') return <fieldset className="suite-option-group" disabled={disabled}><legend>{t("快捷优化项")}</legend>
    {retouchChoices.map(([value, label]) => <label className="suite-option-choice" key={value}><input type="checkbox" checked={enhancements.includes(value)} onChange={event => onEnhancements(event.target.checked ? [...enhancements, value] : enhancements.filter(item => item !== value))} />{t(label)}</label>)}
  </fieldset>;
  if (tool === 'apparel-set') return <fieldset className="suite-option-group" disabled={disabled}><legend>{t("服装展示设置")}</legend>
    <div className="suite-settings">{[['FRONT', '正面'], ['BACK', '背面']].map(([value, label]) => <label className="suite-option-choice" key={value}><input type="radio" name="garment-view" checked={garmentView === value} onChange={() => onGarmentView(value)} />{t(label)}</label>)}</div>
    <label className="suite-option-choice"><input type="checkbox" checked={white3d} onChange={event => onWhite3d(event.target.checked)} />{t("3D 立体效果图使用白底")}</label>
    <label className="suite-option-choice"><input type="checkbox" checked={whiteMannequin} onChange={event => onWhiteMannequin(event.target.checked)} />{t("人台图使用白底")}</label>
  </fieldset>;
  return null;
}
