# ADR-0001：ThreadMind MVP 技术栈

- 状态：Accepted
- 日期：2026-08-27
- 决策范围：MVP 客户端、服务端、AI 编排、数据与认证基础设施

## 1. 背景

ThreadMind 是 Android 优先的个人关系与行动 Agent。系统需要处理包含敏感信息的聊天截图，以可追溯方式生成 Action Cards、长期记忆与洞察，并保证所有联系人和日历写入都由用户对具体卡片版本明确确认。

[RFC-0001](../rfcs/0001-context-actions-memory-insights.md) 已固定领域边界与系统不变量。本 ADR 在不改变这些产品语义的前提下，固定 MVP 的实现技术栈。模型供应商保持可替换，以便根据准确率、延迟、成本与数据处理条款进行评测和切换。

## 2. 决策摘要

| 层 | 选择 |
| --- | --- |
| Android | Kotlin、Jetpack Compose、Material 3 |
| 客户端架构 | ViewModel、StateFlow、单向数据流、Hilt |
| 客户端数据与任务 | Room、WorkManager |
| 客户端网络 | Retrofit、OkHttp、kotlinx.serialization |
| 服务端 | TypeScript、Node.js 24 LTS、Fastify |
| API 与校验 | REST、OpenAPI 3.1、Zod/JSON Schema |
| AI 编排 | LangChain JS、LangGraph JS |
| 视觉理解 | 仅使用云端多模态模型；不接入专用 OCR |
| 身份认证 | Supabase Auth 邮箱六位 OTP、自定义 SMTP |
| 主存储 | Supabase 托管 PostgreSQL、Kysely、显式 SQL migration |
| 临时图片 | Supabase Storage 私有 Bucket |
| 后台任务 | 独立 Worker、PostgreSQL-backed queue |
| MVP 检索 | PostgreSQL 结构化过滤与全文检索 |
| 可观测性 | OpenTelemetry；LangSmith 仅可选用于脱敏开发评测 |
| 交付 | Docker 容器化 API 与 Worker |

## 3. Android 客户端

### 3.1 UI 与状态

- 使用 Kotlin、Jetpack Compose 和 Material 3。
- 页面状态由 screen-level ViewModel 通过 StateFlow 暴露，UI 采用单向数据流。
- 使用 Hilt 管理 Repository、Provider adapter、网络客户端和测试替身。
- 不引入额外 MVI 框架；Action Card 的状态与事件使用 Kotlin sealed interface 和不可变 data class 显式建模。

### 3.2 本地数据与后台任务

- Room 保存待上传提交、Action Card 草稿与版本、确认快照、回执同步状态和必要缓存。
- WorkManager 只处理可恢复的上传、状态同步、回执上报与删除同步。
- WorkManager 不得自动执行联系人或日历写入。

### 3.3 系统 Provider

- Contacts Provider 和 Calendar Provider 分别封装为独立 adapter。
- Android 端负责权限请求、目标账户选择、重复项检查、联系人消歧和真实写入。
- 执行器只接收不可变的 `confirmedSnapshot`；服务端和 AI 工作流不能直接写入设备 Provider。

### 3.4 网络

- 使用 Retrofit、OkHttp 和 kotlinx.serialization 调用服务端 REST API。
- API 类型以 OpenAPI 3.1 为跨 Kotlin/TypeScript 的语言中立契约。

## 4. 服务端

### 4.1 运行时与框架

- 使用 TypeScript 和 Node.js 24 LTS。
- 使用 Fastify 构建模块化单体；首期部署为 API 与 Worker 两个进程。
- 使用 Zod/JSON Schema 校验网络输入、模型结构化输出和节点间数据。
- 不采用微服务、GraphQL、Kubernetes、Redis 或 Temporal。

### 4.2 模块边界

```text
server/
  api/
  worker/
  domain/
  submission/
  extraction/
  action/
  memory/
  insight/
  account/
  adapters/
```

模型输出必须先转换为已验证的领域候选对象，不能直接写入 CanonicalData 或触发外部副作用。

## 5. AI 与多模态流水线

### 5.1 框架边界

- LangChain JS 提供模型适配、结构化输出和供应商切换能力。
- LangGraph JS 编排概率性的多阶段 AI 工作流、节点重试、fallback、校验和观测。
- PostgreSQL 保存确定性的产品状态；LangGraph checkpoint 不能成为 Action Card 授权、执行回执或 Memory 删除状态的事实源。
- 不向模型注册联系人或日历写入工具，不构建具有自由副作用能力的通用 ReAct Agent。

### 5.2 仅多模态模型

MVP 不接入 ML Kit、云 OCR API 或其他专用 OCR。使用同一个可替换的云端多模态模型完成两个逻辑阶段：

```text
截图
  → Visual Transcription
  → Context Extraction
  → Evidence Validation
  → Action Card / Memory 候选
```

`Visual Transcription` 必须保留逐字转录、消息顺序、说话人、近似截图区域和置信度。`Context Extraction` 生成的实体、时间和行动字段必须引用转录消息 ID。模型对原文的规范化或纠错属于新的推断，不能静默覆盖原始转录。

长截图允许进行无损预处理和带重叠的切片，但不得以有损压缩破坏小字号文字。

### 5.3 模型供应商

不在本 ADR 中固定模型供应商或具体模型。实现统一 `VisionExtractionModel` adapter，并通过脱敏/合成截图评测以下指标后选择生产模型：

- 转录字符准确率、消息顺序和说话人归属；
- Action Card 类型 precision/recall 与字段 exact match；
- Evidence 引用完整率和无依据事实率；
- 延迟、成本、可用性及数据留存条款。

## 6. Supabase Auth 与账户隔离

### 6.1 登录方式

- MVP 只使用邮箱六位 OTP，不使用密码、Magic Link、Google 原生登录或其他社交登录。
- Android 通过 supabase-kt 发起 OTP 并验证验证码。
- 使用自定义 SMTP；不得依赖 Supabase 默认邮件服务作为生产登录通道。
- SMTP 供应商不在本 ADR 中固定，但必须验证中国大陆常见邮箱的真实送达率。
- 注册与登录显式区分；只有用户接受隐私与数据处理说明后才允许创建新用户。

### 6.2 Session 与服务端验证

- Android 只包含 Supabase publishable key，绝不包含 secret key 或 `service_role`。
- Android 将 Supabase Access Token 作为 Bearer Token 发送给 Fastify。
- Fastify 使用 Supabase 非对称 Signing Key 的 JWKS 验证签名、issuer、audience、expiry 和 subject。
- `auth.users.id` 作为 ThreadMind `accountId`，所有业务对象显式携带 `account_id`。
- 不使用用户可编辑的 `user_metadata` 或 `raw_user_meta_data` 做授权判断。

### 6.3 数据库访问与 RLS

- 核心业务表放在不暴露给 Supabase Data API 的 `threadmind` schema；Android 不直连核心业务表。
- Fastify 和 Worker 使用专用、最小权限且不具备 `BYPASSRLS` 的数据库角色。
- 应用查询显式限定 `account_id`，RLS 作为纵深防御；RLS 的连接池上下文和策略在数据库实现 ADR 中另行固定并测试。
- 任何未来暴露给 Data API 的表都必须显式配置 GRANT 和 RLS；仅使用 `TO authenticated` 不构成账户级授权。
- `service_role` 仅允许在受信服务端用于必要的 Auth 管理操作，不能作为普通业务查询凭据。

### 6.4 登出与删除

- 删除流程先将账户标记为 `deleting` 并阻止新任务，再撤销 Session、删除 Storage 对象与业务数据，最后删除 Auth 用户。
- 已签发 Access Token 在到期前可能仍然有效；敏感操作需要校验账户状态，账户删除与全量导出还需要校验 `session_id` 或近期重新认证。
- Supabase Storage 中由用户拥有的对象必须在删除 Auth 用户前清除。

## 7. 数据、存储与检索

- Supabase 托管 PostgreSQL 是 Submission 元数据、Action Card、Action Receipt、MemoryRecord 和 InsightHistory 的主存储。
- 使用 Kysely 编写类型安全查询，数据库结构通过显式 SQL migration 管理，以保留对事务、索引、RLS、全文检索和版本关系的直接控制。
- 使用 Supabase Storage 私有 Bucket 暂存原始截图；处理完成后主动删除，并由独立定时清理任务处理异常遗留对象。
- 后台任务采用 PostgreSQL-backed queue，由独立 Worker 执行模型调用、记忆更新、洞察生成和异步删除。
- MVP 使用联系人、实体、时间、状态、置信度和 PostgreSQL 全文检索组合召回；不启用独立向量数据库或 pgvector。
- 只有评测证明语义漏召回构成真实问题后，才通过新 ADR 引入 pgvector。

## 8. 可观测性、测试与交付

- 服务端使用 OpenTelemetry 记录 traces、metrics 和脱敏 logs。
- 日志不得包含原始截图、完整视觉转录、联系人明文、OTP 或 Memory 正文。
- LangSmith 只可用于脱敏或合成数据的开发评测，不默认采集生产内容。
- 服务端使用 Vitest 和 Testcontainers；Android 使用 JUnit、Compose UI Test 和 instrumentation test。
- 建立脱敏/合成截图回归集，模型、Prompt 或 Schema 变更必须重新运行多模态评测。
- API 与 Worker 使用 Docker 容器部署；容器运行平台不在本 ADR 中固定。

## 9. 中国大陆可用性边界

选择邮箱 OTP 是为了避免依赖 Google 服务和浏览器 OAuth。Supabase Hosted、SMTP、模型 API 与 Supabase Storage 在中国大陆的连通性和延迟不能仅凭供应商文档推定。

在对外承诺中国大陆可用前，必须使用真实 Android 设备分别在移动、联通和电信网络验证：

- OTP 发送、验证码验证与 Session 刷新；
- Fastify API、Supabase PostgreSQL/Storage 和模型 API；
- 截图上传、完整 Worker 执行、Provider 写入回执和账户删除。

若 Hosted Supabase 无法满足目标网络质量或数据治理要求，应另立部署 ADR；本 ADR 不预先承诺自托管或迁移方案。

## 10. 结果与约束

本决策带来的主要结果是：

- Android 保持原生系统能力和外部写入授权边界。
- LangChain/LangGraph 提供可观测、可恢复且受领域规则约束的 AI 编排层。
- 仅多模态方案减少 OCR 供应商和管线数量，但必须以分阶段结构化输出、Evidence 引用和回归评测补偿可追溯性风险。
- Supabase 统一 Auth、PostgreSQL 与临时对象存储，但引入了中国大陆网络验证和平台依赖风险。
- 首期不引入向量数据库、通用自主 Agent、微服务或复杂工作流基础设施。

任何实现都不得削弱 RFC-0001 的确认、证据、权威数据优先级、记忆删除和账户隔离不变量。

## 11. 参考资料

- [Android 应用架构指南](https://developer.android.com/topic/architecture)
- [LangGraph 概览](https://docs.langchain.com/oss/javascript/langgraph/overview)
- [Supabase Email OTP](https://supabase.com/docs/guides/auth/auth-email-passwordless)
- [Supabase JWT](https://supabase.com/docs/guides/auth/jwts)
- [Supabase Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security)
- [Node.js 发布与 LTS](https://nodejs.org/en/about/previous-releases)
