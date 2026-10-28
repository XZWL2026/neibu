# 04: 扩展增强目标、适用画幅与多档版本

**What to build:** 用户依据真实原生规格和后台模板，选择可用的 720p、1080p、4K 增强目标；同一原生视频可生成并使用不同档位的增强版。

**Blocked by:** 03：打通首条独立付费增强与双版本交付。

**Status:** resolved

**Parent spec:** 内部项目：原生视频交付与用户按需超分增强。

**User stories:** 8–9、17–19、24、32。

- [x] 扩展首条执行路径到配置实际支持的其他原生输入和更高目标，不再使用固定低一档来源规则；不承诺任意来源都能增强至所有目标。
- [x] 720p、1080p、4K 的可用执行目标与 02 的模板匹配、报价保持一致；去掉 03 的临时执行范围限制，缺匹配或非更高档位仍被拒绝且不扣费。
- [x] 覆盖现有模板实际支持的横屏、竖屏、方形、标准和超宽画幅；按正确的配置和真实源视频规格选择编排及验证输出，不用目标名称猜测固定像素尺寸。
- [x] 单模板和已启用串联编排按既有校验规则执行，只有完整成功、规格符合要求的最终输出归档为用户增强版；中间结果不冒充成品。
- [x] 同一原生输出可拥有不同目标档位的成功增强版，各版费用、状态和文件明确区分，并在同一记录中切换、分别下载。
- [x] 每个增强版均源自原生视频，不能把已有增强版作为再次增强输入；相同来源相同目标的成功结果保持复用，不重复收费。
- [x] 原生 4K 或其他不存在更高可用目标的视频显示不可增强；不因报价档位存在就默认模板或处理能力可用。
- [x] 公开 HTTP 与页面操作验证至少一条 1080p、一条 4K、一个非首票画幅组合及同源多档版本，并覆盖输出规格不匹配时仅退款对应增强任务。

## Implementation boundary

完整扩展已存在的付费增强路径，而不是仅补枚举或前端选项。使用可配置 HTTP fixture 验证执行与结果关联，实际云模板开通状态不由测试虚构为产品默认能力。

不依赖 05 的故障交互扩展、06 的历史操作扩展或 07 的管理员消费展示，保持这些任务之间没有人为阻塞。

## Implementation evidence

- 2026-10-13: Implemented on codex/internal-native-04 after merging paid enhancement integration 65c5842e. Removed all four temporary target/source/ratio execution and recovery restrictions; actual template/source checks remain authoritative.
- All nine unique authenticated HTTP/MySQL scenarios passed with no skips after a test quote-selection correction; source/targets, all six aspect ratios, complete schedules, 1080p/4K multiple versions, success reuse, enhancement-source rejection, native 4K unavailable, and schedule final archive mismatch refund isolation are covered. See services/media-service/docs/enhancement-targets-evidence.md for exact runs and fixtures.
- Page target confirmation, same-identity remount recovery and three-version preview/download tests passed 3/3; related page regression 37/37, user typecheck and build passed. Merger integration acceptance completed; status resolved.

## Integration receipt — 2026-10-13

- Whole codex/internal-native-04 at e664872d merged without conflicts into codex/internal-native-enhancement at f111583e96037e7ed5b8f241a7d4681dbc64ebc0. Merged tree equals verified final implementer tree 29fd03292eebb2231704af5a155bf35216444e21, so unchanged feature evidence is reused without repeating the full matrix.
- All nine unique authenticated HTTP/MySQL scenarios have passing evidence, with zero skips: initial matrix 8/9 followed by corrected target-based selection and scoped confirmations 2/2 plus 1/1. This is not represented as a single final full-matrix 9/9 run. Page regression 37/37, user typecheck and build passed. Exact coverage and commands: services/media-service/docs/enhancement-targets-evidence.md.
- Ticket 04 resolved with all acceptance checked. Other ticket statuses and parent claimed state are preserved. Four untracked user/admin QA fixtures and both live Vite sessions are unchanged and excluded from this tracker-only commit.
