# ADR-0002：PostgreSQL 账户上下文与 RLS

- 状态：Accepted
- 日期：2026-08-28

## 背景

ThreadMind 的核心业务表位于 Supabase PostgreSQL 的私有 `threadmind` schema，Android 只通过 Fastify API 访问。应用既需要 Kysely 的类型安全查询，也需要数据库在服务端查询遗漏过滤条件时继续阻止跨账户读写。连接池会复用数据库连接，因此账户上下文不能永久写入 session。

## 决策

### 角色与权限

- `threadmind_runtime` 是部署密钥管理器持有密码的登录角色，无 `BYPASSRLS`，且不直接获得表权限。
- `threadmind_api` 是 `NOLOGIN NOINHERIT` 业务角色，只获得私有 schema 的 `USAGE`、账户上下文函数的 `EXECUTE`，以及当前 API 所需表的 `SELECT/INSERT/UPDATE`。
- `anon`、`authenticated` 和 `public` 不获得核心 schema/table 权限；Android publishable key 和 Supabase `service_role` 都不用于普通业务查询。

### 事务账户上下文

每次 repository 调用都开启短事务，并依次：

1. `SET LOCAL ROLE threadmind_api`；
2. `set_config('app.current_account_id', jwt_sub, true)`；
3. 执行同一账户的全部查询并提交。

`jwt_sub` 只来自 Fastify 已验证的 Supabase Access Token，不接受请求 body、query 或账户头提供的身份。`true` 让设置只在当前事务有效，连接归还池时不会泄漏账户上下文。

### RLS 与数据建模

- 所有核心表启用并强制 RLS，分别定义 `SELECT`、`INSERT` 和 `UPDATE` policy。
- policy 将行的 `account_id` 与稳定的 `threadmind.current_account_id()` 比较。
- 所有业务表显式保存 `account_id` 并外键关联 `auth.users(id)`；跨表/版本关系使用包含 `account_id` 的复合外键，阻止跨账户引用。
- 活动记忆读取使用账户前缀的部分复合索引；外键列具备相应索引。
- Memory 修订锁定活动旧版本、将其标记为 `superseded`，再插入指向旧版本的新 `active` 行；删除是状态变更，不做在线硬删除。

### 连接失败与幂等性

池化连接可能在 PostgreSQL 已提交后、API 收到提交响应前断开。Memory 写入因此以稳定记录 ID 和版本关系提供幂等恢复：重复创建返回同 ID 记录；重复修订同一旧版本和内容返回已存在的新版本；重复删除已删除版本仍返回成功。只对明确的瞬时连接错误自动重试一次。

客户端为每条查询（包括 BEGIN/COMMIT/ROLLBACK）设置 `DATABASE_QUERY_TIMEOUT_MS` 响应截止时间，API 与 Worker 共用，默认 35000 ms，可配置范围 1000–120000 ms。它与数据库端 `statement_timeout` 分工不同：后者限制服务器执行，前者防止网络响应丢失导致无限等待。客户端超时立即以 `release(true)` 淘汰连接并关闭活动查询的 socket；事务回滚不能再排队到该失效连接，下一次任务使用新连接。普通 SQL 错误仍正常回滚并复用连接。连接池适配器本身不重放任何操作，尤其不盲目重试响应不确定的 COMMIT；既有 repository 的幂等恢复规则保持不变。

## 后果

- 即使 Kysely 查询遗漏显式 `account_id` 条件，强制 RLS 仍阻止跨账户访问。
- repository 操作不能跨账户或长时间持有事务；后台批处理必须按账户分批执行。
- 数据库管理员和 migration 通道仍可能绕过普通运行角色，因此 migration、advisors 和真实 RLS 集成测试是发布前检查项。
- 持久服务优先使用 Supabase session pooler；短生命周期环境可使用 transaction pooler，但不得启用 transaction mode 不支持的命名 prepared statements。

## 验证

- `npm run test:integration` 使用真实 Auth 用户 UUID，验证创建、账户内读取、随机其他账户不可见、修订、跨账户删除失败、账户内删除和幂等恢复。
- `server/test/database-deadline.test.ts` 使用本地 PostgreSQL 协议 TCP 测试端，覆盖 BEGIN、查询和 COMMIT 无响应、连接淘汰后重新连接、普通 SQL 错误回滚及旧连接句柄失效。
- Android 真实 E2E 验证持久 Session 经 Fastify 访问远端 PostgreSQL，并在 Memory Center 中完成读取、修订和删除。
- 每次 schema 变更后运行 Supabase security/performance advisors，并清理带 `integration:` 或 `e2e:` 标识的测试记录。

## 参考

- [Supabase Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security)
- [Supabase database connection management](https://supabase.com/docs/guides/database/connecting-to-postgres)
- [Supabase transaction pooler prepared statements](https://supabase.com/docs/guides/database/connecting-to-postgres#supavisor-transaction-mode)
