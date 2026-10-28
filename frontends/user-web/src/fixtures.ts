import seedance25 from './assets/home/popular-seedance-25.webp';
import seedance20 from './assets/home/popular-seedance-20.webp';
import cloudVilla from './assets/home/popular-cloud-villa.webp';
import gptImage25 from './assets/home/popular-gpt-image-25.webp';
import moonArch from './assets/home/popular-moon-arch.webp';
import nanoBanana from './assets/home/popular-nano-banana.webp';

export const popularModels = [
  { name: "Seedance 2.5", kind: "视频模型", note: "电影级视频生成", image: seedance25, recommended: true },
  { name: "Seedance 2.0", kind: "视频模型", note: "稳定高效视频生成", image: seedance20, lightImage: cloudVilla, recommended: false },
  { name: "GPT Image 2.5", kind: "图片模型", note: "高质量图像生成", image: gptImage25, lightImage: moonArch, recommended: false },
  { name: "Nano Banana Pro", kind: "图片模型", note: "快速商业视觉", image: nanoBanana, recommended: false }
] as const;

export const darkPopularModels = [
  { name: "Seedance 2.5", kind: "视频模型", note: "电影级视频生成" },
  { name: "Seedance 2.0", kind: "视频模型", note: "稳定高效视频生成" },
  { name: "GPT Image 2", kind: "图片模型", note: "高质量图像生成" },
  { name: "Nano Banana Pro", kind: "图片模型", note: "电商创作" }
] as const;

export const showCases = [
  { title: "一棵树", author: "墨墨墨Ink", category: "精选画布", model: "Seedance 2.5", tone: "forest" },
  { title: "所有人都在装", author: "186****8614", category: "短剧漫剧", model: "Seedance 2.5", tone: "gold" },
  { title: "髓原", author: "一只土狗", category: "专业影视", model: "Kling 2.1", tone: "stone" },
  { title: "山海驯兽师", author: "阿伟AIGC", category: "动漫游戏", model: "Seedance 2.5", tone: "storm" },
  { title: "未来机甲", author: "霓虹剧场", category: "商业广告", model: "Kling 2.1", tone: "orange" },
  { title: "青山入梦", author: "山河工作室", category: "教育生活", model: "Seedance 2.5", tone: "ink" },
  { title: "宗师快打", author: "PANDA", category: "短剧漫剧", model: "Kling 2.1", tone: "red" },
  { title: "武牛", author: "纸上江湖", category: "精选画布", model: "Seedance 2.5", tone: "paper" }
] as const;

export const imageHistory = [
  { id: "IMG-0829-104", prompt: "透明香水瓶置于黑曜石展台", meta: "GPT Image 2 · 今天 15:22", tone: "cyan" },
  { id: "IMG-0828-992", prompt: "暮色中的未来城市天际线", meta: "GPT Image 2 · 本地历史 fixture", tone: "violet" },
  { id: "IMG-0828-784", prompt: "水墨风格的白色机械鹿", meta: "GPT Image 2 · 昨天 18:46", tone: "paper" }
] as const;

export const videoHistory = [
  { id: "VID-0829-063", prompt: "两位舞者在金色剧场半空相遇", meta: "Seedance 2.5 · 首尾帧 · 生成中", tone: "gold" },
  { id: "VID-0828-018", prompt: "香氛产品在晨雾中缓慢旋转", meta: "Seedance 2.5 · 全能参考 · 本地历史 fixture", tone: "cyan" }
] as const;

export const transactions = [
  { id: "TXN-662801", action: "充值", amount: "+500.00", time: "2026-08-28 12:08", status: "已完成" },
  { id: "TXN-662774", action: "图片生成", amount: "-12.00", time: "2026-08-28 11:42", status: "已结算" },
  { id: "TXN-662681", action: "视频生成", amount: "-36.00", time: "2026-08-27 21:16", status: "已结算" }
] as const;
