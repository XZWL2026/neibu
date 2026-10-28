import { t, useLanguage } from '../i18n';
import { Link } from 'react-router-dom';
import { UserFrame } from '../UserFrame';
import { ProductionVideoComposer } from '../creator/ProductionVideoComposer';
import { commerceTools } from './commerce-tools';
import { commerceVideoTools, type CommerceVideoTool } from './commerce-video';
import './commerce-video.css';

export function CommerceVideoPage({ tool }: { tool: CommerceVideoTool }) {
  useLanguage();
  return <UserFrame><div className="commerce-video-page">
    <Link className="commerce-video-back" to="/commerce">{t("← 电商创作")}</Link>
    <nav className="commerce-video-nav" aria-label={t("电商工具")}>{Object.entries({ ...commerceTools, ...commerceVideoTools }).map(([id, value]) => <Link key={id} to={`/commerce/${id}`} aria-current={id === tool ? 'page' : undefined}>{t(value.title)}</Link>)}</nav>
    <header className="commerce-video-heading"><span>{t("电商视频")}</span><h1>{t(commerceVideoTools[tool].title)}</h1></header>
    <ProductionVideoComposer key={tool} commerceTool={tool} className="commerce-video-composer" />
  </div></UserFrame>;
}
