# 02: 配置增强价格并向用户展示目标与报价

**What to build:** 管理员独立配置增强价格和适用模板后，用户选定本人原生视频，可看到可用增强目标、真实计费秒数及本次费用；无适用配置时无法进入有效付费提交。

**Blocked by:** None（can start immediately）。

**Status:** resolved

**Parent spec:** 内部项目：原生视频交付与用户按需超分增强。

**User stories:** 8–10、12–14、27–28、31–32。

- [x] 管理员可通过现有定价管理维护独立增强价格，不修改或复用生成价格作为增强费用；管理权限与既有版本冲突保护保持有效。
- [x] 现有 MPS 配置可表达所接受的原生输入、目标和画幅，不再把每个目标强制绑定唯一的低一档来源；报价预检以实际匹配和启用状态为准。
- [x] 服务端验证本人成功原生输出及结果关联，并取得源文件实际规格、时长与可读性。跨用户、非原生生成结果、失效文件和增强版来源均不返回可提交报价，也不扣费。
- [x] 可用目标限定为 720p、1080p、4K，且高于原生档位；匹配画幅、模板及价格缺失或停用时不作为可提交目标。
- [x] 报价使用目标档位对应的账户价格规则和源视频真实时长，向上取整到秒；5.03 秒按 6 秒报价，不能由客户端伪造时长或金额。
- [x] 用户能在视频详情中查看增强目标和提交前费用；无可用目标时有明确提示，不能展示一个会提交无效请求的可用按钮。
- [x] 本票只开放目标预检和报价，不触发 MPS、不扣费；付费提交由 03 接入。报价信息不得把尚未实现的处理能力伪装为已经可以付费执行。
- [x] 通过管理员 HTTP 保存价格、用户 HTTP 查询报价及页面用户操作演示完整配置到报价流程，覆盖账户价格、缺价、缺模板、无权限和文件失效。

## Implementation boundary

该票可使用系统已经支持的本人原生 480p 成功结果独立验证，因此不以 01 为阻塞项。配置与报价的数据契约、SDK 和页面展示在本票内完整交付；之后与 01 的其他原生档位组合验证。

沿用既有 Media、Billing 和 Storage 边界，不增加测试专用接口或虚构默认价格。用户不选择底层模板、编排和码率。

## Verification

复用已确认的公开 HTTP 流程及页面用户操作两条测试边界。通过管理员权限与真实报价响应检查规则，不以页面按钮禁用替代服务端校验。

## Comments

- 2026-10-13: Implementation on codex/internal-native-02; remains claimed pending integration and final verification.
- TDD observed RED then GREEN: enhancement admin HTTP 400 to 200; accepted native 480p-only to 1080p policy 400 to 200; real 5.03-second source quotes as 6 seconds; ultrawide 854x366 source 422 to 200; admin pricing/template UI and single/compact quote UI; malformed 200/null quote response now recoverable with no unhandled Promise rejection.
- MySQL HTTP acceptance 3/3: EnhancementQuotesMysqlTest 2/2 using real 854x480 and 854x366 H.264 bytes, production Ark/MPS adapters and local supplier HTTP fixtures with real Access/Billing/Storage/Media services and fresh MySQL schemas; admin price HTTP test 1/1. Global enhancement 0.25 yuan/second, owner override 0.1234, 6 seconds quoted as 0.7404 yuan / 7.4040 points; wallet unchanged and ProcessMedia count zero. Missing/disabled price, missing template, unauthenticated/nonadmin/foreign owner, wrong output, unreadable file and hidden history covered.
- Regression: admin price/account/API HTTP 5/5, real Billing concurrency 2/2, MediaApiTest 19/19, MPS admin permission 2/2. Admin UI 33/33; user result UI 26/26. ReleaseContractTest 4/4（集成实测）extracts compiled Controllers; generated SDK, workspace type checks and production frontend builds verified.
- Paid submission stays closed in this ticket. 03 must verify/freeze the trusted quote in the Billing debit transaction, and verify that created enhancement results cannot become native sources. Server quote ID/source specs/template evidence/cost snapshot persist for confirmation; execution, debit receipt and refund belong to 03.
- Post-merge native/quote HTTP 4/4 passed on feature tree b593fe93: two real source quote cases, admin price persistence and native delivery with no MPS. User generator/history/business scope regression 119/119 (92 composer and 27 result cases), admin 33/33, workspace type checks and production builds passed. Business ProductSuite shared cards opt out by default; CommerceVideoPage and non-create draft scopes also opt out, while create-video and its common history keep quotation controls. Commercial route scope was observed RED (unwanted quote button) then GREEN.
- 03 handoff: public GET `/api/v1/media/tasks/{taskId}/outputs/{outputIndex}/enhancement-options`; browser session owner only (current platform-key whitelist does not expose this added path). SDK `mediaOptionsGetApiV1MediaTasksTaskIdOutputsOutputIndexEnhancementOptions`.
- Reuse `EnhancementQuoteService.source(owner, taskId, index)`, `options(owner, taskId, index)` and `confirmed(owner, quoteId)`. `EnhancementQuoteStore.Quote` freezes owner, selected Source/file/index/specs/ceil seconds, targetResolution, templateVersion/id, read-only templateEvidence, cost(amount/rate/points) and 15-minute expiresAt. `confirmed` re-probes immutable source/spec facts and returns a fresh authorized URL; signed URL rotation is deliberately not identity. V22 initializes the persistent quote table. 03 still must compare/freeze Billing cost atomically before debit, not use browser amount fields, and freeze the accepted source/plan in a recoverable task.
- Verification commands: set MEDIA_TEST_MYSQL_URL and BILLING_TEST_MYSQL_URL to `jdbc:mysql://127.0.0.1:13306/`, usernames root, passwords empty; set STORAGE_FFPROBE_PATH to an installed ffprobe executable. Maven: `mvn -o -pl services/media-service/media-bootstrap -am -Dtest=EnhancementQuotesMysqlTest,MediaServiceLoopMysqlTest#administratorCanConfigureIndependentEnhancementPricesWithoutGenerationPriceChanges+nativeVideoSelectionReachesProviderWithoutMpsAndDeliversOwnedArchivedResults -Dsurefire.failIfNoSpecifiedTests=false test` (quote arguments when running in PowerShell). Contract: gateway ReleaseContractTest; frontend: typecheck, build, check:api-new-system plus the named admin/user suites.

## Integrated acceptance

- 2026-10-13：实现 99a6b9a8、范围修复 d362b793 与 handoff 格式收尾 9780f1be 已于 593f1d46 集成，无冲突。联合原生/报价真实 MySQL HTTP 4/4、用户生成/历史/商务范围 119/119、管理端 33/33、workspace 类型检查及生产构建通过。后续 UI-only 范围修复未改变已验证的后端行为及 API 合同。
- 普通 ReleaseContractTest 4/4（无 writeReleaseContracts）、check:api-new-system、check:api-generation、test:api-generation-checker 均通过。Windows 使用真实 junction 拒绝测试；POSIX FIFO 覆盖明确 unavailable，未把它记录为实际通过。日志：.scratch/internal-video-native-enhancement/integrated-01-02-contract.log、integrated-01-02-sdk-check.log。
- 本票验收完成，状态 resolved；只提供预检和报价，尚未开放付费提交。03 已解除 01/02 阻塞，必须实现独立扣费、来源/任务关联、冻结费用与模板、MPS 和退款验收。
