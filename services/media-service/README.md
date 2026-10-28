# Media service

Java 21 / Spring Boot 3.5.16 / MyBatis-Plus 3.5.17。服务端口默认为 `8217`。
仅提供新系统接口。生产入口使用 `application/task` 的持久化工作流；既有 `generation`
和 `worker` 领域用例保留回归测试，不作为另一条生产执行入口。

## 接口

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/v1/media/generation-options` | 当前 Owner 有价格且 Routing 配置有效的模型、分辨率与比例 |
| POST | `/api/v1/media/quotes` | 按同一能力校验并调用 Billing 试算，返回四位积分字符串 |
| POST | `/api/v1/media/tasks` | 提交 IMAGE 或 VIDEO，要求 `Idempotency-Key`；返回 202 与已持久化状态 |
| GET | `/api/v1/media/tasks/{taskId}` | 查询当前用户的任务、状态与已归档结果 |
| GET | `/api/v1/media/tasks?limit=20&beforeId=...` | 当前用户任务列表，按 ID 降序游标分页，limit 为 1–100 |
| GET | `/api/v1/media/tasks/{taskId}/results` | 成功或部分成功任务的 `{index,fileId}` 列表 |
| GET | `/api/v1/media/tasks/{taskId}/outputs/{outputIndex}/enhancement-options` | 本人成功视频可用的更高档位、实际计费秒数和 15 分钟有效报价；缺少适用模板或价格时不提供该目标 |
| POST | `/api/v1/media/tasks/{taskId}/outputs/{outputIndex}/enhancements` | 以 `quoteId` 和 `Idempotency-Key` 确认独立付费增强；重试复用已受理任务 |

视频生成页面及其历史视图提供主动“超分增强”。生成交付策略保持现状，已交付原视频作为源，增强版通过任务响应的 `enhancements` 关联回原记录。相同目标处理中禁止重复付费，已有成功版本直接复用；失败只退款增强费用。增强处理或退款期间禁止隐藏、重新生成原记录。后台“价格设置”提供 720p、1080p、4K 增强价格，计费使用源视频实际时长向上取整到秒。复用既有 V22–V24 表结构，无新增迁移。

请求例子：

```json
{
  "type": "IMAGE",
  "model": "configured-model",
  "prompt": "A cloud over the sea",
  "aspectRatio": "1:1",
  "resolution": "configured-resolution",
  "count": 1,
  "durationSeconds": 0,
  "references": [{"fileId": "existing-file-id"}]
}
```

VIDEO 使用 `count: 1` 和 `durationSeconds: 1..600`。IMAGE 支持 `count: 1..4`、
`durationSeconds: 0`。参考素材至多 16 个，每项只能有 `fileId` 或 `httpsUrl`。
文件先通过 Storage 的 multipart 上传接口取得文件 ID；媒体任务接口不接收文件字节。
Worker 在扣费前调用 Storage 的 Owner 与 `REFERENCE_IMAGE` 用途校验接口，将 fileId
解析为短期 HTTPS 引用并持久化；原始请求保留 fileId 用于幂等比较。Adapter 只接收已解析引用。
未知 JSON 字段、Base64 字段、data URL、非 HTTPS 媒体地址和用户信息 URL 被拒绝。
模型、分辨率由 Routing / Billing / 具体供应商适配器验证其业务支持范围。

同一用户、同一幂等键、同一请求返回原任务；不同请求返回 409。其他用户查询返回 404。
用户身份来自已认证的 Servlet Principal，或使用现有 `XZWL_SESSION` Cookie 经 IAM
校验；写请求还需 `X-CSRF-TOKEN`。没有新增身份头信任规则。
IAM 读取 `/api/v1/auth/session`，写校验使用 `/internal/v1/iam/session/verify`。

202 表示任务已受理，不代表已生成或已扣费。正常受理请求同步尝试保存 Routing 快照、
完成扣费，然后由后台提交供应商；依赖暂不可用时保留可恢复阶段。
查询不泄漏供应商端点、配置、签名源 URL、凭据或内部异常消息。

## 画布生成选项与试算

Media 领域在缺少选择字段时兜底：图片为 `seedream-5.0-lite / 1:1 / 2K / 1 / 0`，
视频为 `seedance-2.5 / 16:9 / 720p / 1 / 5`（模型／比例／分辨率／数量／秒数）。
默认值不创建模型或价格；仍须配置 Routing 能力、Billing 价格以及供应商允许列表。
产品支持比例 `1:1`、`16:9`、`9:16`、`4:3`、`3:4`，实际可选范围取对应模型能力。

Routing 的 `configuration` 示例：

```json
{
  "type": "IMAGE",
  "displayName": "Seedream 5.0 Lite",
  "resolutions": ["2K"],
  "aspectRatios": ["1:1", "16:9"],
  "maxCount": 4,
  "minDurationSeconds": 0,
  "maxDurationSeconds": 0
}
```

将它配置在产品模型 `seedream-5.0-lite`，其 `providerModel` 须对应下文的
`MEDIA_ARK_MODELS` 标识。视频使用 `type: VIDEO`、`maxCount: 1` 和实际最短、最长秒数。
目录忽略缺失或非法能力配置，并只保留当前 Owner 账户类型有价格的分辨率；零价格仍可用。

```json
{"type":"IMAGE","model":"seedream-5.0-lite","aspectRatio":"1:1","resolution":"2K","count":1,"durationSeconds":0}
```

以上是 `POST /api/v1/media/quotes` 请求，响应格式为 `{"points":"12.3400"}`，数值由 Billing 实际配置决定。
图片按每张单价乘张数，视频按每秒单价乘时长，再乘积分汇率；沿用 `Decimal4` 逐步截取规则。
试算只读取资格与价格，不修改钱包、扣费回执或消费流水。比例不参与账务计价和幂等事实串。
任务提交与试算共用能力校验，不支持的选择返回 422；提交校验失败时不创建任务、不扣积分。

新增内部调用均使用 `X-Service-Token`，不暴露给 Gateway：

| 服务 | 内部路径 | 凭据匹配 |
| --- | --- | --- |
| Routing | `GET /internal/v1/models` | `MEDIA_ROUTING_TOKEN` = `ROUTING_MEDIA_TOKEN` |
| Billing | `GET /internal/v1/billing/media-prices?ownerId={ownerId}`、`POST /internal/v1/billing/media-quotes` | `MEDIA_BILLING_TOKEN` = `ABP_INTERNAL_TOKEN` |
| Storage | `POST /internal/v1/files/reference-images/access-url` | `MEDIA_STORAGE_TOKEN` = `STORAGE_MEDIA_TOKEN` |

Storage 请求体为 `{"ownerId":"owner-id","fileId":"reference-file-id"}`，返回 `url` 与 `expiresAt`。
仅文件本人且用途为 `REFERENCE_IMAGE` 时签发一小时 HTTPS 地址；他人文件、头像和生成结果返回 403。
Worker 保存解析后的执行引用，提交与轮询使用该快照，原请求保留用于幂等重放。
运行配置只填写凭据环境变量；日志与公开任务响应不包含签名 URL 或真实密钥。

## 工作流与持久化

`ROUTING → CHARGE_PENDING → SUBMIT_PENDING → RUNNING → ARCHIVING → SUCCEEDED`。
明确失败、超时和图片部分失败进入 `REFUND_PENDING`，原扣积分退款确认后进入
`FAILED`、`TIMED_OUT` 或 `PARTIAL_SUCCESS`。退款不可用时保留待退款状态直到恢复。
缺少供应商配置时保留首次路由快照，超时结束且不扣费。

每次外部操作前已持久化其阶段；每个执行周期推进一个持久化阶段。
ROUTING 内依次选择路由、检查供应商环境、解析参考图、校验供应商请求，再冻结路由与执行引用。
租约 token、version、旧状态与未到期条件共同阻止旧进程写回。到期租约可被另一进程抢占。
任务创建、状态变更、状态历史及输出记录使用同一数据库本地事务。
跨服务失败通过持久化阶段与稳定操作 ID 恢复，不跨库读表或执行分布式事务。

| 表 | 内容 |
| --- | --- |
| `media_task` | 用户／幂等键唯一约束、请求与 Routing/Billing 快照 JSON、当前阶段、providerTaskId、截止时间、下次执行时间、租约与版本 |
| `media_task_state` | 每次持久化推进的旧／新状态、版本和时间 |
| `media_task_result` | 任务与输出序号唯一约束；归档前暂存 HTTPS 来源，归档后仅保留 Storage fileId；单项确定失败保留失败码 |

迁移位于 `media-infrastructure/src/main/resources/db/media-migration`。只需要该服务独立的
MySQL schema，MySQL 专属 V2 迁移将三张表固定为区分大小写的 `utf8mb4_bin` collation。
没有二进制数据库列。共用 V1 与 MySQL V2 按 Flyway vendor location 分开加载。
普通查询使用 MyBatis-Plus BaseMapper；抢占、CAS、幂等创建和结果条件归档使用显式 Mapper SQL。

## 跨服务适配

- Routing：调用现有 `POST /internal/v1/tasks/{taskId}/route`，保存完整响应快照。
  同一任务不重新选择供应商，无自动 failover。
- Storage：调用现有 `POST /internal/v1/files/media-results/archive-url`，传入
  `ownerId`、`sourceUrl` 与 `archiveKey`，保存响应 `fileId`。所有输出归档后才可返回成功。
  `archiveKey` 固定为 `taskId:stage:index`，stage 区分 `NATIVE`、`ENHANCEMENT`、`RESULT`；
  同一任务输出重试即使临时下载签名变化仍使用同一个 key。存储以 owner 隔离该身份，
  下载仍使用完整 `sourceUrl`。内部接口缺省/null key 兼容旧媒体请求；提供时限定 1..256 位
  ASCII 字母数字及 `._:-`，无效 key 返回 400。无需数据库结构迁移。
  发布顺序：先升级兼容新字段的 storage，暂停归档领取并排空旧 media/storage 活动上传，
  再升级 media 并恢复领取；新旧身份不能跨版本互斥，不能省略排空直接混跑。
  已保存的旧 fileId 不变；旧版未保存回执的对象不自动映射到新身份。
  新身份下归档成功但响应丢失时，重试复用同一个文件 ID；对象已上传而记录缺失时校验后恢复记录。
- Billing：`BillingPort.charge` 要求按 taskId 原样重放扣费回执，回执含原四位金额、
  rate 与实际扣除积分；`refund` 以 `{taskId}-refund` 重放并恢复原消费分摊。
  完全失败退原扣积分，部分生图失败按失败数量比例截取四位。
  任何网络超时都不能被适配器翻译为确定拒绝。扣费响应不明时先重放确认，再按超时处理退款。
- Provider：`ProviderPort` 明确包含配置可用性检查、幂等提交和轮询。
  生产适配器使用 taskId 对应的数据库提交回执重放已确认结果；不能在超时后盲目新建任务。
  供应商返回的结果只能是 HTTPS URL，经 Storage 归档后对外返回 fileId。
  文件 ID 的后台解析依赖尚缺，当前明确拒绝；不可转换为 Base64。
  `submit` 同时接收任务中保存的 ownerId，适配器必须按该身份验证文件访问权。

## 配置与运行

| 环境变量 | 默认值／用途 |
| --- | --- |
| `MEDIA_DB_URL`, `MEDIA_DB_USERNAME`, `MEDIA_DB_PASSWORD` | 必填，独立 MySQL 数据源 |
| `MEDIA_PORT` | `8217` |
| `MEDIA_BILLING_URL`, `MEDIA_BILLING_TOKEN` | `http://127.0.0.1:8214`，token 默认取 `ABP_INTERNAL_TOKEN` |
| `MEDIA_IAM_URL` | `http://127.0.0.1:8210` |
| `MEDIA_ROUTING_URL`, `MEDIA_ROUTING_TOKEN` | Routing 地址默认 `http://127.0.0.1:8216`，服务身份凭据 |
| `MEDIA_STORAGE_URL`, `MEDIA_STORAGE_TOKEN` | Storage 地址默认 `http://127.0.0.1:8219`，服务身份凭据 |
| `MEDIA_HTTP_CONNECT_TIMEOUT`, `MEDIA_HTTP_READ_TIMEOUT` | `5s`, `180s`（同步生图） |
| `MEDIA_WORKER_ENABLED` | `true`，关闭时仅暂停后台扫描 |
| `MEDIA_SCAN_INTERVAL` | `1000` 毫秒 |
| `MEDIA_IMAGE_TIMEOUT`, `MEDIA_VIDEO_TIMEOUT` | `1h`, `6h`；在受理时固定任务截止时间 |
| `MEDIA_POLL_INTERVAL`, `MEDIA_RETRY_INTERVAL` | `10s`, `10s` |
| `MEDIA_LEASE_DURATION` | `210s`，必须大于连接＋读取超时再加 5 秒 |
| `MEDIA_BATCH_SIZE` | `20`，每轮最大任务数，1–100 |

数据库时间由 UTC 应用时钟换算毫秒保存；多实例主机需保持时钟同步。
外部 HTTP 客户端不跟随重定向，服务 token 仅用于各自配置的服务地址。
供应商配置、endpoint、API key、secret 等只应由具体 Adapter 从环境变量读取，
不得写入任务或日志；下文列出已接入的方舟与腾讯 MPS 配置。

```sh
./mvnw -pl services/media-service -am package
java -jar services/media-service/media-bootstrap/target/media-bootstrap-0.1.0-SNAPSHOT.jar
```

准备 IAM Cookie、CSRF token，在 Routing 配置实际模型后：

```sh
curl --fail-with-body http://127.0.0.1:8217/api/v1/media/tasks \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: media-demo-1' \
  -H "X-CSRF-TOKEN: $MEDIA_DEMO_CSRF" \
  -b "XZWL_SESSION=$MEDIA_DEMO_SESSION" \
  --data '{"type":"IMAGE","model":"configured-model","prompt":"A cloud","resolution":"configured-resolution","count":1,"durationSeconds":0,"references":[]}'
```

用返回的 taskId 查询任务；成功后使用返回 fileId 调用 Storage 的
`GET /api/v1/files/{fileId}/access-url` 获取受控访问地址。

## 当前真实联调条件

Media 通过 `HttpBillingPort` 调用 Billing 的 `/internal/v1/billing/media-charges` 和
`/internal/v1/billing/media-charges/{operationId}/refund`。这两个媒体入口复用现有
`BillingLedgerStore.consume/refund` 的真实钱包、FIFO 分摊和原路积分退款。
Billing 从 Access 查询用户主 Key，要求有效 MEDIA Key；请求不能指定他人 Key 或扣费金额。
首次计价按账户类型、模型、分辨率选择 Billing 配置，图片乘张数，视频乘秒数；
金额与当前兑换率均截取四位，计价回执与扣费在同一 Billing 本地事务提交。
同操作 ID 在资格检查前先取得 Billing 数据库事务锁，避免在途扣费被重试提前判为失败。
原回执先于当前配置/Key 状态检查重放，禁用后仍可确认历史扣费和退款，不能新增消费。
没有价格配置时拒绝新扣费，不提供默认价格。

配置 `BILLING_MEDIA_PRICES` 为 JSON 数组，例如下列**测试配置**：

```json
[{"accountType":"PERSONAL","model":"configured-model","resolution":"configured-resolution","pricePerUnit":"1.2345"}]
```

`pricePerUnit` 为每张图片／每秒视频的金额；兑换率由 Billing 已有充值兑换率配置维护。
调整配置只影响新任务；Billing 的 `media_charge_receipt` 保存既有任务的金额、兑换率和积分。
该接口只接受新系统任务，不使用旧协议 DTO。

Media→Billing 使用 `X-Service-Token`，`MEDIA_BILLING_TOKEN` 必须匹配 Billing 的 `ABP_INTERNAL_TOKEN`。
Media→Routing/Storage 同样使用 `X-Service-Token`：`MEDIA_ROUTING_TOKEN` 对应
`ROUTING_MEDIA_TOKEN`，`MEDIA_STORAGE_TOKEN` 对应 `STORAGE_MEDIA_TOKEN`（均至少 32 字符）。
Routing token 可查询模型目录和执行任务路由，不能配置 Provider/Model；Storage token 可解析本人参考图和归档结果 URL。
缺少或错误 token 均拒绝对应内部操作。

已接入方舟与 MPS 生产 Adapter。真实联调仍需模型权限、供应商凭据，以及 Storage 已配置的
TOS region、endpoint、私有 bucket、Access Key ID/Secret 和结果下载域名白名单。
缺少方舟配置时，任务不会进入扣费阶段；增强目标缺少 MPS 配置时也不会扣费。

## 方舟与腾讯 MPS 生产 Adapter

仅复用供应商协议；公开请求仍是本页的新系统 TaskRequest。支持 Seedream 同步单图/组图、
Seedance 异步文生视频与首帧/首尾帧图片输入，MPS `ProcessMedia` / `DescribeTaskDetail`。
公开 DTO 使用 `aspectRatio` 指定画幅；视频参考类型、音频开关与手动增强仍未开放。
Seedream 参考图与输出数量之和至多 15；Seedance 参考图至多 2，按列表顺序为首帧/尾帧。
所有参考和结果均验证 HTTPS，图片请求固定 `response_format: url`，不接受 Base64 结果。

| 环境变量 | 含义 |
| --- | --- |
| `MEDIA_ARK_PROVIDER_ID` | 与 Routing 快照 providerId 精确匹配的已配置供应商 |
| `MEDIA_ARK_BASE_URL` | 方舟根地址或以 `/api/v3` 结尾的基址，无默认值 |
| `VOLC_ARK_API_KEY` | Seedream / Seedance 的 Bearer Key，仅环境读取 |
| `MEDIA_ARK_MODELS` | JSON 模型允许列表，精确匹配 Routing 的 providerModel；无默认模型 |
| `MEDIA_MPS_ENDPOINT` | MPS API 根地址，无默认值 |
| `TENCENTCLOUD_SECRET_ID`, `TENCENTCLOUD_SECRET_KEY` | 腾讯 MPS TC3 签名与 COS 下载签名凭据 |
| `MEDIA_MPS_REGION` | MPS API 地域 |
| `MEDIA_MPS_BUCKET`, `MEDIA_MPS_BUCKET_REGION` | MPS 输出 COS Bucket（含 APPID 后缀）与地域 |
| `TENCENTCLOUD_MPS_TRANSPARENT_SCHEDULE_ID_<720P/1080P/4K>_<WIDE/SQUARE/STANDARD/ULTRAWIDE>` | 生视频串联编排 ID，按目标分辨率和画幅选择 |
| `MEDIA_MPS_TEMPLATE_720P` | 720p 目标使用的完整转码/增强模板 Definition |
| `MEDIA_MPS_TEMPLATE_1080P` | 1080p 目标使用的完整转码/增强模板 Definition |
| `MEDIA_MPS_TEMPLATE_4K` | 4K 目标使用的完整转码/增强模板 Definition |

这些变量直接从进程环境读取，空值不启用对应能力。供应商端点必须是 HTTPS；
仅 `http://127.0.0.1:<port>` 可用于本地 HTTP fixture。供应商 HTTP 不携带内部服务 token，
不跟随重定向。连接/读取时限复用 `MEDIA_HTTP_CONNECT_TIMEOUT` / `MEDIA_HTTP_READ_TIMEOUT`；
Seedream 同步生成可能需要调高读取时限，并同步调高 `MEDIA_LEASE_DURATION`，满足启动时的租约校验。

`MEDIA_ARK_MODELS` 配置示例（须匹配实际开通能力）：

```json
[
  {"model":"doubao-seedream-5-0-lite-260128","type":"IMAGE","resolutions":["2K"],
   "aspectRatios":["1:1","16:9"],"sizes":{"2K":{"1:1":"2048x2048","16:9":"2560x1440"}},
   "minDuration":0,"maxDuration":0},
  {"model":"configured-video-provider-model","type":"VIDEO","resolutions":["720p"],
   "aspectRatios":["16:9"],"minDuration":5,"maxDuration":15}
]
```

图片 `size` 精确取自 `sizes[resolution][aspectRatio]`；配置或组合缺失时在扣费前拒绝。
视频请求携带 `ratio`，不通过代码猜测图片尺寸。
视频复用既有
`VideoDeliveryPlan`：480p 原生；720p 由 480p 增强；1080p 由 720p 增强；4K 由 1080p 增强。
只有上游模型实际支持相应源档位时才应配置目标档位。具体模型 ID、已开通版本、尺寸和时长
能力由运营环境允许列表决定；不接受任意 Routing 模型，也不默认启用未来模型版本。
路由快照选择 providerId/providerModel，实际调用地址来自环境，不把快照 endpoint 当作凭据发送地址。

新增强任务优先使用 `TENCENTCLOUD_MPS_TRANSPARENT_SCHEDULE_ID_*`。WIDE 对应16:9/9:16，
SQUARE对应1:1，STANDARD对应4:3/3:4，ULTRAWIDE对应21:9。只要配置任一编排，对应档位/画幅缺失就拒绝，
不会回退单模板。十二个已确认值见 [schedules.env](../../deployment/aiyomio/env/mps-schedules.env)，
加载到 media 进程环境后重启生效；本文件不含凭据，凭据、COS桶/地域仍使用现有配置，后台数据库配置不覆盖编排映射。

扣费前只读查询 `DescribeSchedules` 的纯串联图及 `DescribeTranscodeTemplates` 的末模板，
保存编排ID、全链模板、最终宽高和末Definition；竖屏按 `ResolutionAdaptive=open` 交换长短边。
`ProcessMedia` 只发 `ScheduleId`，完成后解析 `ScheduleTask.ActivityResultSet`，
逐个核对全部模板成功并选唯一末节点，核对实际宽高、桶、地域和路径。中间结果不会发布。
方形/超宽的实际尺寸以已配置末模板为准，不能套用固定短边720/1080/2160。

未配置任何编排时沿用单个完整Definition及原短边验证。历史任务使用原快照，配置变化或重启不重新选择编排/模板。
验证失败不公开低清源视频，按原失败处理流程退款。COS 对象 URL 先以无签名形式暂存，调用 Storage 归档时在内存生成 15 分钟签名；
SecretId/SecretKey 不随签名 URL 落库。Storage 必须允许下载方舟输出域名及配置的 COS 域名。
只有 Storage 返回 fileId 后才能发布正式结果，文件下载与对象存在性检查沿用 Storage。

### 提交与恢复

V3 新增 `media_provider_operation`，只保存非秘密执行快照、阶段、上游任务 ID、
待归档 HTTPS 来源及解析后的输出索引。模板、源/目标档位在扣费前固化。
不保存供应商凭据、原始请求、原始响应或错误文本，不产生二进制数据库字段。

每次付费 POST 前先 CAS 落库 `ARK_SENDING` / `MPS_SENDING`；只有唯一赢家可发送。
已保存回执的重复调用直接重放，不依赖当前供应商凭据；进程在写主任务状态前退出也能恢复。方舟异步回执保存后
按 ID 查询，图片同步回执通过本地 ProviderPort handle 交给原归档阶段。MPS 使用 taskId
作为 `SessionId`，但重复 SessionId 错误不被解释为首次提交失败。

网络超时、408/409/429/5xx、重定向或格式损坏导致提交结果不明时，不重新发送 POST，
保持待恢复状态直到原任务截止时间，然后按原四位扣费退款。供应商未提供已确认的方舟
幂等提交/按本地操作 ID 找回能力，因此不能保证这类未知任务能找回成品；上游仍可能收费。
明确拒绝或轮询终态失败进入原失败退款流程。部分图片归档成功仍保留，仅退未交付比例。
环境中的 Key 可轮换，已付款任务的地址/Bucket 改变会暂停执行而不改派；恢复原配置后继续。

### 协议参考与真实凭据验收

协议依据旧系统指定的只读实现，并核对供应商文档：
[方舟图片](https://www.volcengine.com/docs/82379/1541523)、
[方舟视频提交](https://www.volcengine.com/docs/82379/1393047)、
[方舟视频查询](https://www.volcengine.com/docs/82379/1521309)、
[MPS ProcessMedia](https://cloud.tencent.com/document/product/862/37578)、
[腾讯 TC3 签名](https://cloud.tencent.com/document/product/862/132096)、
[COS 请求签名](https://intl.cloud.tencent.com/zh/document/api/436/7778?lang=zh)。
方舟文档在线抓取不可用时以已提供旧实现为依据，没有把第三方代理协议作为规范。

仍需真实凭据验证：Seedream 模型权限、实际 size/组图与参考图限制；Seedance 模型与源档位、
首尾帧及时长限制；MPS Secret 权限、所配 Definition 的实际增强效果与尺寸；COS 签名下载、
Storage 域名白名单与最终 TOS 归档。HTTP fixture / H2 结果不代表真实供应商联调通过。

## 定向验证

供应商变更的必要验证（JDK 21；`-am` 仅构建依赖，测试按 media 完整类名选择）：

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -pl services/media-service -am package \
  -Dtest='com.xzwl.cloud.media.infrastructure.provider.ProductionProviderTest,com.xzwl.cloud.media.infrastructure.task.MyBatisMediaTaskStoreTest,com.xzwl.cloud.media.infrastructure.task.MediaRestartTest,com.xzwl.cloud.media.infrastructure.http.MediaHttpClientsTest,com.xzwl.cloud.media.infrastructure.http.HttpBillingPortTest,com.xzwl.cloud.media.infrastructure.http.HttpMediaSessionPortTest,com.xzwl.cloud.media.application.task.MediaWorkflowTest,com.xzwl.cloud.media.domain.task.MediaTaskRulesTest,com.xzwl.cloud.media.MediaApiTest,com.xzwl.cloud.media.MediaProviderConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

`ProductionProviderTest` 使用本地 HTTP 服务验证真实 Adapter 的提交/轮询/错误解析、
并发与不明响应不重发、MPS 尺寸验证、TC3/COS 固定签名向量、归档屏障、部分退款，
以及 H2 文件数据库关闭重开后的回执恢复与超时退款。

以上定向测试不调用真实供应商，覆盖输入与状态规则、四位退款、
任务幂等、跨用户隔离、调用响应丢失、超时、部分失败、归档、HTTP 路径与载荷、
真实 MyBatis/Flyway 数据库事务、并发抢占、租约 fencing，以及关闭后重新打开文件数据库恢复。
原有内核数据库测试采用 H2 MySQL 模式；新增发布集成用例使用真实 MySQL，均不代表真实供应商验收。

发布集成定向命令（JDK 21，本地 MySQL `127.0.0.1:13316`，测试账号 `root` 空密码）：

```sh
bash scripts/verify-release-integration.sh
```

该命令显式选择受影响服务的测试类并打包。`MediaServiceLoopMysqlTest` 使用各服务独立 MySQL
schema 与真实 HTTP Controller，覆盖扣费成功后丢失响应、兑换率变化后重放、Key 禁用后历史
扣费重放/退款、余额不足回滚、Media/Billing 重启、归档与部分成功退款；仅外部身份查询、
生成供应商与对象存储网络调用使用测试替身。它不代表真实供应商验收。

## 按供应商接入 ZEX

上游请求拒绝、超时和生成失败的持久化诊断与查询方式见 [PROVIDER-DIAGNOSTICS.md](PROVIDER-DIAGNOSTICS.md)。

管理连接支持 `https://zexapi.com/v1`，沿用 providerId 独立持久化、主管理员权限和换主机重填密钥规则。保存后读取该账号的 `/v1/models`，管理员自行选择目录中的模型。已适配模型和接口以 media-infrastructure 中的 provider 适配器实现为准；未知模型明确禁用，不回退为 Ark。

ZEX 图片使用 Images 或 Gemini 原生 JSON，视频使用 `/v1/videos`，由冻结的任务连接地址选择协议。Gemini 原生档位不配置虚构的固定像素尺寸；Omni 为原生 720p/10秒，不走 MPS。连接验证为只读模型查询。模型目录、鉴权通过均不代表已验证付费生成能力。

## 上传虚拟人像后自动审核

`POST /api/v1/media/portraits` 接收已上传并属于当前用户的图片 `fileId`、公开模型 `model` 与
`Idempotency-Key`。图片字节仍由 Storage 上传；本接口不扣除视频生成积分。审核在数据库持久化后
依次调用火山 `CreateAssetGroup`（`GroupType=AIGC`）、`CreateAsset` 与 `GetAsset`。
后台扫描独立推进审核；列表只返回当前用户记录，详情刷新已知资产（包括已通过的资产），不创建资产。
只有 `ACTIVE` 记录可以在视频 `OMNI` 模式通过图片引用的 `portraitId` 使用。

部署必须独立设置 `MEDIA_ASSET_PROVIDER_ID`、`MEDIA_ASSET_ACCESS_KEY_ID`、
`MEDIA_ASSET_SECRET_ACCESS_KEY`、`MEDIA_ASSET_PROJECT_NAME`。审核不会复用 TOS 或腾讯凭据。
`MEDIA_ASSET_PROVIDER_ID` 对应后台持久化 Ark 连接（也支持现有环境回退连接）；生成连接必须为
官方 `https://ark.cn-beijing.volces.com`，模型必须是 `OmniReference` 已支持的精确 Seedance 版本。
资产服务默认使用 `https://ark.cn-beijing.volcengineapi.com`；`MEDIA_ASSET_ENDPOINT` 仅允许该官方主机
或服务端显式配置的 `http://127.0.0.1:端口` 测试夹具，浏览器无法指定地址。

V11 保存用户与幂等键回执、审核阶段以及任务的独立资产映射。每个创建请求先用数据库 CAS 标记发送；
响应不明时进入 `UNCERTAIN`，换键和重启均不重发创建请求。已知资产可以继续只读轮询。
同一幂等键先重放原请求，即使配置已变更；不同文件或模型返回 409。生成与资产凭据仅保留服务端
SHA-256 身份摘要，环境回退连接的密钥轮换同样使旧审核失效。报价不选择路由；任务在扣费阶段前
验证路由、审核身份和上游当前 Active 状态，并持久化资产映射；发送时用同一份捕获的配置复核身份、资产 ID、资产组 ID 与 Active 状态并构建 Ark 请求。审核失败、删除或状态不明均不能使用旧 ACTIVE 记录放行。已创建的视频任务在重新校验前按原始 owner/幂等键/请求重放。
普通媒体引用始终只接受 HTTPS，只有内部已验证图片位置才转换为 `asset://`。

本地签名、审核状态、并发幂等、重启恢复、跨用户隔离和 Ark 请求载荷测试不调用真实供应商；
实际 AK/SK、项目权限、图片审核与付费生成仍需部署环境中的有效凭据验收。

### 本地深度白模处理（BIZ-CT-DEPTH-0001）

深度任务仅在 `local` Spring profile 且 `MEDIA_DEPTH_ENABLED=true`、`MEDIA_DEPTH_TOKEN` 至少32字符时开放。
普通环境返回 `depth-options.enabled=false`；不能经普通 `/api/v1/media/tasks` 提交 DEPTH 请求。
这是本地开发处理能力，不使用 Routing、Ark、MPS 或商业 Billing，也没有默认商业价格。

- `MEDIA_DEPTH_URL` 默认 `http://127.0.0.1:18727`，仅允许 loopback HTTP。
- `MEDIA_DEPTH_TOKEN` 与 native worker 的 Bearer token 一致；文件下载、任务读取与提交均需此令牌。
- Storage 的 `STORAGE_MEDIA_TOKEN` 与 Media 的 `MEDIA_STORAGE_TOKEN` 必须一致。
- 上传使用 `/api/v1/files/processing-videos`，单文件200MiB；服务器探测真实视频轨道时长。超过30秒只允许480p。
- 专用提交只包含 `sourceFileId/resolution`；现有 task/result/file 查询复用。响应 `operation=DEPTH`，
  `references[0].durationSeconds` 为服务端精确时长；整数 `durationSeconds` 仅为现有展示字段向上取整。
- `aspectRatio=source` 表示执行器保留源显示比例；FPS、时间戳、旋转与SAR由执行器读取真实输入字节。
- 执行器只接收经 storage 所有权核验的字节。输入不接受浏览器 URL；本地 storage 测试可返回 loopback URL，
  其他下载地址仍必须为 HTTPS，禁止跳转。
- 输入与结果传输均限制200MiB，结果还需满足 Storage 的现有 `storage.result-max-bytes` 配置；超限失败不会截断视频。
- 结果使用已有内部 multipart 归档，返回用户 `fileId`，不返回 worker地址。沿用任务deadline与重试；
  归档成功但应答丢失时可能产生重复的同所有者对象，现有归档接口未提供幂等键。

常驻执行器与实际视频验收命令见 [depth-worker/README.md](depth-worker/README.md)。不涉及生产部署或GPU采购。
