import { t } from '../i18n';
import { commerceTools, type CommerceTool } from './commerce-tools';
import { commerceVideoTools, type CommerceVideoTool } from './commerce-video';

type Tool = CommerceTool | CommerceVideoTool;
const descriptions: Record<Tool, string> = {
  'product-suite': '一次生成多种商品展示图',
  'detail-page': '组织卖点，制作商品详情页',
  'image-replica': '参考构图，复刻商品图片',
  'product-retouch': '优化质感与商品细节',
  'model-wear': '将服装展示在模特身上',
  'apparel-set': '制作多种服装展示效果',
  'video-replica': '参考镜头与节奏制作视频',
  'seller-video': '围绕商品卖点制作视频',
};
const iconPaths: Record<Tool, string> = {
  'product-suite': 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  'detail-page': 'M6 3h9l4 4v14H6zM14 3v5h5M9 12h7M9 16h7',
  'image-replica': 'M8 8h13v13H8zM16 4V3H3v13h1M10 18l3-4 3 3 2-2 3 3M12 11h.01',
  'product-retouch': 'M3 21l3-6L16 5l3 3L9 18zM13 8l3 3M5 3v4M3 5h4M20 14v4M18 16h4',
  'model-wear': 'M9 5a3 3 0 1 0 6 0 3 3 0 1 0-6 0M8 11l-3 6M16 11l3 6M8 11h8l-1 6H9zM10 17l-1 5M14 17l1 5',
  'apparel-set': 'm8 3-6 4 3 5 3-2v11h8V10l3 2 3-5-6-4M8 3a4 4 0 0 0 8 0',
  'video-replica': 'M8 7h13v14H8zM16 3H3v14M12 11l5 3-5 3z',
  'seller-video': 'M3 6h18v15H3zM3 11h18M7 6l3 5M14 6l3 5M10 14l5 2-5 3z',
};

export function CommerceToolIcon({ tool }: { tool: Tool }) {
  return <svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d={iconPaths[tool]} /></svg>;
}

export function CommerceToolMenu({ current, onClose }: { current: CommerceTool; onClose: () => void }) {
  const groups = [
    { title: '图片创作', tools: Object.entries(commerceTools) },
    { title: '视频创作', tools: Object.entries(commerceVideoTools) },
  ];
  return <nav className="suite-tools" aria-label={t('电商工具')}>
    <header className="suite-tools-heading">
      <h2>{t('切换创作工具')}</h2>
      <button className="suite-tools-close" type="button" aria-label={t('关闭工具切换')} onClick={onClose}>
        <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="m6 6 12 12M18 6 6 18" /></svg>
      </button>
    </header>
    {groups.map(group => <section className="suite-tools-section" key={group.title} aria-label={t(group.title)}>
      <h3>{t(group.title)}</h3>
      <div className="suite-tools-grid">{group.tools.map(([id, value]) => <a className="suite-tool-link" key={id} href={`/commerce/${id}`} aria-label={t(value.title)} aria-current={id === current ? 'page' : undefined}
        onClick={event => { if (id === current) { event.preventDefault(); onClose(); } }}>
        <span className="suite-tool-icon"><CommerceToolIcon tool={id as Tool} /></span>
        <span className="suite-tool-copy"><strong>{t(value.title)}</strong><small>{t(descriptions[id as Tool])}</small></span>
        {id === current && <svg className="suite-tool-current" aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m5 12 4 4L19 6" /></svg>}
      </a>)}</div>
    </section>)}
  </nav>;
}
