import { useId, useRef, type ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { t, useLanguage } from './i18n';
import { useToolRailDisclosure, useToolRailMotion } from './home/tool-rail-motion';
import './creation-navigation.css';
import { useNavigationPosition } from './useNavigationPosition';

type NavigationProps = {
  variant?: 'home' | 'workspace';
  onStory?: () => void;
  selectedTo?: string;
};

function NavigationIcon({ children }: { children: ReactNode }) {
  return <svg aria-hidden="true" focusable="false" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round">{children}</svg>;
}

// The homepage is the visual source for every creation menu, including its order and icons.
const navigation = [
  { label: '首页', to: '/home', icon: <NavigationIcon><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z" /></NavigationIcon> },
  { label: '视频', to: '/create/video', icon: <NavigationIcon><rect x="3" y="2" width="18" height="20" rx="2" /><path d="M17 2v20M17 7h4m-4 5h4m-4 5h4m-13-9 6 4-6 4Z" /></NavigationIcon> },
  { label: '图片', to: '/create/image', icon: <NavigationIcon><rect x="2" y="2" width="20" height="20" rx="3" /><circle cx="8" cy="8" r="1.5" /><path d="m3 18 5-5 4 4 4-7 6 7" /></NavigationIcon> },
  { label: '画布', to: '/canvas', icon: <NavigationIcon><path d="m3 16 13-13 5 5L8 21l-6 1 1-6Zm0 0 5 5M14 5l5 5M10 22h12" /></NavigationIcon> },
  { label: '电商', to: '/commerce', icon: <NavigationIcon><rect x="3" y="7" width="18" height="15" rx="3" /><path d="M8 10V6c0-6 8-6 8 0v4" /></NavigationIcon> },
  { label: '剧创', to: '/home?entry=story', icon: <NavigationIcon><path d="M3 9h18v12H3ZM3 9l-1-6 18-2 1 6Z M7 3l3 5m3-6 3 5M3 14h18" /></NavigationIcon> },
];

/** One disclosure for the homepage and creation workspaces; changing routes closes it. */
export function CreationNavigation(props: NavigationProps) {
  const location = useLocation();
  return <NavigationRail key={location.pathname} {...props} pathname={location.pathname} />;
}

function NavigationRail({ variant = 'workspace', onStory, selectedTo, pathname }: NavigationProps & { pathname: string }) {
  useLanguage();
  const navigate = useNavigate();
  const isHome = pathname === '/home';
  const rail = useRef<HTMLElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const disclosure = useToolRailDisclosure(rail, trigger);
  const position = useNavigationPosition(true, disclosure.close);
  const selected = selectedTo ?? navigation.find(item => pathname === item.to || pathname.startsWith(`${item.to}/`))?.to;
  const motion = useToolRailMotion(list, selected ?? '');
  return <aside ref={rail} className={`creation-navigation creation-navigation--floating creation-navigation--${variant} ym-tool-rail`} data-expanded={disclosure.expanded} data-dragging={position.dragging} data-opens-up={position.opensUp} data-aligns-right={position.alignsRight} style={position.style} aria-label={t(variant === 'home' ? '创作类型' : '用户端导航')} {...disclosure.handlers}>
    <button ref={trigger} className="ym-orb-button" type="button" aria-expanded={disclosure.expanded} aria-controls={menuId} aria-describedby={`${menuId}-drag-help`} aria-label={t(isHome ? disclosure.expanded ? '收起创作导航' : '展开创作导航' : '返回首页')} {...position.handlers} onClick={event => {
      if (position.consumeClick(event.detail)) return;
      if (isHome) disclosure.toggle();
      else navigate('/home');
    }}>
      <span className="ym-orb" aria-hidden="true" />
    </button>
    <span id={`${menuId}-drag-help`} className="sr-only">{t('拖动圆圈调整位置，也可按 Alt 加方向键移动，Alt 加 Home 恢复位置。')}</span>
    <div ref={list} id={menuId} className="ym-tool-list" inert={!disclosure.expanded} aria-hidden={!disclosure.expanded} {...motion}>
      <span className="ym-rail-highlight" aria-hidden="true"><span /></span>
      {navigation.map(item => item.label === '剧创' && onStory
        ? <button key={item.to} className="ym-tool" type="button" onClick={onStory}>{item.icon}<span>{t(item.label)}</span></button>
        : <Link key={item.to} className={`ym-tool${selected === item.to ? ' ym-selected' : ''}`} to={item.to} aria-current={variant === 'workspace' && selected === item.to ? 'page' : undefined}>{item.icon}<span>{t(item.label)}</span></Link>)}
    </div>
  </aside>;
}
