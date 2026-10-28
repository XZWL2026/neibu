import { useState } from 'react';
import { t, useLanguage } from '../i18n';
import { showCases } from '../fixtures';
import original from '../assets/home/showcase-art.webp';
import extra from '../assets/home/showcase-extra-0930.png';
import './home-showcase.css';

const categories = ['全部', 'Seedance 2.5', '精选画布', '专业影视', '短剧漫剧', '商业广告', '动漫游戏', '教育生活'];
const notes = ['自然 · 奇幻 · 电影感', '情感 · 城市 · 短剧漫剧', '奇幻 · 电影感', '奇幻 · 动漫游戏', '科幻 · 商业广告', '国风 · 东方美学', '武侠 · 短剧漫剧', '力量 · 视觉冲击'];
const added = [
  ['深海之境', '专业影视', '唯美 · 奇幻 · 电影感'],
  ['小龙的伙伴', '动漫游戏', '动漫 · 治愈'],
  ['极速都市', '商业广告', '商业广告 · 汽车'],
  ['星际漫游', '专业影视', '科幻 · 探索'],
  ['机械之心', '专业影视', '科幻 · 数字人'],
  ['窗边的猫', '教育生活', '生活 · 治愈'],
  ['遗迹之门', '动漫游戏', '奇幻 · 游戏概念'],
  ['雨夜街角', '专业影视', '城市 · 氛围'],
  ['长风万里', '精选画布', '国风 · 史诗'],
  ['一杯时光', '教育生活', '生活 · 温暖'],
  ['她的夏天', '短剧漫剧', '情感 · 电影感'],
  ['与光同在', '教育生活', '生活 · 温暖'],
].map(([title, category, note], cell) => ({ title, category, note, cell, image: extra, rows: 3, model: '' }));
const originals = showCases.map((item, cell) => ({ ...item, cell, image: original, rows: 2, note: notes[cell] }));
const works = [...[0, 1, 4, 5, 6, 7, 3].map(index => originals[index]), ...added, originals[2]];

export function HomeShowcase() {
  useLanguage();
  const [category, setCategory] = useState('全部');
  const visible = works.filter(item => category === '全部' || (category === 'Seedance 2.5' ? item.model === category : item.category === category));
  return <section className="home-showcase" aria-labelledby="home-showcase-title">
    <h2 id="home-showcase-title">{t('精选作品')}</h2>
    <div className="home-showcase-filters">{categories.map(item => <button type="button" key={item} aria-pressed={category === item} onClick={() => setCategory(item)}>{t(item)}</button>)}</div>
    <div className="home-showcase-grid" data-filtered={category !== '全部'}>
      {visible.map(item => <article className="home-showcase-card" key={item.title}>
        <svg className="home-showcase-art" role="img" aria-label={t(item.title)} viewBox={`${item.cell % 4 * 600} ${Math.floor(item.cell / 4) * 400} 600 400`} preserveAspectRatio="xMidYMid slice">
          <image href={item.image} width="2400" height={item.rows * 400} />
        </svg>
        <div className="home-showcase-copy"><h3>{t(item.title)}</h3><p>{t(item.note)}</p></div>
      </article>)}
    </div>
  </section>;
}
