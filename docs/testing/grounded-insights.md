# 执行后洞察验证记录

日期：2026-09-04；对应 ADR-0007。此记录覆盖联系人快照、执行后洞察质量和一条本地真实 Agent 链路。

## 本地自动化

`npm test --workspace=@threadmind/server`：63 项测试通过（默认命令不连接 PostgreSQL）。

- 定向召回在限额前筛选；超过 100 条无关记忆不能遮蔽旧相关记录。
- 当前 Extraction 和多人引用进入生成输入；修订后的版本生效，删除/其他账户不进入召回。
- 回执重放不恢复已删除行动记忆，不重复生成历史。
- 模型只能引用提供的证据；拒绝捏造引用、推断提升事实、过高置信度、把建议标为事实和只复述回执。
- Responses 请求使用 strict JSON Schema、`store: false`，上游错误内容不泄露。
- 模型失败返回 `503 insight_generation_pending`，同时明确 `receiptRecorded: true`。成功回执已落库，重试同一个 `receiptId` 只补洞察；Android 现有同步队列会保留该回执。
- Android 默认值省略后的联系人快照可以被 API 规范化；非成功回执不能携带联系人资料，同名匹配不能声明身份已确认。

## 联系人快照范围

联系人资料只在用户确认的 Provider 写入成功后读取。创建/更新联系人只按 Provider 返回的联系人 ID 读取目标；创建会议只按已确认参会邮箱精确查询。一次最多 10 个联系人，每人最多 3 个邮箱和 3 个电话，并可包含显示名、组织和职位；不读取备注、地址、账户、头像，也不枚举整本通讯录。

回执记录 `source=android_contacts_provider`、`capturedAt`、`permissionStatus`、查询依据和身份状态。只有本次写入返回的 Provider ID 可标为 `confirmed_target`；邮箱精确命中仍只是 `candidate`，多条命中为 `ambiguous`，显示名相同不能确认身份。进入模型前会移除 Provider 联系人 ID，保留受限字段及其证据键。

## 真实模型合成样例

命令：`npm run test:insight-model --workspace=@threadmind/server`。此命令显式调用外部模型、会产生 API 用量，只发送脚本内的虚构联系人、会议和记忆，不读取账户数据。

模型取 `THREADMIND_INSIGHT_MODEL`，未指定时本测试使用现有 `THREADMIND_VISION_MODEL`；本次为 `gpt-5.6-luna`。这不修改 `.env`，也不自动启用生产洞察生成器。

输入：已创建产品评审会；Chen 要求会前通过邮件收到最终方案；历史记忆分别说明邮件偏好和会前审阅承诺。

第一次运行暴露两个质量问题：输出了只复述回执的成功条目；把“会前发送”的时间设为会议开始。增加回执单独引用的硬拒绝，并明确“只有相对先后关系时 suggestedAt 必须为空”的提示后，真实回归通过。

将样例固化成脚本后，一次运行又未返回 `next_step` 条目而失败；随后加入“至少一条具体 next_step”的强制提示和领域校验。上述失败说明模型输出仍有波动，必须运行质量门禁，不能把结构合法等同于可交付建议。

回归输出包括：通过邮件发送最终方案、确认对方收到并确认能否会前审阅；依据同时包含当前 submission 和历史偏好/承诺。没有凭空指定更早的发送时刻。

脚本检查非空下一步、同一条目综合当前与历史依据、无虚构精确时刻。语义质量仍需人工审阅；一个样例不代表用户帮助度、身份匹配或批量质量已验收。

## 建议质量门禁

命令：`npm run test:insight-quality --workspace=@threadmind/server`。本次使用 `gpt-5.6-luna` 和合成数据，7 个案例全部通过：

- 当前聊天 + 已确认联系人 + 历史邮件偏好，建议明确使用已确认邮箱发送最终方案，并同时引用三类依据。
- 历史预算反馈承诺被写入具体下一步；回执本身不被当成承诺已完成。
- 修订后的邮件偏好生效，旧电话偏好不出现；删除的 Memory 不进入证据。
- 两条同名/同邮箱候选保持 `ambiguous`，建议先核对组织或角色。
- 权限拒绝且只有“之后再联系”时，建议核实出席意愿与邮箱归属，不声称对方已接受安排。
- Provider 执行失败时不调用洞察生成器。

门禁还拒绝旧通用模板、缺少具体 `next_step`、无依据的性别/称谓/关系推断、把他人请求写成用户承诺，以及凭相对时间生成精确 `suggestedAt`。本轮真实模型曾触发“请求被写成承诺”“综合结论被标为事实”和无依据性别代词；除提示约束外，生成器会依据每条洞察实际引用的 premises，逐词检查代词、称谓、家庭关系及社会/业务关系措辞。证据中的“先生”只允许保留“先生”，不会放行“他”或“妻子”。对应组合回归加入后，真实模型 7 个案例重跑通过。模型输出有随机性；该命令是付费语义回归，不能由 JSON Schema 合法或模型配置存在代替。

## 本地真实 Agent 链路

设备：Android 17 `Pixel10-API37`（`emulator-5554`）。命令启动 `npm run dev:real-e2e --workspace=@threadmind/server` 后，通过 `adb reverse tcp:3000 tcp:3000` 运行 `RealAgentFlowTest`，结果 `OK (1 test)`。

测试生成一张本地 PNG 聊天截图并上传，Worker 调用真实视觉模型得到卡片；测试编辑并确认 `create_contact` 卡片；Android 通过真实 Contacts Provider 创建联系人，再只按返回 ID 读取受限快照；成功回执自动生成至少两条 Memory，真实洞察模型返回包含该联系人邮箱、联系人证据和具体 `suggestedAction` 的洞察。测试最后删除联系人和提交，未使用真实联系人数据。

| 层级 | 本次状态 | 证据边界 |
| --- | --- | --- |
| 代码完成 | 通过 | Android 快照、回执/API、Memory 上下文、模型提示和 E2E 驱动均已提交 |
| 替身测试 | 通过 | 63 项服务端测试；本地仓储和临时图片存储验证幂等、边界与证据规则 |
| 真实运行 | 通过（本地功能链路） | Android 17 Contacts Provider、HTTP 图片上传、真实视觉模型、真实洞察模型均实际运行 |
| Hosted PostgreSQL | 通过（联系人回执切片） | `contact_context` 迁移已应用；Memory 与 Action Repository 强制 RLS 集成共 2 项通过 |
| 其余生产基础设施 | 未验收 | E2E 服务使用内存队列、内存图片存储和测试账户头，未覆盖 Supabase Auth、Storage 或进程间 Worker |

## PostgreSQL

`npm run test:recall-integration --workspace=@threadmind/server`：本次 Node 数据库连接响应超时，未通过；本机 Docker 引擎未启动。使用 Supabase SQL 通道独立验证 JSONB 来源/人物筛选表达式得到预期 2 条，并确认无本轮合成测试数据残留。

SQL 表达式验证不能替代 Node → Kysely → 强制 RLS 的完整集成测试。连接恢复后运行上述命令；测试只插入合成 UUID 记录并按 UUID 清理，不需要迁移。

## 启用与剩余范围

API 显式设置 `THREADMIND_INSIGHT_MODEL` 后启用模型推理，同时需要 `OPENAI_API_KEY`，可沿用 `OPENAI_BASE_URL`。未设置时仍为 `rules:evidence-v1` 基线；配置错误或模型失败不会静默降级为规则成功。

联系人回执迁移 `20260903194716_add_receipt_contact_context` 已应用到 Hosted ThreadMind 数据库；列、JSONB 形状约束和迁移记录已查询确认。`npm run test:integration --workspace=@threadmind/server` 中 Memory 与 Action Repository 两项均通过。迁移后安全 Advisor 没有报告本次表变更引入的问题；现有“泄露密码保护未开启”警告与该迁移无关。

仍缺独立的洞察后台作业、跨截图可靠身份关联、更大且有人审标的质量数据集。当前“请求/承诺”和相对时间语义仍主要由提示与合成门禁约束，尚无通用语义校验器。
