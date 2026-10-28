import { t, useLanguage } from '../i18n';
import exampleProduct from '../assets/home/popular-nano-banana.webp';

export function SuitePreview({ image, modules = ['商品主图', '卖点展示', '细节特写', '场景搭配'], title = '商品套图' }: {
  image?: string; modules?: string[]; title?: string;
}) {
  useLanguage();
  return <section className="suite-preview" aria-label={t("套图布局预览")}>
    <header className="suite-heading"><h1>{t(title)}</h1></header>
    <div className="suite-preview-board">
      <figure className="suite-preview-source" data-reference={Boolean(image)}><img src={image || exampleProduct} alt={t(image ? '套图预览商品原图' : '香水商品示例')} /><figcaption>{t(image ? '商品原图' : '示例商品')}</figcaption></figure>
      <span className="suite-preview-arrow" aria-hidden="true">↗</span>
      <div className="suite-preview-outputs">{modules.map((name, index) => <figure className={`suite-preview-tile suite-preview-tile-${index % 4}`} key={`${index}-${t(name)}`} data-module={t(name)}>
        <img src={image || exampleProduct} alt="" /><figcaption>{t(name)}</figcaption><span className="suite-preview-lines" aria-hidden="true"><i /><i /><i /></span>
      </figure>)}</div>
    </div>
    <span className="suite-preview-caption">{t("布局示意")}</span>
  </section>;
}
