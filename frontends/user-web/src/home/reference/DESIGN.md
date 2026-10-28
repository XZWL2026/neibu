---
name: YOMIO 首页参考模块
description: 用户提供的新首页参考在现有 React 首页中的视觉实现，仅约束首页。
colors:
  dark-page: "#020d13"
  dark-ink: "#eef1f8"
  dark-muted: "#c3cbd6"
  dark-line: "#cbe4fb3b"
  light-page: "#f4f6f8"
  light-ink: "#172832"
  light-muted: "#536874"
  light-line: "#27495940"
typography:
  body:
    fontFamily: 'Arial, "Microsoft YaHei", "PingFang SC", sans-serif'
  model:
    fontFamily: "YomioNoto, Arial, sans-serif"
    fontSize: "clamp(25px, 2.15vw, 40px)"
    fontWeight: 400
    lineHeight: 1.25
    letterSpacing: "-.025em"
  gallery-heading:
    fontSize: "clamp(26px, 2.05vw, 36px)"
    fontWeight: 400
    lineHeight: 1.4
  carousel-title:
    fontSize: "19px"
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: ".04em"
rounded:
  carousel: "18px"
  gallery: "1.3vw"
  gallery-mobile: "14px"
  pill: "30px"
  round: "50%"
spacing:
  mobile-gutter: "20px"
  mobile-grid: "12px"
components:
  carousel-control-dark:
    textColor: "{colors.dark-ink}"
    rounded: "{rounded.round}"
    height: "32px"
    width: "32px"
  carousel-control-light:
    textColor: "{colors.light-ink}"
    rounded: "{rounded.round}"
    height: "32px"
    width: "32px"
---

# Design System: YOMIO 首页参考模块

## Overview

**Creative North Star: "新首页参考"**

采用用户提供参考中的波纹线条标志、透明玻璃输入区与环形商品卡片，导航球和提交箭头沿用重构前效果。图像承担主体表达，文字导航保持轻量；本文记录已实现状态，不新增品牌方向，也不扩展到创作页或其他模块。

**Key Characteristics:**

- 深浅主题共享结构，分别适配背景、文字与玻璃材质。
- 首屏动效增强静态图像，交互和路由由 React 保持。
- 环形工具入口后接精选作品网格。

## Colors

首页主题由根元素 `data-theme` 选择，实际颜色声明限定在 `.yomio-home`。前述深浅四组 token 分别对应 `--page`、`--ink`、`--muted`、`--line`；暗色为默认值，浅色以同名变量覆盖。

深色采用近黑蓝背景、冷白主文字和低透明度边线；浅色采用冷灰背景、深蓝灰文字。浅色首屏画布和线条标志有反相处理，输入面板使用白色半透明渐变；像素背景读取主题变化。不要将画作本身做全页反相。

## Typography

正文使用页面现有无衬线字体栈，模型名称使用现有 YomioNoto。模型标签、作品区标题、轮播说明分别使用前述角色。导航采用 `clamp(14px, 1.26vw, 21px)`；在页面窄屏布局中导航为 15px、模型名称为 23px。轮播内部窄屏标题为 18px，副标题从 12px 变为 11px。装饰性巨幅标志是 SVG 素材，不是新增显示字体。

## Layout

桌面首屏高度为 51.3vw，限制在 600–1000px；输入区位于首屏 58% 高度，宽度 44.85%。增强轮播高度为 `clamp(450px, 37vw, 650px)`，作品网格默认三列。

| 条件 | 当前行为 |
| --- | --- |
| 页面 ≤1200px | 缩减顶部辅助入口，收紧作品筛选空间 |
| 页面 ≤760px | 首屏高度 700px、最小 88svh；水平边距 20px，输入区 top 48%；增强轮播高 420px，作品两列 |
| 轮播容器宽 <650px | JS 改用窄屏卡宽与轨道投影；参考 CSS 的 ≤650px 查询调整标题等细节 |
| 页面 ≤430px | 作品单列；无增强引擎的轮播降级高度为 340px |

页面与轨道内部断点用途不同。当前最终适配以 `home-reference.css` 覆盖为准，不直接复制参考文件中的整页固定尺寸。

## Elevation & Depth

层次来自透明材质、局部阴影、波纹背景和轨道透视。输入面板使用玻璃模糊；卡片深度由投影、遮色和鼠标反射共同表达。阴影及运动参数放在 sidecar；不为普通文字导航额外增加容器阴影。

## Shapes

输入区和灵感按钮使用胶囊轮廓，控制按钮为圆形；增强工具卡片与作品采用柔和圆角。导航球保持原有圆环及缩放反馈。卡片图片裁切用 `object-fit: cover`，作品的个别焦点位置由首页覆盖样式明确指定。

## Components

- **输入区与灵感按钮：** 延续原创作表单、错误提示和参数展开交互；玻璃材质仅改变外观。按钮选中状态保留 `aria-pressed`。
- **模型导航：** 四个原模型链接，横线随悬停、焦点或当前模型移动；不将导航改成纯装饰文字。
- **环形工具卡片：** 五个真实链接、活动标题与计数、圆点、前后与暂停按钮。增强引擎提供入场、惯性拖动和持续旋转，手动选择后暂缓旋转。减少动态效果偏好下禁用巡航和视差，手动定位立即生效。
- **作品卡片：** 保留分类和预览；悬停缩到 95%，减少动态效果偏好下不缩放。键盘焦点仍可见，预览关闭后返回触发源。
- **导航球与提交箭头：** 沿用重构前的组件外观、悬停及按下反馈，保持现有导航与提交行为。

## Do's and Don'ts

- **Do** 将视觉约定限定在首页模块，按现有主题和媒体查询适配。
- **Do** 保留真实链接、焦点反馈、暂停控制及静态降级路径。
- **Do** 在 React 卸载时释放每次挂载拥有的动效资源。
- **Don't** 修改用户提供的原参考目录或把本文提升为全项目设计规范。
- **Don't** 让装饰脚本接管创作草稿、账户状态或业务路由。
