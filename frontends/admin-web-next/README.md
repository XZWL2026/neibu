# YOMIO 管理后台前端

独立的管理员前端重构，包含侧栏中的 18 个工作区。界面采用深海军蓝分组导航、浅色工作区、靛蓝操作控件、连贯的筛选与记录面板及合并指标带；手机使用抽屉导航、双列指标和表格内滚动。

共享设计规范记录在 [DESIGN.md](./DESIGN.md)，对应机器可读数据在 `.impeccable/design.json`。页面样式以 `src/design-system.css` 和 `src/admin-business.css` 为准；扩展页面时须保留共享覆盖样式的加载顺序。

## 开发与验证

`clean/core` 使用本目录独立的 `package-lock.json`，安装和构建均按下列 `--workspaces=false` 命令执行；本次保留原项目根锁文件及其他源码不变。线上非敏感配置保存在 `.env.production`，备份、发布与回滚说明见 [aiyomio.cn 发布记录](deployment/aiyomio.md)。本机 `.env.local`、日志、构建产物及备份不提交。

在本目录执行，使用 Node.js 22.18 或更新版本：

```powershell
npm ci --workspaces=false
npm run dev --workspaces=false
```

开发地址为 `http://127.0.0.1:18081/admin/overview`。复制 `.env.example` 为 `.env.local` 并填写本地 Gateway 地址。使用开发代理时配置 `ADMIN_GATEWAY_PROXY_TARGET`，同时保持 `VITE_GATEWAY_BASE_URL` 为空，以便使用同源 Cookie 和 CSRF。生产环境需配置 `/admin/*` 的 SPA 回退和 Gateway 路由。

预览线上数据时，将 `.env.local` 的 `ADMIN_GATEWAY_PROXY_TARGET` 设为 `https://aiyomio.cn/admin`，保持 `VITE_GATEWAY_BASE_URL=`，并将 `VITE_USER_WEB_URL` 设为 `https://aiyomio.cn`。重启开发服务后，在本地登录页使用线上管理员账号登录。代理保留线上 `/admin` 会话隔离，将 Cookie 路径映射至本地根路径，并保留 Secure、HttpOnly 和 CSRF 校验。此模式直接操作线上接口，保存、删除、充值等操作会影响真实数据；本地与线上网站的登录状态分别管理。

```powershell
npm run typecheck --workspaces=false
npm run test --workspaces=false
npm run test:e2e --workspaces=false
npm run build --workspaces=false
```

浏览器回归使用已安装的 Microsoft Edge；测试接口响应仅存在于 `e2e/gateway-fixture.ts`，发布应用没有模拟登录或模拟数据入口。截图保存在 `.impeccable/review/`，不会进入生产构建。

2026-10-14 推荐修复验证：Vitest 全量 54 个文件、682 项测试通过；随后新增 2 项身份切换竞态回归，受影响的 3 个文件、47 项测试再次通过。Playwright 现有 8 项和新增 2 项测试通过，覆盖 18 个工作区及重点页面的 1280px / 390px 布局、停用确认和单项价格保存。最终生产构建与类型检查通过；原目录边界校验 2086 个文件未变。新增截图及复核记录保存在 `.impeccable/review/2026-10-14-fixes/`。构建仍提示已有约 595KB 图表分块大小；该提示不阻断构建。

独立视觉复核最终结论为 `ship`：列出的 5 项修正均已确认解决。该结论覆盖复核修正项；完整记录位于 `.impeccable/review/finish-review.md` 和 `finish-verdict.md`。最后的操作颜色修正后，相关弹窗回归与生产构建再次通过。

## 交互与数据约定

- 管理员登录、主管理员权限和深链接由真实 Gateway 会话验证；无权限页面不会挂载受限业务组件。
- 可搜索选择器支持即时搜索、键盘操作、明确的选中状态和远程加载更多。
- 概览的预设日期、自定义日期和全部时间同时作用于资金图表、模型统计及每日账务列表。资金图表遵循接口每页最多 30 天的协议，读取全部分页后才显示完整趋势；加载失败提供重试，未知金额保持未知。
- 金额显示、汇总和业务写入保留十进制字符串及 BigInt 精度。只有图表绘制转换为 Number；图表保留未知值间断和负数净消费。
- 查询缓存只存于内存并按身份和查询隔离；退出、换号和会话失效清理敏感数据。密钥仅在明确复制操作时读取，失效请求不能继续写入剪贴板。
- 支付订单的组合筛选受现有服务端接口能力限制，沿用客户端快照筛选，不能视为服务端查询全部数据。

## 修改边界

仅修改本目录的代码与文档。原 `frontends/admin-web` 和共享目录只作为业务及接口参考。

`scripts/check-original.mjs` 对本目录外的 Git 跟踪文件计算 SHA-256。首次记录基线后，使用 `npm run check:original --workspaces=false` 检查。限制子进程的环境可以在仓库根目录执行：

```powershell
git -c core.quotepath=false ls-files | node frontends/admin-web-next/scripts/check-original.mjs --files-from-stdin
```

基线 `.original-baseline.json` 仅用于本次工作区校验，不提交到版本库。
