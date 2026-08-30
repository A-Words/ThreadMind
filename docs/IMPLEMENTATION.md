# ThreadMind 实现说明

当前实现是 MVP 的第一条可运行纵向切片，目标是先把不可破坏的授权、证据、账户隔离与删除语义编码，而不是提前接入未经评测的生产模型。

## 已实现

- `server/`：Node.js 24 + TypeScript + Fastify 模块化服务端。
- Action Card：字段/evidence 校验、阻塞状态、编辑增版、逐版本确认、不可变确认快照、独立执行回执与账户隔离。
- Memory：来源强制、事实/推断标记、用户修正版本化、删除与默认召回过滤。
- Insight：正式洞察必须存在同账户成功回执，并且每个 item 都有依据。
- Auth：生产入口通过 Supabase JWKS 验证 Bearer Token；不提供生产可用的账户头旁路。
- PostgreSQL：Supabase 托管实例、显式 SQL migration、强制 RLS、最小权限运行角色与 Kysely Memory repository。
- `android/`：Kotlin + Compose + Material 3 + StateFlow + Hilt 工程，支持图片选择、Android 分享入口、卡片确认界面和按动作请求 Provider 权限。
- Android Provider executor：只接受 `ConfirmedActionSnapshot`，支持 Calendar/Contacts 写入，并在重试前通过稳定 marker 检查既有记录。
- Android Auth：通过 supabase-kt 提供邮箱密码与六位 OTP 两种登录/注册方式；密码注册确认、找回密码和账户内设置密码都在 App 内验证邮件六位码，注册前强制确认隐私与数据处理说明，会话由 SDK 持久化和刷新。
- Android API client：Retrofit/OkHttp/kotlinx.serialization 客户端从当前 Supabase Session 注入 Bearer Token，不接受账户 ID 头旁路。
- Android Memory Center：读取活动记忆，展示事实/推断、置信度、版本、敏感级别与来源；支持保留历史的修订和确认后删除。
- Gradle Wrapper、服务端 Dockerfile、Node 和 Android 领域/API 自动化测试。

## 尚未接入

- Supabase Storage bucket 与 PostgreSQL-backed worker queue。
- 截图上传、视觉模型 adapter、LangGraph 提取流程及模型评测集。
- Android 的业务 API repository、Room/WorkManager 离线恢复、重复联系人/会议冲突审核界面。
- 真实设备上的联系人/日历写入、权限撤销、账户删除和中国大陆网络验证。

这些边界不应被当前的通过测试误写成已交付能力。

## 本地验证

服务端：

```powershell
npm install
npm run typecheck
npm test
npm run test:integration
npm run build
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

服务端启动前复制 `.env.example` 为仓库根目录下的 `.env`，配置真实 Supabase Auth 项目、`DATABASE_URL` 和 `THREADMIND_E2E_ACCOUNT_ID`。`DATABASE_URL` 使用 `threadmind_runtime` 登录角色，不使用 `postgres`、`service_role` 或 Android publishable key。持久 Fastify 服务优先使用 session pooler（5432）；短生命周期环境可使用 transaction pooler（6543），当前 `pg`/Kysely 查询不启用命名 prepared statements。

`DATABASE_SSL_REJECT_UNAUTHORIZED` 默认必须为 `true`。只有本地被受信调试代理替换证书且已核对代理边界时才可在忽略的 `.env` 中临时设为 `false`，不得复制到部署环境。`npm run dev` 和 `npm start` 会自动读取根目录 `.env`；部署环境仍可直接注入环境变量。`x-account-id` 只在测试构造的显式不安全模式中启用，普通启动不会接受它。

数据库迁移顺序为：

1. `server/migrations/0001_initial.sql` 创建私有 schema、约束、索引、GRANT、强制 RLS 与账户策略。
2. `server/migrations/0002_create_runtime_role.sql` 创建无 `BYPASSRLS` 的登录角色并授予受限业务角色；密码由部署密钥管理器单独设置，不进入 migration 或 Git。

每个 repository 操作都在短事务内执行 `SET LOCAL ROLE threadmind_api`，再用 JWT `sub` 写入 transaction-local `app.current_account_id`。RLS policy 从该设置读取账户，事务结束后上下文自动清除。创建、修订和删除具有可恢复的幂等语义，以处理连接在提交后断开、客户端无法确认提交结果的情况。详细决策见 [ADR-0002](adrs/0002-postgresql-rls-context.md)。

## 真实 E2E 基线

当前真实链路已在 Android 模拟器和 Supabase Hosted 项目上验证：持久 Supabase Session → Fastify JWT 验证 → Kysely → PostgreSQL 强制 RLS → Memory Center 读取、修订为新事实版本、删除并从活动列表消失。测试数据使用 `e2e:` 来源标识，并在验证后从远端清除。

模拟器访问宿主 API 使用 `THREADMIND_API_BASE_URL=http://127.0.0.1:3000` 和 `adb reverse tcp:3000 tcp:3000`。若虚拟机配置了宿主 HTTP 代理，代理可能拦截回环请求；测试本地 API 前可在该测试虚拟机执行 `adb shell settings put global http_proxy :0`，需要恢复外网代理时再显式写回原值。
