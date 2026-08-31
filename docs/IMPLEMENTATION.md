# ThreadMind 实现说明

当前实现已覆盖 MVP 的主要本地与服务端交互面，并把授权、证据、账户隔离、可恢复执行与删除语义编码。独立 Worker 进程和可配置的多模态模型 adapter 已接通；但尚未用脱敏/合成标注集选定并验收生产模型，因此不能把任意配置的模型描述成已验证的生产 AI 链路。

## 已实现

- `server/`：Node.js 24 + TypeScript + Fastify 模块化服务端。
- Action Card：字段/evidence 校验、阻塞状态、编辑增版、逐版本确认、不可变确认快照、独立执行回执与账户隔离。
- Memory：来源强制、事实/推断标记、用户修正版本化、可读来源摘录、关键词/联系人/类型/时间检索、删除后正文抹除与默认召回过滤。
- Insight：正式洞察必须存在同账户成功回执，每个 item 都有依据；按稳定生成键幂等持久化并提供账户隔离的历史查询。当前默认 generator 是保守的证据规则实现，不是生产模型。
- Auth：生产入口通过 Supabase JWKS 验证 Bearer Token；不提供生产可用的账户头旁路。
- PostgreSQL：Supabase 托管实例、显式 SQL migration、强制 RLS、最小权限运行角色，以及 Kysely Submission、Action、Memory、Insight 与账户导出 repositories。
- Action API：卡片创建使用客户端稳定 UUID，编辑使用期望版本阻止重复应用，确认可安全重试，执行回执使用稳定 UUID 在提交结果不确定时返回原记录。
- Screenshot Submission API：接收单张 PNG/JPEG/WebP multipart 图片，限制 15 MiB，校验文件签名并计算 SHA-256；相同提交 UUID 和内容可安全重试，API 响应不暴露 Storage 路径或指纹。
- 临时图片与任务：服务端使用 Supabase secret key 写入私有 `threadmind-submissions` bucket；Submission 元数据和 `analyze_submission` 任务在同一账户事务落库，队列 payload 不保存截图或正文。
- Worker 处理闭环：后台任务使用带所有者校验的租约领取、续租、指数退避和最大尝试次数；模型输出经结构校验后，Extraction、Action Cards 与 Memory 在账户事务中幂等落库。原图删除成功后 Submission 才进入 `ready`，终态失败也先删图；删图失败会转为独立清理任务。租约被其他 Worker 接管后，旧 Worker 不再落库、删图或结束任务。
- 独立 Worker 进程：`npm run dev:worker` / `npm run start:worker` 使用专属 `threadmind_worker_runtime` 数据库连接，支持空队列轮询、异常退避、SIGINT/SIGTERM 中断和连接池关闭；Dockerfile 提供彼此独立的 `api` 与 `worker` targets。
- 多模态模型 adapter：`VisionExtractionModel` 的 OpenAI Responses 实现通过环境变量选择模型，不在代码中固定“生产模型”；请求发送截图 data URL 和补充文字，设置 `store: false`，使用 strict JSON Schema 输出。模型只返回业务载荷，服务端注入可信 `modelTrace`，随后仍经过 Evidence、Action 与 Memory 领域校验。
- `android/`：Kotlin + Compose + Material 3 + StateFlow + Hilt 工程，支持图片选择、Android 分享入口、卡片确认界面和按动作请求 Provider 权限。
- Android Provider executor：只接受 `ConfirmedActionSnapshot`，支持 Calendar/Contacts 写入，并在重试前通过稳定 marker 检查既有记录；确认前查询会议冲突和重复联系人，更新联系人时展示字段级旧值/新值并在写入前重新校验旧值。
- Android Auth：通过 supabase-kt 提供邮箱密码与六位 OTP 两种登录/注册方式；密码注册确认、找回密码和账户内设置密码都在 App 内验证邮件六位码，注册前强制确认隐私与数据处理说明，会话由 SDK 持久化和刷新。
- Android API client：Retrofit/OkHttp/kotlinx.serialization 客户端从当前 Supabase Session 注入 Bearer Token，不接受账户 ID 头旁路。
- Android Submission workflow：业务 repository 将待上传截图先复制到设备私有且不备份的目录，并使用按账户隔离的 Room 数据库保存 Submission、Action Card 缓存与待同步执行回执；WorkManager 在网络恢复后继续上传、轮询分析和同步回执。
- Android 恢复与幂等保护：应用重启后恢复最近提交、审核卡片和未同步回执；Provider 已完成但回执未同步时禁止再次执行，执行终态与回执在本地一并持久化。
- Android Memory Center：读取活动记忆，展示事实/推断、置信度、版本、敏感级别与来源；支持保留历史的修订和确认后删除。
- Android Insight History：展示执行后洞察、事实/推断、置信度、来源摘录与建议，不把洞察历史反向写入 Memory。
- 数据控制：服务端提供版本化账户 JSON 导出、幂等单次提交删除、全量记忆清除和 Auth Admin 账户硬删除；Android 通过系统文档选择器导出，并在确认后同步清除 Room、WorkManager 与本地会话。已由用户确认写入系统 Provider 的记录不被后台删除。
- Gradle Wrapper、服务端 Dockerfile、Node 和 Android 领域/API 自动化测试。

## 尚未接入

- LangGraph 提取编排、脱敏/合成模型评测集，以及基于评测结果选定的生产模型配置。当前 adapter 与严格输出契约已接通，但没有真实模型凭证与回归集的验证证据。
- Android Provider 目标账户仍以可见、可编辑 ID 输入为主，尚未实现从设备实际日历/联系人账户枚举出的选择器；会议/联系人数据也可能在预检和最终写入之间发生变化，联系人更新已做写入时旧值复核，会议和创建联系人仍需更强的竞态保护。
- 真实设备上的联系人/日历写入、权限撤销、账户删除、导出文件回读和中国大陆网络验证。
- 新增的 Memory 来源证据与 Insight 生成键迁移尚未应用到 Hosted 项目，远端集成测试会在迁移前因缺列失败；应用 migration 属于外部数据库变更，需要明确部署授权。

这些边界不应被当前的通过测试误写成已交付能力。

## 本地验证

服务端：

```powershell
npm install
npm run typecheck
npm test
npm run test:integration
npm run build
npm run dev          # API
npm run dev:worker   # independent Worker
```

Android：

```powershell
cd android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Android 本地构建在已被 Git 忽略的 `android/local.properties` 配置以下项目级值（可与已有的 `sdk.dir` 并存）：

```properties
THREADMIND_SUPABASE_URL=https://YOUR_PROJECT.supabase.co
THREADMIND_SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
THREADMIND_API_BASE_URL=https://api.example.com
```

CI 或临时构建可通过 Gradle `-P` 参数或 `ORG_GRADLE_PROJECT_*` 环境变量覆盖这些值。

只允许在 Android 客户端配置 publishable key，禁止写入 Supabase secret 或 `service_role`。若未配置，应用仍可构建并显示配置提示，但无法发起登录或业务 API 请求。

Supabase Hosted 项目的 Auth 配置必须满足：

- 启用 Email Provider、Allow new users to sign up 与 Confirm email。
- Email OTP Length 设为 6；Confirm signup、Magic Link/OTP 和 Reset password 模板都包含 `{{ .Token }}`，使注册确认、passwordless 登录和密码恢复无需浏览器或 Deep Link 即可在 App 内验证。
- 配置自定义 SMTP，并用中国大陆常见邮箱验证真实送达率；不得将 Supabase 默认邮件服务作为生产通道。
- Minimum password length 至少为 8。客户端只提前检查八位长度和两次输入一致，字符组合、泄露密码拦截和最终强度判断以 Supabase Auth 为准。

服务端启动前复制 `.env.example` 为仓库根目录下的 `.env`，配置真实 Supabase Auth 项目、`DATABASE_URL` 和 `THREADMIND_E2E_ACCOUNT_ID`。`DATABASE_URL` 使用 `threadmind_runtime` 登录角色，不使用 `postgres`、`service_role` 或 Android publishable key。Worker 另用 `THREADMIND_WORKER_DATABASE_URL`，登录角色必须为 `threadmind_worker_runtime`，不能复用 API 登录连接。持久进程优先使用 session pooler（5432）；短生命周期环境可使用 transaction pooler（6543），当前 `pg`/Kysely 查询不启用命名 prepared statements。

`DATABASE_SSL_REJECT_UNAUTHORIZED` 默认必须为 `true`。只有本地被受信调试代理替换证书且已核对代理边界时才可在忽略的 `.env` 中临时设为 `false`，不得复制到部署环境。`npm run dev` 和 `npm start` 会自动读取根目录 `.env`；部署环境仍可直接注入环境变量。`x-account-id` 只在测试构造的显式不安全模式中启用，普通启动不会接受它。

数据库迁移顺序为：

1. `server/migrations/0001_initial.sql` 创建私有 schema、约束、索引、GRANT、强制 RLS 与账户策略。
2. `server/migrations/0002_create_runtime_role.sql` 创建无 `BYPASSRLS` 的登录角色并授予受限业务角色；密码由部署密钥管理器单独设置，不进入 migration 或 Git。
3. `server/migrations/20260830174205_add_submissions_and_jobs.sql` 创建 Submission、Extraction、后台任务、Worker 角色/RLS、Submission 外键和私有 Storage bucket。
4. `server/migrations/20260830181534_add_action_card_review_metadata.sql` 为 Action Card 增加字段级置信度与待处理校验问题，供低置信和歧义审核使用。
5. `server/migrations/20260831070229_add_memory_source_evidence.sql` 为 Memory 增加可展示、可检索且随删除抹除的来源证据。
6. `server/migrations/20260831073538_add_insight_generation_key.sql` 为 Insight 增加幂等生成键、Submission 级联外键与历史索引。
7. `server/migrations/20260831130616_verify_active_session.sql` 暴露最小权限的 session 存活检查；账户导出和账户删除除 JWT 验签外还必须验证 `session_id` 仍属于当前账户。

截图上传和账户硬删除还要求服务端环境配置 `SUPABASE_URL`、仅服务端可见的 `SUPABASE_SECRET_KEY`，以及可选的 `SUPABASE_STORAGE_BUCKET`。Android 仍只配置 publishable key；secret key 不得写入 `local.properties`、APK 或客户端日志。

Worker 还要求仅服务端可见的 `OPENAI_API_KEY` 和 `THREADMIND_VISION_MODEL`。模型名必须来自当前部署的评测结论，仓库不提供默认生产模型。可选的 `OPENAI_BASE_URL`、`THREADMIND_MODEL_TIMEOUT_MS`、`THREADMIND_MODEL_MAX_OUTPUT_TOKENS`、Worker 轮询与退避参数均列在 `.env.example`。Responses adapter 的接口形状以 [OpenAI Responses API](https://developers.openai.com/api/reference/typescript/resources/beta/subresources/responses/methods/create) 为准；单元测试使用注入的本地 `fetch`，不会发送真实截图或调用外部模型。

容器可以从同一 Dockerfile 分别构建：

```powershell
docker build -f server/Dockerfile --target api -t threadmind-api .
docker build -f server/Dockerfile --target worker -t threadmind-worker .
```

每个 repository 操作都在短事务内执行 `SET LOCAL ROLE threadmind_api`，再用 JWT `sub` 写入 transaction-local `app.current_account_id`。RLS policy 从该设置读取账户，事务结束后上下文自动清除。Memory 创建、修订和删除，以及 Action Card 创建、确认和回执写入都具有可恢复语义；非幂等的卡片编辑使用 `expectedVersion` 拒绝重复应用。详细决策见 [ADR-0002](adrs/0002-postgresql-rls-context.md)。

## 真实 E2E 基线

当前真实链路已在 Android 模拟器和 Supabase Hosted 项目上验证：持久 Supabase Session → Fastify JWT 验证 → Kysely → PostgreSQL 强制 RLS → Memory Center 读取、修订为新事实版本、删除并从活动列表消失。测试数据使用 `e2e:` 来源标识，并在验证后从远端清除。

模拟器访问宿主 API 使用 `THREADMIND_API_BASE_URL=http://127.0.0.1:3000` 和 `adb reverse tcp:3000 tcp:3000`。若虚拟机配置了宿主 HTTP 代理，代理可能拦截回环请求；测试本地 API 前可在该测试虚拟机执行 `adb shell settings put global http_proxy :0`，需要恢复外网代理时再显式写回原值。
