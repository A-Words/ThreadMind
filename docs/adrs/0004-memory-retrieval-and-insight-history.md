# ADR-0004：记忆检索与洞察历史

- 状态：Accepted
- 日期：2026-08-31
- 决策范围：MVP Memory Center 检索、来源展示与执行后洞察持久化

## 背景

PRD 要求用户能够搜索和筛选长期记忆、查看来源摘录，并在至少一张 Action Card 成功执行后立即获得可解释洞察。RFC-0001 同时规定，历史洞察不能参与事实召回，删除和账户隔离必须贯穿主存储与派生索引。

当前实现已将 Memory、Action Card 与 Action Receipt 持久化到私有 PostgreSQL schema，但 Memory API 仅返回最近的活动记录，来源只包含不可读的引用 ID；`InsightBundle` 也只有领域校验，尚无 repository、历史 API 或数据库表。

## 决策

### 1. PostgreSQL 继续作为 MVP 检索与历史的事实来源

MVP 先使用账户限定的 PostgreSQL 查询完成关键词、联系人、类型和时间筛选，不引入外部向量数据库。查询始终通过现有 transaction-local 账户上下文和强制 RLS 执行，并强制排除 `deleted`、`superseded` 与无来源记录。

若后续加入 embedding 或关键词缓存，它们只能是可重建派生索引；主记录的删除状态必须在任何召回入口先行生效。

### 2. Memory 保存最小可展示来源快照

每条 Memory 除 `sourceRefs` 外保存 `sourceEvidence`，包含来源 ID、可展示摘录、可选消息 ID和置信度。自动提取时从已校验的 `evidenceSpans` 复制必要摘录；用户修正时追加一条明确标记为用户修正的事实来源。

来源快照不得包含原始截图或完整视觉转录。它用于可解释展示和删除追踪，不取代 SourceContext。

### 3. InsightBundle 与 Memory 严格分表

`InsightBundle` 保存到独立的 `insight_bundles` 表，按账户和 Submission 建立历史索引，并使用强制 RLS。每个 item 必须有依据、事实或推断标签与置信度；正式执行后洞察必须引用至少一条同账户成功回执。

Insight 历史只用于展示与质量反馈，不得被 Memory repository 或事实召回查询读取。洞察不能直接创建 Memory。

### 4. 生成器可替换，持久化保持幂等

执行成功后由服务端调用可替换的 `InsightGenerator`，输入仅由服务端按账户组装：成功回执、已确认卡片证据、当前 SourceContext、有效 Memory 与客户端提供的最新 CanonicalData 快照。生成结果先通过领域契约校验，再落库。

同一成功回执集合使用稳定 generation key。网络重试或重复回执不得创建重复历史；失败和取消回执不生成正式执行后洞察。

生产模型 adapter、供应商保留策略和评测阈值仍需单独验证。没有生产 adapter 时只能使用明确标识的开发实现，文档不得宣称生产模型链路已接通。

## 后果

- Memory Center 可以在不依赖外部索引的情况下交付确定、可删除且账户隔离的检索行为。
- 来源摘录会增加少量 PostgreSQL 存储，但避免 UI 将内部 ID 冒充用户可理解的证据。
- 洞察生成与回执 API 的失败语义必须分离：Provider 回执成功不能因洞察生成暂时失败而被回滚或误报。
- 提交删除、全量记忆清除和账户删除必须同时处理 `sourceEvidence`、`insight_bundles` 及未来派生索引。

## 验证要求

- PostgreSQL integration test 证明搜索/筛选、默认状态过滤、账户隔离和来源摘录往返。
- API test 证明失败/取消回执不产生正式洞察，重复成功回执不会重复生成，洞察历史不进入 Memory 列表。
- RLS 测试证明其他账户无法读取或写入 InsightBundle。
- Android 测试证明 Memory Center 搜索/筛选与洞察历史均来自服务端结果，而不是只做本地视觉过滤。
