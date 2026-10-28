# aiyomio.cn 管理员前端发布记录

2026-10-14（北京时间），版本 `20261014-195310-admin-next` 已上线。

- 入口：https://aiyomio.cn/admin/login
- EDGE：`47.97.85.146`
- 新版：`/opt/xzwl/admin-ui-releases/20261014-195310-admin-next`
- 生效软链接：`/opt/xzwl/admin-ui/current`
- 服务器备份：`/opt/xzwl/admin-ui-backups/20261014-195310-admin-next`
- 上传包与执行记录：`/root/xzwl-release/20261014-195310-admin-next`
- 本地备份与校验记录：本项目 `.deployment/20261014-195310-admin-next/`

备份含完整旧管理员前端压缩包、切换前后 Nginx 配置、旧文件 SHA-256 清单和回滚脚本。旧前端目录也保留在原位置。备份压缩包 SHA-256：`75817f0ad52db3de78ed8f9e4209fab37da864d7302f79dce04a24f3c7b978f3`，服务器和本地副本一致。

## 部署边界

只增加管理员页面及新版静态资源的 Nginx 匹配规则。官网、代理门户、API 门户仍使用原有文件。`/admin/assets/` 继续提供旧资源；新版使用 `/admin-ui/assets/`。管理员 API 匹配规则位于新页面规则之前，继续代理原后端。只 reload Nginx，没有重启后端或修改数据库、账号。

`.env.production` 的 `VITE_GATEWAY_BASE_URL=/admin` 必须保留：线上使用路径隔离的管理员会话 Cookie。不能改为空，否则会进入普通用户会话通道。Vite 路由 base 保持 `/`，因为路由本身已包含 `/admin`；只通过 `ADMIN_ASSETS_DIR=admin-ui/assets` 隔离静态资源。

后续构建使用 `npm run build`。`scripts/deploy-aiyomio-admin.py` 的 prepare/activate **仅适用于此次首次部署**，会拒绝覆盖既有 active 指针。后续发布需先读取线上当前状态，备份当前配置及当前管理员版本，再设计升级切换；不要直接重跑首次部署或覆盖原共享前端目录。旧工程的整体发布流程如果覆盖 Nginx 配置，需要同步保留本次独立管理员路由。

## 验证结果

- 生产构建及 TypeScript 检查通过。
- `e2e/production-build.spec.ts` 通过：实际生产包、模拟登录成功、用户页数据、深链接跳转、管理员 API 前缀、独立资源路径。
- 线上源站：5 个管理员页面路径、68 个新静态资源逐个校验通过；官网及代理/API 入口和旧静态资源响应哈希未改变。
- 线上真实 HTTPS 浏览器检查：未登录访问用户管理会跳转新版登录页；会话接口 `/admin/api/v1/auth/session` 返回 200；桌面/移动端渲染正常，无页面异常和资源错误。截图及请求状态见本地发布目录。
- 没有使用真实管理员凭据，因此未验证线上登录后的业务操作。生产包的登录成功流程使用测试夹具验证。
- 后端进程 PID 与原前端文件哈希未改变；本地原项目 2086 个文件校验通过。

## 回滚

在 EDGE 上执行：

```sh
python3 /root/xzwl-release/20261014-195310-admin-next/deploy-aiyomio-admin.py rollback --release-id 20261014-195310-admin-next --bundle /root/xzwl-release/20261014-195310-admin-next
```

脚本只在当前配置与本次切换前/后配置一致时恢复，避免覆盖后续人工修改；通过 `nginx -t` 后 reload，确认旧登录入口恢复，再移除新版 active 指针。保留备份和新版发布目录。如果已有后续 Nginx 改动，应先审阅差异，不能强制覆盖整个配置。
