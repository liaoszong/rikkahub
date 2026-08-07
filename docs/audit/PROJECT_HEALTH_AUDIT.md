# RikkaHub Fork 项目健康审计

> 审计快照：2026-08-01
> 修复复核：2026-08-07
> 本地仓库：`G:\rikkahub`
> Fork：`liaoszong/rikkahub`
> Upstream：`rikkahub/rikkahub`
> 审计基线：`74e2d043610326d995f65b4635b3789e2114360a`
> Upstream/共同祖先：`8349ef2599dfc1cfad3823554441a63fd919dcb4`

## 1. 执行摘要

这个 Fork **适合继续作为长期基座**。审计时它没有偏离 upstream（`0 behind / 9 ahead`），增量围绕聊天内生成/编辑图片、渐进式画廊、后台任务、自更新和发布工程展开。2026-08-02 的修复批次删除了独立生成入口与第二套图片任务 owner，让聊天内生图统一接管 durable state/FGS；同时落地 MediaAsset v1、历史图库迁移和 CapabilitySnapshot 的 Provider/UI 接管。2026-08-05 又根据 pale.4 稳定复现补齐普通聊天文本/工具流的真实 FGS owner，修正了此前把“显示 ongoing 通知”误当成“已进入前台服务”的审计结论。

风险不在于 README 声明的能力没有实现。图片任务、统一媒体资产和模型能力三条主线已完成第一轮收口；普通附件 materializer 也在 2026-08-07 接入稳定 assetId 与长期 replica。剩余主要风险转为 upstream 原有的 Web、可靠同步、Provider 附件零拷贝、工具权限/审计和非图片媒体协议适配。原始发现和计数保留为审计历史，下面每项用状态标记反映当前修复进度。

### 发现计数

| 等级 | 数量 | 含义 |
|---|---:|---|
| P0 | 21 | 数据、安全、重复请求/计费、发布与兼容性风险 |
| P1 | 14 | 会显著放大后续多模态、Agent、同步、后台任务复杂度的架构风险 |
| P2 | 15 | 可维护性、边界、测试与 upstream 同步问题 |
| P3-Q | 4 | 局部代码质量问题 |
| P3-P | 5 | 性能、内存、体积、耗电与可测性问题 |
| **合计** | **59** | P3 合计 9 项 |

### 2026-08-02 本批修复状态

| 任务 | 状态 | 当前结果 |
|---|---|---|
| 5 个发布脚本改动 | ✅ 已发布验证 | `07ac4c1f chore(release): make publication resumable`；已随 pale.4 推送，并真实完成中断续跑 |
| pale.6 显式版本目标 | ✅ 已完成 | `0f5f6911`：默认仍发布下一 PaleInk 修订；显式 `-TargetRevision 6` 可跳过未公开的 pale.5，同时保持 Android versionCode 仅递增 1；本地版本名/版本码必须与线上 stable feed 同时一致，恢复发布时拒绝修订号错配；PowerShell 合同测试通过 |
| 删除独立生成入口，聊天内生图接管 FGS/durable task | ✅ 已完成 | 仅保留跨会话“图片库”；旧 `Screen.ImageGen` 只用于导航状态兼容，不再创建生成任务 |
| 普通聊天切后台长连接中断 | ✅ 技术修复完成，生产网关验收待发布包 | `331085b5`：发送 admission 同步启动共享 `ChatGenerationForegroundService`，Provider dispatch 前等待 ready；replace/自动 metadata 无 owner 空窗，取消绑定精确 Job；Android 17/16 KB AVD 故障注入 4/4 且清空日志后无 FGS timeout/FATAL |
| MediaAsset v1 与历史图库迁移 | ✅ v1 与升级恢复已完成 | Room 25→26、稳定 assetId、受管理文件、来源/上下文/父版本元数据、旧图库与孤儿文件恢复；`23877b5f` 保留 v1 sidecar 对应迁移行的既有身份，补通 placeholder 升级并消除固定 256 项恢复饥饿 |
| CapabilitySnapshot 接管 Provider/UI | ✅ v1 已完成 | OpenAI Chat/Responses、Gemini、Claude 与主要 UI gating 使用统一快照；旧枚举仅作兼容输入 |
| pale.3 生图回归热修 | 🟡 pale.4 已发布、真机验收待完成 | `9ef9a3c3` 保留旧图片工具输出、禁止付费图片 POST 的透明网络重放、文件提交后不因图库登记失败降级 |
| 展开图库滚动与消息跳转按钮 | 🟡 代码修复完成、真机帧时间待验 | `9fef8cec` 只按用户会话中实际消费的滚动距离判定速度，移除 fling 预测、140 ms 滞留和退出动画；慢速、停止、边界及程序化滚动立即隐藏。展开画廊移除整项尺寸动画与主图交叉双绘制，任意时刻只保留一张主图 |
| pale.6 稳定架构与 Fork boundary | ✅ 已完成 | `6e8a2b2e` 冻结 MediaAsset、Conversation、RequestLedger、Credential、Sync、Citation 的 ID/表关系/迁移序列并新增独立 `:pale` 合同模块；`06c4d0f1` 建立完整架构边界，`4a6fa970` 在全部滚动热修后重算并登记 391 项 integration touchpoint，集合 hash `80e50c7b…274271` 已通过 CI/Release verifier |
| 会话清理误删图库/外部文件 | ✅ 已完成并强化 | `d9eefc83` 先收窄 canonical 删除边界；2026-08-07 又加入 MediaAsset ownership gate，已登记资产即使仍位于 `upload/`/`tool_outputs/` 或原对话已删除也禁止文件清理，并由启动回填迁入长期目录 |
| 备份遗漏生成图片文件 | ✅ 已完成 | `ed6ad71e`：WebDAV/S3 共用 FILES allowlist，递归归档/恢复 `images/` 与 `chat_generated_images/`，继续执行路径、长度与 SHA-256 manifest 校验 |
| 启动恢复门禁与安全模式 | ✅ 代码完成，生产同包验收待 pale.6 | `f42fcf5c`：恢复哈希/复制/fsync 移出主线程；文件与设置共同提交、runtime activation 成功后才发布 Ready；rollback/commit 证据跨进程持久化；SafeMode 与正常 Ready 分离并以冷进程重启恢复完整 runtime |
| Citation Android 8–12 URL 解码兼容性 | ✅ 已完成 | 最终发布 Lint 在 3 处发现 API 33 `URLDecoder.decode(String, Charset)`；`4b378dbc` 改用 API 1 的 charset-name overload，保持 `+`/百分号查询语义，聚焦 Citation JVM 测试与 Debug Kotlin 编译通过 |
| MediaAsset v2 / Room 27 | ✅ 普通附件 materializer 已完成 | `993d63fb` + `0041f698` 完成稳定 file/blob/replica/relation/journal；`fce9585c` 完成精确引用与 GC fail-closed；2026-08-07 将 Image/Video/Audio/Document 全部接入稳定 assetId、原子流式搬运、历史 `upload/`/`tool_outputs/` 回填、备份 allowlist 与资产库分类。本次复用现有 Room 结构，无 schema 版本变更 |
| ConversationStore v2 / Room 28 | ✅ 生产 cutover、并发 owner、FTS 与媒体交界已完成 | `95e58733`、`8d03be27`、`6c261345`、`fce9585c`：正规化生产读写、revision CAS、durable FTS、会话级串行化、metadata patch、删除生命周期和精确 `message_media_ref` 已统一到事务边界 |

本批架构验证：`:ai:testDebugUnitTest`、`:app:testDebugUnitTest`、`:app:compileDebugAndroidTestKotlin`、`:app:assembleDebug` 使用 `--rerun-tasks` 强制执行，253 个任务全部成功；`:app:lintDebug` 成功，新增 error 为 0（24 warnings；既有 baseline 过滤 103 errors、273 warnings、2 hints）。Pixel 10 AVD（Android 17、16 KB page size）先执行 Room 26→27、27→28、MediaAsset/ManagedFile/Conversation v2 DAO 共 12 项，随后在 `0041f698`/`632c6afc`/`9f253355` 工作树执行 migration、MediaAsset 与 Conversation backfill 共 17 项仪器测试，全部通过；`95e58733`/`8d03be27` 又完成 Conversation 生产 cutover、迁移、DAO 与 durable FTS 共 26 项组合仪器测试，定向 JVM 测试同时通过。

精确媒体引用收口（`fce9585c`）：App 全量 Debug JVM 测试、Debug/Unit/AndroidTest Kotlin 编译通过；Pixel 10 AVD（Android 17、16 KB page size）执行 Conversation writer、Media ref/backfill、GenMedia 删除保护与失败附件复制共 **29/29** 项通过；`:app:lintDebug` 成功，新增 error 为 0（26 warnings；103 errors、273 warnings、2 hints 由既有 baseline 过滤）；独立 blocker review 结论 READY。

生图回归热修验证：AI/App JVM 单测共 **376/376** 通过，`:app:assembleDebug` 成功；`:app:lintDebug` 为 **0 errors / 26 warnings**（另有 103 errors、273 warnings、2 hints 被既有 baseline 过滤）。Debug universal APK 为 94,239,253 bytes。该 APK 使用 Debug application ID，不能读取生产安装的数据，因此这些结果不能替代同包升级后的历史图库和重复计费真机验证。

普通附件 materializer 最终验证（2026-08-07）：`gradlew test lintDebug assembleDebug` 中全模块 JVM 测试与 Debug APK 构建通过；首次 App Lint 准确拦截本轮 6 个俄/韩/日缺失翻译，使用项目 `locale-tui` 补齐后单独复跑 `:app:lintDebug`，结果为 **0 errors / 27 warnings / 1 hint**（另有 103 errors、273 warnings、2 hints 被既有 baseline 过滤）。`:app:compileDebugAndroidTestKotlin` 通过；本轮未执行设备仪器测试，因此不把 AndroidTest 源码编译冒充为真机迁移验收。

**版本边界**：旧 `v2.4.5-pale.3` tag 指向 `6cb3eb61`；`v2.4.5-pale.4` 于 2026-08-02 发布，tag 指向 `9873e641`、`versionCode=176`。`v2.4.5-pale.6` 已于 2026-08-06 由发布脚本正式发布，release commit/tag/`origin/master` 指向 `b1986666`，`versionCode=177`，仍使用生产包名和既有永久签名；pale.5 未公开发布。pale.4 用户可直接原位升级到 pale.6。

## 2. 审计方法与边界

原始审计只进行了只读分析、构建验证和文档写入；2026-08-02 修复复核包含经用户授权的业务代码修改和本地提交，仍未 push。证据来自：

- 当前 README、9 个 Fork 提交、`upstream/master...HEAD` 全量差异和 upstream 近期变更热点；
- 消息树、Provider、图片工具、持久任务、Room、文件、WebDAV/S3、Web API、MCP、Workspace、自更新和发布脚本的代码路径；
- JVM 测试、Android Lint、Debug/Release 构建、APK 内容/签名/16 KB 页对齐检查；
- 2026-08-01 时的 upstream PR/Issue，以及 OpenAI、Anthropic、Google 的官方产品/协议资料；
- ADB 设备检查；后续启动无窗口 `Pixel_10` AVD（Android 17、16 KB page size）执行 Room 迁移/DAO 仪器测试，并采集 Debug 空数据冷启动与 PSS 基线。该 AVD 使用软件图形栈，不能替代生产签名真机的画廊帧时间、功耗或 ANR 结论。

本报告把 README 中已声明且代码中确实存在的能力视为**已实现基线**，只评价其正确性、恢复语义、存储一致性、扩展性和验证强度。

## 3. 当前 Fork 的真实边界

### 3.1 已实现且得到代码确认的能力

- 聊天内图片生成与参考图编辑：`ImageGenerationTool` 读取上下文图片/附件，调用图像 Provider，并将生成进度作为工具状态回写消息树。
- 多图渐进式画廊与附件画廊：生成过程可以逐张显示；聊天附件也有独立画廊入口。
- 跨页面、后台持续生成：聊天 `generate_image` 通过 `ChatImageGenerationTaskCoordinator`，普通聊天回复/工具流通过精确 coroutine owner，共同托管于 `ChatGenerationForegroundService`；离开对话后任务仍继续，通知可返回原会话或取消对应任务。
- 持久任务与中断恢复：请求在 Provider 调用前保存 requestId/attempt、预留 assetId 和任务元数据；进程恢复后把未完成任务标记为中断，不自动重放付费请求。
- 图片文件与 Room 双持久化：生成结果先原子写入受管理目录，再登记 MediaAsset；启动恢复可按 durable task 元数据补登记文件已成功、Room 尚未提交的产物。
- 跨会话图片库：图片库是所有对话生成/编辑图片的聚合相册，不是第二个生成入口；支持查看、分享、保存和隐藏。
- PaleInk Provider 与背景模型选择：Provider 初始化、模型合并和标题/建议等背景模型选择已接入。
- 自更新与发布：版本 feed、ABI 选择、SHA-256、DownloadManager 状态恢复、签名指纹检查、staging/live feed 发布流程均存在。
- Debug/Release Firebase 路由隔离：Debug 使用独立 application ID，禁用生产 Google Services 处理，并注入 NoOp analytics。

### 3.2 Git 与长期同步风险

- `upstream/master...HEAD = 0 / 9`：当前没有落后，9 个提交全部位于共同祖先之后。
- Fork 差异共 65 个文件，约 `+4990/-858`，集中在图片任务、聊天 UI、更新/发布和设置资源。
- upstream 最近 100 个提交的高频冲突区恰好包括 `strings.xml`、`app/build.gradle.kts`、`AppModule`、`PreferencesStore`、`RouteActivity`、`ChatViewModel` 和 `ChatService`。继续把 Fork 逻辑直接塞进这些热点文件，会让每次同步成本非线性增长。
- 当前仅 9 个提交、历史清晰，这是很好的长期维护起点。后续应把 Fork 能力收敛到新模块/窄适配层，并把可泛化修复尽量 upstream-first。

## 4. 构建、测试、Lint 与制品验证

| 检查 | 实际结果 | 判定 |
|---|---|---|
| `gradlew test lint assembleDebug --continue` | 367 个 JVM 测试，33 失败；Lint 105 errors / 270 warnings / 2 hints | **失败** |
| 测试分布 | `highlight` 53 个中 30 失败；`workspace` 19 个中 3 失败；其余 315 个通过 | 主要是 Windows 可移植性，但不能称全量通过 |
| `gradlew assembleDebug` | 成功，约 11 秒 | **通过** |
| 默认 Release | 编译/R8 后在 `uploadCrashlyticsMappingFileRelease` 网络超时失败 | **失败，外部服务耦合** |
| Release（仅跳过映射上传） | `:app:assembleRelease -PpaleinkUniversalOnly=true -x :app:uploadCrashlyticsMappingFileRelease` 成功 | **制品构建通过** |
| Release APK | 49,921,961 bytes（47.6 MiB），包名 `me.rerere.rikkahub`，`versionCode=174`，`2.4.5-pale.2` | 可安装制品形成 |
| APK 签名 | APK Signature Scheme v2 有效，签名 SHA-256 与发布脚本永久期望值一致 | **通过** |
| 16 KB page size | arm64 `libtermux.so` 的 LOAD 对齐为 4 KB；Lint 报 3 个 `Aligned16KB` | **失败** |
| 真机性能 | `adb devices -l` 无设备 | **未执行，不作通过结论** |
| 2026-08-02 生图回归热修 | AI/App 376 个 JVM 测试全通过；Lint 0 errors / 26 warnings；Debug universal APK 94,239,253 bytes | **代码门禁通过；生产数据同包升级待验** |
| 2026-08-02 展开图库滚动热修 | `FastScrollVelocityTrackerTest` 3/3；App JVM 测试 214/214；`:app:lintDebug` 0 errors / 26 warnings；`:app:assembleDebug` 成功 | **代码门禁通过；展开大图快速上滑的真机 frame timeline 待验** |
| `v2.4.5-pale.4` 正式发布 | 全仓 `verifyForkRelease`、R8 Release、Lint Vital、16 KB 对齐、包名/176/pale.4/永久 signer、签名 feed v2、SHA-256 与公网 HEAD 回读全部通过；APK 49,990,985 bytes | **发布链通过；真机业务验收待完成** |
| `v2.4.5-pale.6` 正式发布 | 修复后 `verifyForkRelease` 481 tasks 成功，App Lint 0 error；R8 Release、Lint Vital 0/0、16 KB 对齐、包名/177/pale.6/永久 signer、签名 feed、GitHub Release 与公网 APK HEAD 回读全部通过。APK 45,227,105 bytes，SHA-256 `894ba6883b1d0721c33ecdd5cc2b6ab619f4299f258f9da7e270f884cb1fedb1`。独立 Crashlytics mapping 上传因 Google 443 connect timeout 失败，可重试且未改变已发布制品 | **发布链完整通过；符号上传与生产真机验收转为发布后任务** |
| 2026-08-07 普通附件 materializer | 全模块 JVM test 与 Debug APK 通过；AndroidTest Kotlin 编译通过；App Lint 首轮拦截 6 个新增缺失翻译，补齐后 0 error / 27 warnings / 1 hint | **代码门禁通过；同包升级与删除会话后资产保留待设备验收** |

测试失败细节：

- `workspace`：`WorkspaceShellRunner.kt` 固定使用 `/bin/sh`，Windows 上 3 个测试失败；且无 stdin 时未主动关闭子进程 stdin，可能让部分 CLI 永久等待 EOF。
- `highlight`：仓库 `core.autocrlf=true`，fixture 未被 `.gitattributes` 固定 LF，造成 30 个期望文本换行差异。
- Lint 的 105 个 error 由 63 个 `MissingTranslation`、38 个 `LocalContextGetResourceValueCall`、3 个 `NonObservableLocale`、1 个 `ContextCastToActivity` 构成。另有 3 个 16 KB 对齐 warning、无障碍、RTL、未使用资源和依赖更新等问题。

APK 体积构成约为：native libraries 18.61 MiB、assets 12.46 MiB、DEX 8.85 MiB、static 3.08 MiB、resources 约 4.32 MiB。最大单项包括 MuPDF 双 ABI、Barhopper 双 ABI、`simple_dict` 和三张横幅图。

## 5. P0 发现

### 🟡 P0-01 聊天内生成图片会在下次 App 启动时被清理（代码与迁移完成，生产数据验收待 pale.6）

- **状态**：🟡 代码与升级恢复已完成、生产数据真机验收待 pale.6。聊天生成文件使用预留的稳定 assetId 命名并写入受管理目录，`UIMessagePart.Image` 可同时保存 URL 与 assetId；文件写入成功而 Room 提交中断时，启动恢复会从 durable task 恢复 model/provider/prompt/origin/conversation/toolCall/parent 元数据后补登记，而不是再次请求 Provider。`23877b5f` 进一步修复 v1 sidecar 在 25→26 迁移后派生出不同 assetId 的永久冲突：同路径已提交行及其 ManagedFile 链接校验通过后，以现存 DB 身份为权威完成 sidecar ACK，不重写稳定 ID、不复制或删除文件。
- **pale.3 兼容性回归**：已发布的 pale.3 可能保存了包含 `UIMessagePart.Image`、但没有新版 `ChatImageGenerationState` JSON 的 `generate_image` 工具输出。新版分组逻辑曾把这类输出识别为图片画廊后吞掉实际 Image，只渲染“图片生成未完成”。这解释了升级后旧图片集体变成占位图，**不等同于文件已经丢失**。
- **兼容修复证据**：`ChatMessageCot.groupMessageParts` 现在同时保留工具 output/progress 中的历史图片；`ChatImageGenerationState.withFallbackImages` 将真实 durable Image 作为成功事实，并覆盖陈旧的 failed slot；`ChatMessage`、`ChatImageGenerationGallery` 与 `Export` 统一消费该 fallback。若 URI 指向的物理文件确实不存在，UI 会明确显示“本地文件缺失”，不再永久 shimmer 或伪装成仍在生成。
- **提交边界修复**：`ImageGenerationTool.registerCommittedImageOrDefer` 把原子文件落盘视为付费结果已提交；后续 Room/图库登记失败只记录并交给启动 reconcile，聊天仍返回保留 assetId 的图片，不能把已付费成功降级为可重试失败。
- **恢复补强与验证**：`23877b5f` 将未登记文件和“有可靠 durable descriptor 才可升级”的 legacy placeholder 分开查询，按 ManagedFile ID keyset 分页；早期坏文件只记录失败，不能再把后续付费结果挡在固定 256 项窗口之外。JVM sidecar 回放测试与 Android Room/仪器测试源码编译通过，生产查询测试覆盖 placeholder 二次升级和前置失败后的后续恢复；仍需在生产包数据上执行“pale.3 原地升级—查看旧图—生成—杀进程—重启—再次编辑”。

- **结论**：聊天图片工具把结果保存到临时工具目录，但该目录在每次 `Application.onCreate` 时递归删除；Room 中的消息仍保留文件 URI，形成已持久化引用指向已删除文件。
- **证据**：`app/.../data/ai/tools/ImageGenerationTool.kt:293-312` 写入 `FileFolders.TOOL_OUTPUTS`；`app/.../RikkaHubApp.kt:71-75,137-143` 启动时执行 `cleanupToolOutputs()`；`Conversation.kt:37-42,142-153` 会把工具输出图片 URI 纳入会话文件引用。
- **实际影响**：重启后历史消息出现破图，导出/同步记录与文件系统不一致；连续编辑失去参考原图。
- **推荐处理方式**：生成完成后原子迁移到受管理的持久媒体目录，消息只保存稳定 `assetId`；清理器只删除具备 TTL 且无引用的临时文件，并增加“生成—重启—查看/编辑”集成测试。
- **工作量**：M（2–4 天，若先引入媒体资产表则 L）。
- **是否来自 upstream**：否，Fork 图片工具与启动清理组合导致。
- **是否阻塞未来功能**：是；阻塞版本链、分享、同步和长期项目文件。

### 🟡 P0-02 空工具结果与聊天中断可触发重复执行和重复计费（代码账本完成，生产网关验收待 pale.6）

- **状态**：✅ 代码修复完成（2026-08-03，`8ff916e4`）；同包签名候选版的 PaleInk 实际付费网关验收保留为 pale.6 发布门槛。聊天图片现在按每个付费槽位持久化独立 Request/Attempt/Output，稳定 requestId、attemptId、idempotencyKey、预留 assetId 与父工具调用绑定；Provider 越过 SENT 后禁止自动重放，文件已原子落盘但 Room/MediaAsset 登记失败时只做本地精确修复，不会再次请求 Provider。
- **现网事件证据与判断**：用户提供的网关截图显示两条 `/v1/images/edits` 请求时间重叠并分别计费，而 App 最终仍显示失败。pale.3 的图片 mutation 使用共享 OkHttp client，开启 `retryOnConnectionFailure(true)`；长耗时非幂等 POST 在连接断开时可能被传输层透明重放。该时间线与截图高度一致，但没有设备网络日志，故记为**高置信根因推断**，不是已经完成的现场证明。
- **传输修复证据**：`OpenAIProvider` 的 `/images/generations` 与 `/images/edits` 改用专用 `imageMutationClient`，强制 `retryOnConnectionFailure(false)`；每个槽位携带稳定 requestId，并发送 `Idempotency-Key` 与 `X-RikkaHub-Request-Id`。本地不会再自动重放付费 POST；上游是否按幂等键去重取决于兼容网关实现，不能把请求头本身宣称为端到端保证。安全的图片 GET 下载仍可使用普通重试策略。
- **验证**：AI/App/Pale 441 个 JVM 测试通过；Pixel 10 AVD（Android 17、16KB）46 项 RequestLedger、图片恢复、工具权限和 MediaAsset 故障注入测试通过。覆盖 mutation 禁止透明重试、ready 前零请求、SENT/UNKNOWN 不重放、每槽稳定身份、文件 fsync/rename 后 Room 故障恢复、canonical URI/digest/asset 集合验真、失败槽无残留图片、任务缓存丢失重建，以及 Conversation/Tool 删除时 RUNNING、COMMITTING/NOT_SENT、COMMITTING/RESULT_RECEIVED 的分态收敛；最终独立 P0/P1 复审 READY。实际 PaleInk 网关的单请求/断网/切后台只作为发布验收，不再代表代码账本缺失。

- **结论**：工具是否执行完成由 `output.isNotEmpty()` 推断；合法的空 MCP 结果、进程在图片请求完成前死亡、或进度已持久化但最终输出未写入时，都可能再次进入待执行路径。独立图片任务的“不自动重试”保护没有覆盖聊天图片工具。
- **证据**：`ai/.../ui/Message.kt:479-495` 的 `Tool.isExecuted`；`McpManager.kt:111-125` 允许返回空 content；`GenerationHandler.kt:120-123,239-313` 根据该派生状态重新执行；`ImageGenerationTool.kt:119-203` 直接请求 Provider，绕开 `ImageGenerationTaskManager` 的 durable/no-retry 语义。
- **实际影响**：有副作用的 MCP 工具可能重复执行，图片请求可能重复扣费，且用户看不到稳定的 request identity。
- **推荐处理方式**：持久化显式状态机（queued/running/succeeded/failed/cancelled/interrupted）和不可变 `requestId/idempotencyKey`；空结果也必须可表示为 succeeded；恢复时默认要求用户确认重试。
- **工作量**：L（1–2 周，宜与统一任务账本一起做）。
- **是否来自 upstream**：混合；`isExecuted` 语义来自 upstream，Fork 聊天图片路径放大了计费风险。
- **是否阻塞未来功能**：是；阻塞可靠 Agent、后台任务、语音工具和用量审计。

### ✅ P0-03 超大消息读取异常会被静默跳过，后续保存会永久删除（2026-08-02 已完成）

- **状态**：✅ “异常后跳页并继续可写”的数据删除风险已完成止血；节点读取失败会整体中止，禁止形成残缺会话。`9f253355` 又统一按 SQLite Unicode code point 推进分块 offset，关闭 P0-20 的 emoji 边界跳字路径。
- **验证**：覆盖 256 KiB 边界 emoji、混合 CJK/emoji、精确整块、空文本和读取中节点缺失；目标 JVM 测试、Room KSP、Debug 编译通过。消息存储正规化与逐节点增量更新仍归入 P1-04，不再作为本 P0 的未完成项。
- **结论**：分页读取 MessageNode 时捕获 `SQLiteBlobTooBigException`/`IllegalStateException` 后直接增加 offset 并继续；随后更新会话会删除全部节点并重插“成功加载”的节点，跳过的大节点因此永久丢失。
- **证据**：`ConversationRepository.kt:429-468` 的分页/异常跳过；同文件 `296-305` 的 delete-and-reinsert 更新策略。
- **实际影响**：包含 Base64 图片、长工具结果、长文档的会话最容易命中；表现为无提示历史缺口，下一次编辑后不可恢复。
- **推荐处理方式**：禁止跳过后继续可写；遇到超大行进入只读损坏态并提供导出/修复。尽快把大二进制移出 JSON blob，并采用逐节点更新而非整棵树重写。
- **工作量**：短期 S（保护与告警），根治 L/XL（存储模型迁移）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；直接阻塞统一多模态、长会话和可靠恢复。

### ✅ P0-04 Web 服务启用后的默认组合可暴露会话和本地秘密（2026-08-01 已完成）

- **状态**：✅ 已完成止血修复（2026-08-01）。Web 服务默认仅绑定 loopback；请求 LAN 绑定时，只有启用 JWT 且配置非空访问密码才会放行，否则自动降级 localhost；相对路径文件接口仅允许数据库登记的 managed file，并执行 canonical root 与路径一致性校验。
- **验证**：新增 `WebSecurityPolicyTest`，覆盖 localhost 默认值、LAN 认证门槛、合法 managed file、未登记 DataStore 文件和 `..` 穿越拒绝；`:ai:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` 与 `git diff --check` 通过。
- **结论**：Web 服务默认不是 localhost-only、JWT 默认关闭；启用后绑定 `0.0.0.0` 并广播 mDNS。文件路由允许按相对路径读取 `filesDir` 下任意文件，包括 DataStore 设置文件，而非仅已管理附件。
- **证据**：`PreferencesStore.kt:238-242,569-573` 默认值；`WebServerManager.kt:53-94` 的绑定与 mDNS；`WebApiModule.kt:61-181` 的可选鉴权；`FilesRoutes.kt:117-151` 的 `filesDir` 相对路径读取；设置存于 `files/datastore/settings.preferences_pb`。
- **实际影响**：用户只要开启 Web 服务，局域网客户端就可能读取/修改聊天，并取到含 Provider key、MCP OAuth、WebDAV/S3 和 Web 密码的设置数据。该风险不依赖公网暴露。
- **推荐处理方式**：默认仅 localhost；LAN 模式强制一次性配对或强认证；文件接口改为不可猜测 `assetId` + allowlist；禁止访问数据库/DataStore/缓存/密钥目录；加路径规范化和局域网攻击集成测试。
- **工作量**：M（3–5 天）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞可信 Web 客户端、远程控制和家庭局域网使用。

### ✅ P0-05 WebDAV/S3 恢复存在 Zip Slip 与解压炸弹风险（2026-08-01 已完成）

- **状态**：✅ 已完成安全归档读取修复（2026-08-01）。WebDAV 与 S3 恢复现共用 `SafeBackupArchive`：统一校验 entry 路径/名称，限制 10,000 项、单项 512 MiB、总展开 2 GiB、压缩比 200:1，设置文件另限 16 MiB；所有文件目标执行 canonical containment，并先写同目录临时文件再替换，异常时删除临时产物。
- **验证**：新增合法归档、Zip Slip、单项展开上限与 canonical 越界测试；`:app:testDebugUnitTest` 目标测试及两条恢复链路编译通过。数据库一致快照、全局 staging 与原子切换已随后在 P0-06 完成。
- **结论**：恢复端直接遍历 ZIP，没有 entry 数量、单项/总解压大小、压缩比和允许路径规则；`upload/` 条目拼接到目标目录但未做 canonical containment 校验。
- **证据**：`WebDavSync.kt:216-327,275-295`；`S3Sync.kt:193-313` 的重复实现。
- **实际影响**：恶意或损坏备份可覆写目标目录外文件、耗尽磁盘/内存，或让恢复处于半完成状态。
- **推荐处理方式**：统一安全归档读取器：先 manifest 校验，再以 canonical path 限制根目录，设置 entry/bytes/ratio 上限，只允许明确目录；全部解压到 staging，验证后再切换。
- **工作量**：M（3–5 天）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞可靠导入导出与跨设备同步。

### ✅ P0-06 备份与恢复直接复制正在使用的 Room DB/WAL/SHM，且无事务切换（2026-08-06 完整收口）

- **完成情况（2026-08-06）**：WebDAV/S3 已删除两套 live DB/WAL/SHM 直接复制与在线覆盖实现，统一通过 `BackupRestoreCoordinator` 对当前 Room 数据库执行受控 checkpoint + `VACUUM INTO`，新备份只携带一个独立一致快照。恢复阶段仅将允许项解压到 `noBackupFilesDir` 的持久化 staging，完成设置反序列化、SQLite header、重复 entry、长度与 SHA-256 manifest 校验。`f42fcf5c` 又将整段启动恢复移入异步 bootstrap：Room、正常 UI、Workspace/OAuth 与后台消费者在文件和设置共同提交且 runtime activation 成功前保持关闭；同一 pending identity 每进程只做一次 payload hash。
- **崩溃一致性**：文件事务使用 durable `APPLYING / ROLLING_BACK / ROLLBACK_FAILED / FILES_APPLIED` 状态；回滚失败保留 journal，重启必须先幂等完成回滚。回滚成功和设置提交都先将 active transaction/pending 原子改名为不可重新解释的 GC 目录，删除仅为 best-effort。SafeMode 是独立 Gate 终态，不具备 Room 或正常 runtime 权限；用户确认离开后先安排显式冷启动、同步提交 crash marker 清除，再结束当前进程，禁止半初始化进程直接进入聊天或恢复付费请求。
- **验证（2026-08-06）**：一致快照的 3 个 JVM 故障注入与 16 KB Android 17 模拟器 2 个设备测试继续有效；新增聚焦测试覆盖 rollback failure/interruption、journal 保留、commit rename/部分 GC、verified-session identity、Ready-after-activation、SafeMode 隔离及冷重启操作顺序/失败。相关 JVM 测试、`BackupAppFilesTest`、Debug/Unit 源码和 `compileDebugAndroidTestKotlin` 均通过；三轮独立 P0/P1 复审最终结论 READY。OEM Alarm 投递与 pale.4→pale.6 同包 fresh restore 仍保留为发布后真机验收，不将其冒充为自动化证据。
- **结论**：备份直接复制 live DB 三件套；恢复先写设置，再顺序覆盖数据库、WAL、SHM 和文件，过程中 Room 仍可能打开，没有 checkpoint、关闭、staging、校验、回滚或原子提交。
- **证据**：`WebDavSync.kt:134-165,226-270`；`S3Sync.kt` 对应重复路径。
- **实际影响**：备份可能不是一致快照；网络/进程中断可形成混合版本的设置、数据库和附件，最坏导致数据库损坏或消息引用错位。
- **推荐处理方式**：通过 Room/SQLite backup API 或受控 checkpoint 生成快照；恢复必须暂停写入、关闭 DB、验证 schema/manifest/hash，在同一 staging 根完成后原子切换；失败自动回滚。
- **工作量**：L（1–2 周）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；是可靠同步、迁移与灾难恢复的前置条件。

### ✅ P0-07 秘密会进入明文云端备份与 Android Auto Backup（2026-08-02 止血完成）

- **状态**：✅ 默认导出脱敏、Auto Backup 边界、恢复输入强制脱敏、owner/scope 绑定和自定义键默认秘密均已完成。`328309e3` 关闭 Google 认证模式/目标切换、URL user-info/fragment 与跨 owner/scope 重绑；`6b3c2ffc` 对所有网络 URL 无条件剥离 query，并保持 nullable OAuth endpoint 与本地 URI 的原语义。
- **验证**：真实 `Settings`、Provider/Model/Assistant、自定义 Header/Body、MCP Pair/OAuth、WebDAV/S3/Web 密码、owner 重排、远端独有 owner、nullable endpoint 和本地 URI 往返测试通过；独立安全复核未发现剩余 P0/P1，目标 sync JVM、KSP 和 Debug 编译成功。凭据迁移至 Android Keystore 和用户显式选择的加密 secrets 包仍属于 P1-06 Credential Vault，不再作为本 P0 的止血阻塞项。
- **结论**：备份无条件序列化完整 Settings，其中包含 Provider API key、MCP client secret/token、WebDAV/S3 密码/secret。Manifest 同时允许 Android 自动备份，而 API 31 data extraction rules 仍是空模板，没有排除 DataStore/数据库/附件中的敏感数据。
- **证据**：`WebDavSync.kt:144-148` 及 S3 对应逻辑；`ProviderSetting`、`McpConfig.kt:24-34` 的秘密字段；`AndroidManifest.xml:50-55` 的 `allowBackup=true`；`res/xml/data_extraction_rules.xml:1-19`。
- **实际影响**：备份文件或系统云备份泄露即可获得多个第三方服务凭据；用户没有“包含秘密”的明确知情与加密选项。
- **推荐处理方式**：密钥迁移到 Android Keystore 支持的 secret store，业务设置只保存引用；导出默认排除秘密，需显式选择并使用 AEAD + KDF 加密；完善 Auto Backup 排除/迁移策略。
- **工作量**：L（1–2 周，含迁移）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞跨设备同步、分享和企业/团队场景。

### ✅ P0-08 生产日志可记录请求正文、鉴权头、剪贴板与工具参数（2026-08-01 已完成）

- **状态**：✅ 已完成集中止血修复（2026-08-01）。Provider 不再记录请求/响应/SSE 正文、图片 Base64 或远端图片 URL；工具日志不再记录 arguments，剪贴板日志只保留字符数；通用 HTTP 请求日志不再复制 body，只记录内容类型/长度，并集中移除全部 query 值、仅放行安全 header；常驻 OkHttp HEADERS 日志器已移除。
- **验证**：新增 `HttpLogSanitizerTest`，覆盖 query secret、Authorization/API key header 与请求正文不进入日志；请求日志继续保留 UUID、方法、无 query URL、耗时、HTTP 状态和错误类别；目标 JVM 测试通过，危险日志模式复扫无正文命中。
- **结论**：多个 Provider 无条件记录完整请求/响应结构，工具执行记录 arguments，剪贴板工具记录文本；可选 HTTP 拦截器还会复制整个 body 和全部 headers，且未统一脱敏。
- **证据**：`ClaudeProvider.kt:124,172`、`GoogleProvider.kt:241`、OpenAI Chat Completions/Responses/Image Provider 的 request logging；`GenerationHandler.kt:290`；`ContextUtil.kt:71-73`；`RequestLoggingInterceptor.kt:9-68` 与 `DataSourceModule.kt:202`。
- **实际影响**：prompt、Base64 图片、个人文件、Authorization/API key、MCP 参数可能进入 Logcat、Crashlytics breadcrumb 或用户导出的诊断日志；大 body 还会放大内存峰值。
- **推荐处理方式**：默认只记录 requestId/provider/模型/耗时/字节数/状态；集中式 header/body redaction；Debug 详细日志必须显式短时启用且限制大小；任何剪贴板和附件正文永不记录。
- **工作量**：M（3–5 天）。
- **是否来自 upstream**：主要来自 upstream。
- **是否阻塞未来功能**：是；阻塞隐私模式、企业使用和安全诊断。

### ✅ P0-09 兼容 Provider 返回的图片 URL 可造成 SSRF 与内存耗尽（2026-08-01 已完成）

- **状态**：✅ 已完成安全下载止血修复（2026-08-01）。兼容 Provider 图片 URL 默认仅允许 HTTPS；只有 Provider 本身配置为 loopback 时才允许 loopback HTTP 开发例外。每一跳均限制重定向次数、重新校验 URL 与 DNS 结果并固定已校验地址，拒绝 loopback/link-local/private/CGNAT/metadata/multicast/IPv6 ULA；只接受明确图片 MIME，响应上限 25 MiB，并以受限流读取替代无界 `bytes()`。
- **验证**：新增 HTTP/HTTPS、localhost 开发例外、私网/CGNAT/metadata/IPv6 ULA、MIME 与流式字节上限测试；`:ai:testDebugUnitTest` 目标测试与编译通过。完整文件型 `MediaAsset` 流水线仍归入 P1-02。
- **结论**：图片响应 URL 被任意 OkHttp 下载，未限制 scheme/host/重定向/private IP/MIME/Content-Length/总字节数，随后一次性 `bytes()` 再转 Base64。
- **证据**：`OpenAIProvider.kt:315-334,348-365`。
- **实际影响**：恶意或被劫持的兼容 Provider 可探测局域网/metadata endpoint；超大响应可造成 OOM。自定义 Provider 场景使此风险现实可达。
- **推荐处理方式**：只允许 HTTPS（明确的 localhost 开发例外）；解析并阻断 loopback/link-local/private/metadata 地址及跨域重定向；限制 MIME、长度和像素；流式写临时文件并校验后原子提交。
- **工作量**：M（3–5 天）。
- **是否来自 upstream**：否，位于 Fork 图片结果处理增量。
- **是否阻塞未来功能**：是；阻塞安全的统一媒体下载与兼容 Provider 扩展。

### ✅ P0-10 旧生成 Job 的 completion 可清空新 Job 引用（2026-08-01 已完成）

- **状态**：✅ 已完成（2026-08-01）。新 Job 先取得所有权，再取消旧 Job；completion 使用 `MutableStateFlow.compareAndSet`，仅当前 owner 可清空引用。
- **验证**：新增旧 Job 延迟退出的确定性交错测试，`:app:testDebugUnitTest` 通过。

- **结论**：设置新 generation job 时先取消旧 job，但每个 job 完成回调都会无条件把 `_generationJob` 设为 null；旧 job 较晚完成即可覆盖新 job 的所有权状态。
- **证据**：`ConversationSession.kt:72-80`。
- **实际影响**：UI 可能误判“未生成”，新请求失去 stop/cancel 入口，session 可被错误回收；用户再次发送时增加并发请求风险。
- **推荐处理方式**：用 generation token/Job identity 做 compare-and-set，仅当前 owner 可清空；为 cancel/start/old-finally 交错增加确定性协程测试。
- **工作量**：S（0.5–1 天）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞可靠后台任务、实时语音和多会话并发。

### ✅ P0-11 付费图片请求可能在前台服务真正就绪前开始（2026-08-02 已完成）

- **状态**：✅ 已完成（2026-08-02）。任务在 Provider 调用前等待 task-scoped service-ready acknowledgement；共享 FGS 以最新 `startId` + `stopSelfResult` 防止旧任务停止新任务，并记录当前 service instance 实际拥有的 taskId。服务异常销毁只中断该实例拥有且仍 active 的任务；一个任务启动失败不会误停其他任务。
- **验证**：覆盖“未 ready 不请求”“ready 失败零请求”“同一服务只中断自己拥有的任务”；强制单测/编译/Debug APK 与 Lint 通过。真机后台限制、通知拒绝与多任务交错仍需设备验收。

- **结论**：任务持久化后调用 `startForegroundService`，随即发起 Provider 请求；该调用只启动服务进程，不保证服务已成功 `startForeground`。通知权限/服务启动失败时，manager 不感知，请求仍继续。
- **证据**：`ImageGenerationTaskManager.kt:38-82`；`ImageGenerationForegroundService.kt:53-67`。服务销毁也不取消 manager 任务，`EXTRA_TASK_ID` 未形成 task/service 握手。
- **实际影响**：后台限制下请求可能被系统杀死，或在用户认为已停止时继续计费；README 所述跨页/后台能力存在窄但关键的生命周期缺口。
- **推荐处理方式**：建立 service-ready acknowledgement；只有进入 foreground 后才启动网络阶段；服务启动失败时任务转 blocked/failed 并不扣费；明确 service destroy 与 task owner 的策略。
- **工作量**：M（2–4 天）。
- **是否来自 upstream**：否，Fork 后台图片链路。
- **是否阻塞未来功能**：是；阻塞统一后台任务中心和长时语音/Agent。

### ✅ P0-12 背景文本任务会静默跨 Provider 回退（2026-08-01 已完成）

- **状态**：✅ 已完成默认安全策略（2026-08-01）。存在明确模型来源时只在同一 Provider 内解析；没有来源时仅允许唯一 ready Provider 自动承接，多 Provider 场景返回未解析而不静默跨家。
- **验证**：新增双 Provider 隔离测试，`:app:testDebugUnitTest` 通过。未来若提供跨 Provider opt-in，必须配合 UI 明示与 RequestLedger。

- **结论**：标题/建议的背景模型解析在首选、fallback、当前模型失败后，会遍历全部 ready Provider，再按模型名中的 mini/luna/nano 选取；用户内容可能被发送给未显式选择的另一家 Provider。
- **证据**：`PreferencesStore.kt:693-719` 的 `resolveBackgroundTextModel`；`ChatService.kt:759+、803+` 的标题/建议调用。
- **实际影响**：形成隐私、数据驻留和费用边界惊喜；不同 Provider 的 system/tool 语义也可能导致不可复现结果。
- **推荐处理方式**：默认仅在同 Provider 内回退；跨 Provider 必须显式 opt-in 并展示目标；将选择决策写入 request ledger 和用量页面。
- **工作量**：S/M（1–3 天）。
- **是否来自 upstream**：否，Fork 背景模型策略。
- **是否阻塞未来功能**：是；阻塞可信自动路由和能力探测。

### ✅ P0-13 新发现的 MCP 工具默认启用且无需确认（2026-08-01 已完成）

- **状态**：✅ 已完成最小安全修复（2026-08-01）。新发现的 MCP 工具默认禁用且需要确认；已由用户明确授权且 schema 未变化的工具保留原权限；同名工具 schema 变化时自动禁用并恢复为需要确认。
- **验证**：新增 `McpToolSecurityPolicyTest`，覆盖新工具安全默认、未变化工具保留显式权限、schema 变化撤销旧授权；目标 JVM 测试通过。
- **结论**：MCP tool 默认 `enable=true`、`needsApproval=false`；服务重新发现工具后自动合并，随后生成循环可直接执行。对文件、网络或外部系统有副作用的工具没有安全默认值。
- **证据**：`McpConfig.kt:53-59`；工具合并逻辑 `499-510`；`ChatService.kt:567-576`；`GenerationHandler` 的自动执行路径。
- **实际影响**：被攻陷/升级后的 MCP server 可新增高风险工具，模型提示注入可触发实际副作用，用户未获得逐工具或逐调用知情。
- **推荐处理方式**：新工具默认 disabled 或 ask；权限按 server/tool/resource/action 分级，参数预览后确认；批准记录带版本和 scope，工具 schema 变化自动撤销授权。
- **工作量**：L（1–2 周，最小安全默认修复为 S）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞可信 Agent 和更多工具生态。

### ✅ P0-14 Claude thinking 能力被粗粒度标记，部分模型会收到不兼容参数（2026-08-01 已完成）

- **状态**：✅ 已完成协议矩阵修复（2026-08-01）。Fable/Mythos 使用 always-on adaptive（OFF 时省略 `thinking`）；Claude 4.6+ / Sonnet 5 使用 adaptive + 可支持的 effort；旧模型使用 `enabled + budget_tokens`，预算保证至少 1024 且小于 `max_tokens`；4.6 的 xhigh 自动降为 high，现代 adaptive-only 模型不再发送采样参数。
- **验证**：新增 `ClaudeProviderThinkingTest`，覆盖 Opus 4.8、Sonnet 4.6、旧 Sonnet 3.7、Fable 5、legacy OFF 及多个现代模型 fixture；目标 `:ai:testDebugUnitTest` 通过。
- **结论**：从 Claude 3.5 到 4.8 均被统一标记为 reasoning，Provider 在 AUTO/开启时一律发送 `thinking.type=adaptive`；但 Anthropic 的 adaptive thinking 是新模型能力，旧模型使用 extended/manual 或不支持该字段。
- **证据**：`ModelRegistry.kt:188-245`；`ClaudeProvider.kt:314-339`。官方协议说明见 [Extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking) 和 [API errors](https://platform.claude.com/docs/en/api/errors)。
- **实际影响**：旧/代理 Claude 模型可稳定返回 400；“模型支持推理”的布尔值无法表达参数模式、预算和签名约束。
- **推荐处理方式**：按具体 model/API surface 建模 `manual/adaptive/none`；未知模型保守不发 thinking 参数或先探测；加入 fixture contract tests，并评估 upstream PR #1616 的 thinking signature 修复。
- **工作量**：M（2–4 天）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞可靠自动选模、兼容 Provider 和长工具链。

### ✅ P0-15 arm64 `libtermux.so` 不满足 16 KB page size（2026-08-01 已完成）

- **结论**：Release APK 内的 arm64 `libtermux.so` 来自 `com.termux.termux-app:terminal-emulator:0.118.0`，LOAD segment 仅 4 KB 对齐；Lint 明确报 3 个 `Aligned16KB`。
- **证据**：Release APK/native 检查与 Lint；Android 官方要求见 [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)。App 已 target 37，不能把它视为遥远兼容问题。
- **实际影响**：16 KB 设备上 Workspace terminal 可能无法加载或应用无法正常启动，并影响后续 Play 发布合规。
- **推荐处理方式**：升级/重编 terminal-emulator native 依赖为 16 KB 对齐版本；在 CI 对所有 arm64 `.so` 做 `llvm-readelf`/APK Analyzer gate，并至少在 16 KB emulator 验证启动与终端。
- **完成情况（2026-08-01）**：Workspace 自编译的 `termux_pty.cpp` 已作为最终 APK 中的 `libtermux.so`，两个仓库 native target 显式使用 `-Wl,-z,max-page-size=16384`；新增统一 Release/CI 门禁，逐个检查 APK 内所有 arm64 ELF 的 `PT_LOAD.p_align` 并执行 `zipalign -P 16`。重新构建的 universal Release 中 14 个 arm64 `.so` 全部通过，APK `libtermux.so` 与仓库构建后的 stripped 产物 SHA-256 一致。Android 17 `google_apis_ps16k` 模拟器确认 `PAGE_SIZE=16384`，设备测试成功加载 `com.termux.terminal.JNI` 并解析、调用 native `close` 符号。
- **工作量**：M/L（取决于上游依赖可用性）。
- **是否来自 upstream**：是（依赖基线）。
- **是否阻塞未来功能**：否，但阻塞目标设备兼容与正式发布。

### ✅ P0-16 Release 流程不能在干净、离线可诊断的门禁下复现（2026-08-01 已完成）

- **结论**：默认 Release 因 Crashlytics mapping 上传超时而失败，跳过该外部任务后同一源码完整打包成功。Fork 发布脚本只跑 `:app:testDebugUnitTest`，不跑全模块测试/Lint；仅显示 dirty 状态却继续执行，且使用 `git add -A`，可能把无关本地文件带入发布提交。
- **证据**：实际 `:app:assembleRelease` 与跳过 `uploadCrashlyticsMappingFileRelease` 的 A/B；`ops/release/release.ps1:119-126,149-165`；daily workflow 只执行 `assembleRelease`。
- **实际影响**：外网短暂故障阻塞构建；模块测试/Lint 红灯不阻止发布；脏工作区可能污染 tag。脚本的签名/digest/feed 校验虽强，但前置质量门禁不足。
- **推荐处理方式**：拆分 deterministic build 与可重试的 symbols upload；发布前强制 clean tree、全模块 test、Lint 基线/门禁、16 KB 检查；显式 stage allowlist，禁止 `git add -A`；上传失败保留制品并报告独立状态。
- **完成情况（2026-08-01）**：`ops/release/release.ps1` 与 daily workflow 已统一执行全模块 `test`、`lint`、确定性 universal Release 构建和完整 16 KB native/APK 检查；Release 脚本只允许发布公告在构建前处于修改状态，提交阶段仅显式暂存版本文件与归档公告，并二次核对 staged allowlist；Crashlytics symbols upload 已从构建结论中拆出，失败只报告可重试警告，不再丢弃已验证制品。PowerShell AST 解析与发布门禁静态断言均通过。随后在 Windows 发布机实跑总门禁时发现的 `/bin/sh` 硬编码与 highlight fixture CRLF/LF 漂移也已修复：Host runner 现按 Android/Linux/Windows PATH 选择 shell，语言 golden 比较统一换行符，截断测试去除 awk 方言依赖；本机 `gradlew test` 全模块通过。Lint 采用版本化 `app/lint-baseline.xml` 隔离 105 个既有 error、275 个 warning 与 2 个 hint，同时保持 `abortOnError=true`、`checkDependencies=true`，因此新增问题仍会阻断门禁；新增的备份恢复实现与测试均未进入基线，本机 `gradlew lint` 全模块通过。
- **补充完成（2026-08-02）**：发布与更新站点流程的 5 个续跑/合同修正已独立提交为 `07ac4c1f chore(release): make publication resumable`。pale.4 首次发布在签名 APK 和本地 tag 完成后遇到 GitHub push 连接重置；脚本保留 49,990,985-byte APK 与 SHA-256 收据，随后 `-Phase Publish` 复用完全相同制品完成 GitHub Release、签名 feed v2 原子切换和公网回读，真实验证续跑合同。Crashlytics symbols 独立阶段因 Google 443 连接超时失败，但没有阻断或改变已验证 APK，待网络恢复后单独重试。
- **pale.6 实证（2026-08-06）**：`b1986666` / `v2.4.5-pale.6` 由同一脚本一次完成签名构建、16KB 对齐、release commit/tag/master push、GitHub Release、更新站 staging→live 与公网回读；远端 45,227,105-byte APK 和 SHA-256 与本地收据一致。独立 Symbols phase 再次仅因 `firebasecrashlyticssymbols.googleapis.com:443` connect timeout 失败，证明符号上传故障不会污染、替换或回滚已经验证的制品。
- **pale.6 补充（2026-08-05）**：`0f5f6911` 新增显式且 fail-closed 的 `-TargetRevision`，允许本次从已发布 pale.4 直接产生 pale.6，而不是制造虚假的 pale.5 中间提交；目标必须严格前进，Android `versionCode` 仍只加 1，本地 versionName/versionCode 必须与线上 stable feed 同时匹配，Publish 恢复也必须与已准备修订一致。PowerShell 解析及默认 +1、显式前跳、恢复匹配/拒绝回退合同均通过。
- **工作量**：M（2–4 天）。
- **是否来自 upstream**：否，Fork 发布链路。
- **是否阻塞未来功能**：否，但阻塞可信持续发布。

### ✅ P0-17 删除会话会把图库原图一并删除（2026-08-07 ownership gate 完整收口）

- **ID 和优先级**：P0-17 / P0。
- **结论**：旧清理链把消息中可解析的本地文件 URI 直接交给物理删除。生成图片同时是跨会话“图片库”的长期资产，因此删除对话不应等同于删除相册原图。
- **文件/类/函数证据**：修复前入口为 `FilesManager.deleteChatFiles()`；修复提交 `d9eefc83` 在 `FilesManager.kt` 增加删除策略并由 `FilesManagerDeletionPolicyTest` 覆盖 `images/` 与 `chat_generated_images/`。
- **实际影响**：删除对话、分支或历史消息可能让图库数据库仍有记录但原文件永久消失，也会破坏 Fork/连续编辑的共享引用。
- **推荐处理方式**：已实现双重门禁：图库/资产长期目录不进入会话清理；仍在临时目录的历史文件只要被活动 MediaAsset 的 path 或 managedFileId 持有，也一律拒绝删除。启动 materializer 先按稳定 assetId 原子复制并提交新 replica/asset path，再允许临时目录清理；只有显式资产生命周期与引用/墓碑合同才有权进入 GC。
- **工作量**：S（止血已完成）；M（v27 引用计数/GC）。
- **是否来自 upstream**：部分来自 upstream 的会话附件清理语义，生成图库长期资产语义来自 Fork。
- **是否阻塞未来功能**：止血后不阻塞；可靠同步、Fork 与版本链仍要求 v27 的显式引用。
- **验证**：新增“合法 managed upload 仍因 durable asset ownership 被拒删”JVM 护栏；普通附件/历史图库搬运的 Room relocation AndroidTest 已通过 Kotlin 编译；全模块 JVM test、Debug APK 和 App Lint 最终通过。仍建议在下一候选 APK 做同包升级后的删除会话/保留资产真机回归。

### ✅ P0-18 原始 file URI 删除缺少受管边界（2026-08-02 已完成）

- **ID 和优先级**：P0-18 / P0。
- **结论**：旧逻辑在 canonical containment 和数据库登记校验前即可删除 App 能访问的 file URI，安全边界大于“本应用拥有的附件”。
- **文件/类/函数证据**：`FilesManager.deleteChatFiles()`；`d9eefc83` 后仅接受 canonical 后位于 `upload/` 或 `tool_outputs/`、且路径/目录/ManagedFile 身份全部匹配的普通文件。
- **实际影响**：恶意导入、损坏消息或错误 URI 可能删除非预期文件；数据库行与物理文件也可能发生不可逆不一致。
- **推荐处理方式**：保持默认拒绝；MediaAsset v2 的删除只能接收 host-owned `fileId/blobId`，不得接受任意路径作为删除能力。
- **工作量**：S，已完成。
- **是否来自 upstream**：是，Fork 的生成资产扩大了影响面。
- **是否阻塞未来功能**：否；底层能力化删除仍应在 v27 收口。
- **验证**：外部路径、未登记 upload、目录/身份不匹配拒绝测试及合法受管 upload 删除测试通过。

### ✅ P0-19 FILES 备份遗漏生成图片目录，恢复会形成 DB/文件断裂（2026-08-02 已完成）

- **ID 和优先级**：P0-19 / P0。
- **状态**：✅ `ed6ad71e` 已完成。两后端改为共用 `BackupAppFiles` 的递归 allowlist，统一包含 `upload/skills/fonts/images/chat_generated_images`；恢复入口只接受明确允许根，继续由 `SafeBackupArchive` 与 manifest 校验 canonical path、文件长度和 SHA-256。
- **结论**：WebDAV 与 S3 的 `FILES` 归档当前只枚举 `upload/`、`skills/`、`fonts/`；Room 快照已经包含 MediaAsset/消息引用，却没有打包 `images/` 与 `chat_generated_images/` 的原图。
- **文件/类/函数证据**：`WebDavSync.prepareBackupFile()` 与 `S3Sync.prepareBackupFile()` 的 FILES 分支；`BackupRestoreCoordinator` 负责归档 manifest/恢复切换。
- **实际影响**：用户在新安装恢复后会看到图库/聊天引用存在但图片文件全缺失，表现与本轮“旧图变成未完成”高度相似，而且源设备丢失后不可恢复。
- **推荐处理方式**：在现有安全归档合同中以共享 allowlist 递归加入两类目录；manifest 记录长度/hash，fresh-install 恢复后逐项核验。后续 MediaAsset v2 改为由 replica manifest 枚举 blob，避免目录清单继续分叉。
- **工作量**：S–M。
- **是否来自 upstream**：备份实现来自 upstream；生成目录与资产关系来自 Fork。
- **是否阻塞未来功能**：是，阻塞 pale.6 发布、灾难恢复和 Sync v2。
- **验证**：`BackupAppFilesTest` 10/10 通过，覆盖两类生成图片目录、嵌套文件、两后端路径一致、未知根与路径越界拒绝；`:app:compileDebugKotlin` 成功。仍需在 pale.6 发布门禁中完成同包签名 fresh-install WebDAV/S3 round-trip。

### ✅ P0-20 超大 Unicode 消息的分块 offset 混用 UTF-16 与 SQLite code point（2026-08-02 已完成）

- **结论**：修复前 `readChunkedText` 用 SQLite `substr` 按 Unicode code point 取块，却用 Kotlin `String.length`（UTF-16 code unit）推进下一块 offset；`9f253355` 改为用 `codePointCount` 推进，并拒绝返回超出请求码点数的异常 loader。
- **证据**：`ConversationRepository.readChunkedText`；ConversationStore v2 backfill 使用同样的 code-point 合同并额外核对预期块长度。
- **实际影响**：超过分块阈值且包含 emoji 的 message-node JSON 可能被拼成损坏 JSON；当前保护会阻止残缺写回，但用户看到整场会话读取失败，迁移也会把记录隔离而非正常升级。
- **推荐处理方式**：已实施；后续 ConversationStore v2 reader 切换必须保留同一合同，不再复制第三种分块实现。
- **工作量**：S，已完成。
- **是否来自 upstream**：是，Fork 的 P0-03 分块止血实现交界。
- **是否阻塞未来功能**：否；Room 28 backfill 与 pale.6 的该项阻塞已解除。
- **验证**：`ConversationRepositoryChunkedReadTest` 覆盖 256 KiB 边界 supplementary character、超大混合 CJK/emoji、精确整块、空文本和中途缺行；定向 JVM 测试、KSP 与 Debug 编译通过。

### ✅ P0-21 pale.4 普通聊天切后台后长连接被中断，但服务端仍继续计费（2026-08-05 技术修复完成）

- **ID 和优先级**：P0-21 / P0。
- **完成提交**：`331085b5 fix(chat): keep generation alive in shared foreground service`。
- **结论**：pale.4 的普通文本/工具生成 Job 虽运行在 `AppScope`，但 `ChatNotificationManager` 只发布 ongoing 通知，没有启动 Android Foreground Service；切后台数分钟后 OEM/Android 可终止客户端 socket，网关仍完成请求并计费，App 最终显示 `Software caused connection abort`。当前已改为发送 admission 同步建立精确 owner 并启动共享 FGS，该聊天回复/工具 continuation 的 Provider dispatch 必须先通过 ready barrier。
- **文件/类/函数证据**：`ChatService.launchReplacingGenerationJob` 在 lazy Job attach/Room 消息准备前调用 `ChatGenerationForegroundController.start`，Job 内 `awaitReady` 后才进入主回复/工具循环；`ChatGenerationForegroundRegistry` 保存 owner→exact Job cancel authority 并保证取消至多一次；`ChatGenerationForegroundService.startTextOwner/startImageOwner` 共用同一 `specialUse` FGS，START 即使遇到 owner 已释放也先用安全 fallback notification 完成 promote；`ChatNotificationManager.handleGenerationUpdate` 只更新共享 owner projection，不再创建第二个伪前台通知。自动标题/建议使用独立 metadata owner，在主 owner 释放前完成 handoff。
- **实际影响**：修复前会出现“服务器已生成且收费、客户端却报失败”，用户可能手动重试并再次计费；快速发送后立刻 Home/锁屏还可能因启动 FGS 太晚而 fail-before-dispatch。延迟的旧通知取消动作若按 conversationId 查当前 Job，也可能误杀同会话的新请求。
- **推荐处理方式**：已实施 admission-time start、dispatch-time ready、exact Job cancellation、owner handoff、空 owner promote fallback 和 no-replay 语义；保留 RequestLedger 作为进程死亡后的 durable authority，绝不因 socket/进程中断自动重放 SENT 请求。发布前再用同包生产签名 APK 对 PaleInk 做“发送后立即 Home/锁屏 5 分钟—返回—核对单 requestId/单账单/完整正文”的最终验收。
- **工作量**：M，技术修复已完成。
- **是否来自 upstream**：主要来自 upstream 普通聊天通知/生成生命周期；Fork 的图片 FGS 先行实现使两条链路的不一致更明显。
- **是否阻塞未来功能**：否；本项代码阻塞已解除。它是后台任务中心、长工具调用和实时语音前必须保留的统一 owner/ready 合同。
- **验证**：`ChatGenerationForegroundRegistryTest`、`ChatGenerationForegroundPolicyTest` 与 `ChatImageGenerationTaskCoordinatorTest` 全部通过；Debug/AndroidTest Kotlin 编译通过；Pixel 10 AVD（Android 17、16 KB page size）真实 Manifest/Koin/Service 执行 ready、销毁精确取消、release-before-promote、owner handoff 共 4/4，通过前曾由故障注入真实捕获 `ForegroundServiceDidNotStartInTimeException`，修正后清空 logcat 重跑，无 `RemoteServiceException`、FGS timeout 或 FATAL。尚未把付费生产网关实测冒充为自动化验证。

## 6. P1 架构发现

### ✅ P1-01 图片生成存在两套不等价的任务系统（2026-08-02 已完成）

- **状态**：✅ 第二套生成系统已删除。`ImgGenPage`、`ImageGenerationTaskManager`、`ImageGenerationTaskStore` 和旧 `ImageGenerationForegroundService` 已移除；抽屉只进入 `ImageLibraryPage`。为旧导航状态保留的 deprecated `Screen.ImageGen` 会直接展示图片库，不能发起生成。
- **当前实现证据**：`ImageGenerationTool.execute` 在付费请求前创建 `ChatImageGenerationTaskRecord`；`ChatImageGenerationTaskCoordinator.begin/updateProgress/complete/fail/cancel/interruptActive` 是唯一 durable image owner；共享 `ChatGenerationForegroundService` 负责 image task/text owner-scoped ready、通知、取消和异常销毁；`ImageGenerationTaskExecutor` 是统一 Provider attempt executor；`ImageLibraryPage`/`ImgGenVM` 仅投影 MediaAsset。
- **验证**：同 requestId/attempt 去重、每个付费槽位独立账本、ready 前零请求、恢复只中断不重放、按 service instance 中断、多图进度、通知取消、文件/Room 故障修复和 orphan parent 收敛测试通过；Manifest/DI 已无旧服务/manager/store 注册。AI/App/Pale 441 个 JVM 测试及 Pixel 10 16KB AVD 46 项定向仪器测试通过。`5f8b645c` 又以参数化合同覆盖 1–8 张：每个数量都进入相同的独立 `n=1` 槽位路径，非法数量拒绝而非静默裁剪，Provider custom body 不能覆盖 `n`/`model`/`prompt`/图片字段；AI/App 两组目标测试与 Debug 编译通过。
- **原始结论**：独立图片页曾使用 durable manager/FGS/store，而聊天图片工具直接调用 gateway，导致两条路径的取消、恢复、通知、存储和防重试合同不同。
- **原始证据**：已删除的 `ImageGenerationTaskManager.kt`、`ImageGenerationTaskStore.kt`、`ImageGenerationForegroundService.kt` 与旧 `ImgGenPage.kt`，对比当前聊天统一链路。
- **实际影响**：任何修复都需做两遍；用户从不同入口发起同一类工作会获得不同保证。继续增加视频、深度研究、Agent 或长文件处理时会复制第三、第四套任务状态。
- **推荐处理方式**：建立统一 `TaskRecord + RequestLedger + TaskExecutor`；聊天、独立页和通知只是 presentation adapter。所有付费/长时请求共享 request identity、取消、恢复、进度、产物和用量语义。
- **完成情况（2026-08-05）**：聊天保留 1–8 槽、参考图和渐进展示，同时完整接管 durable task、FGS 和 MediaAsset 登记；每个槽位是独立付费身份和独立 `n=1` 请求，1–8 个槽位均进入同一客户端调度波次（底层传输与 Provider 仍可自行限流）。每次完成会在持有状态锁时发布不可变 projection，避免并发完成乱序造成 UI 从“已显示两张”倒退到“一张”或合并成整批刷新。大型 Base64 响应的解析、落盘与登记最多同时处理 2 份，既不阻塞 Provider 生成，也避免 8 张同时回包导致堆内存峰值。离开页面不取消，进程死亡明确 interrupted 且不自动重试。图片库作为跨会话相册独立保留，但没有请求执行权。未来“后台任务中心”只需消费同一 task projection，不再创造第三套 owner。
- **工作量**：XL（2–4 周，可分阶段迁移）。
- **是否来自 upstream**：否，Fork 增量与 upstream 生成循环交界形成。
- **是否阻塞未来功能**：否；图片任务基座已统一。通用 Agent/工具账本仍由 P1-07 负责。

### ✅ P1-02 多模态附件仍是 URL 字符串，而不是稳定资产（2026-08-07 materializer 完成）

- **状态**：✅ 普通 Image/Video/Audio/Document 已统一接入 MediaAsset v2。新消息在首次持久化前完成长期文件提交和稳定 assetId 绑定；生成结束再收口 assistant/tool 输出；启动阶段先迁移无对话依赖的临时目录资产，再分页回填 READY 会话。
- **已完成证据**：Room 25→26 以原表原位演进为 `MediaAssetEntity`，增加稳定 `assetId`、managed file 外键、MIME/尺寸/字节、origin/visibility、conversation/toolCall 上下文与 parent/version；`Migration_25_26` 为旧 `GenMedia` 行生成稳定身份；`GenMediaRepository`（兼容别名 `MediaAssetRepository`）统一登记、隐藏、恢复和文件/数据库 reconcile；`FilesManager.resolveManagedFile` 对受管理根做 canonical guard；启动恢复覆盖旧 `images/`、新 `chat_generated_images/` 及 file-only/row-only 状态。
- **消息接入**：`UIMessagePart.Image/Video/Audio/Document` 均带可选 `assetId`；ConversationStore v2 part 行和精确 `message_media_ref` 会保存四类资产身份，包括嵌套 Tool output/progress。Provider 仍消费兼容 URL，但 URL 已重绑定到 host-owned 长期 replica。
- **v2 实现证据（`993d63fb`、`0041f698`、`fce9585c`）**：`managed_files.file_id` 为永久 UUID/legacy identity，DAO 不再以 `REPLACE` 改写数字主键；新增 `media_blob`、`media_asset_blob`、`media_replica`、`media_relation`、`message_media_ref`、`media_migration_journal`。运行时复用 migration 的 nullable-hash blob identity；ConversationStore writer 在同一 Room 事务内写精确 `(conversationId,branchGroupId,messageId,partId,assetId)`，全量 READY 扫描以 revision/digest/CAS 对账后才完成 journal 并退役 coarse ref；异常与未解析生成路径会使 GC fail closed。多参考图 lineage、窄字段 reconcile CAS、typed deferred 删除和物理删除失败保留 identity 均已覆盖。
- **实现证据**：`MediaAssetMaterializer` 以 messageId + nested part location 生成可恢复身份，对普通附件执行 temp+fsync+atomic move 的流式复制，Room 事务提交 ManagedFile/Blob/Replica/Asset 与 relocation journal；Conversation 成功持久化后才清理被替代的临时源文件，失败时保留旧源；生成结束的整场扫描逐项容错，历史缺失附件不能把已付费成功的新回复降级成“生成失败”。`FilesManager` 的单项删除、批量清理和启动 tool-output 清理都先查询 MediaAsset ownership；`BACKUP_APP_FILE_ROOTS` 已包含 `library_attachments/`。资产库 UI 分为“图片/普通附件”，缺文件会明确显示且禁用分享/保存。
- **验证**：四类 part assetId 的 ConversationStore codec 与递归 media projector JVM 测试、资产删除门禁测试通过；Attachment relocation/非图片 metadata 的 AndroidTest 编译通过；全模块 JVM test、Debug APK 与 App Lint 最终通过。新增 6 个资产库文案由 Lint 门禁发现并经 `locale-tui` 补齐俄/韩/日资源；本轮没有执行设备测试。
- **剩余结论**：消息兼容层仍保留 URL，因此 Provider 侧尚未全面改成基于 replica 的零拷贝流式 multipart；这属于性能/协议适配演进，不再是资产身份与删除一致性缺口。
- **实际影响**：删除对话不再决定资产文件生死；普通附件可跨会话引用、进入备份、按 content digest 去重，并为同步/语音/视频建立统一身份基础。
- **推荐处理方式**：下一步将 Provider encoder 的 URL 读取集中到 AssetInputResolver，直接从 replica 流式 multipart，逐步禁止大附件 Base64 进入消息 JSON。
- **工作量**：核心项已完成；剩余 Provider 零拷贝优化为 M/L。
- **是否来自 upstream**：是；Fork 图片能力使问题变得紧迫。
- **是否阻塞未来功能**：否；稳定资产身份前置已解除，可靠跨设备同步仍需 Sync v2 的 replica manifest/冲突合同。

### ✅ P1-03 Provider 能力模型无法表达真实协议矩阵（CapabilitySnapshot v1 已完成）

- **状态**：✅ Provider/UI 接管已完成。`CapabilitySnapshot` v1 可表达 TEXT/IMAGE/AUDIO/VIDEO/DOCUMENT 输入输出、图像生成/编辑、工具、推理策略及 7 类 API surface；`CapabilityOverride` 固化 replace → add → remove（remove 最终胜出）语义并对未来 schema fail-closed。
- **当前实现证据**：OpenAI Chat Completions/Responses、Gemini、Claude adapter 在组装协议前读取 `effectiveCapabilitySnapshot`；`ChatService` 的工具 gating，以及 `ChatInput`、`FilesPicker`、`ModelList`、`SearchPicker`、`SettingProviderDetailPage`、`OcrTransformer` 的能力展示/开关统一读取快照。旧 `ModelType/Modality/ModelAbility` 保留为持久化与 upstream 兼容输入，不再是主要决策源。
- **验证**：snapshot 序列化/推导/override、registry、Claude thinking、Gemini media/tools、OpenAI Responses message fixtures 与强制 `ai/app` 单测通过；Debug 构建与 Lint 通过。
- **原始结论**：能力曾主要由 CHAT/IMAGE/EMBEDDING、TEXT/IMAGE 和少量 TOOL/REASONING 标志加模型名正则推断，无法稳定表达真实协议矩阵。
- **实际影响**：Provider 与 UI 现在共享同一版本化决策快照，新增模型的错误组合风险显著下降；未知兼容 Provider 仍依赖 catalog/用户 override，尚无在线 probe。
- **推荐处理方式**：v2 增加 Provider discovery/probe、来源时间戳、置信度、上下文/文件/速率限制和服务端 catalog 刷新；probe 失败必须保持保守能力，不静默扩大权限。
- **工作量**：L/XL（2–4 周）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：否（v1 基座）；自动探测与实时能力协商仍是下一阶段增强项。

### ✅ P1-04 会话以整棵消息树 JSON blob 重写，状态源也在持续增加（2026-08-03 已完成）

- **状态**：✅ Room 28 已增加会话内复合 message/part identity、真实 parent FK、branch group、migration journal/quarantine、无外键 delete outbox 与 revision CAS；`95e58733` 将生产 reader/writer 切到 v2，`8d03be27` 统一 durable FTS outbox，`6c261345` 完成会话级 persistence mutex、精确 lease/idle close、generation epoch、删除 tombstone、可逆删除/撤销/最终清理、删除事件传播、字段级 metadata patch 与 revision CAS；`fce9585c` 又在同一 writer 事务内维护精确媒体引用，并为启动/恢复提供 durable backfill/CAS 对账。Chat/UI/Web/Folder 的保存和删除入口均已收口到 `ChatService`。
- **结论**：MessageNode 内含候选消息和复杂 UI parts，Room 更新常以“删全部节点—重插—全量 FTS”处理；生成状态同时存在于 `ConversationSession`、ViewModel、消息 Tool part、Room 和独立 task store。
- **证据**：`ConversationV2Writer`、`ConversationMetadataPatch`、`ConversationSession`、`ChatService`、`ConversationRepository`、`ConversationV2DAO`、`ConversationV2ShadowProjector`、`ConversationV2BackfillCoordinator`、`MessageFtsOutboxProcessor`、`ConversationMediaReferenceIndexer`；生产读写、元数据更新、文件夹变更、删除/撤销、UI/Web 生命周期、FTS 与生成图片 ownership 已共用同一会话 owner。
- **实际影响**：长会话写放大、SQLite 单行过大、流式更新频繁序列化，崩溃恢复时难判定哪个状态为权威。P0-03 已证明会造成数据风险。
- **推荐处理方式**：把节点、message revision、content part、tool run、asset ref、request run 正规化；流式内容写 append/checkpoint，完成后 compact；定义 Room 中的 durable state 为唯一真值，UI 只做 projection。
- **工作量**：XL（4–8 周，需渐进迁移）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞超长会话、版本历史、任务中心和可靠同步。

### 🟡 P1-05 WebDAV/S3 是“全量快照上传”，不是可靠同步协议（2026-08-04 基座完成）

- **状态**：🟡 Sync v2 foundation 已由 `e497a0d3` 完成，但面向用户的跨设备同步尚未启用。新增 protocol v2 稳定 Replica/Space/Operation ID、dotted version/version vector、HLC、tombstone acknowledgement、canonical JSON envelope 与 AES-GCM E2EE；Room 30 增加 replica/head/outbox/conflict，counter/head/outbox 单事务提交并按 space/epoch 隔离；WebDAV/S3 共用 `ObjectSyncTransport`，只有真实 `If-None-Match`、成功 CAS、stale CAS 与持久读取全部通过才允许使用。vault secret、active request 和设备私有 permission 在协议实体 allow-list 中不可表示。业务实体 projector、合并 worker、设备撤销/管理和用户 UI 仍属后续产品接线，因此现有 ZIP 继续明确称为灾难恢复，不能宣称已经上线可靠同步。
- **验证**：App JVM 316/316、Pale JVM 36/36；Android 17／16KB AVD 上 Room 29→30、26→30、跨 space FK 与事务失败回滚共 6/6；App Lint 0 error；首次独立复审发现的跨事务 counter、space/epoch 污染、weak ETag、探针漏测成功 CAS、fresh/migration schema 漂移 5 项 P1 均已修复，最终复审 READY。

- **结论**：当前已有备份/恢复能力，但没有稳定实体 ID、变更日志、设备/快照 identity、冲突检测、墓碑、附件 manifest 或加密 envelope，不能等同于跨设备同步。
- **证据**：`WebDavSync`/`S3Sync` 的 ZIP 全量打包与整库覆盖；没有 per-entity sync metadata 或 merge engine。
- **实际影响**：两台设备并行修改只能 last-writer-wins/整库覆盖，易丢数据；备份变大后上传耗时和流量随全部历史增长。
- **推荐处理方式**：先修 P0-05/06/07；再建立 snapshot manifest + hash 校验作为安全备份 v2，随后以 stable ID、change journal、tombstone、冲突副本和端到端加密演进为同步 v1。
- **工作量**：XXL（安全备份 2–3 周；真正同步 2–4 月）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞跨设备可靠同步和团队/项目场景。

### ✅ P1-06 秘密与普通 Preferences 共用一个可序列化聚合体（2026-08-04 已完成）

- **状态**：✅ 已完成（`40b45113`）。引入 Android Keystore-backed envelope vault，Settings/DataStore 仅持久化 `vault:v1:<uuid>`；Provider、模型 Header/Body、MCP OAuth/header、Search、TTS/ASR、WebDAV、S3 与 Web 凭据均通过 canonical slot/audience 投影。每次轮换与 OAuth CAS 产生新 immutable ref，`PREPARE → ENVELOPE_VERIFIED → REFERENCES_WRITTEN → LEGACY_CLEARED` journal 覆盖普通变更、受众重绑定和进程死亡；DataStore 未提交时恢复旧 active ref。所有 UI 受众变化要求空白重输，未保存 Provider/endpoint draft 禁止测试出网。

- **结论**：Provider key、同步凭据、MCP OAuth 与普通 UI/行为设置共存于 Settings/DataStore，导致备份、Web 文件服务、诊断和迁移很容易整体暴露秘密。
- **证据**：`PreferencesStore` 与 Settings serializer；`ProviderSetting`、`McpConfig`、WebDAV/S3 设置字段；P0-04/P0-07 的可达路径。
- **实际影响**：每个读取“设置”的功能都被迫承担 secret handling；无法实现可审计的凭据轮换、设备级密钥或仅同步非秘密设置。
- **推荐处理方式**：引入 `CredentialRef` 和 Keystore-backed vault；Provider/MCP/同步配置保存非秘密 metadata，实际 secret 按作用域隔离，访问通过窄接口且不实现通用序列化/日志输出。
- **验证**：App 全量 306 项 JVM 测试零失败；Pixel 10 AVD（Android 17、16KB）Credential、聊天、图片、工具 43 条路径经首轮 42/43 加修正项复跑后全部通过；Android Lint 0 error、26 warning（baseline 外）；三轮独立 P0/P1 审查最终 READY。故障注入覆盖 DataStore 写失败、PREPARE/index crash window、普通 rotate、OAuth CAS、批量 audience rebind、Keystore locked/corrupt、图片后台 A/B key 冻结、MCP refresh 后二次 ledger ref 门禁和导出脱敏。
- **工作量**：L（1–3 周，含迁移与恢复策略）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞隐私模式、可靠同步和企业部署。

### ✅ P1-07 工具执行缺少统一权限、审批与审计账本（2026-08-03 已完成）

- **状态**：✅ 已完成（2026-08-03）。`1c18b56c` 将普通、MCP 与 Workspace 工具统一为聊天 Provider 请求的稳定子请求；`8ff916e4` 又让聊天图片按每个付费槽位建立独立 IMAGE_GENERATION Request/Attempt/Output，并与父 `generate_image` invocation、FGS task、MediaAsset 和 Conversation projection 对齐。每次调用具备稳定身份、PermissionGrant、输入/能力/结果摘要、lease heartbeat、付费/副作用边界和冷启动 reconcile；SENT 后无精确持久结果进入 UNKNOWN_OUTCOME，禁止自动重放。
- **结论**：Workspace 有自己的权限/执行约束，MCP 有 enable/approval 字段，Provider built-in search 又是另一套能力；调用确认、参数变更、重试、副作用和结果 lineage 没有统一模型。
- **证据**：`McpConfig`/`GenerationHandler`、Workspace tools、各 Provider built-in tool 构建逻辑；P0-02/P0-13。
- **实际影响**：同样的“读文件/写文件/联网”在不同工具来源下安全体验不同；无法回答“谁在何时批准了哪个参数，是否重复执行”。
- **推荐处理方式**：统一 `ToolDescriptor/ToolInvocation/PermissionGrant`；权限按 capability + resource scope + effect 分类；所有调用进入 request ledger，危险动作默认 preview/confirm，恢复重试必须重新核对副作用。
- **验证**：AI/App/Pale 441 个 JVM 测试及 Pixel 10 AVD（Android 17、16KB）46 项 RequestLedger/图片恢复/工具执行/MediaAsset 仪器测试全部通过。除审批隔离、稳定输入身份、持久化先于成功、heartbeat、SENT→UNKNOWN、精确 `(requestId, toolCallId)` 恢复与结果篡改拒绝外，还覆盖图片文件已提交但元数据失败、缓存丢失、失败槽残留、错误 URI、自洽但非 canonical 投影和 COMMITTING orphan 收敛；最终独立 P0/P1 复审 READY。
- **工作量**：XL（3–6 周）。
- **是否来自 upstream**：主要来自 upstream。
- **是否阻塞未来功能**：是；是 Agent、MCP 扩展和自动化的核心前置。

### ✅ P1-08 搜索来源与引用不是一等数据模型（2026-08-05 已完成）

- **ID 和优先级**：P1-08 / P1。
- **结论**：`adb1ce64` / Room 31 已把来源与消息中的引用实例拆成一等数据：全局 `citation_source` 只承载稳定来源身份/内容与显式 tombstone，`message_citation` 持有每次出现的展示快照、可用性、span、part ordinal、provenance 和安全 provider metadata。结构化搜索结果即使没有旧 marker 也会登记为 message-level citation。
- **文件/类/函数证据**：`CitationEntities.kt`、`CitationDAO.kt`、`Migration_30_31.kt`、`CitationProjection.kt`、`CitationBackfill.kt`、`ConversationV2Writer.kt`、`ConversationV2Projection.kt`；`UIMessageAnnotation.UrlCitation`；OpenAI `ChatCompletionsAPI`/`ResponseAPI` 与 `GoogleProvider`；Android `ChatMessage.kt`/`Export.kt`/`ChatUtil.kt`；Web `chat-message-annotations.tsx`/`citations.ts`/`export-markdown.ts`。
- **实际影响**：旧实现中重新生成、裁剪工具输出、分享/导出或后续同步可能让引用丢失或错配。新实现以稳定 ID、幂等投影和会话级 occurrence snapshot 消除了跨会话标题/availability 污染，并让 Android、Web、复制与导出使用同一条引用记录。
- **推荐处理方式**：已完成。继续保持 `citation_source` 的全局 tombstone 只能由显式 source 生命周期 API 修改；普通会话写入不得覆盖或复活。Deep Research/抓取正文以后只在此模型上增加 source revision/artifact，不再另建引用旁路。
- **工作量**：L，已完成。
- **是否来自 upstream**：原问题来自 upstream；Room 31 与兼容投影为 Fork 实现。
- **是否阻塞未来功能**：否；成熟搜索、可验证回答、可靠分享与 Sync projector 的数据前置已解除，Deep Research 仍需独立产品实现。
- **验证**：AI/App Citation 与 startup recovery 定向 JVM 测试通过，其中 `CitationProjectorTest` 20/20；Web 3/3、TypeScript typecheck 与目标 Oxlint 通过；Android 17 / 16KB Pixel 10 AVD 上 `CitationMigrationTest` + `ConversationV2CutoverTest` 共 28/28 通过。覆盖 30→31、稳定回放、跨会话隔离、tombstone、物理秘密擦除、定时 lease/backoff 恢复与 TOCTOU fence、Unicode/percent URL 幂等、每消息 metadata/quote 总预算、300 occurrence 分批水合、流式 digest 和 writer 权威回读；独立 P0/P1 复审结论 READY，`git diff --check` 通过。

### ✅ P1-09 更新 feed 有 digest，但尚未形成端到端签名信任（2026-08-02 已发布激活）

- **结论**：客户端校验 HTTPS 来源、versionCode、ABI 和 SHA-256，发布端也核对 APK signer，这是强基础；但 feed 自身未签名/固定公钥，客户端下载安装前未预检 APK package/version/signer，下载重定向的最终来源也未形成独立策略。
- **证据**：Fork update manager/feed parser/download path；`ops/release/release.ps1` 的 signer/digest/staging/live 校验。
- **实际影响**：若更新服务器/CDN/feed 发布凭据被攻陷，攻击者可同时替换 APK 与 digest；最终仍可能被 Android 安装签名拦截，但用户会下载恶意/无效制品且错误难解释。
- **推荐处理方式**：签名 canonical feed（离线 release key，客户端内置公钥）；下载后先解析 package/version/signer，再调用 installer；记录最终 URL，限制跨 host 重定向；保留 last-known-good feed 和明确失败状态。
- **完成情况（2026-08-02）**：发布端以独立 RSA-3072 离线密钥签名精确 UTF-8 payload，并在上传前核对内置公钥指纹和自验签；feed 顶层兼容旧客户端/网站，新客户端只信任验签后的 `signedPayload`。客户端固定 keyId/公钥，验签后才解析版本、URL 与 digest；网络故障可回退到本机 last-known-good 签名信封，签名错误不会回退。下载前以 HEAD 解析最终 URL 并强制 HTTPS、同 host、443 端口，下载后和安装前均校验 SHA-256、package、versionName/versionCode 与永久 APK signer。pale.4 已真实激活线上 schema v2：`keyId=paleink-update-feed-rsa-2026-01`，签名与 signedPayload 均存在，版本 176、APK SHA-256 `c069a3869e836917ac475d628a27963f11341d1e3f84e4562036cc12bf3eb6ed`、49,990,985-byte 公网下载全部回读一致。
- **工作量**：M/L（1–2 周）。
- **是否来自 upstream**：否，Fork 自更新链路。
- **是否阻塞未来功能**：否，但阻塞高信任公开分发。

### 🟡 P1-10 Fork 改动仍直接穿过 upstream 高频冲突区（治理门槛已完成，持续项）

- **结论**：Fork 与 upstream 的接近度仍是可维护优势；pale.6 已把稳定合同下沉到独立 `:pale` 与 Fork-owned package，并以 fail-closed manifest/CI/Release verifier 管住跨界触点，但聊天、Provider、Room、资源和核心 UI 的必要集成点仍属于长期同步热点。
- **证据**：`06c4d0f1` 将共同祖先 `8349ef25…` 到 pale.6 核心架构的 295 个 integration touchpoint 固定为有序集合；`4a6fa970` 在滚动热修、隐私收口、渐进多图、启动恢复门禁和性能修正完成后重算为 391 项、hash `80e50c7b…274271`。相较 295 项基线新增 96、移除 0，均可归因到已独立审查提交；未登记路径变化会让 CI/Release 直接失败。
- **实际影响**：若继续在热点文件加入产品逻辑，未来同步会从机械冲突变成语义冲突；大 PR 难以回馈 upstream，也难 bisect。
- **推荐处理方式**：继续把 manifest 更新限制为独立提交；新能力优先新 module/package + 窄接口；把通用 bugfix 单独提交并 upstream-first；每次 upstream sync 刷新 overlap report，并要求合同测试与语义冲突复审后才能更新 hash。
- **工作量**：首轮治理已完成；后续为每次 upstream sync 的持续成本。
- **是否来自 upstream**：否，Fork 治理问题。
- **是否阻塞未来功能**：是；不处理会显著抬高所有后续能力的长期成本。

### ✅ P1-11 缺文件的 nullable-hash 历史资产无法完成 MediaAsset v2 reconcile（2026-08-02 已完成）

- **结论**：26→27 migration 为无 hash 资产使用 `legacy-media-blob-<assetId>`，post-open repository 却使用另一套 name-UUID；当文件仍缺失、probe 也得不到 hash 时，DAO 拒绝 nullable blob 到另一个 nullable blob 的 identity 变更并回滚。
- **证据**：`Migration_26_27` 的 synthetic blob ID；`GenMediaRepository.buildGraphRegistration`；`GenMediaDAO.commitReconciledAsset` 的 blob replacement 前置条件。
- **实际影响**：受影响 asset、replica 与 migration journal 每次启动都会重试失败，历史图库永久停在半迁移状态。
- **推荐处理方式**：`0041f698` 已实施：运行时优先复用现有 ORIGINAL mapping 的 blob identity；只有得到已验证 SHA 后才合并到 content-addressed blob。
- **工作量**：S/M，已完成。
- **是否来自 upstream**：否，Fork MediaAsset v2 增量。
- **是否阻塞未来功能**：否；该升级阻塞已解除。
- **验证**：26→27 migration→post-open reconcile 串联测试覆盖缺文件/null hash 与后续 hash 合并；Android 17／16KB 仪器测试通过。

### ✅ P1-12 `message_media_ref` 在缺少 message/part 权威时被提前标记完成（2026-08-03 已完成）

- **结论**：Room 27 只能写 conversation/node/tool 的粗粒度 legacy ref，却可能把 `reference_backfill` 标成 complete；新生成的有 hash 资产甚至不一定创建待回填 journal。
- **证据**：`Migration_26_27.seedMediaMigrationJournal`；`GenMediaRepository.registerGeneratedAsset/buildGraphRegistration`；现有 ref 的 `message_id/part_id` 为空。
- **实际影响**：Fork/重用后的真实引用可能不存在；未来删除原会话时若信任该状态会误删仍被其他消息使用的图片，若永久保守则形成不可回收泄漏。
- **推荐处理方式**：`0041f698` 先让 coarse ref 永久 pending 并阻止 hard delete/GC；`fce9585c` 已完成 Room 28 全分支/嵌套 Tool 精确引用、稳定 owner/ref ID、revision/source/reference digest 双重验证、journal epoch CAS、启动/恢复调度和权威扫描后的 legacy-v1 退役。普通上传不冒充图库资产，缺失生成图库路径继续 fail closed；Fork 共享稳定 asset，普通附件则同步复制登记，失败中止并回滚。
- **工作量**：M，已完成。
- **是否来自 upstream**：否，Fork MediaAsset/ConversationStore 交界。
- **是否阻塞未来功能**：否；可靠删除与 Fork 的图片 ownership 前置已解除，普通多模态附件迁移仍归 P1-02。
- **验证**：App 全量 JVM、Debug/AndroidTest 编译、Lint 通过；Android 17/16KB AVD 29/29 项；独立复审 READY。

### ✅ P1-13 多参考图生产调用未写入完整 `REFERENCE_INPUT` lineage（2026-08-02 已完成）

- **结论**：图片工具把全部参考图路径发送给 Provider，但登记生成资产时未传这些来源；`parentAssetId` 又只接受单一参考图，因此两图及以上编辑会留下零条 lineage。
- **证据**：`ImageGenerationTool` 的 `selectedReferences/referencePaths` 与 `GeneratedMediaAssetRegistration` 构造；`GenMediaRepository` 仅从 registration source 建 relation。
- **实际影响**：用户连续编辑、版本链、来源解释、导出和同步无法还原实际输入；Provider 已收费并使用参考图，但本地永久丢失 provenance。
- **推荐处理方式**：`0041f698` 已按 UI 选择顺序传递所有可识别 asset/managed source，并幂等写有序 `REFERENCE_INPUT`；单父字段仅保留兼容投影。
- **工作量**：S/M，已完成。
- **是否来自 upstream**：否，Fork 聊天生图增量。
- **是否阻塞未来功能**：否；完整版本链输入 provenance 已具备。
- **验证**：JVM 测试覆盖多参考图顺序、去重和 source fallback；MediaAsset 仪器测试通过。

### ✅ P1-14 Media reconcile 可用陈旧整行覆盖并发用户元数据（2026-08-02 已完成）

- **结论**：candidate 读取和文件 probe 在事务外，之后用旧 entity 做全行 update；只比对 assetId，没有 revision/updatedAt CAS，可能覆盖 probe 期间发生的 visibility/lifecycle/privacy 修改。
- **证据**：`GenMediaRepository.reconcileAsset` 的事务边界；`GenMediaDAO.commitReconciledAsset` 与 entity `@Update`。
- **实际影响**：启动恢复、图库隐藏/删除或隐私范围变更并发时，后台 reconcile 可撤销用户操作，造成状态回退和同步错误。
- **推荐处理方式**：`0041f698` 已改为只更新文件探测拥有的窄字段并以 expected `updated_at` CAS；并发 metadata 变化时拒绝陈旧覆盖并留待下一轮 reconcile。
- **工作量**：M，已完成。
- **是否来自 upstream**：否，Fork MediaAsset v2 增量。
- **是否阻塞未来功能**：否；该并发覆盖阻塞已解除。
- **验证**：仪器测试覆盖 probe 后并发 visibility/lifecycle 更新不被覆盖，以及无竞争时 metadata 正常提交。

## 7. P2 可维护性发现

### P2-01 WebDAV 与 S3 同步核心逻辑重复

- **结论**：两份实现重复打包设置/DB/files、ZIP 遍历和恢复逻辑，安全修复必须同步两处。
- **证据**：`WebDavSync.kt` 与 `S3Sync.kt` 对应的 prepare/upload/restore 代码块近似重复。
- **实际影响**：P0-05/06/07 很容易只修一个 backend；未来增加本地导出或其他云端会继续复制。
- **推荐处理方式**：抽取 `BackupArchiveService`（manifest、snapshot、加解密、校验、恢复），WebDAV/S3 只负责对象传输和并发/重试。
- **工作量**：M/L（1–2 周）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞同步 v2 的可控演进。

### ✅ P2-02 图片结果的 MIME、模型 identity 和孤儿恢复信息不完整（2026-08-01 已完成）

- **结论**：本地结果存储固定使用 `.png`，即使返回 MIME 不是 PNG；entity 的 modelId 写入 modelName；文件成功但 DB 失败时虽保留 `recoveredImage`，却没有持久 repair queue/图库补登记入口。
- **证据**：`LocalImageGenerationResultStore.kt:39,66,79,89-104`。
- **实际影响**：文件扩展名与内容不一致，统计/重放无法稳定定位模型；崩溃后孤儿文件可能永久脱离图库。
- **推荐处理方式**：从验证后的 MIME 决定扩展；保存 providerId + modelId + displayName 快照；原子临时写/rename；建立可幂等的 media reconciliation job。
- **完成情况（2026-08-05）**：结果落盘现校验 PNG/JPEG/WebP/GIF 容器完整性并通过 Android `BitmapFactory` 做低内存实际解码探测，再按真实 MIME 选择扩展名；图片与 `.imgmeta.json` sidecar 均使用同目录临时文件、`fsync` 和原子替换，且先记录 intent 再提交图片。Room schema 25 为 path 增加唯一索引，24→25 非破坏迁移先去重并保留最早 ID；DAO 以事务性 insert-or-get 实现崩溃后 exactly-once 补登记。`providerId`、稳定 `modelId`、显示名称已完整落库/回读并兼容旧记录。`23877b5f` 又关闭迁移旧行/未 ACK sidecar 身份冲突、placeholder 无法被 durable metadata 升级和固定窗口恢复饥饿；任何失败只保留诊断与原文件，不触发 Provider replay。
- **工作量**：M（2–4 天）。
- **是否来自 upstream**：否，Fork 图片持久化。
- **是否阻塞未来功能**：是；阻塞稳定媒体库、用量和版本链。

### ✅ P2-03 Gemini custom function tools 可被 built-in tools 覆盖（2026-08-01 已完成）

- **结论**：请求构建先写 custom function declarations，启用 built-in search/code 等时再次写入 `tools`，后一次覆盖前一次，而非按协议组合/拒绝不兼容组合。
- **证据**：`GoogleProvider.kt:423-445,451-471`。Google 当前组合规则见 [Gemini tool combinations](https://ai.google.dev/gemini-api/docs/tool-combination)。
- **实际影响**：用户以为 MCP/function tool 与搜索同时可用，实际其中一类静默消失；模型行为难以解释。
- **推荐处理方式**：按具体 model/API surface 构造单一 tools 数组；不支持的组合在发送前给出明确错误或降级说明，并增加 request fixture 测试。
- **完成情况（2026-08-01）**：`GoogleProvider` 现只构造一次 Gemini `tools` 数组，function declarations、Google Search 与 URL Context 可同时保留；generateContent 无法表达的 `ImageGeneration` built-in 会在发送前明确抛错，不再静默丢弃。新增请求体回归测试验证三类可组合工具均存在及不支持组合显式失败，完整 `:ai:testDebugUnitTest` 通过。
- **工作量**：S/M（1–3 天）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞统一工具能力建模。

### ✅ P2-04 Gemini 媒体与 system 内容转换存在有损默认值（2026-08-01 已完成）

- **结论**：Audio/Video MIME 被固定为 `audio/mp3`/`video/mp4`，多个 system text 用默认 `joinToString`（逗号分隔）拼接，未保留原始 part 边界和 MIME。
- **证据**：`GoogleProvider.kt:358-364,691-703` 及媒体 part mapping。
- **实际影响**：WAV/M4A/WebM 等附件可被错误声明；system prompt 语义被额外逗号改变，跨 Provider 一致性下降。
- **推荐处理方式**：MIME 必须来自资产 metadata/探测；定义明确的 system-part 拼接合同（通常 `\n\n`）并通过 golden request 测试覆盖。
- **完成情况（2026-08-01）**：Gemini adapter 现按 part metadata、data URL 声明、URL/文件扩展名和安全默认值的顺序解析真实 Audio/Video MIME，支持 WAV、M4A、MP3、WebM、OGG、AAC、FLAC、MOV、AVI、MKV 等常见格式；未知类型的诊断不包含原始路径。多个 system text 固定以 `\n\n` 分段。新增请求 JSON 回归测试，目标测试及完整 `:ai:testDebugUnitTest` 均通过。
- **工作量**：S（1–2 天）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：是；阻塞音频/视频多模态。

### ✅ P2-05 PaleInk 合并与就绪判断依赖启发式（2026-08-01 已完成）

- **结论**：Provider 合并以 base URL 等字段识别，可能把用户创建的等价兼容 Provider 视为 PaleInk；就绪主要依赖 API key，对自定义 header、本地无鉴权端点等场景表达不足。
- **证据**：PaleInk Provider 初始化/merge、background model readiness 与 `PreferencesStore` 相关逻辑。
- **实际影响**：自动配置可能改写用户意图，背景模型选择出现意外；品牌 Provider 与普通 OpenAI-compatible 的身份边界不稳定。
- **推荐处理方式**：为内建 Provider 使用稳定 `providerKind/managedBy` 标识；base URL 仅用于迁移提示，不作身份；就绪由 Provider adapter 返回结构化诊断。
- **完成情况（2026-08-01）**：稳定 `managedBy`、不依赖 Base URL 的合并和结构化 readiness 已落地；canonical/marker 重复项会确定性合并，分享、扫码导入、推荐项复制和 Provider override 都会转成 user-owned copy 并清除 marker。OpenAI、Google、Claude 的最终鉴权 Header 合成与 readiness 规则已对齐：用户鉴权头优先、空白/重复头被规范化、无 key 时不注入空 Bearer/API key；Google generate/stream 真实路径已接入统一 policy，Service Account 仅在 Vertex 模式且 email/private key/project/location 全部完整时 ready；IPv6 loopback 也按本地无鉴权端点处理。Provider identity/readiness、分享导入及真实请求回归测试均通过。
- **工作量**：M（2–4 天）。
- **是否来自 upstream**：否，Fork PaleInk 增量。
- **是否阻塞未来功能**：是；阻塞可靠自动选模与配置迁移。

### ✅ P2-06 Debug Firebase 是调用路由隔离，不是二进制/初始化完全隔离（2026-08-01 已完成）

- **结论**：Debug 确实禁用生产 Google Services 处理并注入 NoOp analytics；但 Firebase dependencies 仍是 `implementation`，Debug merged manifest 仍包含 FirebaseInitProvider、Crashlytics registrar、DataTransport 及广告/安装来源权限。
- **证据**：`app/build.gradle.kts:130-136,187-189`；`AppModule.kt:95-98`；实际 merged debug manifest。
- **实际影响**：不能把当前状态表述为“Debug 包完全不包含/不初始化 Firebase”；会增加启动、体积和隐私审查不确定性，虽未证明 Debug 实际上传遥测。
- **推荐处理方式**：若产品目标是严格隔离，改为 releaseImplementation/flavor dependency，Debug manifest remove provider/permission，并用网络/初始化测试验证；README 用“业务调用隔离”或“二进制隔离”准确区分。
- **完成情况（2026-08-01）**：Firebase Analytics/Crashlytics 及 BOM 已改为 `releaseImplementation`，Analytics 工厂拆为 Debug NoOp/Release Firebase；Debug 禁用 Google Services 与 Crashlytics 注入，不再需要 `.debug` Firebase client。复审发现并修正了首版“排除 DataTransport CCT”会破坏 ML Kit/Quickie 扫码运行时的问题：现在保留 CCT artifact 与 backend discovery、Job/Alarm scheduler，只隔离应用拥有的 Firebase Analytics/Crashlytics/Measurement 产品模块、初始化器及广告/安装来源权限。自动门禁使用显式 artifact/manifest inputs，已证明可存储并复用 Gradle configuration cache；Debug 隔离门禁、App Lint、全仓 `verifyForkRelease`（437 tasks）均通过，Release 编译和新 APK 16 KB 校验通过。
- **工作量**：M（2–4 天）。
- **是否来自 upstream**：混合；Fork 已做路由隔离，依赖结构沿用 upstream。
- **是否阻塞未来功能**：否。

### ✅ P2-07 更新检查并发与错误语义不够明确（2026-08-01 已完成）

- **结论**：更新检查缺少 single-flight/mutex；部分网络/解析失败可落入与 UpToDate 相近的静默体验，用户和诊断层无法区分“已是最新”与“未检查成功”。
- **证据**：Fork update checker/state mapping、页面触发路径与 DownloadManager 恢复逻辑。
- **实际影响**：启动与手动刷新可能并发请求；服务器故障时给用户错误信心，发布问题难追踪。
- **推荐处理方式**：明确 `Idle/Checking/Available/UpToDate/Failed/Stale`，single-flight + 缓存时间；保存上次成功检查时间和错误分类，后台失败不打扰但不得冒充最新。
- **完成情况（2026-08-01）**：新增原子 single-flight gate，启动检查与手动刷新不能并发重复请求，完成后可再次进入；远端验签成功时持久化 last-known-good feed 和成功检查时间。网络失败且存在已验签缓存时进入独立 `Stale` 状态并显示上次成功时间，首次失败进入 `Failed`，后台失败不再写成 `UpToDate`；`Available` 与 `UpToDate` 只来自本次成功的远端验签结果。UI、侧边栏 actionable 判断和重试路径已适配，single-flight、签名缓存与策略测试通过。
- **工作量**：S/M（1–3 天）。
- **是否来自 upstream**：否，Fork 自更新。
- **是否阻塞未来功能**：否。

### ✅ P2-08 测试与 CI 没有跨平台、全模块质量门禁（2026-08-01 已完成）

- **结论**：Windows 上 33 个测试失败；daily 只构建 Release，Fork 发布脚本只跑 app 单测。Lint 当前 105 errors，也没有可控 baseline/新增错误门禁。
- **证据**：本轮 Gradle XML 统计；`.gitattributes`；`WorkspaceShellRunner.kt:25-35`；GitHub workflows 与 `release.ps1:149-154`。
- **实际影响**：开发机、CI、发布脚本对“通过”的定义不同；真实回归容易藏在长期红灯中。
- **推荐处理方式**：Linux + Windows matrix；fixture 强制 LF；shell runner 按平台/测试注入 shell；先建立审计过的 Lint baseline，再禁止新增 error；发布依赖同一 aggregate verification task。
- **完成情况（2026-08-01）**：新增仓库级 `verifyForkRelease`，统一聚合所有模块的 JVM test 与 Android Lint；Quality Gate 使用 Linux 全量门禁 + Windows PowerShell/shell/换行敏感合同，并由独立 required job 汇总结论。Daily Build 将无 secret 的 verify 与持有发布 secret 的 publish 拆成两个 job，只允许 master 的 schedule/manual 发布；scheduled build 可按 workflow + 精确 SHA 复用成功质量门，查询失败会安全回退重验。发布 secrets 仅通过 step environment 注入，使用 `printf` 原样写入，避免密码/JSON 被 shell 二次解释。发布脚本、daily 与 required gate 已统一启用可复用的 configuration cache，PowerShell AST/权限边界合同通过；本机 App Lint、437-task 全仓门禁、当前源码 Release APK 与 16 KB 校验均通过。`v2.4.5-pale.3` release commit `6cb3eb61` 的 GitHub Quality Gate 已由 Linux 全量与 Windows 平台 lane 汇总为 success，远端证据闭环。
- **工作量**：M（3–5 天）。
- **是否来自 upstream**：主要来自 upstream，Fork 发布门禁亦有责任。
- **是否阻塞未来功能**：是；没有绿基线就无法安全做架构迁移。

### P2-09 缺少覆盖 DB/文件/恢复一致性的故障注入测试

- **结论**：独立图片 task store 有较好的状态测试，但没有覆盖“文件写完 DB 失败后重启修复”、聊天图片重启、Room 大 blob、备份中断、schema 不兼容、ZIP 恶意条目和 live DB 恢复的集成测试。
- **证据**：现有 `ImageGenerationTaskManager`/store tests 与聊天工具、同步模块测试覆盖对比；本轮发现多条异常路径无守护。
- **实际影响**：最昂贵的数据损坏问题只能由用户真实数据触发；重构时无法证明语义未退化。
- **推荐处理方式**：建立 deterministic fault-injection harness：可控文件/DB/网络失败、进程死亡点、旧 schema fixture、恶意 ZIP corpus；把“不重复计费、引用不悬空、恢复原子”写成合同测试。
- **工作量**：L（1–2 周，持续扩充）。
- **是否来自 upstream**：混合。
- **是否阻塞未来功能**：是；阻塞媒体资产迁移与同步重写。

### 🟡 P2-10 upstream 的小型高价值修复尚未形成筛选合并队列（Responses #1646 已吸收）

- **结论**：Fork 当前没有落后，但 upstream 未合并 PR/Issue 中仍有与本审计直接相关的修复：PR #1616 Claude thinking signature、#1613 搜索工具互斥、#1577 聊天生成前台服务；同时 #1628 是 335 文件的 Agent 大重构，不宜现在跟进。2026-08-05 已以独立提交 `09874926` 吸收 #1605，未传 stdin 的 Host/Proot 子进程现在立即收到 EOF，并为 Proot 固定非交互环境；`3d97d9e4` 吸收 #1646 的 Responses 工具上下文修复，并补强为每条 stream 独立的 `item_id → call_id` 映射，避免并行乱序完成串到错误工具或 RequestLedger identity。
- **证据**：2026-08-01 的 upstream PR/Issue 快照；相关 issue 还包括 #1598 stop failure、#1591 generated content deleted、#1569 repeated search、#1550 MCP reconnect tools、#1538 MCP 重复初始化、#1508 Workspace 图片工具结果、#1435 Gemini/MCP 结构、#1380 sync、#1140 interrupted response loss。
- **实际影响**：没有显式 watchlist 容易重复实现或错过低冲突修复；盲目合入大 PR 又会吞没 Fork 边界。
- **推荐处理方式**：每周维护 upstream radar：优先复核/回馈 #1605、#1616、#1613；#1612 用量统计需先审 DB migration；#1577 与统一任务账本协同而不是直接叠加；暂缓 #1628。
- **工作量**：S（建立流程），每周约 1–2 小时。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：否，但显著影响重复劳动和合并成本。

### ✅ P2-11 旧媒体 hard-delete API 与 v2 relation FK 语义冲突（2026-08-02 已完成）

- **结论**：`media_relation.related_asset_id` 使用 `ON DELETE NO ACTION` 保护版本链，但旧 `deleteMedia` 仍直接 hard-delete asset，已有关系时只会把 SQLite FK 异常暴露给上层。
- **证据**：`Migration_26_27` relation FK；`GenMediaDAO.deleteMedia`；现有 DAO 测试未建立 relation。
- **实际影响**：图库删除行为依数据关系而随机失败，调用方无法区分“受保护引用”与数据库故障。
- **推荐处理方式**：`0041f698` 已让引用、入向关系或 pending reference journal 存在时进入 `delete_pending/hidden` 并返回 `DEFERRED_REFERENCED`；只有安全判定通过才 hard-delete。
- **工作量**：S/M，已完成。
- **是否来自 upstream**：否，Fork v1/v2 兼容边界。
- **是否阻塞未来功能**：部分；阻塞可靠图库删除和 GC。

### ✅ P2-12 物理文件删除失败仍会移除数据库 identity（2026-08-02 已完成）

- **结论**：`File.delete()` 的 false 结果被忽略，随后 managed file row 仍被删除、replica 被标 missing，真实文件变成未登记 orphan。
- **证据**：`FilesManager` 的单文件删除路径；`ManagedFileDAO.deleteManagedFile` 的 replica/row 更新顺序。
- **实际影响**：权限或 I/O 故障时 API 误报成功并制造 DB/文件/replica 不一致，后续备份、恢复和空间统计失真。
- **推荐处理方式**：`0041f698` 已改为物理删除成功或文件已不存在后才删除数据库 identity；目录、解析失败和 `File.delete=false` 均返回失败并保留记录。
- **工作量**：S，已完成。
- **是否来自 upstream**：混合；文件管理来自 upstream，v2 replica 语义来自 Fork。
- **是否阻塞未来功能**：部分；阻塞自动 GC 与故障恢复。

### ✅ P2-13 备份 URL 清洗会把 nullable OAuth endpoint 改为空字符串（2026-08-02 已完成）

- **结论**：URL sanitizer 对 `JsonNull` 走了非字符串 fallback 并写成 `""`，改变 `McpOAuthState` 三个 nullable endpoint 的序列化语义。
- **证据**：`BackupSettingsSanitizer.sanitizeEndpointElement`；`McpOAuthState.authorizationEndpoint/tokenEndpoint/registrationEndpoint`；原测试构造 null 但未断言往返。
- **实际影响**：合法 `registrationEndpoint=null` 恢复后 scope 不同，本地 token 无法合并并触发不必要的重新授权。
- **推荐处理方式**：`6b3c2ffc` 已显式保留 `JsonNull`，并覆盖正式 encode/restore/local-secret merge 往返。
- **工作量**：S，已完成。
- **是否来自 upstream**：否，本轮安全清洗增量。
- **是否阻塞未来功能**：否，但阻塞可靠备份恢复体验。

### ✅ P2-14 网络 URL query allowlist 不能证明参数值不是凭据（2026-08-02 已完成）

- **结论**：只按 `tenant/model/project/deployment` 等参数名放行并原样保留 raw value，无法保证兼容服务没有把凭据藏在看似安全的参数中。
- **证据**：`BackupSettingsSanitizer.sanitizeEndpoint` 的 query filter/join；独立安全复审的对抗样例。
- **实际影响**：标准 token/signature 参数会被移除，但非标准 query-auth 仍可能进入便携备份；同时未知普通 query 被删除，恢复后 endpoint 可能需重新配置。
- **推荐处理方式**：`6b3c2ffc` 已对网络 URL 完全剥离 query/user-info/fragment；file/content/data/android.resource URI 通过正式备份往返保持原样。
- **工作量**：S，已完成。
- **是否来自 upstream**：否，本轮安全清洗增量。
- **是否阻塞未来功能**：否；P0-07 的该项阻塞已解除。

### P2-15 Google 凭据 scope 对未使用字段存在保守过度绑定

- **结论**：Google scope 同时收集通用 endpoint/literal 与派生 auth target；Vertex 模式可能仍受未使用 `baseUrl` 影响，API-key 模式也可能受休眠的 Service Account 字段影响。
- **证据**：`BackupSettingsSanitizer.credentialScope/googleCredentialScopeParts` 与 `GoogleProvider` 的模式化 URL/auth 分支。
- **实际影响**：无关字段变化会拒绝回填本机秘密，用户需要重新输入；不会把 secret 发往错误目标，属于 fail-closed UX 成本。
- **推荐处理方式**：本轮保留保守策略；Credential Vault 迁移后按 credential slot + 实际 auth target 存储，再安全移除冗余 scope 字段。
- **工作量**：S/M。
- **是否来自 upstream**：混合，Google 配置来自 upstream，scope 为 Fork 修复。
- **是否阻塞未来功能**：否；列入 Credential Vault 收口，不以弱化当前安全边界换 UX。

## 8. P3-Q 代码质量发现

### P3-Q-01 PaleInk 命名在代码与文档中不一致

- **结论**：代码/README 中存在 `PALENIK_*`、`Palenik` 与品牌 `PaleInk` 混用。
- **证据**：Fork 常量、Provider 文案和 README。
- **实际影响**：搜索、配置迁移和外部支持易混淆；若直接重命名序列化 key 又可能破坏兼容。
- **推荐处理方式**：显示名统一 PaleInk；内部旧 key 保留兼容 alias，新增正确命名并写迁移测试。
- **工作量**：S。
- **是否来自 upstream**：否。
- **是否阻塞未来功能**：否。

### P3-Q-02 异常处理与错误分类不一致

- **结论**：存在 broad catch、`printStackTrace`、把解析/网络/权限错误折叠为字符串或静默 fallback 的路径。
- **证据**：Provider、更新、同步、文件和会话加载的异常处理；P0-03/P2-07 是高风险实例。
- **实际影响**：用户无法获得可行动错误，日志也难以聚合；重试策略只能猜测。
- **推荐处理方式**：定义跨模块 `AppError` taxonomy（auth/rate-limit/network/protocol/storage/permission/cancelled/interrupted），保留 cause/requestId，UI 再映射文案。
- **工作量**：M/L（渐进实施）。
- **是否来自 upstream**：主要来自 upstream。
- **是否阻塞未来功能**：是；可观测性和任务中心需要它。

### P3-Q-03 Locale、Context、无障碍和 RTL 质量债务已被 Lint 量化

- **结论**：Lint 有 63 MissingTranslation、38 LocalContext resource、3 NonObservableLocale、1 ContextCastToActivity；另有 terminal 页面无障碍和 RTL 问题。
- **证据**：Lint 报告；`ChatList.kt:216`、`ChatMessageTranslation.kt:90`、`StatsPage.kt:222`、`TranslatorPage.kt:240`、`WorkspaceTerminalPage.kt:197,216`。
- **实际影响**：语言切换不刷新、非 Activity context 崩溃、读屏/RTL 体验退化；长期红灯掩盖新增问题。
- **推荐处理方式**：先修 crash/observable locale/accessibility，再审 MissingTranslation 是否应 fallback；建立 baseline 后不允许新增。
- **工作量**：M（3–7 天）。
- **是否来自 upstream**：主要来自 upstream，Fork 资源有增量。
- **是否阻塞未来功能**：否。

### P3-Q-04 CI action、依赖与资源卫生缺少周期性治理

- **结论**：Lint 还报告较新依赖、未使用/重复 icon、KTX 建议和大量 typography；GitHub Actions 多以 tag 而非 commit SHA 固定。
- **证据**：Lint 270 warnings；workflow action 引用。
- **实际影响**：供应链可复现性和 APK 体积略受影响，噪声降低审查信噪比。
- **推荐处理方式**：Action 固定 SHA 并由 Renovate/Dependabot 更新；每版本安排一次资源/依赖清理，不与功能提交混合。
- **工作量**：S/M。
- **是否来自 upstream**：主要来自 upstream。
- **是否阻塞未来功能**：否。

## 9. P3-P 性能发现

### 🟡 P3-P-01 图片/Base64 路径存在多份整块内存拷贝（边界与多图回包限流已完成）

- **状态**：✅ 聊天生成结果不再长期写入消息 Base64，验证后以稳定 assetId 原子落受管理文件并登记 Room；兼容 Provider 的远程图片下载已有逐跳安全校验与 25 MiB 有界读取；单输入、聚合请求、像素/Bitmap 峰值和生成结果 Base64 均已有 fail-fast 预算。1–8 张独立请求可以同时等待 Provider，但大型响应解析、下游落盘和 Room 登记最多并行 2 份。🟡 用户附件、Provider 请求编码与其他媒体仍存在受限但非流式的整块 Base64/ByteArray/String 拷贝。
- **结论**：`FileEncoder` 等输入路径仍会先写 `ByteArrayOutputStream` 再生成大 String；部分文件写入仍先完整 decode。Manifest 的 `largeHeap=true` 会掩盖而不是解决剩余峰值。
- **证据**：`FileEncoder` 的输入/聚合预算、OpenAI/Claude/Google 请求级预算 tracker、`OpenAIProvider.imageResponseMaterializationSemaphore`、生成图片 Base64 预检、`FilesManager` 原子落盘与 `AndroidManifest.xml` 的 `largeHeap` 兼容设置。
- **实际影响**：多图、高清编辑或长会话中同时持有 response bytes、Base64 bytes、UTF-16 String 和 Bitmap，峰值可达原文件数倍，增加 OOM/GC pause/ANR 风险。
- **量化边界（2026-08-02）**：16 MP ARGB bitmap 理论驻留约 61 MiB；当前 Base64 → ByteArray → Bitmap → PNG 路径可同时保留多份整块数据，两张并发输入仅 bitmap 就可能超过 120 MiB，尚未计 Base64 UTF-16 与编码缓冲。
- **推荐处理方式**：在 MediaAsset v2 中让 Provider multipart 从受管理 source streaming，远程输入直接限流写临时文件；UI 使用缩略图和尺寸采样；逐步禁止所有大 Base64 进入消息 JSON。
- **工作量**：剩余 M/L（1–3 周）。
- **是否来自 upstream**：混合。
- **是否阻塞未来功能**：是；阻塞视频、高清多图和长会话。

### P3-P-02 Markdown 流式渲染会反复解析，主线程仍承担初次解析与高亮

- **结论**：流式 delta 会在后台重新 parse，但初始 `remember { parseMarkdown(content) }` 可在主线程执行；代码高亮也在 Compose 渲染路径。好的一面是高亮对输入有 4096 字符上限，列表也使用稳定 node key。
- **证据**：Markdown composable `227-250`；`Highlighter.kt:19,58-68`；聊天 LazyColumn key。
- **实际影响**：超长 Markdown/频繁 token 流会形成 parse churn 和 frame drop；代码块虽有上限，整条消息 parse 仍随长度增长。
- **推荐处理方式**：按 message revision 缓存 AST，流式阶段节流（例如 50–100 ms）并只更新尾部 block；所有 parse/highlight 明确在 Default，主线程只消费 immutable render model。
- **工作量**：M/L（1–2 周）。
- **是否来自 upstream**：是。
- **是否阻塞未来功能**：否，但影响 ChatGPT 级长会话体验。

### 🟡 P3-P-03 Universal Release APK 的 native/assets 体积偏高（失联 banner 已移除）

- **结论**：pale.4 Release APK 为 49,990,985 B（47.7 MiB）。`f5a18a89` 在全仓零引用和无动态枚举后删除三张失联 banner；pale.6 universal Release APK 实测 45,227,105 B（43.13 MiB），减少 4,763,880 B（约 9.53%）。pale.4 的主要剩余体积仍来自 MuPDF/Barhopper 双 ABI、native、DEX 与 `simple_dict`，后续应按功能拆分收益评估，而不是牺牲 universal 可安装性。
- **证据**：`apkanalyzer apk summary/file-size/download-size` 与 APK ZIP 分组；当前 SHA-256 `C069A3869E836917AC475D628A27963F11341D1E3F84E4562036CC12BF3EB6ED`，versionCode 176 / pale.4 / 3 DEX。
- **实际影响**：下载/更新耗时、磁盘和安装失败率上升；未来加入 ML Kit OCR、实时语音 codec 或更多模型资源会快速突破舒适区。
- **推荐处理方式**：发布 feed 支持 per-ABI APK并保留 universal fallback；当前静态估算单 ABI 约 38–39 MiB。Release gate 暂定 universal ≤ 50 MiB、估算下载 ≤ 48 MiB，且相对已接受版本增长不得超过 1.5 MiB 或 3%；banner 已完成安全删除，后续评估字典按需资源与 MuPDF/Barhopper 依赖裁剪，先测功能覆盖，不盲删。
- **工作量**：M/L（1–2 周）。
- **是否来自 upstream**：混合，universal 发布策略来自 Fork。
- **是否阻塞未来功能**：否，但会约束离线 OCR/语音等大依赖。

### P3-P-04 后台长任务的通知、持久化和网络耗电尚无预算

- **结论**：每个图片请求/预览会更新持久状态和通知；多图由多个独立 n=1 请求并发，Provider 长连接和失败重试策略分散。没有统一的电量/网络/thermal budget 或 batching/debounce 观测。
- **证据**：`ImageGenerationTaskManager`、foreground service、聊天 `ImageGenerationTool` 的最多 2 并发请求和进度写回。
- **实际影响**：弱网、多图和切后台时可能频繁唤醒、通知更新和数据库写；没有真机数据前无法判断是否已达到问题阈值。
- **推荐处理方式**：任务账本统一记录 bytes、radio time、attempt、foreground duration；进度/通知节流；用 WorkManager 仅承载可延迟且可幂等任务，付费生成保留明确用户启动语义。
- **工作量**：M（观测 3–5 天，优化依数据）。
- **是否来自 upstream**：否，主要是 Fork 图片后台链路。
- **是否阻塞未来功能**：是；阻塞更广泛后台任务中心。

### P3-P-05 缺少可重复的真机性能基线

- **状态（2026-08-02）**：🟡 已根据真机体验报告修复“展开生成图片后快速上滑卡顿”和消息跳转按钮错误时机的代码级原因；已建立第一轮 headless AVD 冷启动/PSS 基线，但尚无可重复画廊 frame、长会话和生产真机基线，因此本项不能标记完成。
- **结论**：仓库已有冷启动 Macrobenchmark 与 57,410 行、约 6.2 MB generated profile，但只测 CompilationMode None/Partial 的 TTID，尚未调用 `reportFullyDrawn`，也没有长会话/Markdown/图片画廊 benchmark。Pixel 10 / Android 17 / 16 KB 的 Debug 空数据冷启动五次 `TotalTime` 为 5861/4596/4558/5579/4129 ms（p50 4596 ms，样本 p95 5861 ms），稳定后 `TOTAL PSS` 214,821 KiB、`TOTAL RSS` 362,672 KiB；该软件渲染 AVD 数值不可与真机阈值等同，但已证明当前启动远未形成绿色基线，需要继续用 Macrobenchmark/Release profile 拆分初始化耗时。
- **新增代码证据**：`9fef8cec` 让 `ChatList.rememberFastScrollDetector` 使用 2200/1300 dp/s 双阈值，但不再保留定时迟滞：低于退出阈值、零消费、停止或超过 80 ms 的陈旧采样立即复位；只有 `NestedScrollSource.UserInput` 能建立用户滚动会话，随后实际发生的惯性消费才可继续显示，`available` fling 预测和程序化滚动均不能点亮按钮。`MessageJumper` 停止时直接移出组合，不再播放残留退出动画。`ChatImageGenerationGallery` 移除根 `animateContentSize` 与主图 `AnimatedContent` 交叉双绘制；`ZoomableAsyncImage` 仍以稳定请求模型复用 Coil 缓存。
- **验证证据**：更新后的 `FastScrollVelocityTrackerTest` 5/5，覆盖慢速、快速后减速、200 ms 卡帧、程序化滚动、零消费和 reset；`:app:compileDebugKotlin` 与目标 App JVM 测试通过。此前 App JVM 214/214、`:app:lintDebug`（0 errors / 26 warnings）和 Debug APK 构建也通过；无窗口 AVD 已完成上述启动/PSS采样及 12 项 Room/DAO 测试，但测试数据中没有用户截图所示的展开 2K 图片长消息，仍不能证明目标真机卡顿已消失。
- **实际影响**：性能判断容易变成主观体验；`largeHeap`、Debug Firebase 组件、Markdown 和图片峰值没有回归阈值。
- **推荐处理方式**：本机已有 `Pixel_10` AVD；先以 headless 模拟器建立可重复门禁，再用生产同包签名真机确认。冻结阈值：TTID p50 ≤ 1.2 s / p95 ≤ 2.5 s；补 `reportFullyDrawn` 后 TTFD p50 ≤ 2.0 s / p95 ≤ 3.5 s；1000 节点与 8 张 2048² 图场景 jank ≤ 5%、p95 frame ≤ 24 ms、p99 ≤ 50 ms、冻结帧 0；6 GB 设备长会话峰值 PSS ≤ 350 MiB，画廊增量 ≤ 160 MiB，退出残留增量 ≤ 30 MiB。
- **工作量**：M（3–5 天建立基线）。
- **是否来自 upstream**：混合。
- **是否阻塞未来功能**：否，但应在语音/大图/Agent 上线前完成。

## 10. 已确认的工程亮点

为避免审计只呈现问题，以下能力已经达到值得保留和扩展的质量：

1. **与 upstream 零落后**：9 个 Fork 提交边界清楚，尚未形成长期分叉泥潭。
2. **独立图片任务防重复语义正确**：状态先持久化、阻止重复 start、应用级作用域、显式 cancel；恢复时把 active task 标记 interrupted 且不再次调用 Provider。
3. **独立图片任务测试方向正确**：覆盖导航离开、重复启动、恢复不重试和生成文件可读性。
4. **文件优先于 Room 登记**：数据库登记失败时保留已生成文件并返回恢复信息，避免把昂贵结果一起删除；后续只需补 repair queue。
5. **更新链已有强校验骨架**：HTTPS allowlist、versionCode、ABI、SHA-256、DownloadManager 恢复都已存在。
6. **发布端签名治理扎实**：固定 signer 指纹、staging feed、线上 feed/APK digest 回读和 draft release 降低误发布风险。
7. **Debug 路由隔离方向正确**：独立 application ID、禁用生产 Google services processing、NoOp analytics 明确降低误报生产遥测的概率。
8. **Compose 长列表有基本防线**：LazyColumn 使用稳定 key，代码高亮有长度上限，没有发现把整个消息列表替换为非惰性 Column 的退化。

## 11. 上游合并建议快照

| 项目 | 建议 | 原因 |
|---|---|---|
| PR #1605 Workspace stdin EOF | ✅ 已独立吸收（`09874926`） | 无 stdin 立即关闭管道；Host/Proot 共用合同，Workspace JVM 全量测试通过 |
| PR #1616 Claude thinking signature | 紧随 P0-14 评估 | 与 Claude 工具链正确性直接相关，但仍为 draft，需协议 fixture |
| PR #1613 built-in/external search exclusivity | 复核后合入 | 小而集中，可减少错误工具组合 |
| PR #1612 token/request statistics | 架构评审后再合入 | 产品价值高，但 20 文件/DB migration，应对齐统一 request ledger |
| PR #1611 rootfs hardlinks | Workspace 用户需要时合入 | 范围窄、clean |
| PR #1633 MCP header visibility | 可低风险跟进 | 小型安全/易用性改善，但注意不要在日志中暴露值 |
| PR #1632 local ML Kit OCR | 可选，先做体积 A/B | 隐私/离线价值高，但会增加 APK 和模型资源 |
| PR #1646 Responses tool call context | ✅ 已独立吸收（`3d97d9e4`） | 保留语义 `call_id`、并行调用先 calls 后 outputs；增加乱序/缺失 ID/消息合并测试 |
| PR #1648 AI stream refactor | 暂缓，持续观察 | 价值方向正确但跨 Provider/消费者重构面大；当前先吸收协议正确性修复，不在 pale.6 前更换整套流事件模型 |
| PR #1474 manual image compression | 等 MediaAsset 方案后吸收 | 直接合入会继续加深 URL/Base64 分支 |
| PR #1577 chat generation FGS | 暂不直接合入 | 与 Fork 图片任务重叠，宜纳入统一任务中心而非第三套 owner |
| PR #1628 Agent redesign | 暂缓 | draft/unstable，335 文件、约 +40k，当前合入会吞没 Fork 边界 |

该表已于 2026-08-02 通过 GitHub API 刷新：upstream `master` 仍为 `8349ef25`，本地为 `0 behind / 30 ahead`（实现继续提交时 ahead 数会增加）；#1605、#1613、#1612、#1633、#1632 均仍 open/non-draft，#1616、#1577、#1628 仍 open/draft，均未合并。实际吸收前仍必须锁定 PR head、复核 CI/评论并以独立 upstream-integration commit 处理。

## 12. 审计结论

建议继续以当前 Fork 为长期基座。它仍完整继承 upstream、增量提交集中，且本批已经把最容易指数增殖的图片任务、统一媒体资产和模型能力三条骨架收口：聊天是唯一生成入口；资产库以“图片/普通附件”投影跨会话长期资产；MediaAsset v2 提供稳定 asset/file/blob/replica 身份；CapabilitySnapshot 成为 Provider/UI 的共同决策源。

下一阶段不应重新增加第二入口或 Provider 特判。图片 RequestLedger、Credential Vault、Sync v2 foundation 与 Room 31 Citation 已随 pale.6 发布，**当前最具体的实施任务**是建立一次可复用的“pale.4→pale.6 同签名生产验收包”：覆盖历史图库、1/3/4/8 张部分成功与取消、普通聊天切后台 5 分钟、WebDAV/S3 fresh-restore、展开长图 frame timeline，并把结果回填为下一版本的自动化回归合同。

该验收完成后，最值得继续的架构任务是 **AssetInputResolver + Provider 附件流式输入**：让 OpenAI、Anthropic、Gemini 与兼容 Provider 都从 MediaAsset replica 读取，经统一预算、MIME 校验和流式 multipart 编码发送，逐步禁止大附件 Base64 进入消息 JSON。它能直接降低高清多图、长文档、音频和未来视频输入的峰值内存，同时不再制造 Provider 特判。
