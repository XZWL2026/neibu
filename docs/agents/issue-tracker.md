# Issue tracker：本地 Markdown

## 文件约定

- 每个功能一个目录：`.scratch/<feature-slug>/`。
- 规格：`spec.md`。
- 任务：`issues/<NN>-<slug>.md`，从 01 开始按依赖顺序编号，每个任务一个文件。
- 每个任务记录 What to build、Blocked by、Status 和验收条件。
- Blocked by 引用同一功能下的任务编号与标题；没有依赖时写 None。
- 讨论和补充信息追加到文件底部的 `## Comments`。

## 状态与执行

- 已确认的规格和任务使用 `Status: ready-for-agent`。
- 开始执行任务前设置 `Status: claimed`。
- 仅领取所有依赖任务均为 resolved 的 ready-for-agent 任务。
- 完成实现、通过验收并集成后，记录验证结果及相关提交，设置
  `Status: resolved`，勾选已满足的验收项。
- 验证受阻或验收未通过时，不标记 resolved；记录原因和待办。
- 子任务完成不自动关闭规格；全部任务完成且规格整体通过验收后，
  再将规格标记为 resolved。

## 技能操作的含义

- “发布到 issue tracker”：写入上述本地文件。
- “获取 ticket”：读取对应文件及 Comments。
- “关闭/解决 ticket”：记录完成证据并设置 resolved。
- “添加 ready-for-agent 标签”：在本地使用同名 Status 值。
- 任务号仅在对应功能目录内唯一；引用有歧义时使用完整路径。
