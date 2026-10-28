# 01: 按原生分辨率生成并交付视频

**What to build:** 用户选择模型实际支持的原生分辨率后，直接生成、保存、预览和下载原生视频，生成过程不自动执行超分，也不依赖 MPS 是否可用。

**Blocked by:** None（can start immediately）。

**Status:** resolved

**Parent spec:** 内部项目：原生视频交付与用户按需超分增强。

**User stories:** 1–4。

- [x] 生成目录和用户参数选项只提供供应商实际支持的原生分辨率；不以超分能力补出模型档位。
- [x] 用户请求原生 720p、1080p 或其他已启用档位时，供应商收到相同档位，不发生固定降档；未支持的组合在扣费前被拒绝。
- [x] 方舟新生成流程不提交 MPS；MPS 未配置或不可用时，原生生成仍可成功归档并交付。ZEX 原生直出行为保留。
- [x] 用户获得归档后的本人原生文件，可以预览和下载；供应商临时结果地址不作为正式用户结果返回。
- [x] 生成费用仍使用所选原生规格的生成价格，原有生成失败退款和文件访问权限保持有效。
- [x] 用户创作页与共用历史视图显示正确原生规格和成功结果，图片及其他既有处理入口的功能不回退。
- [x] 从全新数据库初始化后通过公开 HTTP 完成生成和结果访问；供应商 HTTP fixture 验证相同档位请求及没有 MPS 提交，页面用户操作验证选项和结果展示。

## Implementation boundary

在本票开始时，将生成边界中的原生能力选择与增强模板选择解耦，再完成原生交付行为。将需要的内部整理限制在本票的生成路径内，不另开缺少用户行为的基础设施票。更新实际受影响的合同、共享 SDK 与运行说明，保持本票完成后可独立演示和相关检查通过。

不需要旧数据库或旧在途计划的兼容迁移。后续增强票通过已有成功生成结果关联具体原生输出。

## Verification

复用已确认的公开 HTTP 流程及页面用户操作两条测试边界。测试外部交付和费用行为，不锁定内部方法或阶段编号；完成时记录验证结果，未实际验证的场景不勾选。

## Implementation evidence

- 2026-10-13：ticket 01 已领取，在 codex/internal-native-01 隔离实现。
- TDD red：真实 MySQL + 鉴权 HTTP 原生生成场景发现 Seedance 2.5 目录错误提供 4k（期望 [1080p,720p]，实际 [1080p,4k,720p]）；页面用户操作测试发现缺少“原生分辨率”参数语义。两者已 green。
- green：MediaServiceLoopMysqlTest#nativeVideoSelectionReachesProviderWithoutMpsAndDeliversOwnedArchivedResults 1/1，真实空数据库初始化和 Access/Billing/Routing/Storage/Media；Ark HTTP fixture 验证 720p/1080p 原样请求且无 MPS 请求；成功结果为本人归档 fileId，他人 task 查询 404 / 文件访问 403；钱包由 1000 降至 850（5秒720p + 5秒1080p 原生生成价格），拒绝不支持4k不扣费。第三方对象存储与来源字节由系统边界 fixture 模拟。
- 回归：ProductionProviderTest 59/59、ArkModelCatalogTest 2/2；视频创作页面 90/90，图片创作及共享结果/历史视图 85/85；user-web typecheck/build 通过。并行页面测试曾遇到时序失败，单 worker 重跑全相关文件通过。
- 新生成不再自动创建 MPS 执行计划；既有 MPS 模板尺寸、归档、诊断、恢复测试使用明确的冻结增强计划 fixture 保留验证。现有 Ark 模型原生能力条目未修改，仅复用于公开生成目录。
- 集成前待办（现已完成）：分支合并、ticket 02 协作更新的 OpenAPI 原生档位说明及共享 SDK 生成一致性检查。

### Windows SDK verification prerequisite

- 2026-10-13：集成 ebdb01b2 后执行严格 SDK 校验；red 为 Windows 文件符号链接 EPERM 和 Git autocrlf 导致生成目录 CRLF 与 Orval 临时输出 LF 字节不一致。
- 修复只涉及生成目录 eol=lf 属性和安全校验测试 fixture；未修改 snapshot 的真实 lstat、符号链接/特殊项拒绝或字节比较。Windows 使用真实子目录及根目录 junction 验证拒绝，普通文件根目录也必须拒绝。
- green：test-check-generation.mjs、check:api-new-system、check:api-generation 全部通过；两棵工作目录生成 SDK 已归一为 LF。Windows 无 POSIX FIFO，子项及根 FIFO 覆盖明确报告 unavailable，尚未在 POSIX 实际运行；POSIX 分支保留 mkfifo 验证。

## Integrated acceptance

- 2026-10-13：实现 aa7dbc89 / d24143bc 已于 956b25fb 集成；01+02 最终代码与 Windows 校验修复于 593f1d46 集成。原生 HTTP 与报价 HTTP 在合并后的 b593fe93 代码树联合通过 4/4，后续仅增加 UI 范围约束及校验 portability。普通 ReleaseContractTest 4/4（无写合同参数），新系统与 smoke SDK 严格重生成一致性检查及 checker 测试均通过。
- publicGatewayApiKeyLoop 1/1，真实 MySQL、Gateway JAR 与公开 Python 客户端；Windows Python UTF-8 通过环境配置，未修改业务源码。最终日志：.scratch/internal-video-native-enhancement/integrated-native-public-gateway.log。
- 所有本票验收完成，状态 resolved。后续付费增强由 03 开始；父规格仍在实施中。
