# ThreadMind PRD 覆盖矩阵

- 基准：`docs/PRD.md`，MVP v0.1
- 审计日期：2026-09-01
- 判定规则：`代码完成` 只表示实现与自动化证据存在；需要 Hosted、真实模型、真实 Android Provider 或运营商网络的项目必须另列 `运行验收`，不能由单元测试代替。

## 核心流程

| PRD 要求 | 代码状态 | 当前证据 | 仍需运行验收 |
| --- | --- | --- | --- |
| App 内选择 / Android 分享导入截图，补充文字，上传前告知与取消 | 代码完成 | `MainActivity`、`MainViewModel`、`AndroidSubmissionWorkflowRepository`；PNG/JPEG/WebP 签名与 15 MiB 限制测试 | 真实聊天应用分享、相册 URI 与大图 |
| 云端多模态转录、顺序、说话人、实体、行动与 Evidence | 代码完成 | Responses strict JSON Schema adapter；LangGraph 分析/领域校验节点；Worker 自动化测试 | 使用脱敏/合成标注截图运行真实模型回归 |
| 展示分析结果、低置信与 Action Cards 来源 | 代码完成 | `/v1/submissions/:id/extraction` 账户隔离测试；Android Room v3 持久转录、说话人、置信度与警告 | 真实模型输出的可读性与 Compose 长列表体验 |
| 卡片完整字段、编辑增版、歧义处理、逐卡确认与取消 | 代码完成 | 服务端/Android Action policy 测试；会议、创建联系人、更新联系人字段模板；确认快照不可变 | 真实用户编辑三类卡片 |
| 设备目标账户可见、可修改且来自实际 Provider | 代码完成 | Calendar/Contacts 可写账户枚举、选择后卡片增版、预检再次验证；ViewModel 测试 | 多账户、只读日历、设备本地联系人账户 |
| 重复会议、重复联系人与字段覆盖二次确认 | 代码完成 | Provider preflight、版本绑定持久审核、候选转换与字段旧值复核测试 | 真实 Provider 冲突、预检到写入间竞态 |
| 无确认不写入；成功/失败/取消回执与可恢复重试 | 代码完成 | `ConfirmedActionSnapshot` 唯一写边界、稳定 marker、待同步回执、权限取消后卡片恢复测试 | 权限撤销、Provider 异常、进程终止与回执重放 |
| 成功行动后即时、可解释的洞察；失败不伪装成功 | 代码完成（保守规则生成器） | 成功回执门禁、Evidence 强制、相关 Memory、会议/联系人下一步与 Insight History 测试 | 用户帮助度评测；生产模型方案若启用需独立评测 |

## Memory、隐私与数据控制

| PRD 要求 | 代码状态 | 当前证据 | 仍需运行验收 |
| --- | --- | --- | --- |
| 自动事实/推断记忆，来源、置信度、敏感级别与版本 | 代码完成 | Worker 输出校验、Memory domain/repository 测试；成功 Action 生成幂等权威事实记忆 | 真实模型事实/推断标注准确率 |
| 查看、检索、联系人/类型/时间筛选、修订、单删、全清 | 代码完成 | Memory API、Kysely 检索与 Android Memory Center 测试 | 大数据量检索和真实设备 UI |
| 删除后不再召回，洞察不反写事实 | 代码完成 | 删除正文抹除、active 过滤、来源级提交删除、洞察与 Memory 分表测试 | Hosted migration 后的真实 RLS/删除 E2E |
| 原图处理后删除，不进入长期记忆或任务 payload | 代码完成 | Worker 终态前删除、失败清理任务、队列脱敏测试 | Supabase Storage 真实对象生命周期与异常重试 |
| 导出、单次提交删除、全量记忆清除、账户删除 | 代码完成 | API/Android 数据控制测试、系统文档选择器、Auth Admin 删除 | 导出文件回读、Hosted Storage/Auth/DB 全链路 |
| JWT、账户隔离、敏感会话复核 | 代码完成 | JWKS、强制 RLS、transaction-local account context、active session 检查 | 尚未部署的 migrations 获授权后执行 Hosted E2E |

## PRD 验收场景 A–H

| 场景 | 代码结论 | 尚缺的最终证据 |
| --- | --- | --- |
| A 创建会议 | 字段补齐、设备时区默认、日历选择、冲突二次确认、写入 marker、回执与会前建议均已实现 | 真实日历 Provider 写入与重复事件 |
| B 创建联系人 | 姓名/联系方式门禁、设备账户选择、重复搜索、可选字段批量写入均已实现 | 真实联系人账户写入 |
| C 更新联系人 | 唯一候选选择、创建转更新、字段差异、旧值复核与仅选中字段写入均已实现 | 多 RawContact 与聚合联系人设备样本 |
| D 歧义和重复 | validation issue 阻塞、低置信展示、会议/联系人预检与二次确认已实现 | 真实时间歧义模型样本及 Provider 竞态 |
| E 权限和失败 | 无权限不写入、取消回执无目标 ID、卡片保持可重新确认、离线回执同步已实现 | 权限撤销和 Provider 故障注入 instrumentation |
| F 洞察可解释性 | 成功回执门禁、事实/推断、置信度、展示 Evidence、有效记忆与下一步已实现 | 用户帮助度与建议采纳评测 |
| G 记忆纠错和删除 | 修订增版、旧版 superseded、删除整条 lineage、活动召回过滤已实现 | Hosted RLS 下的真实回归 |
| H 数据删除 | Storage、Submission、Extraction、Cards、Receipts、Memory 来源、Insights 与账户删除已实现 | 获授权后部署 migrations 并做 Hosted 全链路证明 |

## 当前不能宣称完成的外部验收

1. Hosted Supabase 项目仍未应用仓库新增 migrations；这是远端状态变更，需要明确部署授权。
2. 尚未提供真实模型凭证和脱敏/合成标注截图集，不能指定或宣称某个模型已达到生产质量。
3. 真实 Android 设备上的 Calendar/Contacts、权限撤销、导出文件回读与三大运营商网络尚未完成本轮验证。
4. Dockerfile 已提供 `api` / `worker` targets；本轮本机 Docker Desktop 引擎未运行，尚无实际镜像构建证据。
