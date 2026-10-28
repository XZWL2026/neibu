# 03: 打通首条独立付费增强与双版本交付

**What to build:** 用户对本人原生 480p、16:9 视频确认 720p 增强费用后，独立付费处理并取得增强版，在同一视频记录中保留、预览和下载原生版；明确增强失败只退本次增强费用。

**Blocked by:** 01：按原生分辨率生成并交付视频；02：配置增强价格并向用户展示目标与报价。

**Status:** resolved

**Parent spec:** 内部项目：原生视频交付与用户按需超分增强。

**User stories:** 5、7、11、15–18、20、22–23、27–28。

- [x] 使用适用的后台模板，从用户报价确认、增强提交、独立扣费、MPS 处理、归档到本人增强版访问形成完整路径；不再次调用视频生成供应商。
- [x] 增强任务、计价回执和具体原生输出关联持久化，全新数据库正常初始化后可执行；生成任务的成功状态、生成收费、原生文件和原记录均保留。
- [x] 提交重新验证来源、模板、价格和计费时长；实际扣费与已确认费用一致，不发生用户未确认的费用变更。
- [x] 用户在原记录中看到独立增强状态，成功后切换原生版和增强版并分别下载；归档前不发布供应商临时地址或增强中间结果。
- [x] 明确处理失败或最终归档失败时仅退增强费用，退款状态根据真实账务确认展示；原生视频始终可访问。
- [x] 同一原生输出和目标处理中不创建第二项付费处理；重复请求不会重复扣费，已有成功增强版直接复用且不再收费。
- [x] 请求或供应商回执不明时保持原任务可恢复，不自动重发付费处理；本票即建立后续恢复所需的持久化身份，05 扩展故障恢复交互和验证。
- [x] 存在处理中增强任务的原记录不能被隐藏；即使后续历史体验尚未扩展，也不能使付费任务从用户历史中消失。
- [x] 仅本票已经实现并验证的增强来源组合开放执行；其他有效目标的执行范围由 04 扩展，不允许半成品处理入口接受收费。
- [x] 通过公开 HTTP、真实 Billing 回执与钱包、持久化任务及本地 MPS HTTP fixture 验证成功、明确失败、重复提交和归档行为；页面操作可独立演示完整增强及双版本使用。

## Implementation boundary

这是增强的最窄可用执行路径，包含关联结构、正式接口、SDK、页面和行为测试，不拆成单独的数据库、服务端或客户端任务。复用既有任务执行及退款能力，不能用会隐藏原记录的“重新生成”作为增强入口。

本票完成基本计费、权限、幂等和处理中禁止隐藏的约束，不能把这些付费前提留给后续票。真实 MySQL 账务验证被跳过时记录为未完成，不标记本票 resolved。


## Implementation and acceptance — 2026-10-13

Implemented the narrow native 480p/16:9 → 720p path with independent MPS, trusted Billing cost, persistent native-output association and durable request aliases; active/successful same-source/target attempts reuse one charge. Source-row plus shared request-key locks protect hide/regeneration and immutable request identity. Failed enhancement refunds its own receipt; native generation/file/charge remain intact. Public responses contain safe archived file IDs and version summaries, never the internal signed source URL. User confirmation, child status polling, owner-aware recovery, dual previews/downloads and actionable hide refusal use the formal generated SDK.

Final verification: backend 92/92, producer ordinary contracts 4/4, user-page card/history/gallery 40/40; 136 scoped tests, **0 failures, errors or skips**. Actual HTTP/MySQL Billing/MPS/Storage evidence includes real 5.03s MP4s, exact 7.4040-point debit/refund, eligible other-owner refusal, concurrent aliases, explicit new-key retry, native/enhanced byte access, REFUND_PENDING wallet/hide protection and simultaneous hide/admission. SDK generation/strict consistency, user typecheck and build passed. Narrow support is explicit in quote `executionSupported`; other execution combinations remain gated before debit.

Exact commands, red/green evidence and practical limits: [independent-enhancement-evidence.md](../../../services/media-service/docs/independent-enhancement-evidence.md). Broad restart/query-fault scenarios remain ticket 05; parent integration owns final desktop/mobile visual inspection.

## Integration receipt — 2026-10-13

- Whole codex/internal-native-03 branch at def0975ab56c6eb1f16d567eba6823779bc4e5c7 merged without conflicts into codex/internal-native-enhancement at 65c5842e2b38fe39c0c506a2ac1f35a5fd98989c. The merged source tree exactly matches the verified final branch tree (855113b9d9ffdafd42627340cfed508f4a8f35f2); final-head ordinary contract and strict SDK evidence therefore applies without repeating unchanged scoped tests.
- Merger reviewed the completed acceptance checklist and independent-enhancement-evidence.md: backend 92, ordinary ReleaseContractTest 4, user UI 40; 136 scoped tests with 0 failures, errors or skips, plus strict SDK/user typecheck/build. Ticket 03 remains resolved; 04, 05, 06 and 07 are unblocked. Parent spec remains claimed until all tickets and final review finish.
- Untracked integration visual QA fixtures and the active Vite session were preserved; they are excluded from this tracker-only receipt commit.
