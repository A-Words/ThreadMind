# ADR-0003：后台任务队列与临时截图存储

- 状态：Accepted
- 日期：2026-08-31

## 背景

ThreadMind 必须把截图分析放到可恢复的后台任务中，并在分析后证明原始截图已经从云端删除。API、Worker 和 Supabase Storage 都会遇到“操作已提交但调用方未收到响应”的不确定结果；同时 Worker 需要跨账户发现待处理任务，但不能因此获得跨账户读取业务正文的能力。

## 决策

### 上传与临时对象

- Android 通过 Fastify multipart API 上传单张图片；MVP 不向客户端下发 Storage secret 或直接上传签名。
- API 只接受 PNG、JPEG 和 WebP，单文件上限 15 MiB，并在上传前后同时校验声明大小和实际字节数。
- API 计算 SHA-256 内容指纹，使用 `accountId/submissionId` 派生不可猜测的对象路径，通过服务端 Supabase secret key 写入私有 `threadmind-submissions` bucket；secret key 不进入 Android、日志或数据库。
- 数据库只保存短期 `image_object_path`、MIME、大小和不可逆指纹，不保存原图字节，也不把对象路径复制进 MemoryRecord 或 Insight。
- 相同 `submissionId` 的重试返回既有提交；若请求元数据或指纹不同则返回冲突，不覆盖原对象。

### 处理完成与删除证明

- `uploaded` 表示元数据、对象与分析任务均已持久化；`processing` 表示 Worker 已开始分析或正在清理原图。
- 成功路径只有在提取结果和候选对象提交成功、且 Storage 删除确认成功后才进入 `ready`。
- 失败路径也必须先删除原图；删除确认后才进入 `failed`。若删除暂时失败，提交保持 `processing`，并创建幂等清理任务。
- Storage 的“对象不存在”视为删除成功。定时清理器扫描超期临时对象和卡住的任务；不得把原图写入失败日志或死信 payload。
- 用户删除提交时立即使其不可查询，并异步删除对象、提取、卡片、仅以该提交为来源的记忆和洞察；完整来源重写规则由删除里程碑实现并单独验证。

### PostgreSQL-backed queue

- `threadmind.background_jobs` 只保存 `account_id`、任务类型、聚合 ID、幂等键、调度/锁定信息和脱敏错误码；payload 不含截图、转录、补充文字、联系人明文或 Memory 正文。
- API 使用 `threadmind_api` 角色，只能为当前账户插入和查看任务。
- 独立 `threadmind_worker` 角色只能跨账户 `SELECT/UPDATE` 队列表，不能读取 Submission、Extraction、Action、Memory 或 Insight 表。
- Worker 用 `FOR UPDATE SKIP LOCKED` 原子领取任务。拿到 `account_id` 后，业务处理在新的短事务中切换到 `threadmind_api` 并设置 transaction-local `app.current_account_id`；队列角色不能代替账户上下文。
- `threadmind_worker_runtime` 是 `NOINHERIT` 登录角色，只被授予 `threadmind_worker` 和 `threadmind_api`；密码由部署密钥管理器设置，不进入 migration 或 Git。
- 相同账户、任务类型和幂等键只能存在一条任务。运行锁超时后可重新排队；超过最大尝试次数进入 `dead`，等待人工或定时恢复，不自动把业务对象标为成功。

### 模型边界

- Worker 依赖可替换的 `VisionExtractionModel`，模型输出必须经过 Zod/领域校验后才能写入提取、卡片或记忆。
- 队列、存储和领域状态不依赖具体模型供应商。生产模型必须通过脱敏/合成评测后另行配置；测试使用确定性 adapter。

## 后果

- API 承担一次图片中转，但统一了大小、类型、指纹、账户路径和 secret 管理；若未来上传规模需要直传，必须保持相同的完成与删除语义。
- Worker 可以公平领取所有账户的任务，但跨账户权限被限制在无正文队列表；业务数据仍由强制 RLS 隔离。
- `ready`/`failed` 比模型调用结束更晚，因为原图删除是终态的一部分。客户端需要把较长的 `processing` 解释为可恢复清理，而不是重复上传。

## 验证

- 数据库集成测试覆盖任务领取互斥、跨账户业务表不可见、幂等入队、锁超时重领和最大尝试次数。
- Storage 集成测试覆盖私有 bucket 上传、读取、删除、重复删除和超限/错误 MIME 拒绝，并清理全部 `integration:` 对象。
- 端到端测试证明提交到达 `ready` 或 `failed` 时原始对象已不存在，且日志和任务 payload 不包含原始内容。

## 参考

- [Supabase Storage](https://supabase.com/docs/guides/storage)
- [Supabase Storage bucket restrictions](https://supabase.com/docs/guides/storage/buckets/creating-buckets)
- [PostgreSQL `SKIP LOCKED`](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)
- [ADR-0002：PostgreSQL 账户上下文与 RLS](0002-postgresql-rls-context.md)
