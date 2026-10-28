import { t, formatMessage, useLanguage } from '../i18n';
import { useState } from 'react';
import { ComposerDialog } from '../creator/ComposerControls';
import { commerceTools, type CommerceTool } from './commerce-tools';
import './tool-examples.css';

const assets = import.meta.glob<string>('../assets/commerce/examples/*.webp', { eager: true, query: '?url', import: 'default' });
type ExampleImage = { src: string; label: string };
const examples: Record<Exclude<CommerceTool, 'detail-page'>, { prefix: string; labels: string[] }> = {
  'product-suite': { prefix: 'product-set', labels: ['商品原图', '主图', '卖点解析', '细节特写', '商品规格'] },
  'product-retouch': { prefix: 'product-retouch', labels: ['原图', '精修图'] },
  'image-replica': { prefix: 'hot-seller-replicate', labels: ['参考图', '高度复刻', '参考风格'] },
  'model-wear': { prefix: 'apparel-group', labels: ['服装原图', '同场景姿势 1', '同场景姿势 2', '同场景姿势 3'] },
  'apparel-set': { prefix: 'apparel-set', labels: ['服装原图', '效果 1', '效果 2', '效果 3'] },
};

function exampleGroups(tool: CommerceTool, slide: number): ExampleImage[][] {
  const image = (name: string, label: string) => ({ src: assets[`../assets/commerce/examples/${name}.webp`], label });
  if (tool === 'detail-page') return [
    [1, 2, 3].map(index => image(`detail-page-${slide}-1-${index}`, `产品图 ${index}`)),
    [image(`detail-page-${slide}-2-1`, '详情长图')],
    [1, 2, 3, 4, 5, 6].map(index => image(`detail-page-${slide}-3-${index}`, `模块图 ${index}`)),
  ];
  const { prefix, labels } = examples[tool];
  const images = labels.map((label, index) => image(`${prefix}-${slide}-${index + 1}`, label));
  return tool === 'image-replica' ? images.map(item => [item]) : [[images[0]], images.slice(1)];
}

export function ToolExamples({ tool }: { tool: CommerceTool }) {
  useLanguage();
  return <ExampleGallery key={tool} tool={tool} />;
}

function ExampleGallery({ tool }: { tool: CommerceTool }) {
  useLanguage();
  const [slide, setSlide] = useState(1);
  const [expanded, setExpanded] = useState<ExampleImage | null>(null);
  return <section className="tool-examples" aria-label={formatMessage("{0}效果示例", commerceTools[tool].title)}>
    <header className="tool-examples-heading"><h1>{t(commerceTools[tool].title)}</h1></header>
    <div className={`tool-examples-board tool-examples-board--${tool}`}>
      {exampleGroups(tool, slide).map((group, index) => <div className="tool-examples-group" key={index}>
        {group.map(image => <figure key={image.src}>
          <button type="button" className="tool-example-image" aria-label={formatMessage("放大{0}", image.label)} aria-haspopup="dialog" onClick={event => { event.currentTarget.focus(); setExpanded(image); }}>
            <img src={image.src} alt={t(image.label)} decoding="async" />
          </button>
          <figcaption>{t(image.label)}</figcaption>
        </figure>)}
      </div>)}
    </div>
    <div className="tool-examples-pagination" role="group" aria-label={t("切换效果示例")}>
      {[1, 2, 3].map(index => <button type="button" key={index} aria-label={formatMessage("切换到第{0}个示例", index)} aria-pressed={slide === index} onClick={() => setSlide(index)}><span /></button>)}
    </div>
    <span className="tool-examples-caption">{t("效果示例")}</span>
    {expanded && <ComposerDialog title={t(expanded.label)} closeLabel={t("关闭示例预览")} onClose={() => setExpanded(null)}>
      <img className="tool-example-expanded" src={expanded.src} alt={formatMessage("{0}放大预览", expanded.label)} />
    </ComposerDialog>}
  </section>;
}
