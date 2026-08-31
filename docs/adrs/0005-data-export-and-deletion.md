# ADR-0005：数据导出与删除编排

- 状态：Accepted
- 日期：2026-08-31
- 决策范围：单次提交删除、记忆清除、账户导出与账户删除

## 背景

PRD 要求用户能够导出数据、删除一次提交、清除全部记忆和删除账户。删除必须覆盖原始内容、结构化记录、洞察历史和派生检索数据；Android Provider 中已经由用户确认写入的联系人或日历记录不应被后台静默删除。

ThreadMind 的主数据位于私有 PostgreSQL schema，临时截图位于私有 Supabase Storage bucket，身份和会话由 Supabase Auth 管理。一个只删除应用表或只删除 `auth.users` 的实现都不能单独满足这些边界。

## 决策

### 1. 导出格式固定为版本化 JSON

`GET /v1/account/export` 返回 `threadmind-export-v1` JSON 文档，包含生成时间、账户 ID、Submission 元数据、ContextExtraction、Action Card、Action Receipt、Memory 全版本和 InsightHistory。

导出不包含已删除的原始截图、Storage secret/path、JWT、refresh token、数据库凭据或模型内部推理。所有数据在同一账户限定事务中读取；其他账户记录即使被错误传入 ID 也不能进入导出。

### 2. 单次提交删除先删临时对象，再删主记录

`DELETE /v1/submissions/:id` 的顺序为：

1. 在账户范围内读取 Submission 并停止/删除相关后台任务。
2. 幂等删除对应 Storage 对象；对象已经不存在视为成功。
3. 清理 Memory 中属于该 Submission 的来源。只剩该来源的记录被不可逆清空正文并标记 `deleted`；仍有其他权威来源的记录移除该来源后保留。
4. 删除 Submission 主记录，由外键级联删除 Extraction、Action Card、Action Receipt 和 InsightHistory。

接口使用幂等删除语义；重复请求返回成功，避免网络结果不确定导致用户无法确认删除状态。

### 3. 用户删除的 Memory 只保留不可个性化的最小墓碑

单条删除与全量清除会清空 assertion、subject、来源摘录、置信度和敏感分类，只保留随机记录 ID、版本关系、删除状态及时间戳。墓碑不得进入默认召回、搜索、洞察生成或导出正文。

保留墓碑是为了维持修订链引用和幂等删除；它不能包含可重建个性化内容。若未来派生索引存在，主记录先同步进入删除状态，索引清理由可重试任务异步完成。

### 4. 账户删除由服务端编排并硬删除 Auth 用户

`DELETE /v1/account` 只接受当前已验证账户，执行：

1. 删除该账户 Storage 前缀下的所有临时对象。
2. 使用仅服务端可见的 Supabase secret key 调用 Auth Admin API 硬删除当前 `auth.users` 记录。
3. 依靠 `ON DELETE CASCADE` 删除 ThreadMind 应用表和 Auth sessions；重复删除或结果不确定时按幂等状态核对。

删除 Auth 用户会撤销 refresh token，但已签发 access token 在到期前仍可能通过纯 JWT 签名验证。ThreadMind 的业务表均引用 `auth.users`，因此旧 token 不能重建账户数据；生产环境同时保持短时 JWT。若未来加入不引用 `auth.users` 的敏感资源，必须在访问前验证 JWT `session_id` 仍存在。

Android 收到成功后清除该账户的 Room 数据与待执行 WorkManager 作业，再清除本地 Supabase session。联系人和日历中已经成功创建的记录不随账户删除自动移除。

## 后果

- 导出格式可版本化演进，且不依赖 Supabase Data API 暴露私有 schema。
- Submission 删除需要协调 Storage 与 PostgreSQL，必须保持幂等并允许在中间失败后安全重试。
- Memory 墓碑比保留软删除正文更符合“删除后不可继续个性化”，但历史 UI 不再能显示已删除正文。
- Auth Admin secret 继续仅存在于服务端；Android APK 与客户端日志不得接触它。

## 验证要求

- API 和 PostgreSQL integration test 证明提交删除级联、来源清理、全量记忆清除、导出账户隔离与重复删除。
- Storage fake 证明数据库删除前会尝试删除对象，且对象缺失可以安全重试。
- Auth Admin fake 证明只删除当前账户、失败不会被误报成功、重复删除可恢复。
- Android 测试证明危险操作有明确确认，成功后清理 Room/WorkManager/session，且不会删除设备 Provider 中已创建的记录。
