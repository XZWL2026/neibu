# 07: 在管理端核对独立增强消费与处理结果

**What to build:** 管理员从既有消费及处理详情中区分视频生成和独立增强，核对原生来源、目标版本、实际收费及退款，并定位处理失败而不把它误判为原生成失败。

**Blocked by:** 03：打通首条独立付费增强与双版本交付。

**Status:** resolved

**Parent spec:** 内部项目：原生视频交付与用户按需超分增强。

**User stories:** 11、15、20、33。

- [x] 管理端明确区分原生成消费和增强消费，显示对应原生来源、增强目标、真实计费秒数和各自账务回执信息。
- [x] 成功增强的成品与正确原生输出关联，具有相应管理员权限的人可按既有授权查看处理信息和关联结果。
- [x] 明确增强失败或退款处理中仅改变增强记录的状态，原生成详情仍显示成功和原生成收费；退款完成后可核对对应增强费用已退回。
- [x] 不把独立 MPS 处理展示为又调用了一次视频生成供应商，不凭空显示不存在的上游任务或原生生成耗时。
- [x] 不同目标、重试尝试和各自费用可区分；实现面向目标与尝试集合，不要求所有目标扩展或故障交互票已经完成。
- [x] 沿用现有管理端权限和安全诊断规则，用户、跨用户或无相应管理权限的请求不能读取管理员信息或敏感供应商内容。
- [x] 调整实际受新生成与增强语义影响的既有消费展示或统计口径，避免把原生 720p、1080p 生成误标为自动增强；不新建独立运营仪表盘。
- [x] 通过管理员 HTTP 及已有管理端用户操作测试，从真实生成消费、增强成功及增强失败退款记录核对展示与账务一致。

## Implementation boundary

复用已有消费详情、媒体诊断与文件授权，不新增大范围运营功能。以 03 已建立的真实独立账务和来源关联为基础，交付管理员可使用的核对路径。

本票与 04、05、06 没有新增业务前置条件；集成时组合验证多档版本、重试和隐藏后的消费保留，不能因此人为延迟本票启动。

## Comments

- 2026-10-13：enhancement_quotes 在 codex/internal-native-07 / .scratch/ivne-07 领取；已合并 03 验收集成 ec60321b。采用已批准的管理员 HTTP（真实 MySQL 账务）及管理端页面用户操作边界，独立实现 07。
- 2026-10-13：实现提交 d7d6d5f6；通过 8f50f702 合并 04 集成 fbb205dc。管理员真实 HTTP / MySQL 验收已通过：来源真实 5.03 秒按 6 秒计费，失败 720p 仅退款自身、720p 新尝试及独立 1080p 成功，各自回执、三项关联、隐藏后账务与文件保留、正常管理员权限及无权限拒绝、其他同用户文件拒绝；原生 720p / 1080p 的真实归档不增加 MPS 请求且统计为原生生成。另有既有回归 33 项、04 目标验收 9 项、管理端页面 64 项、普通正式合同 4 项、SDK 请求 29 项全部通过；workspace typecheck/build 与严格 SDK 再生成检查通过，无条件验收跳过。详细证据及命令见 services/media-service/docs/enhancement-consumption-audit-evidence.md。等待父任务合并与视觉核对后在集成分支关闭本票；本分支保持 claimed，不提前宣称已集成。

## Merged receipt — 2026-10-13

- Whole codex/internal-native-07 branch at 31dde060 merged without conflicts into codex/internal-native-enhancement at 7769d1cedc839b76ee6ed41208931a3645492610. Merged tree exactly equals the verified final branch tree aa3ed3501fd77023e7e2266660e43972816794d5; unchanged final-head verification is reused.
- Reviewed services/media-service/docs/enhancement-consumption-audit-evidence.md: administrator authenticated HTTP/MySQL 1/1, existing backend 33/33, multi-target HTTP/MySQL 9/9, Vue operations 64/64, ordinary producer contracts 4/4, API request tests 29/29; workspace typecheck/build and strict generated SDK verification pass, with zero conditional acceptance skips.
- Status remains claimed pending the parent desktop/mobile visual batch. No final visual acceptance is asserted here. Four untracked user/admin QA fixtures and Vite sessions remain untouched and uncommitted; other ticket statuses and parent claimed state are preserved.

## Preserved coordination comments

- 2026-10-13：实现与验收已在独立 worktree 完成，详细证据见 .scratch/ivne-07/services/media-service/docs/enhancement-consumption-audit-evidence.md。等待父任务合并与视觉核对后在集成分支关闭，不提前标记 resolved。

## Final browser acceptance — 2026-10-13

- Parent completed actual browser desktop (1280px) and mobile (390px) user/admin capture batches with production CSS and wrapper loaded. All four surfaces report errors=[] and scrollWidth equal to viewport; quote shows six billable seconds and fee before explicit confirmation, chosen 1080p file remains selected in preview, and native/each enhanced download controls are accessible.
- Administrator origin, actual charge receipt, independent MPS-only stage and refund/attempt details were inspected. The first QA harness omitted production CSS; its test-only imports/wrapper were corrected and the rerun found no production UI defect. No production styling change was introduced for that harness issue.
- Evidence outside the repo: C:/Users/fys/.codex/visualizations/2026/10/13/01a132d6-52cf-7fd1-97cd-10f77d14cd12/enhancement-ui-evidence.json; enhancement-{desktop,mobile}-{versions,quote,preview}.png and enhancement-admin-{desktop,mobile}.png in the same directory. Parent removed the exact four QA fixture files and stopped both Vite sessions.
- This ticket is resolved after functional and visual acceptance. Parent spec remains claimed while the separate final-review price and README findings are being repaired; other ticket states are unchanged.
