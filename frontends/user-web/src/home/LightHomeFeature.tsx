import '../home/reference-language.css';
import { t, useLanguage } from '../i18n';
import { Link } from 'react-router-dom';
import { popularModels } from '../fixtures';
import reference from '../assets/home/light-home-reference-0928.webp';
import './light-home-feature.css';
import './reference-card-motion.css';

const cardViews = ['73 640 367 272', '466 640 357 272', '849 640 354 272', '1229 640 368 272'];

export function LightHomeFeature() {
  const language = useLanguage();
  return <section className="light-home-feature" data-translated={language !== 'zh-CN' || undefined} aria-label={t("首页精选")}>
    <img src={reference} width="1672" height="941" alt="" fetchPriority="high" decoding="async" />
    <h1 className={language === 'zh-CN' ? 'sr-only' : 'reference-title'}>{t("所想，即所见。")}</h1>
    <p className={language === 'zh-CN' ? 'sr-only' : 'reference-subtitle'}>{t("从一个灵感开始，创造属于你的独特影像。")}</p>
    <Link className="light-home-start reference-card reference-start" to="/create/image"><svg viewBox="74 359 213 58" aria-hidden="true" focusable="false"><image href={reference} width="1672" height="941" /></svg><span className={language === 'zh-CN' ? 'sr-only' : 'reference-label'}>{t("开始创作")}</span></Link>
    <section className="light-home-models" aria-label={t("热门模型")}>
      <h2 className={language === 'zh-CN' ? 'sr-only' : 'reference-models-title'}>{t("热门模型")}</h2>
      <div className="light-home-cards">{popularModels.map((item, index) => <Link className="reference-card" key={item.name} to={item.kind === '图片模型' ? '/create/image' : '/create/video'}>
        <svg viewBox={cardViews[index]} preserveAspectRatio="none" aria-hidden="true" focusable="false"><image href={reference} width="1672" height="941" /></svg>
        <div className="reference-model-copy"><h3 className="sr-only">{item.name}</h3><p className={language === 'zh-CN' ? 'sr-only' : 'reference-note'}>{t(item.name === 'Nano Banana Pro' ? '电商创作' : item.note)}</p></div>
      </Link>)}</div>
    </section>
  </section>;
}
