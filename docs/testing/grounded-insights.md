# 执行后洞察验证记录

日期：2026-09-04；对应 ADR-0007。此记录仅覆盖服务端执行后洞察增量。

## 本地自动化

`npm test --workspace=@threadmind/server`：59 项测试通过（默认命令不连接 PostgreSQL）。

- 定向召回在限额前筛选；超过 100 条无关记忆不能遮蔽旧相关记录。
- 当前 Extraction 和多人引用进入生成输入；修订后的版本生效，删除/其他账户不进入召回。
- 回执重放不恢复已删除行动记忆，不重复生成历史。
- 模型只能引用提供的证据；拒绝捏造引用、推断提升事实、过高置信度、把建议标为事实和只复述回执。
- Responses 请求使用 strict JSON Schema、`store: false`，上游错误内容不泄露。
- 模型失败返回 `503 insight_generation_pending`，同时明确 `receiptRecorded: true`。成功回执已落库，重试同一个 `receiptId` 只补洞察；Android 现有同步队列会保留该回执。

## 真实模型合成样例

命令：`npm run test:insight-model --workspace=@threadmind/server`。此命令显式调用外部模型、会产生 API 用量，只发送脚本内的虚构联系人、会议和记忆，不读取账户数据。

模型取 `THREADMIND_INSIGHT_MODEL`，未指定时本测试使用现有 `THREADMIND_VISION_MODEL`；本次为 `gpt-5.6-luna`。这不修改 `.env`，也不自动启用生产洞察生成器。

输入：已创建产品评审会；Chen 要求会前通过邮件收到最终方案；历史记忆分别说明邮件偏好和会前审阅承诺。

第一次运行暴露两个质量问题：输出了只复述回执的成功条目；把“会前发送”的时间设为会议开始。增加回执单独引用的硬拒绝，并明确“只有相对先后关系时 suggestedAt 必须为空”的提示后，真实回归通过。

将样例固化成脚本后，一次运行又未返回 `next_step` 条目而失败；随后加入“至少一条具体 next_step”的强制提示和领域校验。上述失败说明模型输出仍有波动，必须运行质量门禁，不能把结构合法等同于可交付建议。

回归输出包括：通过邮件发送最终方案、确认对方收到并确认能否会前审阅；依据同时包含当前 submission 和历史偏好/承诺。没有凭空指定更早的发送时刻。

脚本检查非空下一步、同一条目综合当前与历史依据、无虚构精确时刻。语义质量仍需人工审阅；一个样例不代表用户帮助度、身份匹配或批量质量已验收。

## PostgreSQL

`npm run test:recall-integration --workspace=@threadmind/server`：本次 Node 数据库连接响应超时，未通过；本机 Docker 引擎未启动。使用 Supabase SQL 通道独立验证 JSONB 来源/人物筛选表达式得到预期 2 条，并确认无本轮合成测试数据残留。

SQL 表达式验证不能替代 Node → Kysely → 强制 RLS 的完整集成测试。连接恢复后运行上述命令；测试只插入合成 UUID 记录并按 UUID 清理，不需要迁移。

## 启用与剩余范围

API 显式设置 `THREADMIND_INSIGHT_MODEL` 后启用模型推理，同时需要 `OPENAI_API_KEY`，可沿用 `OPENAI_BASE_URL`。未设置时仍为 `rules:evidence-v1` 基线；配置错误或模型失败不会静默降级为规则成功。

尚缺最新设备联系人资料快照、独立的洞察后台作业、跨截图可靠身份关联、真实 Android Provider 全链路和更完整的质量数据集。当前相对时间约束依赖模型遵从提示，尚无通用语义时间校验器。
