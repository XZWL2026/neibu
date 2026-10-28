import '../home/reference-language.css';
import { t, useLanguage } from '../i18n';
import { Link } from 'react-router-dom';
import { darkPopularModels } from '../fixtures';
import reference from '../assets/home/dark-home-reference-0928.webp';
import './dark-home-feature.css';
import './reference-card-motion.css';

const cardViews = ['312 559 341 200', '672 559 351 200', '1042 559 351 200', '1412 559 370 200'];

export function DarkHomeFeature() {
  const language = useLanguage();
  return <section className="dark-home-feature" data-translated={language !== 'zh-CN' || undefined} aria-label={t("首页精选")}>
    <svg className="dark-home-artwork" viewBox="256 80 1604 700" aria-hidden="true" focusable="false"><image href={reference} width="1860" height="845" /></svg>
    <h1 className={language === 'zh-CN' ? 'sr-only' : 'reference-title'}>{t("所想，即所见。")}</h1>
    <p className={language === 'zh-CN' ? 'sr-only' : 'reference-subtitle'}>{t("用 AI，打开更大的想象力。")}</p>
    <Link className="dark-home-start reference-card reference-start" to="/create/image"><svg viewBox="312 413 178 58" aria-hidden="true" focusable="false"><image href={reference} width="1860" height="845" /></svg><span className={language === 'zh-CN' ? 'sr-only' : 'reference-label'}>{t("开始创作")}</span></Link>
    <section className="dark-home-models" aria-label={t("热门模型")}>
      <h2 className="sr-only">{t("热门模型")}</h2>
      <div className="dark-home-cards">{darkPopularModels.map((item, index) => <Link className="reference-card" key={item.name} style={{ gridColumn: index * 2 + 1 }} to={item.kind === '图片模型' ? '/create/image' : '/create/video'}>
        <svg viewBox={cardViews[index]} preserveAspectRatio="none" aria-hidden="true" focusable="false"><image href={reference} width="1860" height="845" /></svg>
        <div className="reference-model-copy"><h3 className="sr-only">{item.name}</h3><p className={language === 'zh-CN' ? 'sr-only' : 'reference-note'}>{t(item.note)}</p></div>
      </Link>)}</div>
    </section>
  </section>;
}
