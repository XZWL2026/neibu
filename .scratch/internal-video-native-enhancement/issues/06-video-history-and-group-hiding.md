# 06: 在历史视图管理增强版本并整组隐藏记录

**What to build:** 用户从创作页和历史视图找到同一原生视频及其增强任务，在保留现有浏览方式的前提下使用所有版本；增强终态后可整组隐藏记录，处理中始终保留可见入口。

**Blocked by:** 03：打通首条独立付费增强与双版本交付。

**Status:** resolved

**Parent spec:** 内部项目：原生视频交付与用户按需超分增强。

**User stories:** 6–7、17–18、20–21、29–30。

- [x] 创作页和共用历史视图中的同一视频记录包含原生版及所有关联增强任务和版本；增强不产生额外顶层历史卡片。
- [x] 保留单列、四宫格、九宫格和已有历史加载行为，用户在相应详情或预览中可增强所选原生输出、切换版本及下载。
- [x] 列表分页、增量刷新和历史加载不会因增强任务而重复原记录、漏掉其他原生视频或出现孤立增强卡片。
- [x] 本人既有成功原生视频重新从历史打开后仍能发起增强并取得关联结果，不要求原提交页面仍在内存中。
- [x] 任一关联增强任务仍在处理中时，页面说明不能隐藏的原因，服务端拒绝隐藏；此规则在页面数据陈旧时仍有效。
- [x] 并发增强提交与隐藏请求保持该约束：不能同时受理新付费增强并将其原记录隐藏；不能只靠前端按钮禁用防止竞争。
- [x] 所有关联增强均已终态时，隐藏整组原生及增强记录，历史分页和查询不显示残留顶层版本；原生文件、增强文件及账务记录不实际删除。
- [x] 跨用户不能操作历史或访问结果；隐藏行为保持幂等，既有用户文件访问权限保持有效。
- [x] 通过 HTTP 验证历史分页、归属、整组隐藏及并发竞争，页面操作验证不同布局、从历史进入、版本切换和隐藏提示。

## Implementation boundary

扩展 03 已建立的原记录版本关联和处理中禁止隐藏规则，交付历史分页及整组生命周期体验。该逻辑面向关联版本集合，可用首条 720p 增强独立验证，因此不以 04 或 05 为阻塞项。

不增加单个增强版隐藏或实际文件删除能力；不把现有“重新生成”的隐藏语义用于增强。
## Implementation evidence — 2026-10-13

- Feature commit b960ab11 on codex/internal-native-06. Full/adaptive card previews keep one persistent detail subtree, selected versions and independent downloads. Stale hide refusal is an alert inside the active modal/card, cleared on owner changes; processing/refund-pending records explain the hide guard.
- Actual authenticated public HTTP/MySQL/Billing history acceptance passed 1/1 with zero skips: parent grouping before cursor pagination, funded-other-owner isolation, idempotent terminal group hide retaining original/enhanced MP4 bytes and ledger, refund-pending guard, and concurrent hide/admission mutual exclusion. Existing 03 backend rules satisfied the expanded acceptance without backend business changes.
- Final scoped page operations passed 48/48 including existing card/gallery/accessibility checks; user typecheck/build passed. Existing broader composer timing failures and the owner-picker failure reproduced before 06 are recorded precisely in services/media-service/docs/enhancement-history-evidence.md; the 198-test broader run is not represented as all green.
- Latest integration tip 7769d1ce merged before handoff (including ticket 07; no conflicts in ticket 06 files). Status remains claimed until merger records integration acceptance; no original user source edits changed.

## Merged receipt — 2026-10-13

- Whole codex/internal-native-06 branch at 13927ed99a8f4c0ebe6d29b0f3fcc40d7fa7a9ba merged without conflicts into codex/internal-native-enhancement at 3d2cb3326a5ea33795f487f4321bb9ecab8ca877. Compared with verified final branch, only the already accepted ticket 07 tracker receipt differs; feature code and contracts are identical. No duplicate unchanged acceptance run was needed.
- Reviewed services/media-service/docs/enhancement-history-evidence.md: actual authenticated HTTP/MySQL/Billing history lifecycle 1/1 with zero skips, scoped page operations 48/48 including accessibility/all layouts/selected version and modal hide-alert behavior, user typecheck/build and post-07 SDK typecheck pass.
- Broader composer regression was 195/198, not all green. Unchanged selected timing/material confirmations passed 6/6; persistent owner-picker failure reproduced independently on completed ticket 04 baseline. These limitations remain recorded and are not counted as successful feature tests.
- Functional acceptance checklist is satisfied; status remains claimed pending the parent final browser visual batch, consistent with ticket 07. Four untracked QA files and both Vite sessions are untouched and excluded from the receipt commit; other tickets and parent status are preserved.

## Final browser acceptance — 2026-10-13

- Parent completed actual browser desktop (1280px) and mobile (390px) user/admin capture batches with production CSS and wrapper loaded. All four surfaces report errors=[] and scrollWidth equal to viewport; quote shows six billable seconds and fee before explicit confirmation, chosen 1080p file remains selected in preview, and native/each enhanced download controls are accessible.
- Administrator origin, actual charge receipt, independent MPS-only stage and refund/attempt details were inspected. The first QA harness omitted production CSS; its test-only imports/wrapper were corrected and the rerun found no production UI defect. No production styling change was introduced for that harness issue.
- Evidence outside the repo: C:/Users/fys/.codex/visualizations/2026/10/13/01a132d6-52cf-7fd1-97cd-10f77d14cd12/enhancement-ui-evidence.json; enhancement-{desktop,mobile}-{versions,quote,preview}.png and enhancement-admin-{desktop,mobile}.png in the same directory. Parent removed the exact four QA fixture files and stopped both Vite sessions.
- This ticket is resolved after functional and visual acceptance. Parent spec remains claimed while the separate final-review price and README findings are being repaired; other ticket states are unchanged.
