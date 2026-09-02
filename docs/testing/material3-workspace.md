# Material 3 工作区验收记录

日期：2026-09-03。设备：原有 Pixel10-API36.1 / emulator-5554。覆盖安装，未卸载或清空 App；未写入真实联系人或日历。所有页面截图中的 Alex、邮箱、记忆和洞察均为 instrumentation 内存测试数据，不写入正式 API。

## 已执行

| 检查 | 结果与边界 |
| --- | --- |
| Server 单元回归 | 49 项通过；包含 Worker、模型适配器、行动确认、回执、记忆及历史接口；不是线上 LLM 调用 |
| PostgreSQL 历史集成 | 通过；真实强制 RLS（包括无账户谓词的查询）、微秒时间戳、同时间分页、状态数量、待处理筛选、删除排除 |
| Android 单元回归 | 48 项通过；包含账户切换取消旧请求、过滤恢复、离线同步、缓存合并、跨设备只读、来源删除后清除可执行卡片、删除成功不受其他页面刷新失败影响、现有执行策略 |
| Android 设备回归 | 19 项通过；6 项原有认证 UI、Room v3→v4 数据保留、远端恢复不上传、待同步回执保护、取消协程不破坏缓存、四入口导航、历史详情返回、草稿离开提示、保存状态恢复、深浅色、320dp / 200% 字体与输入 |
| 横屏复跑 | 四入口和设置的深浅色页面测试通过；侧边导航适配短屏高度，结束后还原旋转设置 |
| 构建与安装 | Debug / AndroidTest APK 构建成功，以 install -r 安装；本地 API 运行新版历史接口 |

数据库测试仅创建随机 UUID 的合成记录，finally 中定向删除这些测试记录。没有修改云端权限、公开表授权或迁移业务表。

## 截图证据

竖屏主要页面：`android/app/build/emulator-qa/workspace-final/`；横屏：`android/app/build/emulator-qa/workspace-landscape/`。每组包含 overview/actions/memories/insights/settings 的 light/dark 合成数据截图。另有空状态、新建分析与 200% 字体截图。

截图是本地构建验收产物，未提交到 Git；如执行 clean，请先备份这两个目录。深浅色通过测试主题切换，不改变用户的系统主题。

## 仍需真实账号人工验收

实际启动 App 当前停在登录页，没有可用的登录会话。已请求用户在原模拟器登录；未绕过认证、获取或修改账号密码。因此本轮**尚未**完成真实登录态下的“选图 → 云端 Worker 分析 → 行动确认 → 明确测试目标写入 → 洞察”全链路，也未声称完成这部分。

下一轮使用已有合成截图 `android/app/build/emulator-qa/synthetic-contact.png`，逐项检查：

1. 选择图片后预览，取消不产生提交；系统分享同样等待明确上传。
2. 上传后离开页面，返回主动同步；断网/失败只重试原提交，不重复创建。
3. 完成后从全部记录重新打开，核对行动摘要、展开转录和来源。
4. 编辑失效旧确认，设备授权拒绝可恢复；重复联系人与会议冲突逐项确认。
5. 仅对明确的测试目标执行写入；回执同步失败只同步回执，禁止再次系统写入。
6. 真正进程终止后恢复（目前已验证 SavedState/Compose 状态序列化，未等同于 OS 杀进程全链路）；系统分享 URI 授权恢复；TalkBack 逐页朗读与焦点检查。

## 复现命令

Server（仓库根目录；集成测试从已忽略的 .env 读取连接与测试账户，不输出凭据）：

```powershell
npm test --workspace server
npm run test:history-integration --workspace server
```

Android（android 目录；本机完整 JDK，避免 VS Code 附带的无 jlink JRE）：

```powershell
$env:JAVA_HOME='E:\Scoop\apps\temurin21-jdk\current'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --no-daemon '-Dorg.gradle.java.home=E:\Scoop\apps\temurin21-jdk\current' '-Dorg.gradle.java.installations.paths=E:\Scoop\apps\temurin21-jdk\current' '-Dorg.gradle.java.installations.auto-detect=false' '-Porg.gradle.java.installations.paths=E:\Scoop\apps\temurin21-jdk\current' '-Porg.gradle.java.installations.auto-detect=false'
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w app.threadmind.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 reverse tcp:3000 tcp:3000
adb -s emulator-5554 shell am start -n app.threadmind/.MainActivity
```

不自动推送、不提交 .env、不运行 uninstall / pm clear / wipe-data。
