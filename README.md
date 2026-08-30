# ThreadMind

ThreadMind 是一个面向 Android 用户的个人关系与行动 Agent：它理解聊天截图中的上下文，把可执行事项整理成由用户确认的 Action Cards，并在行动完成后结合联系人、日历与长期记忆，生成可解释的关系洞察和后续建议。

## 核心闭环

1. 用户在 App 内上传聊天截图，或从其他 Android 应用通过系统分享发送截图，并可附加补充文字。
2. 云端多模态模型完成视觉转录、上下文理解与行动识别。
3. 系统生成可编辑的创建会议、创建联系人或更新联系人卡片。
4. 用户补全缺失字段并逐卡确认后，ThreadMind 才能写入 Android 系统日历或通讯录。
5. 执行成功后，系统结合本次上下文、联系人数据、日历数据和长期记忆，生成带依据、置信度与事实/推断标签的洞察和建议。
6. 用户可以在记忆中心查看、修正或删除自动保存的记忆。

洞察与建议是产品的核心价值。Action Cards 既帮助用户完成现实行动，也为后续洞察建立可靠、经确认的事实基础。

## MVP 边界

- 单用户、Android 优先。
- 支持 App 内上传和 Android 系统分享。
- 通过 Android Calendar Provider 和 Contacts Provider 访问设备已有账户。
- 只支持创建会议、创建联系人和更新联系人三类行动。
- 使用可替换的云端多模态模型；MVP 技术栈由 ADR-0001 固定。
- 不包含 iOS、团队空间、聊天回复生成、通用任务管理、每日回顾或 Google API 直连。

## 不可破坏的产品约束

- 没有用户对具体 Action Card 的确认，绝不执行外部写入。
- 没有可追溯依据的内容，绝不表达为事实。
- 联系人、日历和已确认行动等权威数据，不能被低置信模型推断覆盖。
- 生成的洞察和建议不能反向写成事实记忆。
- 删除的记忆和账户数据必须停止参与召回及后续洞察。

## 文档

- [产品需求文档](docs/PRD.md)：产品目标、用户流程、MVP 范围及验收标准。
- [RFC-0001：上下文、行动、记忆与洞察](docs/rfcs/0001-context-actions-memory-insights.md)：领域边界、最小接口、数据流和隐私约束。
- [ADR-0001：ThreadMind MVP 技术栈](docs/adrs/0001-technology-stack.md)：Android、服务端、AI 编排、Supabase 与交付方案。

## 当前实现

仓库已包含第一条可运行 MVP 纵向切片：

- `server/`：Fastify API、Action Card/Memory/Insight 领域规则、Supabase JWT 验证、Kysely Action/Receipt/Memory repositories、PostgreSQL 强制 RLS migration 与 Dockerfile。
- `android/`：Compose 客户端、Supabase 邮箱密码与六位 OTP 登录、App 内密码找回/设置、带 Bearer Token 的 Retrofit 客户端、可查看/修订/删除的 Memory Center、系统分享入口、卡片确认状态机，以及只接受确认快照的 Calendar/Contacts Provider executor。
- 自动化测试覆盖未确认禁止执行、编辑后确认失效、失败回执不生成目标 ID、记忆纠错/删除过滤、数据库账户隔离、洞察证据和 API 状态；真实 E2E 已覆盖 Android Session → API → Supabase PostgreSQL → RLS → Memory Center。

运行方式、已实现范围及尚未接入的生产能力见 [实现说明](docs/IMPLEMENTATION.md)。PRD、RFC 和 ADR 仍是后续实现的约束来源。
