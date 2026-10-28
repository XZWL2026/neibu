# 05: 恢复增强进度并安全重试失败任务

**What to build:** 用户切页、刷新、请求断开或服务重启后能找回原增强任务；仅在明确失败后发起新的付费尝试，不因结果不确定产生重复消费。

**Blocked by:** 03：打通首条独立付费增强与双版本交付。

**Status:** resolved

**Parent spec:** 内部项目：原生视频交付与用户按需超分增强。

**User stories:** 20–26。

- [x] 增强提交已受理但响应丢失时，页面能以原请求身份恢复同一个任务；不会换新身份自动创建另一项付费处理。
- [x] 离开页面、刷新或重新进入后，从服务端恢复增强状态、费用、退款状态和关联结果；原生成已经成功并停止轮询不影响增强继续查询。
- [x] 提交恢复信息按当前用户隔离，切换账户不会恢复或显示他人的视频与增强任务。
- [x] 暂时查询失败不把增强错误地标为失败，也不删除已取得的版本；恢复查询后继续显示真实进度或终态。
- [x] 服务重启、供应商提交回执不明和账务响应丢失沿用既有持久化恢复及未知提交规则；不重复提交已付费上游处理，不改变冻结的费用和模板语义。
- [x] 同来源同目标的并发请求、双击及幂等重放最多产生一项在途付费处理，成功结果复用且不扣费。
- [x] 明确失败后的新尝试有独立身份和收费回执，原失败费用按原路退款；原生成及成功增强版不受影响。退款处理中不把旧请求重放误解释为新消费。
- [x] 通过真实账务和持久化的 HTTP 流程注入响应丢失、重启、查询故障及并发提交；页面操作验证提交不确定、切页恢复、账户隔离和失败后重试。

## Implementation boundary

在 03 已具备的基本幂等和恢复身份上交付完整故障恢复与重试交互，并验证跨进程及真实账务行为。本票不改变目标范围、价格策略或原生视频来源限制，因此不依赖 04。

不新增取消在途增强、按供应商临时地址找回用户成品或自动换模板重发的能力。

## Completion evidence — 2026-10-13

实现分支 `codex/internal-native-05`，基于已完成 03/04 的 integration；代码起始提交 `5e89969a`。逐项证据及完整 PowerShell 复现命令见 [恢复验收记录](../../../services/media-service/docs/enhancement-recovery-evidence.md)。

最终 **76 项通过，0 失败、0 错误、0 跳过**：真实 HTTP/MySQL 15 项（本票 2、03 回归 4、04 回归 9）、页面 57 项（含后续 06 layout 回归）、ReleaseContractTest 4 项。用户 typecheck、生产 build、正式 SDK 一致性检查通过。实际进程恢复测试启动 7 个独立 Media JVM，公开钱包和流水证明响应丢失/重复恢复不新增扣费、MPS 或退款；退款终态后的新请求拥有独立消费与 MPS 身份，旧请求始终返回原任务，原生文件保持可读。

页面恢复测试 9 项，包括 720p/1080p/4K 原键手动确认、切页与账户隔离、从终态生成记录独立恢复、使用真实保存费用、保留成功预览，以及旧受理/拒绝响应不能覆写或清除新重试标识的两条 red/green 回归。全局桌面/移动端视觉复核归主任务 integration 收尾。

最终已合入 integration `a59ba9fb`（含 README 与 07 管理端投影/合同）；合并后的页面 52/52、普通 producer 4/4、用户 typecheck 与正式 SDK 一致性均再次通过，无跳过。07 不改变本票公开增强执行/恢复链路；完整 HTTP 重启及并发矩阵证据保留在上述记录。

随后合入 06 实现 `3d2cb332` 与文档收据 `cab576a2`，组合 recovery/history/layout 页面 57/57、用户 typecheck/build 通过。上述最终 76 项按唯一测试计数，不累加复测次数；06/07 未改变公开增强执行/恢复生产代码。

## Integration receipt — 2026-10-13

- Whole codex/internal-native-05 final c2365d726e63e743076fc45490d31aaf2df77e33 merged conflict-free into codex/internal-native-enhancement at a615009ba8a2655c4c79effaa2b04362d3dd17c9. Merged source tree exactly equals verified final tree 55547794604b0098b3e8c2a2408fc82993e50ec9, including 04/06/07, so final-head evidence applies without duplicate unchanged acceptance runs.
- Reviewed services/media-service/docs/enhancement-recovery-evidence.md: 76 unique passing tests, zero failures/errors/skips (actual authenticated HTTP/MySQL 15 = recovery 2 + paid 4 + targets 9; combined recovery/history/layout pages 57; ordinary producer contracts 4), plus user typecheck/build and strict SDK consistency. Seven independent Media JVM launches prove frozen paid facts, lost debit/refund responses, no ambiguous MPS resend and explicit independent retry.
- Ticket 05 remains resolved. Tickets 06/07 remain claimed pending parent final visual batch; other ticket statuses and parent claimed state are unchanged. Four untracked QA fixtures and both Vite sessions are untouched and excluded from this tracker-only receipt commit.
