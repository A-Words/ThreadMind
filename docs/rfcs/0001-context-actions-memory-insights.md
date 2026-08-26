# RFC-0001：上下文、行动、记忆与洞察

- 状态：Accepted
- 日期：2026-08-26
- 决策范围：MVP 领域模型与数据边界

## 1. 摘要

本 RFC 定义 ThreadMind 从聊天截图到现实行动，再到长期记忆与洞察的最小领域模型。它刻意不选择 Android UI 框架、后端语言、数据库、模型供应商或向量检索产品。

核心原则是把不同可信度和生命周期的数据分开：当前输入不是长期事实，模型推断不是权威数据，行动建议不是执行结果，生成的洞察也不能成为自己的证据。

## 2. 目标与非目标

### 2.1 目标

- 为截图分析、行动确认、系统写入、自动记忆和洞察生成定义稳定边界。
- 保证所有外部副作用都由用户对具体 Action Card 明确授权。
- 让每条事实、推断、行动和洞察都可以追溯到来源及版本。
- 允许未来替换模型、数据库或 memory backend，而不改变产品语义。
- 使删除、纠错和权限撤销能够贯穿原始数据、结构化数据及派生索引。

### 2.2 非目标

- 定义网络 API 的最终 JSON 格式。
- 选择 OCR、多模态模型、embedding 模型或向量数据库。
- 定义 Android 页面视觉设计。
- 支持任意工具调用、通用工作流或无需确认的自主执行。

## 3. 四层数据模型

### 3.1 SourceContext：当前来源上下文

`SourceContext` 是一次用户提交的证据包，包括截图、OCR 结果、补充文字、来源元数据和分析过程中的说话人/消息结构。

- 原始截图只用于当前处理，不进入长期记忆，也不用于训练。
- 云端处理完成后删除原始截图；长期对象只保存必要的来源摘录、位置引用和不可逆内容指纹。
- OCR 文本不是天然事实，必须保留识别置信度和与截图区域的对应关系。
- 用户补充文字与 OCR 内容分别标记来源，不能混为同一种证据。

### 3.2 CanonicalData：权威个人数据

`CanonicalData` 包括：

- 从 Android Contacts Provider 读取的联系人快照。
- 从 Android Calendar Provider 读取的日历快照。
- 用户最终确认的 Action Card 快照。
- Provider 返回的成功执行结果和目标记录 ID。
- 用户在记忆中心做出的显式修正。

权威数据优先于模型推断。低置信推断不能覆盖或静默修改权威数据。外部记录可能在其他应用中发生变化，因此快照必须记录读取时间，不能被视为永久最新。

### 3.3 MemoryRecord：可召回长期记忆

`MemoryRecord` 是从用户来源、用户确认和成功执行结果中提取的长期上下文。它可以是直接事实，也可以是明确标注的模型推断。

每条记录必须独立可查看、修正、删除和版本化。memory backend 只负责保存和检索，不拥有联系人、日历、用户授权或行动执行语义。

### 3.4 InsightHistory：生成内容历史

`InsightHistory` 保存曾向用户展示的洞察、建议、依据引用及反馈，支持历史查看和质量评估。

它不是事实来源：洞察文本不得直接转成 `MemoryRecord`，也不能仅凭“系统以前这样判断过”成为后续洞察的证据。只有新的用户输入、用户修正、用户确认或真实执行结果才能产生新的事实依据。

## 4. 信任与优先级

发生冲突时按以下顺序处理：

1. 用户当前显式修正或当前卡片编辑。
2. Provider 当前读取结果及成功写入回执。
3. 过去用户确认的行动或记忆修正。
4. 来源直接表达、且有明确摘录的事实记忆。
5. 模型推断记忆。
6. 历史洞察，仅作为展示历史，不参与事实裁决。

优先级更高不意味着可以删除低优先级历史。冲突通过新版本、版本关系和召回过滤解决，不静默改写证据链。

## 5. 最小领域接口

以下是逻辑接口，不承诺具体编程语言或传输格式。所有 ID 都必须在单一账户范围内不可混淆。

### 5.1 ScreenshotSubmission

表示一次用户发起的分析请求。

```text
ScreenshotSubmission {
  id
  accountId
  imageHandle
  supplementalText?
  source: in_app | android_share
  createdAt
  status: uploaded | processing | ready | failed | deleted
}
```

`imageHandle` 是短期对象引用，不得被复制进长期记忆。提交删除后，其结构化派生对象也进入级联删除流程。

### 5.2 ContextExtraction

表示对提交的结构化理解。

```text
ContextExtraction {
  id
  submissionId
  messages[]
  participants[]
  entities[]
  temporalExpressions[]
  actionCandidates[]
  evidenceSpans[]
  warnings[]
  modelTrace
  createdAt
}
```

每个实体、时间表达式和行动候选必须引用一个或多个 `evidenceSpans`，并带置信度。`modelTrace` 记录模型及提示版本、处理时间和安全审计所需元数据，但不暴露供应商内部推理内容。

### 5.3 ActionCard

`ActionCard` 是候选行动与用户授权之间唯一的产品边界。

```text
ActionCard {
  id
  submissionId
  type: create_meeting | create_contact | update_contact
  version
  fields
  evidenceRefs[]
  fieldConfidence
  validationIssues[]
  targetAccountId?
  status: draft | blocked | ready | confirmed | executing | succeeded | failed | cancelled
  confirmedSnapshot?
  confirmedAt?
}
```

约束：

- `blocked` 表示必填字段缺失、存在未解决歧义或权限前置条件不满足。
- 只有 `ready` 卡片可以确认。
- 用户编辑使 `version` 增加，旧确认不适用于新版本。
- `confirmedSnapshot` 是不可变执行输入；后台不能用后续模型输出改变它。
- 一次确认只允许执行一个确定的卡片版本和目标账户。

### 5.4 ActionReceipt

表示一次真实执行尝试。

```text
ActionReceipt {
  id
  actionCardId
  confirmedVersion
  attempt
  status: succeeded | failed | cancelled
  provider: android_calendar | android_contacts
  targetRecordId?
  errorCode?
  errorMessage?
  startedAt
  completedAt
}
```

只有 Provider 明确返回成功后，回执才能为 `succeeded` 并形成已完成事实。重试产生新 `attempt`，不得覆盖旧回执。

### 5.5 ContactSnapshot

表示生成卡片或洞察时读取到的联系人状态。

```text
ContactSnapshot {
  id
  accountId
  providerRecordId
  displayName
  phones[]
  emails[]
  organizations[]
  addresses[]
  notes[]
  readAt
  contentVersion?
}
```

快照用于联系人消歧、字段差异和洞察，不取代 Android Provider 中的实际记录。姓名不能作为唯一身份键；优先使用 Provider ID、规范化电话和电子邮件进行匹配。

### 5.6 MemoryRecord

```text
MemoryRecord {
  id
  accountId
  subjectRefs[]
  type: event | preference | relationship | commitment | profile | other
  assertion
  epistemicStatus: fact | inference
  confidence
  sensitivity: normal | sensitive | highly_sensitive
  sourceRefs[]
  validFrom?
  validTo?
  createdAt
  updatedAt
  version
  supersedesId?
  status: active | superseded | deleted
}
```

规则：

- 所有记忆自动创建，但推断必须保持 `inference`，不能随重复召回自动升级为事实。
- `sourceRefs` 必须能回到用户输入摘录、用户修正、已确认卡片或成功回执。
- 新信息与旧记录冲突时创建新版本，通过 `supersedesId` 关联，旧记录标为 `superseded`。
- 用户修正生成优先级更高的新版本。
- 删除状态必须在检索入口强制过滤，并触发派生索引清除。

### 5.7 InsightBundle

```text
InsightBundle {
  id
  submissionId
  actionReceiptIds[]
  items[] {
    kind: relationship_context | new_development | next_step | risk
    title
    explanation
    epistemicStatus: fact | inference
    confidence
    evidenceRefs[]
    suggestedAction?
    suggestedAt?
  }
  generatedAt
  modelTrace
}
```

至少一条 `ActionReceipt` 成功后才生成正式执行后 `InsightBundle`。每个 item 都必须具有依据；没有足够依据时应省略，而不是用通用建议填充。

## 6. 端到端数据流

```text
Android 上传/分享
    → ScreenshotSubmission + 临时原图
    → OCR 与上下文理解
    → ContextExtraction
    → 联系人/日历只读匹配
    → 可编辑 ActionCards
    → 用户逐卡确认
    → Android Provider 写入
    → ActionReceipt
    → 自动提取/版本化 MemoryRecords
    → 检索当前上下文 + CanonicalData + 有效 MemoryRecords
    → InsightBundle
    → InsightHistory
```

两个路径必须保持分离：

- **外部执行路径**：只有确认后的 `ActionCard.confirmedSnapshot` 能进入 Provider。
- **记忆写入路径**：可以自动保存来源事实和推断，但不能触发外部副作用，也不能替代卡片确认。

## 7. 行动执行规则

### 7.1 联系人消歧

- 首先按 Provider ID 匹配，其次使用规范化电话或电子邮件。
- 姓名、公司或关系描述只能用于候选排序，不能单独确定目标。
- 多个合理候选必须由用户选择。
- 更新卡展示逐字段差异；覆盖非空字段需要显式确认。
- 创建前发现疑似重复项时，允许用户选择创建新联系人或切换为更新卡。

### 7.2 时间解析与会议冲突

- 保留原始时间表达式、解析结果、时区和置信度。
- 相对日期以提交时的设备时间与时区为解析基准，并向用户展示绝对时间。
- 无法确定日期、开始时间、结束时间/时长或时区时，卡片保持 `blocked`。
- 写入前读取目标日历的相关时间窗口，展示重复或重叠事件；用户确认后才继续。

### 7.3 幂等与失败

- 同一卡片版本使用稳定幂等键，避免网络重试造成重复记录。
- Provider 成功但回执传输中断时，必须先按幂等键或目标记录核对，不能盲目再次创建。
- 失败不会转化为已完成记忆；成功回执才可成为权威行动结果。

## 8. Memory 召回与洞察规则

### 8.1 写入

自动写入发生在结构化提取后和行动成功后。写入前执行账户范围校验、来源完整性校验、类型与敏感级别分类。即使全部自动保存，也必须保留不确定性，不得把推断改写成确定叙述。

### 8.2 召回

召回必须同时考虑：

- 当前提交涉及的联系人和实体。
- 记忆与当前问题的相关性。
- 事实/推断状态和置信度。
- 时间有效性及事件新旧程度。
- 用户修正和版本关系。
- 敏感级别与当前功能是否确有必要使用。

默认排除 `superseded`、`deleted`、其他账户记录及缺失有效来源的记录。事件过时只降低排序，不等于删除。

### 8.3 生成

洞察生成输入由系统组装，模型不能自行选择账户、越过账户边界或恢复被删除内容。事实陈述必须引用权威数据或事实记忆；推断必须展示为推断。生成结果进入 `InsightHistory`，但不进入事实召回集合。

## 9. 删除与纠错语义

### 9.1 单条记忆删除

1. 在主存储中将记录标为 `deleted`。
2. 在线召回入口立即过滤该记录。
3. 异步删除向量、关键词缓存和其他派生索引。
4. 保留最小删除审计时，不得保留可用于重建个性化内容的正文。

### 9.2 提交删除

删除提交时，删除其临时原图、OCR、结构化提取和以该提交为唯一来源的记忆；包含其他有效来源的记忆移除该来源并重新评估是否仍可保留。相关 InsightHistory 同步删除或去标识化。

### 9.3 用户修正

修正不是静默覆盖。系统创建新版本，关联被取代记录，并保证新版本优先召回。历史版本只用于用户可见的来源/变更追溯和必要审计。

### 9.4 全量清除和账户删除

停止新任务，撤销可撤销凭据，删除 SourceContext、CanonicalData 快照、Action Cards、Action Receipts、MemoryRecords、InsightHistory 及全部派生索引。Android 系统中已经由用户确认创建的联系人或日历记录不由账户删除自动移除，除非产品另行提供明确、逐项确认的删除操作。

## 10. 安全与隐私边界

- 传输和持久化数据加密，密钥和数据访问按账户隔离。
- 服务端只接收完成当前功能所需的数据；Android 权限按具体动作延迟请求。
- 模型调用必须遵守不训练和受控留存配置；供应商变更不能放宽本 RFC 的保留与删除承诺。
- 日志不得包含原始截图、完整 OCR、联系人明文或 memory 正文。
- 所有外部写入记录卡片版本、确认时间、执行目标和结果，形成可审计链。
- 权限撤销后停止新的 Provider 读取和写入；已有云端数据仍受用户查看、导出和删除控制。

## 11. 必须保持的系统不变量

1. 未确认的卡片不能进入外部执行器。
2. 执行器只能接收不可变的已确认卡片快照。
3. 失败或取消的行动不能形成已完成事实。
4. 低置信推断不能覆盖 CanonicalData。
5. 洞察不能成为自身或后续洞察的事实证据。
6. `deleted` 和 `superseded` 记忆不能参与默认召回。
7. 数据和检索始终限定在单一账户范围。
8. 原始截图不能成为长期记忆或训练资产。

## 12. 后续决策

以下事项应在实现前以独立 ADR 或 RFC 固定：

- Android 客户端架构及 Calendar/Contacts Provider 适配方式。
- 云端任务接口、认证和账户隔离方案。
- 主存储、检索索引和可替换 MemoryProvider 接口。
- 模型供应商、结构化输出校验及评测集。
- 原始截图处理完成的精确定义、异常任务的最长保留时间和删除证明机制。
- 数据导出格式、账户删除 SLA 和法定审计保留范围。
