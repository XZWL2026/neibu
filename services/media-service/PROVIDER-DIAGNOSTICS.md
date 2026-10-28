# 上游生成请求诊断

V21 新增 `media_provider_http_diagnostic`，由 Flyway 启动迁移创建。生产运行账号只有 DML 权限，上线前须由数据库管理员安排此表的 CREATE/INDEX 迁移权限，迁移完成后撤回临时权限，保留原有 DML 权限；不可直接重启带新迁移的服务。记录供主管理员专用日志页和服务端运维查询，不通过用户或管理员任务 API 返回上游正文。

`ProductionProviderPort` 为提交和轮询绑定本地任务 ID。每次付费 POST 保存 `request_started`；每次响应保存 `response_received`，网络异常保存 `transport_failed`。正常 queued/running/pending/processing 的 GET 响应不重复保存，终态、HTTP 错误和非预期响应仍保存。MPS 阶段沿用相同机制。

字段包括：本地任务 ID、阶段、exchange ID、发送次数、实际 endpoint（不含用户信息及查询串）、用户选择的分辨率/时长/比例、实际请求模型/分辨率/时长/比例、参考模式和数量、实际载荷结构、提示词 Unicode 字符数/UTF-8 字节数/SHA-256、请求字节数和 SHA-256、HTTP 状态、耗时、响应正文、允许列表中的 request ID/trace ID 响应头。上游将 `message`、`param`、`type`、`request_id` 放入嵌套结构时也能保留。

提示词正文以长度和摘要替代；请求头不记录；密钥、密码和令牌字段脱敏；媒体 URL 只记录 host 与 SHA-256，签名及对象路径不保留；响应内已知凭据和完整提示词回显也脱敏。单个文本限制 16 Ki 字符，单个请求/响应结构限制 32 Ki 字符，超过限制明确标记截断。无法保证第三方错误文本中未知的个人信息完全消除，诊断库和服务日志需沿用服务器访问控制。

数据库写入成功后还会记录 `provider_http_diagnostic` 服务日志；数据库不可用时输出 `provider_http_diagnostic_persistence_failed` 和同一份脱敏证据，避免因为诊断失败改变已提交任务状态或触发再次付费。`failure_code` 及对外错误仍沿用原行为。

## 按任务查询

主管理员可在后台“生成日志”（`/admin/provider-logs`）按完整任务 ID、阶段和时间范围查询。列表按任务聚合并分页，每个任务只出现一次，显示筛选范围内的记录数和最近一条摘要。选中任务后，任务记录区展示截至本次查询时间的全部已保留记录，不受列表阶段和起始时间筛选限制；记录区独立分页，再选择单条查看详情。消费任务详情有直达入口；携带 `?taskId=<task-id>` 默认查询该任务全部已保留记录。页面只读，支持参数对照、上游响应和复制脱敏 JSON，不会发起重试。

接口为 `GET /api/v1/admin/media/provider-logs` 与 `GET /api/v1/admin/media/provider-logs/{id}`。两者都要求 IAM 已验证的 `ADMIN` 和 `PRIMARY_ADMIN`，普通管理员无权访问；成功响应 `Cache-Control: no-store`。列表默认最近 24 小时、20 条，最多 50 条/页、offset 10000；未指定任务时范围最多 31 天。详情返回持久化时已脱敏的证据，不从上游补取。

任务聚合接口 `GET /api/v1/admin/media/provider-logs/tasks` 使用相同筛选、权限和分页边界。`total` 为匹配任务数；每个 item 为 `taskId`、匹配的 `recordCount` 和最近匹配记录 `latest`。数据库先按任务聚合后分页，避免同一任务分散到多个列表页；任务内记录仍通过原列表接口按准确 taskId 查询。聚合查询使用 MySQL 8 窗口函数，无新增迁移。

在 media 数据库执行（替换为新重试任务 ID）：

```sql
SELECT phase, created_at, detail_json
FROM media_provider_http_diagnostic
WHERE operation_id = '<task-id>'
ORDER BY created_at, id;
```

JSON 的 `event` 区分发送和响应，`exchangeId` 配对一次调用。400 拒绝优先查看 `response.error.message`、`response.error.param`、`responseHeaders`；桥接供应商其他结构看完整 `response`。超时查看 `failureKind`。只有发送记录时需检查服务是否退出、日志数据库是否不可用。不要据此自行重发同一任务。

```sh
journalctl -u xzwl-media.service --since '30 minutes ago' --no-pager | grep -F '<task-id>'
```

诊断记录独立于业务任务，建议保留 14 天，导出事故证据后由既有数据库维护流程分批清理；本次不创建定时任务。例：

```sql
DELETE FROM media_provider_http_diagnostic
WHERE created_at < (UNIX_TIMESTAMP() - 14 * 86400) * 1000
LIMIT 1000;
```

已发生但未保存的历史上游错误无法补回。本次上线不重放历史任务，付费重试由用户发起。

## 验证

```powershell
mvn -pl services/media-service/media-bootstrap -am test
```

`ProductionProviderTest` 使用真实 HTTP client、本地 HTTP server 和 H2 执行完整 provider submit/poll；验证 HTTP 400、200 错误体、429/500、HTML 响应、超时、请求 ID、终态轮询及日志表不可用。`ProviderHttpDiagnosticsTest` 验证嵌套字段、签名 URL、提示词脱敏和作用域恢复。测试不会提交真实付费任务。
