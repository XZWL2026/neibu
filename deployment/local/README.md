# 当前项目的本地正式配置副本

部署范围是本工作区的 Java 21 / Spring Boot 项目。配置来自 aiyomio.cn 正式链路：146 和 62 的运行进程、124 的正式 MySQL 与 Nacos `xzwl-prod/XZWL`。远程操作只有读取；没有使用 124 内测或 legacy 配置，也没有接入 59 的独立 Java 部署、47 的 Node 项目、MinIO 或 Qdrant。

## 访问与账号

| 入口 / 服务 | 本地地址 |
| --- | --- |
| 用户端 | http://127.0.0.1:18090/ |
| 管理端 | http://127.0.0.1:18090/admin/login |
| API 门户 | http://127.0.0.1:18090/api/login |
| 推广门户 | http://127.0.0.1:18090/promoter/overview |
| 独立合作伙伴前端 | http://127.0.0.1:18092/ |
| Gateway | http://127.0.0.1:8200/ |
| Nacos 控制台 | http://127.0.0.1:28848/nacos/ |
| Nacos 客户端 gRPC | 127.0.0.1:29848 |
| Docker MySQL | 127.0.0.1:23306 |
| Docker Redis | 127.0.0.1:26379 |

管理员为 `localadmin`；密码、数据库与 Nacos 登录信息在 [本地凭据文件](target/.env.credentials.txt)。本地测试手机号为 `19900000001`，固定验证码 `123456`。测试用户通过真实登录流程创建，并通过本地管理接口配置为 PERSONAL、建立主 Key，不复制正式用户与余额。可以在管理端给测试用户配置积分后再测试生成。

前端按正式 Nginx 的方式隔离用户、管理员、代理和 API 门户 Cookie；只使用 `127.0.0.1` 入口，避免混用 localhost 导致浏览器会话分离。原有 Vite 开发入口可以继续使用同一组本地后端；正式页面和会话隔离验证针对这里的 Nginx 入口。

## 运行方式

基础服务和 Nginx 在 Docker Desktop 的 `xzwl-clean-local` Compose 项目中运行；当前项目构建出的 12 个 Java 服务在 Windows 上运行。数据库、Nacos、Redis、Nginx 使用正式环境版本：MySQL 8.0.46、Nacos 2.5.4、Redis 7.0.15、Nginx 1.24.0。没有容器化另一套项目。

11 个业务 schema 使用 `xzwl_local_*`，Nacos 数据库为 `nacos_local`，命名空间为 `xzwl-local`，group 为 `XZWL`。Gateway 通过本地 Nacos 发现业务服务。IAM 使用项目允许固定验证码的 local profile，并显式导入本地 Nacos 配置；其他带 Nacos starter 的模块使用 prod profile 加本地地址覆盖。canvas、operations 没有 Nacos starter，脚本把这两个本地实例注册为持久实例，由本地验证程序确认真实接口后设置健康状态，停止时取消注册。Docker 内的 Nacos 无法用容器自己的 127.0.0.1 探测 Windows 服务，因此这两个注册的服务端 TCP 探测关闭；它们没有持续的 Nacos 客户端心跳。验证同时检查实例地址、健康状态和真实接口。

各服务使用原有 8200、8210 至 8220 端口。Gateway 为了接收 Docker Nginx 的请求监听 Windows 的 0.0.0.0，其 Nacos 注册地址仍是 127.0.0.1；其余 Java 服务监听 127.0.0.1。容器发布端口全部绑定 127.0.0.1。Redis 已按正式基础设施部署，当前源码没有 Redis 客户端依赖，IAM 会话仍使用 JDBC。

在仓库根目录运行 PowerShell：

```powershell
# 启动或恢复，保留本地数据库与现有配置
./scripts/local/runtime.ps1

# 改完源码后重新构建、启动
./scripts/local/runtime.ps1 -Rebuild

# 容器状态 / 服务、数据库和发现验证
./scripts/local/runtime.ps1 -Action Status
./scripts/local/runtime.ps1 -Action Verify

# 停止本地进程和容器，保留所有数据卷
./scripts/local/runtime.ps1 -Action Stop

# 再次只读获取当前项目的正式配置并导入本地
./scripts/local/runtime.ps1 -Action RefreshProduction
```

需要 Java 21、Node.js >=22.18、Python 3 和 Docker Desktop。脚本优先使用 Codex 自带的 Python；普通 Python 也可运行，无第三方 Python 依赖。首次准备源码需 `npm ci` 和 Maven 构建；本机已经有 Maven 3.9.9 缓存，重建使用离线 Maven。首次从没有构建产物的工作区运行时使用 `-Rebuild`。远程刷新需要现有 SSH 身份文件，普通本地重启不需要 SSH。

源码、Compose 和启动脚本可提交；`target/` 被 Git 忽略，包含凭据、源配置快照、本地服务环境、数据库初始化脚本、日志、PID 和验证报告。普通启动复用这些文件，不从远程重新复制，不覆盖测试数据。

前端和登录验收可使用 Python 单独运行 `deployment/local/verify-http.py`；它会在本地建立测试用户及登录会话，读取配置后退出，不调用付费生成、短信或真实支付。

## 复制的配置及本地差异

配置导入明确限定表名，并把源与本地每行逐项比较：

| 配置 | 数量 |
| --- | ---: |
| Nacos 配置文件 | 11 |
| 媒体供应商连接 | 4 |
| 旧 Ark 兼容连接 | 1 |
| MPS 配置记录 | 1 |
| 路由供应商 | 4 |
| 模型路由 | 13 |
| 价格目录 | 1 |
| 全局模型价格 | 94 |
| 积分换算率 | 1 |
| 推广收益规则及版本记录 | 18 |
| 支付终端配置 | 1 |

正式数据源与 Nacos 地址被替换为本地地址，内部服务 token、Access 加密密钥、管理员账号和会话独立。媒体连接加密密钥保留源值以便解密导入的供应商配置。数据库结构来自当前代码的 Flyway 迁移；正式用户、任务、账务、充值订单、支付订单状态及登录会话均未复制。支付配置只读取 `terminal:` 记录，不读取 `order:` 业务记录。

云端模型供应商、火山 TOS、腾讯 COS/MPS 和资产审核是外部服务。已复制其当前项目的地址、凭据与模板，实际生成、上传和增强仍需要联网，也可能使用该云账号额度；这些云服务的执行引擎没有本地部署。当前 Storage 适配的是 TOS，没有把 MinIO 当作兼容替代品。

本地固定验证码不发送真实短信；收钱吧配置留在源快照中，支付终端配置已导入，但本地在线支付通道关闭。支付 worker 启用，用于完成本地手工充值的密钥开通、积分入账和本地推广账本。127.0.0.1 无法接收公网支付回调，在线支付完整联调需要独立测试商户和可达回调地址。本地手工记账、审核和后台管理仍使用本地数据库。

已有固定验证码测试用户 `19900000001` 可运行 `python deployment/local/seed-test-account.py` 补齐开通记录和约 10,000 测试积分。脚本校验数据库只连接本机 23306 的 `xzwl_local_*`，通过本地管理员手工充值、确认和后台入账流程建立首充记录；备注明确为模拟充值。重复运行复用同一订单，不重复加积分。进度和结果保存在忽略的 `target/test-account.json`，服务重启保留数据库记录。

## 验证与文件

后端 Maven 构建、三个前端构建和 Nginx 配置检查已执行。服务验证检查真实 HTTP 接口、12 个 Nacos 实例地址、11 个 Flyway schema；canvas、operations 没有 Actuator，使用受保护业务接口的实际 401 响应和迁移结果验证。登录验证覆盖管理员密码登录、固定验证码用户登录、配置读取，以及用户 / 管理员 / API 会话隔离；没有提交付费生成或真实支付。

- [配置导入报告](target/import-report.json)
- [服务与数据库验证](target/verification.json)
- [前端及登录验证](target/http-verification.json)
- [运行时日志](target/logs/)
- [本地服务环境](target/.env.services.json)

配置导入前保存本地备份；存在未完成媒体任务时拒绝导入，避免运行中的任务被换供应商配置。Stop 不执行删除数据卷的操作。原 `.scratch/local-runtime` 的 Windows MySQL 与数据库文件保留在原处，新后端使用 Docker 的 23306，旧数据没有覆盖。

实现参考：[Docker Desktop CLI](https://docs.docker.com/desktop/features/desktop-cli/)、[Nacos 2.5 部署说明](https://nacos.io/docs/v2.5/manual/admin/deployment/deployment-cluster/)。
