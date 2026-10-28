# Gateway 与新系统 API 接入说明

基线：`codex/release-integration` / `89d06cb2688f6908a62db2ac8966828fca82ee50`。
开发分支：`codex/release-gateway-contract`。本次为用户直接批准的发布任务，历史治理文件、工作单与旧协议均未修改。

## 路由范围

Gateway 保留 Controller 原始路径和 HTTP 方法，不 StripPrefix、不重写业务路径。
[完整逐项清单](release-controller-inventory.md)记录十服务的 **60 个公开操作、37 个内部操作、1 个 local-only 操作**。
下表的前缀仅作汇总，实际配置逐路径、逐方法允许，不使用这些前缀的通配路由。

| 服务 | 公开入口汇总 | 操作数 | 本地端口 |
| --- | --- | ---: | ---: |
| IAM | `/api/v1/auth/session`、`/api/v1/auth/otp/send`、`/api/v1/auth/otp/login`、`/api/v1/auth/admin/login`、`/api/v1/auth/admin/password`、`/api/v1/auth/logout`；`/api/v1/admin/admins` 及具体子操作 | 11 | 8210 |
| User | `/api/v1/users/me`、`/api/v1/admin/users/{id}/status` | 2 | 8211 |
| Access | `/api/v1/access/keys`、secret/status；`/api/v1/admin/access/keys/{id}/status` | 5 | 8212 |
| Billing | `/api/v1/billing/wallets/{keyId}`、ledger；`/api/v1/admin/billing/credits`；`/api/v1/settings/runtime` GET/PUT | 5 | 8214 |
| Payment | `/api/v1/payments/recharges`、查询、退款；`/callbacks/payment` | 4 | 8215 |
| Routing | 无公开 Controller | 0 | 8216 |
| Storage | `/api/v1/files/reference-images`、`/avatars`、`/{fileId}/access-url` | 3 | 8219 |
| Media | `/api/v1/media/tasks` 提交/列表、任务详情与 results | 4 | 8217 |
| Promotion | `/api/v1/promotions` 与 `/api/v1/admin/promotions` 下清单列明的申请、推荐、佣金、提现操作 | 24 | 8213 |
| Operations | `/api/v1/admin/operations/{resource}` 列表与详情 | 2 | 8220 |

Gateway 自身端口为 8200。Spring MVC 的 GET 同时允许 HEAD；OPTIONS 预检由 Gateway 处理。
`/internal/**`、`/api/v1/internal/**` 在代理前直接返回 404，包括携带服务 token 的请求。
内部清单包含 IAM Session 校验、用户解析、主 Key/鉴权、账务命令、Routing 配置/选路、Storage 归档、Promotion/Operations 投影与 capability 入口。
`/api/v1/auth/admin/local-login` 即使后端以 local profile 启动，也不公开。
未经配置的路径和方法返回 404。路径穿越、重复分隔符、矩阵参数及编码后的歧义分隔符返回 400。
旧系统入口未实现、未验证；原 Smoke 文件仍保留在仓库历史范围内，不再作为发布 Gateway 路由。

## Session、Cookie、CSRF

1. 浏览器先 GET `/api/v1/auth/session`，保存响应 Cookie，并读取 `csrfToken`；未登录时 `authenticated=false`。
2. POST `/api/v1/auth/otp/send`，JSON 为 `{ "phone": "手机号" }`；带 Cookie 和 `X-CSRF-TOKEN`。
3. POST `/api/v1/auth/otp/login`，JSON 为 `{ "phone": "手机号", "code": "验证码" }`；同样带 Cookie 和 CSRF。
4. 登录成功后 IAM 轮换 Session ID 并清除旧 CSRF。再次 GET session，使用新的 Cookie/CSRF 发起后续操作。
5. 管理员使用 `/api/v1/auth/admin/login`，请求字段为 `username`、`password`，也先取得匿名 Session/CSRF。
6. 所有浏览器写操作和 `/api/v1/auth/logout` 均携带 `X-CSRF-TOKEN`。退出成功后重新获取匿名 Session；旧登录状态不能继续访问用户接口。

Cookie 名 `XZWL_SESSION`，由 IAM 设置，Path=/、HttpOnly、Secure、SameSite=Lax，Gateway 不改写。
本地 HTTP 调试使用 IAM 已有的 `server.servlet.session.cookie.secure=false` 配置；正式入口使用 HTTPS。
跨 origin 请求使用 `credentials: 'include'`。Lax Cookie 要求浏览器与 API 使用同站部署；允许 CORS 来源并不会放开跨站 Cookie。
IAM 的 CSRF token 来自 session JSON，不是可读 Cookie。不要把手机号验证码、Session 或 CSRF 写入日志。

Gateway 透明传递 Cookie、CSRF、Idempotency-Key、原始 JSON/multipart、Set-Cookie、Cache-Control 和上游状态码。
Access、Billing、Payment、User、Media 沿用各自现有 IAM 校验；Storage、Promotion、Operations 新增显式开启的 IAM 桥接。
桥接在每个公开请求中重新验证 IAM 身份，写请求调用 `/internal/v1/iam/session/verify`；不缓存角色，不采信客户端身份/角色头。
缺少/过期会话返回 401，CSRF 或角色拒绝返回 403，IAM 故障返回 503。业务所有者与管理员检查仍由 Controller/service 执行。

## 真实权限规则

| 接口族 | 当前代码规则 |
| --- | --- |
| User self | USER；用户必须启用 |
| User status、Billing 手工加点 | ADMIN 或 NORMAL_ADMIN |
| Billing runtime settings | ADMIN；该 Controller 未检查 primary 标记 |
| IAM 管理员列表、修改自己密码 | ADMIN 或 NORMAL_ADMIN |
| IAM 创建管理员、修改角色 | primary ADMIN |
| IAM 重置密码/启停 | 不得操作自己或 primary；NORMAL_ADMIN 只可管理其他 NORMAL_ADMIN |
| Access key 列表/secret | 当前会话所属 Key；status 允许所有者、ADMIN、NORMAL_ADMIN，admin 路径别名未附加额外角色门 |
| Payment 创建充值 | USER；查询/退款按所有者校验 |
| Storage、Media | 已认证会话及业务所有者校验 |
| Promotion 用户操作 | 所有者；部分读取允许 ADMIN；后台操作要求 ADMIN |
| Operations | ADMIN；resource 仅 users/recharges/consumptions/images/videos/promotions |

合同忠实反映代码；没有将历史前端权限说明强行写成后端已经实现的行为。

## OpenAPI 与错误

公开合同：[xzwl-new-system-1.0.0.json](../contracts/openapi/public/xzwl-new-system-1.0.0.json)，OpenAPI 3.1.0，版本 1.0.0，servers 使用相对根 `/`。
可供新系统 SDK 单独生成使用。没有导入旧合同或运行旧合同 SDK 生成链。
路由表在 `gateways/gateway-service/src/main/resources/public-routes.json`。

`ReleaseContractTest` 读取十服务编译后的 Spring Controller 映射和 Jackson 泛型/record/enum 类型，核对公开路径、方法、query/header、JSON/multipart、请求/响应字段和 `@ResponseStatus`。
唯一非 Controller 的公开操作 logout 来自 `IamSecurityConfiguration`；动态 Map 响应与 runtime JsonNode 字段对照其源码显式描述。
请求字段上的领域校验仍以 Java 代码为准；合同没有把所有引用字段推断成必填或虚构状态枚举。
响应记录明确列出 Jackson 输出成员；可空引用使用 3.1 的 null 联合类型。Java BigDecimal 对应 JSON number，原本以 String 返回的四位账务值仍是 string，前端不可统一按两位金额截断持久化。

成功状态码以真实 Controller 为准：Storage 上传为 201，Media 提交和 Payment 退款为 202，IAM 创建管理员为 201，其余公开操作为 200。
Payment callback 的原始字节和请求头直接传至 PaymentProvider；具体供应商签名协议未冻结，合同不虚构其字段或请求头。

上游错误正文保留原样，不假设所有服务都返回相同 envelope：

| 来源 | 已实现形状与典型状态 |
| --- | --- |
| IAM AuthErrorHandler/OtpService/AdminAccounts | `{code}`；400/401/403/404/409/423/429/503；OTP_LOCKED 为 423，发送频控为 429 |
| Access/Billing/Payment 自有 advice/filter | `{code}`；身份、校验与冲突错误，401/403/400/409/503 |
| StorageController | `{message}`；400/403/404/502 |
| MediaTaskErrors/MediaSessionFilter | `{code}`；400/401/403/404/409/503 |
| Promotion/Operations 自有 advice | `{error}`；400/404/409 |
| 共享 WebError/Gateway 本地错误 | `{code,message,requestId,details}`；保留对应 HTTP 状态 |

Gateway 本地未知路由 404、歧义路径 400、CORS 拒绝 403、连接失败 502，以及负载均衡无实例/超时等框架状态使用 JSON envelope。
`X-Request-Id` 允许 1–64 个字母、数字、`.`、`_`、`-`，无效或缺失时由 Gateway 生成并向上游传递。
前端应同时处理 HTTP status 与可选的 code/message/error，保留 default 错误分支。

## 运行配置

默认目标是 `lb://<service>-service`，关闭自动 discovery locator，避免按服务名生成任意公开路由。
也可为每个服务设置 `GATEWAY_IAM_URI`、`GATEWAY_USER_URI`、`GATEWAY_ACCESS_URI`、`GATEWAY_BILLING_URI`、`GATEWAY_PAYMENT_URI`、`GATEWAY_STORAGE_URI`、`GATEWAY_MEDIA_URI`、`GATEWAY_PROMOTION_URI`、`GATEWAY_OPERATIONS_URI` 为明确的内部 HTTP 地址。
local profile 提供上表 loopback 端口，支持相同环境变量覆盖。

跨 origin 部署时设置 `GATEWAY_CORS_ALLOWED_ORIGINS` 为逗号分隔的精确来源（含 scheme/port），禁止 `*` 搭配凭据。
默认不开放跨 origin；同源请求可直接使用。允许 Content-Type、X-CSRF-TOKEN、Idempotency-Key、X-Request-Id，向前端公开 X-Request-Id。
CORS 在路由匹配之前处理写操作预检，内部前缀则在 CORS 之前拒绝。

三个新增桥接分别使用 `STORAGE_IAM_SERVICE_URL`、`PROMOTION_IAM_SERVICE_URL`、`OPERATIONS_IAM_SERVICE_URL`，默认 `http://127.0.0.1:8210`。
Gateway 不持有数据库、Session 存储或内部业务 token。各服务数据库、IAM 与内部调用 token 按后端基线配置。
业务服务端口应保持内网可达；本次只实现 Gateway 隔离，没有配置 VM 防火墙/TLS 终止器。

## 剩余接入项

- Routing 的 provider/model 管理及 route snapshot 全部为内部 Controller。若管理前端需要公开模型/路由配置，需要另行实现明确的公开 Controller；本次没有将内部接口换前缀公开。
- 短信真实 SmsOtpProvider、支付 PaymentProvider 与签名合同、TOS/模型供应商凭据仍依赖既有发布基线的外部配置。支付回调路由已就绪，不等于真实供应商联调通过。
- 本次提供后端入口与新系统合同，没有修改前端或生成业务 SDK。
- 部分后端授权与此前前端页面策略不同，以上权限表列明了当前代码行为。若产品要求进一步收紧，需要单独修改对应业务服务及其测试。

## 重复验证与合同更新

使用 JDK 21 和仅限测试的本地 MySQL。脚本要求下列环境变量，缺少时直接失败，避免把数据库测试的 skipped 当作通过：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export IAM_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:13326/
export IAM_BOOTSTRAP_ADMIN_USERNAME=release-test-admin
export IAM_BOOTSTRAP_ADMIN_PASSWORD=release-test-only-password
export IAM_OTP_HMAC_KEY=release-test-only-otp-hmac-key-000000000000
export IAM_USER_INTERNAL_TOKEN=release-test-only-user-token-000000000000
bash scripts/verify-release-gateway-contract.sh
```

以上值仅为本地测试示例。数据库测试创建随机 `iam_release_test_*` schema 并在结束时删除。
第一段构建 Gateway 与三个受影响服务，只执行指定测试；第二段运行真实 Gateway JAR → IAM → User 的 MySQL Session 闭环。
验证码供应商使用记录型测试实现，其他登录、Session 存储、CSRF、用户查询和 HTTP 代理均执行真实代码。

Controller/DTO 发生正式修改后，可显式更新新合同和公开路由清单：

```bash
./mvnw -pl gateways/gateway-service -am \
  -Dtest=ReleaseContractTest#contractAndRoutesMatchCompiledControllers \
  -DwriteReleaseContracts=true -Dsurefire.failIfNoSpecifiedTests=false test
```

审查生成 diff 后再执行验收脚本。普通验收不会写合同；新增动态 Map/JsonNode 类型必须显式核对源码，不能默认补全字段。


## 2026-09-08 主 ADMIN 配置增量

用户直接授权的 `codex/admin-model-pricing-config` 在原发布基线增加 9 个公开操作，当前完整清单为
77 个操作（以生成的 release-controller-inventory.md 和 public-routes.json 为准）。

- Routing：`GET /api/v1/admin/routing/providers`、`PUT /api/v1/admin/routing/providers/{providerId}`，
  以及 `GET /api/v1/admin/routing/models`、`PUT /api/v1/admin/routing/models/{modelId}`。
- Billing：`GET/PUT /api/v1/admin/billing/media-prices`，整批事务保存及 expectedVersion 冲突检查。
- 配置状态：`GET /api/v1/admin/media/provider-status`、`/api/v1/admin/storage/provider-status`、
  `/api/v1/admin/iam/sms-provider-status`。

上述接口同时要求经过 IAM 校验的 ADMIN 角色与 primary=true。IAM 会话桥接生成仅内部使用的
PRIMARY_ADMIN 角色，外部身份头没有授权效力。原 runtime settings 等接口的既有权限不因此改变。

后台 `/admin/login` 使用真实 IAM 登录；`/admin/pricing` 和 `/admin/providers` 独立复核真实会话。
NORMAL_ADMIN 及非 primary ADMIN 均不能看到配置入口或通过配置接口访问数据。旧运营页面仍使用原有
本地数据演示，本次不将其声明为已接通真实业务。

Billing 的 V6 迁移新增 `media_price_catalog` 与 `media_price`，V7 增加可空的 `yuan_per_unit`，
旧 `points_per_unit` 原值保留。每条记录必须且只能指定一种计价单位。后台新建或修改价格使用
`yuanPerUnit`（元/张或元/秒，最多四位小数）；先按数量计算元金额，再按当前兑换率换算积分，
沿用 Decimal4 截取四位的规则。旧积分价格只换算显示，未修改时按原值提交，继续使用原计费路径。
显示去掉小数末尾的零，不改变数据库四位精度；旧积分价格的显示按当前兑换率截取四位。
首次后台保存前保留 BILLING_MEDIA_PRICES 的原有金额兼容；首次保存后由数据库完整接管，停用或移除
的维度不再回退到环境价格。保存空列表明确停用所有媒体报价。已产生的扣费/任务快照不追溯重算。

模型能力和尺寸随已有路由快照进入 Media 适配器，后台新模型无需再写 MEDIA_ARK_MODELS。
历史模型缺少能力快照时仍可使用该变量。图片配置必须覆盖声明的每个分辨率和比例组合。
供应商停用影响新 generation-options 和新选路，已保存任务路由快照保持原有重放语义。

方舟实际凭证目标继续固定为部署配置的 MEDIA_ARK_BASE_URL，后台供应商 HTTPS 地址是路由元数据，
不能改变密钥发送目标。配置状态的 available 只表示进程所需配置齐备，不是远端健康或密钥有效性认证；
无需、也不会在打开管理页面时发出计费供应商探测请求。状态不返回凭证后缀，标识统一为 `***`。
TOS 缺凭证时可启动并报告缺失变量，存储调用拒绝执行。短信适配器未实现，状态固定为未配置/不可用，
不臆造短信环境变量，不启用固定 OTP。
