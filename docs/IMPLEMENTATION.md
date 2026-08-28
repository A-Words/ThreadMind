# ThreadMind 实现说明

当前实现是 MVP 的第一条可运行纵向切片，目标是先把不可破坏的授权、证据、账户隔离与删除语义编码，而不是提前接入未经评测的生产模型。

## 已实现

- `server/`：Node.js 24 + TypeScript + Fastify 模块化服务端。
- Action Card：字段/evidence 校验、阻塞状态、编辑增版、逐版本确认、不可变确认快照、独立执行回执与账户隔离。
- Memory：来源强制、事实/推断标记、用户修正版本化、删除与默认召回过滤。
- Insight：正式洞察必须存在同账户成功回执，并且每个 item 都有依据。
- Auth：生产入口通过 Supabase JWKS 验证 Bearer Token；不提供生产可用的账户头旁路。
- PostgreSQL 初始 migration：核心约束、复合外键、部分索引与 RLS 启用。
- `android/`：Kotlin + Compose + Material 3 + StateFlow + Hilt 工程，支持图片选择、Android 分享入口、卡片确认界面和按动作请求 Provider 权限。
- Android Provider executor：只接受 `ConfirmedActionSnapshot`，支持 Calendar/Contacts 写入，并在重试前通过稳定 marker 检查既有记录。
- Android Auth：通过 supabase-kt 区分登录与注册，发送并验证邮箱六位 OTP；注册前强制确认隐私与数据处理说明，会话由 SDK 持久化和刷新。
- Android API client：Retrofit/OkHttp/kotlinx.serialization 客户端从当前 Supabase Session 注入 Bearer Token，不接受账户 ID 头旁路。
- Gradle Wrapper、服务端 Dockerfile、Node 和 Android 领域/API 自动化测试。

## 尚未接入

- Supabase 项目、SMTP、Storage bucket 与 PostgreSQL 运行实例。
- Kysely 持久化 repository、RLS policy 和 PostgreSQL-backed worker queue。
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

只允许在 Android 客户端配置 publishable key，禁止写入 Supabase secret 或 `service_role`。若未配置，应用仍可构建并显示配置提示，但无法发起登录或业务 API 请求。Supabase Hosted 项目还需要把 Magic Link/OTP 邮件模板改为包含 `{{ .Token }}` 的六位验证码模板，并配置自定义 SMTP。

服务端启动前复制 `.env.example` 为仓库根目录下的 `.env` 并配置真实 Supabase Auth 项目。`npm run dev` 和 `npm start` 会自动读取该文件；部署环境仍可直接注入环境变量。`x-account-id` 只在测试构造的显式不安全模式中启用，普通启动不会接受它。
