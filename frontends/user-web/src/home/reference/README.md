# 首页参考迁移

本目录主要服务 `YomioHome` 首页；`pixel-effect.js` 同时为图片/视频创作页复用像素背景。视觉依据是用户提供的 `新首页参考/index.html`，素材复制自 `新首页参考/assets`；原参考目录不修改。`ogl.js` 与波纹、水晶球、反射效果的着色器来自参考原文件，迁移主要增加模块导出、资源导入和 React 生命周期适配，不是重新实现一套视觉算法。

## 文件与边界

| 入口 | 职责 |
| --- | --- |
| `../YomioHome.tsx` | 页面状态、登录会话、创作提交、导航、筛选与作品预览 |
| `../HomeReferenceBackdrop.tsx` → `effects.js` | 首屏波纹/线条标志、全页像素背景 |
| `../HomeCommerceCarousel.tsx` → `carousel.js` | React 链接、标题、控制按钮；环形轨道与反射渲染增强 |
| `effect-scope.js` | 每次挂载的帧、监听器、观察器和渲染资源所有权 |
| `../home-reference.css` | 最后加载的首页参考样式覆盖及深浅主题适配 |
| `../reference-assets.ts`、`assets/` | 参考图片映射及静态资产；作品作者头像仍使用原首页素材 |

`effects.js` 与 `carousel.js` 是两条独立的动态导入边界，在组件挂载后加载。它们不等待滚入视口才下载；可见性观察用于暂停渲染。背景入口依赖波纹、像素；轮播入口依赖轨道与反射。图片通过构建器管理，图库封面和头像使用浏览器原生懒加载。首屏静态线条标志优先加载，增强失败时仍可显示。

`pixel-effect.js` 是创作页独立的动态入口，仅加载像素背景和资源清理模块，不加载首页标志与轮播。创作页沿用首页深浅底色与像素参数，背景不拦截操作，离开路由时释放资源。

## 生命周期与降级

- React effect 使用取消标志，卸载后完成的动态导入不会继续挂载。卸载时调用 `dispose()`，轮播引用清空。
- 每个背景效果拥有独立 scope；单个挂载失败时清理该 scope，其他效果可继续。轮播与反射共享一个 scope，初始化失败统一清理。
- scope 幂等释放：先解除监听和观察器，再执行渲染器清理，最后取消剩余 RAF；已失效 scope 不再派发回调或登记新帧。不要改用全局猴子补丁或向全页发送模拟 `pagehide`。
- 渲染器处理标签页可见性，适用的效果同时处理视口可见性；遵循 `prefers-reduced-motion`。WebGL 路径包含 Canvas 或静态降级，反射不可用时保留可点击卡片。
- 缺少 `matchMedia`、`ResizeObserver` 或 `IntersectionObserver` 时不挂载增强引擎。React 卡片仍支持链接、前后切换、圆点、暂停和横向手势；降级自动切换间隔为 5 秒，悬停、焦点、拖动、隐藏页或减少动态效果偏好会暂停。增强轨道则使用连续旋转和弹簧定位，不是 5 秒翻页。
- 轮播 DOM 和中文/英文标题由 React 保持所有权；引擎通过回调更新活动项，通过 `onActivate` 调用 React Router。拖动后的点击被抑制，带修饰键的链接点击保留浏览器行为。

## 保留的行为契约

创作首页仍为 `/home`；“社区”滚动到作品区，“我的作品”进入 `/create/image?tab=history`。创作提交仍携带原 `HomeCreationEntry`、模式、模型与参数，并按原账号隔离规则保存草稿；热门模型进入 `/create/image` 或 `/create/video`。共享账户菜单及 `CreationNavigation` 保持原功能。导航球和创作提交箭头已按用户要求恢复重构前的外观与反馈，使用原组件样式；参考水晶球与金属箭头不再挂载。

五张电商卡片分别进入 `/commerce/product-retouch`、`/commerce/detail-page`、`/commerce/product-suite`、`/commerce/model-wear`、`/commerce/video-replica`。作品筛选、预览弹窗、关闭和焦点返回保持原 React 流程。视觉脚本不得接管业务状态或添加替代路由。

## 维护与验证

模块视觉约定见 [DESIGN.md](./DESIGN.md)，机器扩展见 [.impeccable/design.json](./.impeccable/design.json)。两者仅描述首页，不是全项目设计规则。更新视觉时先核对最终 CSS 级联，尤其不要将原参考 CSS 中未使用的选择器当成当前组件契约。

2026-10-15 迁移验收：桌面暗色与窄屏浅色截图复验通过；机械检测结果 `[]`；首页与动效相关 77 项测试通过，最终 typecheck 和 build 通过。范围外 `i18n/i18n.test.tsx` 扩展检查仍有 3 项既有失败（2 项 Canvas 标题的 sr-only 断言，1 项原首页素材选择/提示词标签翻译检查），已核对 HEAD 中存在，本次未扩修。此记录不表示全仓测试通过。
