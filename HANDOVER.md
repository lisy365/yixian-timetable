# 逸仙课表 · 维护交接说明（HANDOVER）

> 这份文档写给「下一次继续开发」的人（或 AI 助手）。读完它就能在这个环境里改代码、构建、测试、发版。
> 最近的会话已经把环境、脚手架、发布链路全部搭好，**不要重新搭**。

---

## 1. 项目位置与技术栈

| 项 | 值 |
| --- | --- |
| 项目源码 | `C:\dshproject\hita\sysu`（Android Studio 可直接打开） |
| 包名 / 版本 | `com.stupidtree.hitax.yixian`，versionName `1.0.5`，versionCode `1000500` |
| 语言 / 构建 | Kotlin 1.9.20 + AGP 8.5.2 + Gradle 8.7 + JDK 17 |
| SDK | compileSdk 35 / minSdk 23 / targetSdk 33 |
| 仓库 | https://github.com/lisy365/yixian-timetable |
| 发布产物 | 仓库内 `release/yixian-timetable-<tag>.apk` + GitHub Release 资产 |
| 版本快照（回滚用） | `C:\dshproject\hita\_versions\<tag>`，见第 9 节 |

模块：`app`（主应用）、`component`、`style`、`sync`、`user`、`theta`（θ社区，入口已隐藏但保留代码）。

中山大学教务适配层全部在 **`app/src/main/java/com/stupidtree/hitax/data/source/web/sysu/`**：

- `SysuApi.kt` —— jwxt 接口封装（请求头/Cookie/错误码）
- `SysuSession.kt` —— 统一身份认证回调与 Cookie 解析
- `SysuTimetableParser.kt` —— 课表单元格解析（键值串 / 周次 / 节次），**纯函数，可单测**
- `SysuCrawler.kt` —— v1.0.5 新增：教务信息爬虫（培养方案/大纲/教师/考试/成绩/课表）
- `SysuWebSource.kt` —— `EASService` 实现：登录校验、学期、课表、成绩、考试

新增功能所在位置：课表背景 `data/source/preference/TimetableBackgroundSource.kt` + `ui/main/timetable/panel/`；
待办 `ui/task/`（v1.0.5 起内嵌在「小工具」板块里，`TaskManagerActivity` 仍是全屏管理页）；
小工具注册表 `ui/tools/ToolRegistry.kt`；爬虫 `ui/crawler/` + `data/repository/CrawlerStorage.kt`；
主题色板 `utils/ThemePalette.kt` + `utils/ThemePaletteRegistry.kt` + `ui/settings/PopUpThemePicker.kt`；
时间表网格 `utils/TimetableGrid.kt`；
提醒通知 `utils/NotificationUtils.kt`、`utils/ReminderScheduler.kt`、`utils/AlarmReceiver.kt`、
`utils/BootReceiver.kt`、`utils/KeepAliveService.kt`、`utils/QuoteProvider.kt`、`ui/settings/`。

---

## 2. 环境已经就绪（无需重装）

| 组件 | 路径 |
| --- | --- |
| JDK 17 | `C:\dshproject\hita\_toolchain\jdk\jdk-17.0.13+11` |
| Android SDK 35 + build-tools 35.0.0 + platform-tools | `C:\dshproject\hita\_toolchain\sdk` |
| Gradle 8.7 | `C:\dshproject\hita\_toolchain\gradle-8.7` |
| Gradle 依赖缓存（约 1.5 GB，**已全部下好**） | `C:\dshproject\hita\_gradlehome` |
| Java 信任库（沙箱 HTTPS 用） | `C:\dshproject\hita\_toolchain\truststore\cacerts`（口令 `changeit`） |
| 统一构建脚本 | `C:\dshproject\hita\build.cmd`（已设好 JDK/SDK/GRADLE_USER_HOME/JAVA_TOOL_OPTIONS） |

**构建**（命令行，约 3–7 分钟）：

```powershell
cmd /c "C:\dshproject\hita\build.cmd" --no-daemon :app:assembleRelease
# 产物：C:\dshproject\hita\sysu\app\build\outputs\apk\release\app-release.apk
```

**签名**：`sysu/keystore/yixian.jks`（alias `yixian`，口令 `yixian2026`；已随仓库公开，仅用于开源分发，勿用于上架）。

### 这个沙箱的两个特殊之处（踩过的坑）

1. **HTTPS 被中间人代理解密，证书不在 JDK 默认信任库里**：
   - 命令行构建必须走 `build.cmd`（它会给 JVM 指定自定义 truststore）；直接跑 `gradle` 会报 `PKIX path building failed`。
   - Node 脚本访问外网需要 `process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"`。
   - `npm install` 要加 `--cache C:\dshproject\hita\_npmcache`（默认缓存目录不可写）。
2. **不能用 pipes 启动子进程、不能跑模拟器**（虚拟化被禁）→ 无法真机/模拟器 UI 测试，只能靠：编译、静态核验（aapt2/apksigner）、以及下面第 3 节的 JVM 测试。
3. **`build.cmd` 里额外设了 `TEMP`/`TMP` 指向 `C:\dshproject\hita\_tmp`**：
   默认的 `%LOCALAPPDATA%\Temp` 在本机对 Kotlin 编译器可能不可写，会报
   `java.nio.file.AccessDeniedException: ...kotlin-compiler-in-*.alive`。别把这个改动删掉。

---

## 3. 自动化测试（改完代码务必跑）

脚手架在 `C:\dshproject\hita\_scratch\harness`，共三类：

### (a) 离线端到端（mock 教务 + 真实生产代码）

```powershell
# 编译 harness（用 kotlinc-embeddable 直接编译生产源码 + 测试桩）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\dshproject\hita\_scratch\harness\compile.ps1"
# 启动 mock jwxt 服务并跑断言
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\dshproject\hita\_scratch\harness\run.ps1"
```

覆盖：会话/Cookie 解析、周次解析、课表单元格解析（真实 `kcmc:…;;rkjs:…;;skrq:第N周/…` 格式）、逐周合并、成绩/考试解析、待办模型、通知文案模板、提醒触发时间计算、中大官方作息表（v1.0.5 起为 **11 节**）、**教务信息爬虫**。当前 **147 项断言全绿**。

v1.0.4 起另有一个独立入口 `_scratch/harness/HarnessV104.kt`（`run.ps1` 会自动跑），
覆盖新增的两块纯逻辑：调色盘换算 `com.stupidtree.style.widgets.ColorMath`（HSV / `#RRGGBB` 解析与格式化）
与保活排期 `com.stupidtree.hitax.utils.KeepAlivePlan`（触发时刻、重排间隔、精确闹钟判定），**69 项断言全绿**。

v1.0.5 起再加 `_scratch/harness/HarnessV105.kt`（`run.ps1` 同样会自动跑），覆盖：
时间表网格 `com.stupidtree.hitax.utils.TimetableGrid`（固定 11 节、行区间换算、第N节标签、旧结构识别与规整）、
主题色板 `com.stupidtree.hitax.utils.ThemePalette`（预设表、id 规整、昼夜取色、颜色工具）、
励志短句 `com.stupidtree.hitax.utils.QuoteProvider`（内置语录、一言/今日诗词解析、刷新时机、通知文案渲染）、
公开站点爬虫 `com.stupidtree.hitax.data.source.web.sysu.SysuPublicCrawler`（用一个进程内的
`com.sun.net.httpserver.HttpServer` 当被测站点，验证抓取、链接抽取、404/连不上时的降级与日志），
**104 项断言全绿**。

这些类都刻意不引用 `android.graphics` / Android 资源，所以能直接在 JVM 里跑 —— 新加纯逻辑请沿用这个做法。
**提示**：给 harness 加断言时，`Harness.kt` / `HarnessV10x.kt` 只把用到的源码文件列进
`compile.ps1` 的 `$files`，新增纯逻辑类记得同步加进去。

新增功能请同时加断言（`_scratch/harness/Harness.kt`；纯 JVM 桩在 `_scratch/harness/stub/`，安卓 Log/TextUtils/org.json/Room 注解都有 shim）。
若改了 mock 数据结构，同步改 `_scratch/probe/mock_jwxt.js`。

### (b) 真实教务接口验证（需要一次有效会话 Cookie）

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\dshproject\hita\_scratch\harness\run_live.ps1" "<Cookie 文本>"
```

Cookie 获取方式：浏览器登录 https://jwxt.sysu.edu.cn/jwxt/ → F12 → Network → 刷新 → 点第一个请求 → Request Headers 里的整行 `Cookie`。
**注意：Cookie / NetID / 密码属于敏感信息 —— 只用于本地联调，绝不写入代码、提交或交付物。**
（登录接口 `SysuApi.LOGIN_URL` 带风控与滑块，无法脱离浏览器直接 POST，所以 App 内登录用 WebView 承载。）

### (c) 静态核验

```powershell
$env:JAVA_HOME="C:\dshproject\hita\_toolchain\jdk\jdk-17.0.13+11"
$bt="C:\dshproject\hita\_toolchain\sdk\build-tools\35.0.0"
$apk="C:\dshproject\hita\sysu\release\yixian-timetable-v1.0.2.apk"
& "$bt\aapt2.exe" dump badging $apk           # 版本号 / 权限 / 图标
& "$bt\apksigner.bat" verify $apk             # 签名
```

---

## 4. 发布到 GitHub

发布脚本在 `_scratch`：

| 脚本 | 作用 |
| --- | --- |
| `gh_sync_hash.js` | **推荐**：按 git blob SHA 精确比对，把本地新增/修改的文件增量推送到仓库 |
| `gh_publish.js` | 首次建仓 + 全量上传（已用过，一般不需要） |
| `gh_check_final.js` | 校验 README/strings/build.gradle 是否与本地一致 |
| `gh_prune.js` | **删掉「本地已删除、远端还在」的残留文件**（`--delete` 才真删）。改了文件名/删了资源之后一定要跑一次 |
| `gh_clean_old_apk.js` | 只保留当前版本的 `release/*.apk`，清掉仓库里堆积的旧安装包 |
| `gh_ensure_asset.js` | 检查 Release 里当前版本的 APK 资产是否真的挂上了；缺失就补传 |

> ⚠️ **踩过的坑（v1.0.4）**：`gh_sync*.js` 只会「上传」本地文件，**不会删除远端多出来的文件**。
> 所以删掉 `drawable/logo.xml` 这类资源后，仓库里仍然留着它 —— 用户看到的「原项目图标没被替换」就是这么来的。
> 删除任何文件后，务必跑 `node gh_prune.js lisy365 yixian-timetable`（先看列表，再加 `--delete`）。
> 同理，`release/` 里的旧 APK 如果在本地没删掉，下一次 `gh_sync_hash.js` 会把它**重新传回仓库**；
> 发新版时先删本地旧 APK，再用 `gh_clean_old_apk.js` 清远端。

标准发版流程：

```powershell
# 0) 改版本号：sysu/app/build.gradle 的 versionCode / versionName
# 1) 构建并放到 release 目录（ASCII 文件名，避免 Release 资产名乱码）
cmd /c "C:\dshproject\hita\build.cmd" --no-daemon :app:assembleRelease
Copy-Item "C:\dshproject\hita\sysu\app\build\outputs\apk\release\app-release.apk" `
          "C:\dshproject\hita\sysu\release\yixian-timetable-v1.0.3.apk" -Force
# 2) 更新 README 里的版本徽章与下载链接（1.0.2 -> 1.0.3）
# 3) 同步 + 建 Release 上传 APK（脚本会自动删掉旧版本的 release 与旧 APK）
cd C:\dshproject\hita\_scratch
node gh_sync.js       lisy365 yixian-timetable v1.0.3 "C:\dshproject\hita\sysu\release\yixian-timetable-v1.0.3.apk"
node gh_sync_hash.js  lisy365 yixian-timetable v1.0.3    # 二次校验，确保"同字节数改动"也不漏
```

### Token 怎么给（已配置好，通常不用管）

Token 的解析顺序（`_scratch/gh_token.js`）：

1. 环境变量 `GH_TOKEN`（若你在系统里设过，优先用它）；
2. 工作区凭据文件 **`C:\dshproject\hita\.gh-token`**（当前就是这个，只存一行 token）。

所以直接跑上面两条 `node gh_sync*.js` 即可，**不需要每次粘贴 token**，脚本会打印一行 `使用 GitHub token: ghp_xxxx...xxxx（长度 40）` 供你确认。

安全约定：

- `.gh-token` 已在 `C:\dshproject\hita\.gitignore` 与 `sysu/.gitignore` 中忽略，**永远不会被提交**；
- 项目源码里不能出现 token（可用 `Select-String "ghp_[A-Za-z0-9]{20,}"` 自查）；
- 需要换 token 时：把新 token 覆盖写入 `C:\dshproject\hita\.gh-token`（一行）即可，并到 GitHub 删除旧的；
- 需要 `repo` 权限（细粒度 token 还要 `Administration: Read and write` 才能建仓）。

> 备注：本机沙箱禁止写注册表，所以 `setx` / `[Environment]::SetEnvironmentVariable` 都会报 access denied ——
> 这就是用文件而不是系统环境变量的原因。你自己在普通终端里当然可以 `setx GH_TOKEN <token>`，那样第 1 条就会先生效。

---

## 5. 下次怎么让 DSH 继续改（推荐用法）

新会话不会自带这次对话的记忆，所以第一句话把上下文交代清楚即可。推荐模板：

> 工作区 `C:\dshproject\hita`，先读 `HANDOVER.md`（里面有环境、构建/测试/发版流程和已知坑）。
>
> **需求**
> 1）……具体到界面位置、默认值、交互结果
> 2）……
>
> **约束**：保持原 UI 风格与中大绿的配色；不要改变现有数据表结构（若必须改，请加 Migration）
> **验收**：新增/更新 harness 断言；构建 release 无错误；静态核验（版本号/权限/图标）通过
> **交付**：完成后同步 GitHub 并发 v1.0.3，Release 里放 APK；README 的版本徽章与下载链接一并更新
> **自主性**：发现问题自己修，不要中途问我；只有需要我做决定时才提问

要点：

- 让它**先读 `HANDOVER.md`**（本文），环境/流程/坑都在里面，能省掉大量重复摸索；
- 明确「自主测试、自主修复、完成后同步 GitHub」——这样它会自己跑 harness、构建、校验、发版；
- 需求里写清「保持原 UI 风格」，否则容易被改得不一致；
- 需要真机验证的功能（通知、背景、WebView 登录），**你装包实测后把现象/日志发回来**，环境里没有模拟器，AI 无法替代这一步；
- 涉密内容（NetID 密码、会话 Cookie、token）只在需要时说一次，不要写进需求文档或提交。

### 长任务：用「目标（goal）」而不是一次性长指令

DSH 有 **goal** 机制：一轮做完如果目标还没达成，它会带着同一个目标自动继续下一轮，直到完成或确实卡住。适合这类工作：

- 一次要改 4~6 个相互关联的功能（比如「重做课表页 + 适配新的教务接口 + 补齐通知」）；
- 需要「改完 → 测试 → 发现问题 → 再改」反复迭代几轮的 bug 排查；
- 想让它把某个模块打磨到某个明确标准（例如「把成绩页做到和课表页同样的完成度」）。

用法（写在你的第一条消息里即可）：

> 这是一个**长期目标**：把 XXX 做到 YYY 标准。中间可以分多轮推进，每轮自己跑测试、自己修，
> 全部达成后再同步 GitHub 并发版；只有需要我拍板时才停下来问我。

配套建议：

1. **目标要可验收**：写清「完成的标准」（哪些功能可用、哪些断言要通过、APK 要能装能跑），
   否则目标容易被判定为提前完成；
2. **一次只立一个目标**：多个目标会让轮次互相打断，复杂需求拆成「先 A 后 B」，A 完成后再立 B；
3. **给一个轮次上限**：怕它反复折腾时可以说「最多 5 轮，超过就先向我汇报进展」；
4. **中途纠偏**：任务跑着的时候你随时发消息就能调整方向（比如「背景再淡一点」「这版先不发 GitHub」），
   不需要等它跑完；
5. **每轮结束会给结论**：每轮都会说明「这轮做了什么、测试结果、还差什么」，你据此决定继续或收工；
6. **真机相关需求要写进目标**：例如「通知必须实测能收到」——这种 AI 无法自证的部分，
   目标里直接写明「由作者真机确认」，避免它空转。

### 需求描述模板（省事的写法）

```
【做什么】在「功能中心」加一个「课堂签到」入口，点进去显示本周课程列表，可一键标记已签到
【放哪里】功能中心，排在「通知提醒」下面；沿用现有卡片样式（84dp 高、48dp 圆角图标底）
【默认值】默认只显示今天，可切换到本周
【数据】复用 events 表，不新增字段；签到状态存 SharedPreferences
【验收】harness 加断言；release 构建通过；版本号升到 1.0.3
【交付】同步 GitHub + Release 放 APK + README 更新版本号
【自主性】自主测试、自主修复，必要时再问我
```

按这个格式写，基本可以一次跑通，不用来回补信息。


---

## 6. 已知坑与注意事项

1. **`AppDatabase` 的 `@TypeConverters` 必须写全限定名** `@androidx.room.TypeConverters(AppTypeConverters::class)`：
   项目自己的转换器对象已改名为 `AppTypeConverters`，否则会和 `androidx.room.TypeConverters` 注解同名冲突，Room 报 “Cannot figure out how to save Timestamp”。
2. **数据库版本**：当前 `version = 2`（v1→v2 增加了 `note`、`done` 两列）。**再改实体一定要加 `Migration` 并升版本**，否则老用户覆盖安装会崩。
3. **待办复用 `events` 表**：`type = OTHER`、`subjectId = 'YIXIAN_TASK'`、`timetableId = ''`。
   课表删除用的是 `timetableId in (...)`，所以待办不会被误删；查询待办统一用 `subjectId is 'YIXIAN_TASK'`。
4. **作息时间只有一份真源**：`Timetable.getDefaultTimeStructure()`（v1.0.5 起为**中大教务部官方 11 节**）；
   `SysuTimetableParser.buildScheduleStructureFromSections()` 直接复用它，手动新建课表与教务导入课表必须保持一致，改一处即可。
   UI 侧的规整/换算统一走 `utils/TimetableGrid.kt`（`FIXED_PERIODS = 11`、`normalize()`、`rowRange()`、`periodLabel()`）。
5. **课表背景按「课表 id」存图**（`files/backgrounds/<id>.jpg`），取当前课表用 `TimetableRepository.getTimetableAt(ts)`
   （内部是 `MAX(startTime) <= ts`，早于所有课表时回退到最早一套 —— 这就是「第一周/假期背景不显示」的修复点）。
6. **提醒用 AlarmManager + 滚动窗口（7 天）重排**：任何会影响日程/待办/设置的操作后都应调用 `ReminderScheduler.rescheduleAll()`；
   新增这类入口时别忘了补这一句。
7. **不要提交**：`local.properties`、`*/build/`、`_scratch/`、`_toolchain/`、`_gradlehome/`、`_androidhome/`、`_home/`、`_npmcache/`（`.gitignore` 已覆盖）。
   `release/*.apk` 需要提交（Release 下载链接指向仓库内文件）。
8. 打包前确认 `applicationId` 仍是 `com.stupidtree.hitax.yixian`，否则用户覆盖安装会变成两个 App。
9. **「今日」时间轴的视图类型兜底值不能是 `FOOT`**（`TimelineListAdapter.getItemViewType`）：
   `FOOT` 对应的是「页脚空行」布局（无 `tl_card`、无 `timeline` id），而 `onBindViewHolder` 也不处理 `emptyHolder`，
   所以任何掉进 `FOOT` 的事件都会变成一行 ~88dp 的空白 + 断掉的时间轴。
   历史 bug：待办是 `type = OTHER` 且 `from == to`，未到点时 `TimeTools.passed()` 为 false、又不是 CLASS/EXAM，
   于是落进 `FOOT` → 正是「待办在今日显示错误、无法显示在时间轴上」。兜底必须是可见卡片类型（现在统一返回 `CLASS`）。
10. **待办的两条时间语义**：`from == to` 是「截止时刻」而不是时间段。因此：
   - `EventItem.containsTimeStamp()` 对它恒为 false → 永远不会成为 `nowEvent`；
   - `refreshNowAndNextEvent()` 里要显式跳过待办，否则表头会显示「距离<待办>还有 N 分钟」；
   - 待办在时间轴上用专门的卡片 `dynamic_timeline_card_task.xml`（`isTask()` → 视图类型 `TASK = 17`），
     `dynamic_timeline_card_passed.xml` 里 `tl_tv_time` 是 `gone`、也没有 `tl_tv_place`，拿它渲染待办会丢截止时间与备注。
11. **「今日」列表要合并两路数据**：当天 `00:00~24:00` 的事件（`getEventsDuring`）+ **明天及以后未完成的待办**
   （`EventItemDao.getPendingTasksAfter`）。装配规则在纯函数 `ui/main/timeline/TimelineTasks.kt`（可离线单测）。
12. **BottomSheet 里的 WebView 要禁止弹窗拖拽**：`BottomSheetBehavior` 会在 `onInterceptTouchEvent` 抢走竖直手势，
   导致登录页滑不动。现用 `ui/widgets/ScrollableWebView`（按 DOWN/UP 请求祖先不要拦截）+ `behavior.isDraggable = false`，
   弹窗另给「取消」入口。以后凡是把可滚动内容放进 BottomSheet，都要检查这一条。
13. **用 PowerShell 改带中文的源码会毁掉编码**（v1.0.5 踩过）：
   `Get-Content -Raw` + `Set-Content -Encoding utf8` 这条链路在本机把 `TimetableFragment.kt`
   的中文注释整段写成了乱码（还会把多行挤成一行）。**改文件一律用编辑工具，不要用 PowerShell 做文本替换**；
   万一中招，`C:\dshproject\hita\_versions\<tag>\tree\...` 里有原文件可以直接拷回来。
14. **主题色板的运行时切换方式**（v1.0.5）：
   `BaseActivity.onCreate()` 在 `super.onCreate()` **之前**调 `ThemeTools.applyTheme(this)`，
   它先 `AppCompatDelegate.setDefaultNightMode(...)` 再 `setTheme(色板 style)`。
   色板 style 表由 `utils/ThemePaletteRegistry.kt` 注册给 `style` 模块（style 不能反向依赖 app 资源）。
   `AppTheme.Dark` 显式写在 `values/themes.xml`（**没有**放进 `values-night`），
   这样"当前是深色还是浅色"只由 `AppCompatDelegate.getDefaultNightMode()` 一处决定，不会和系统配置打架。
   新增色板要同时改三处：`values/themes.xml`（浅色 + `.Night` 两个 style）、
   `ThemePalette.PRESETS`、`ThemePaletteRegistry` 的两张映射表。
15. **时间表网格按「节」等分，不再按钟点**（v1.0.5）：
   `TimeTableView` 的 `sectionHeight` 语义已从「每小时」变成「每节课」；
   课程块的行区间由 `TimetableGrid.rowRange()` 决定（优先用 `EventItem.fromNumber/lastNumber`，
   手动新建的课没有节次号则按时间反推）。
   左侧 `LeftLabelView` 画「第N节 + 上课时间」，行高必须与网格保持一致（`setRowHeight`）。
16. **`sysu` 内置的 `SysuCrawler` 一定要把 host 传下去**（v1.0.5）：
   爬虫内部会复用 `SysuWebSource`（取学期/考试/成绩/总周数），
   **必须走 `webSource()` 这个带 `host` 的工厂**；直接 `SysuWebSource()` 会连生产环境，
   离线 harness 里表现为「会话已过期」，真机上则是多打一堆真实请求。
17. **`release/*.apk` 只保留当前版本**：本地删掉旧 APK 再用 `gh_clean_old_apk.js` 清远端，
   否则下一次 `gh_sync_hash.js` 会把旧包重新传回仓库。

---

## 7. 版本历史

| 版本 | 内容 |
| --- | --- |
| v1.0.0 | SYSU 适配首版：统一身份认证 WebView 登录、按周抓取并合并的课表导入、成绩、考试、本地搜索、逸仙课表品牌与中大绿主题 |
| v1.0.1 | 新增课表背景自定义、待办事项管理、提醒通知（可自定义提前量/重复/文案模板）；修复登录弹窗缺「登录」按钮、开屏与关于页仍用原项目图标 |
| v1.0.2 | 修复第一周及开学前背景不显示；通知提醒移入「功能中心」设置菜单；待办移至底部导航栏；新增一键统一科目颜色（自选颜色）；导入课表默认作息修正为中大标准时间表；README 写入作者的话 |
| v1.0.3 | 修复待办在「今日」时间轴不显示（根因是 `getItemViewType` 兜底 `FOOT`；同时把明天及以后的未完成待办并入时间轴，新增待办专用卡片）；修复教务登录页在弹窗内无法上下滑动；修正中大作息「第 3 节 10:10 开始、第 4 节 11:50 结束」 |
| v1.0.4 | 修复教务登录页点输入框弹不出软键盘；替换残留的原项目图标；科目颜色选择重做为色盘 + 渐变滑杆 + 色号输入框；新增后台保活机制（详见第 8 节第 3 小节） |
| v1.0.5 | ① **全局主题色可调**（7 套色板 + 昼夜模式，`PopUpThemePicker`）；② **时间表重构**：每天固定 11 节、按节等分网格、卡片标注「第N节」、**修复下午第一节 14:20**（原 14:30）与第 7/8 节 16:30/17:25，老数据自动迁移；③ **教务信息爬虫**（培养方案/教学大纲/教师/考试/成绩/课表 + 实时日志窗口）；④ 底部「待办」→ **「小工具」板块**（注册表驱动，待办内嵌其中）；⑤ **保活通知文案可自定义 + 公益 API 励志短句**。详见第 9 节 |

---

## 9. v1.0.5 新增/变更速查（下次改这几块前先看）
### 9.1 全局主题色（需求 1）
- 用户入口：功能中心右上角调色盘按钮（`MainActivity` 的 `switchTheme`）→ `ui/settings/PopUpThemePicker.kt`；
  长按该按钮仍是旧的「深色→浅色→跟随系统」循环切换。
- 持久化：`SharedPreferences("theme")`，键 `mode`（`light`/`dark`/`follow`）与 `palette`（色板 id）。全部逻辑在 `style/ThemeTools.kt`。
- 色板 id → style 的映射在 `utils/ThemePaletteRegistry.kt`；色板数据在 `utils/ThemePalette.kt`。
- **注意**：色板只覆盖 `colorPrimary / colorPrimaryVariant / colorSecondary / colorSecondaryVariant /
  colorPrimaryDisabled / backgroundIconColorBottom`；背景、文字、分隔线等仍由 `AppTheme` / `AppTheme.Dark` 提供。
  布局里凡是写死 `@color/cruel_summer_primary` 的地方（`widget_today_item.xml`、`widget_ic_location.xml`、
  `element_round_blue.xml`、`SearchActivity` 的兜底色）**不会跟随色板**，属于已知的不一致，
  下次顺手可以换成 `?attr/colorPrimary`。

### 9.2 时间表 UI + 中大作息（需求 2）
- 官方作息取自中大教务部官网页脚（`https://jwb.sysu.edu.cn/` 的「作息时间」）：
  1~4 节上午、5~8 节下午、9~11 节晚上，**共 11 节**，第 5 节 **14:20** 开始。
- 数据真源：`Timetable.getDefaultTimeStructure()`（11 条）。
- 网格：`ui/main/timetable/views/TimeTableView.kt` —— 7 列 × 11 行等分，行高 = `TimetableStyleSheet.cardHeight`
  （默认 180px，可用 `TimetableGrid.rowHeightPx()` 的思路按可用高度收敛）。
  旧实现的两个 bug 一并修掉：`mHeight` 被赋成打包后的 `MeasureSpec`（今日高亮矩形画到屏幕外）、
  `notifyRefresh` 在 `requestLayout()` 之后才更新 `sectionHeight`（首帧用旧值）。
- 左侧栏：`views/LeftLabelView.kt` —— 每行两行文字（节次数字 + `HH:mm`），
  行高由 `TimetableFragment.applyStructure()` 同步，别再依赖已废弃的 `setStartDate`。
- 课程卡片：`layout/fragment_timetable_class_card.xml` / `fragment_timetable_duplicate_card.xml`
  新增 `@+id/period` 角标；文本由 `TimeTableBlockView.periodLabel()` 生成（`第3节` / `第3-4节`）。
- 老数据迁移：`utils/TimetableStructureMigration.kt`（`HApplication.onCreate` 后台跑一次）。
  只替换「明显是老默认结构」的课表（`TimetableGrid.isLegacyDefault`），并删掉 `fromNumber > 11` 的课程事件；
  **不动 Room 版本**（`scheduleStructure` 是 JSON 列，不需要 Migration）。
- 解析侧保护：`SysuTimetableParser.MAX_SECTION` 由 16 改成 **11**，`parseWeek` 会直接丢弃越界节次
  —— 否则 `EASRepository` 里的 `schedule[item.begin - 1]` 会 `IndexOutOfBoundsException`。

### 9.3 教务信息爬虫（需求 3）
- 入口：小工具 → 「教务信息爬取」（`ui/crawler/CrawlerActivity.kt`），
  或 `ActivityUtils.startActivity(ctx, CrawlerActivity::class.java)`。
- 抓取逻辑：`data/source/web/sysu/SysuCrawler.kt`（**不碰存储**，结果装在 `Result.saves` 里返回）。
- 落盘：`data/repository/CrawlerStorage.kt`，目录 `files/crawler/<分类>/<时间戳>-<名称>.json`
  + `index.json` + 可读的 `index.txt`；「查看已保存内容」会导出到
  `Android/data/<pkg>/files/crawler-out/` 方便用文件管理器浏览。
- 七个资源：学生信息、课表原始数据、任课教师（从课表 `rkjs` 聚合）、各课程考试日期、成绩与学分、
  本专业培养方案、课程教学大纲。
- **培养方案 / 教学大纲学校未向学生端开放稳定接口**：代码里各有一组候选入口，
  逐个尝试并写日志；全灭时退化为抓对应页面的可见文本（`SysuApi.getRaw` + jsoup 取 `body().text()`）。
  日志窗口会明确写出「试了哪些入口、结果如何」，不会静默失败。
- **第二阶段：校级 / 学院公开站点**（`data/source/web/sysu/SysuPublicCrawler.kt`）——
  教务部（含**作息时间**权威来源）+ 11 个常用学院官网，抓公开栏目的页面文本与站内链接索引，
  分类为 `school` / `college`。这些站点**不需要登录**，所以放在教务部分之后独立跑；
  个别学院域名变更只会记一条「不可达」日志，不影响其它站点。
  新增学院 = 往 `SysuPublicCrawler.COLLEGES` 加一条 `Site(id, 名称, 域名, listOf(栏目路径))`。
- 爬虫拿不到原文时，UI 底部还提供「在校内系统里检索」的跳转（`SysuPublicCrawler.SEARCH_TEMPLATES`：
  教务部站内搜索 / 教务系统 / 本科教学信息平台 / 图书馆）。
- 存储分类在 `CrawlerStorage.CATEGORIES` 里加一项即可（现在共 8 类）。

### 9.4 小工具板块（需求 4）
- 注册表：`ui/tools/ToolRegistry.kt`。两种工具：
  - `Embedded`：在板块内嵌一个 Fragment（待办就是这样，`factory = { FragmentTask() }`）；
  - `Launcher`：打开 Activity / 弹窗，`action = { ctx, activity -> ... }`。
- 加工具 = 往 `ToolRegistry.ALL` 加一条 + 加两个字符串；分组用 `ToolRegistry.Group`。
- 页面：`ui/tools/ToolboxFragment.kt` + `layout/fragment_toolbox.xml`，
  列表是「分组标题 + 84dp 卡片」（`layout/item_tool_card.xml`），内嵌区带返回键。
- 底部导航菜单项 id 由 `navigation_task` 改成 **`navigation_tools`**；`MainActivity` 的 header 用的是
  `task_layout` / `task_title`（标题文本换成 `@string/title_tools`），**id 没改**，以免连带改一堆绑定。

### 9.5 保活通知文案与励志短句（需求 5）
- 设置入口：小工具 → 通知提醒 → 「保活通知文案」（`FragmentNotificationSettings` 里
  `keepalive_title` / `keepalive_content` / `keepalive_quote` / `keepalive_preview` / `keepalive_refresh`）。
- 存储：`NotificationPreferenceSource` 里 `keepalive_*` 一组键（**新增键一定要同步 `resetToDefault()`**）。
- 文案合成：`data/repository/KeepAliveNotifier.kt`（用户自定义 > 励志短句 > 默认文案）；
  短句解析/内置语录/刷新时机在 `utils/QuoteProvider.kt`（默认接口 `https://v1.hitokoto.cn/?c=d&c=i&encode=json`，
  也兼容今日诗词的字段）；失败自动轮换内置 30 条，**通知里不会出现空白**。
- 刷新时机：保活服务每 15 分钟一跳，命中 `KeepAlivePlan.shouldRefreshQuote`（6 小时）才联网，
  网络请求在子线程，成功后用 `KeepAliveService.refreshNotification()` 重贴常驻通知
  （`startForegroundCompat()` 有 `foregroundStarted` 短路，所以必须显式重贴）。

---

## 10. 版本快照与回滚（v1.0.5 起）

```powershell
cd C:\dshproject\hita
node _scratch\version_snapshot.js create v1.0.5   # 打快照（保存改动前的代码）
node _scratch\version_snapshot.js list            # 列出快照
node _scratch\version_snapshot.js verify          # 校验每个快照的 sha256
node _scratch\version_snapshot.js rollback v1.0.4 # 回滚到某个快照
node _scratch\version_snapshot.js prune 3         # 只保留最近 3 个（最少 3 个，不会删到 3 个以下）
```

> `prune` 是按 `createdAt`（快照创建时间）挑最旧的删，**不是按版本号**。
> 所以从仓库重建出来的 `v1.0.3`、`v1.0.5` 这类「补打」的快照时间戳会偏新，
> 真要清理时先 `list` 看一眼再动手。

- 快照目录：`C:\dshproject\hita\_versions\<tag>\tree\...` + `MANIFEST.json`（相对路径 + 大小 + sha256）。
- 覆盖范围与 `gh_sync_hash.js` 一致（`sysu/` 下所有参与发布的文件，排除 `build/`、`local.properties` 等）。
- **回滚会先删掉「快照里没有」的源码文件再写回**，属于精确复原；回滚前建议先给当前状态打一个快照。
- 目前保留 4 个：`v1.0.3`、`v1.0.4`、`v1.0.5`、`v1.0.6`（`version_snapshot.js verify` 全部 OK）。

### v1.0.3 快照是怎么来的（以及它的已知缺口）

`v1.0.3` 本地已经没有原始目录了，它是**从仓库历史重建**的：
取 commit `ba9953e30d`（`sync: release/yixian-timetable-v1.0.2.apk（v1.0.3）`，
是「消息里带 v1.0.3」的最后一个提交；第一个带 v1.0.4 的提交在它 7.7 小时之后），
该提交的 `app/build.gradle` 为 `versionName "1.0.3"` / `versionCode 1000300`。

- **源码可信**：825 个文件逐个比对过 git blob SHA-1，并用 `_scratch/diff_snapshots.js`
  与 `v1.0.4` 交叉复核 —— 差异恰好是 v1.0.4 该有的那些（删 3 个 θ/logo 图标、加 8 个
  保活与调色盘文件、改 23 个含 MainActivity / strings / build.gradle 等）。
  顺带确认了那个 bug 的历史形态：v1.0.3 的 `Timetable.kt` 里第 5 节就是 **14:30**、且共 14 节。
- **APK 缺失（不影响回滚源码）**：`release/` 下的安装包已从该快照中移除 ——
  重建点残留的是 `yixian-timetable-v1.0.2.apk`（旧包，留着会在回滚时污染 release 目录），
  而真正的 `yixian-timetable-v1.0.3.apk` 因为本机代理会在约 110 秒掐断长连接、
  且 blob API 不支持 Range 续传而无法取回（重试脚本留在 `_scratch/v103_apk_retry.js`）。
  需要安装包就用 `build.cmd` 从这套源码重建。
- 想再补一个更早的版本，照这个思路来即可：找到目标版本对应的最后一个提交，
  用 `/git/trees/<sha>?recursive=1` + `/git/blobs/<sha>` 拉全量，再按 `MANIFEST.json` 的格式落盘。



---

## 8. v1.0.4 新增/变更速查（v1.0.5 的改动都建立在它们之上）

### 8.1 软键盘（教务登录）
- 根因：`InputMethodManager.showSoftInput(view, …)` 只有在 `mServedView === view`
  （即该 WebView 自己是**窗口内获得焦点的 View**）时才生效，否则**直接返回 false**。
  在 `BottomSheetDialog` 里初始焦点常落在「登录」按钮上，WebView 不是焦点 View，
  于是 Chromium 内部的 `showSoftKeyboard()` 被系统丢掉 → 「点输入框没键盘」。
- 修复位置：
  - `ui/widgets/ScrollableWebView.kt` —— `init` 里设 `isFocusableInTouchMode`，
    `ACTION_DOWN/UP` 调 `grabImeFocus()`（优先普通 `requestFocus`，失败才 `requestFocusFromTouch`，
    避免把整个窗口踢出 touch mode 导致按钮出现焦点描边）；
  - `ui/eas/login/PopUpLoginEAS.kt` —— `onStart` 里 `clearFlags(FLAG_NOT_FOCUSABLE or FLAG_ALT_FOCUSABLE_IM)`
    + `SOFT_INPUT_ADJUST_RESIZE`，并在弹窗出现 / 页面加载完成后把焦点交给 WebView；
  - `utils/SysuWebViewUtils.kt` —— `FOCUS_SCROLL_JS`：`focusin` 时把输入框滚到可视区中部。
    **为什么需要**：`BottomSheetDialog.onAttachedToWindow` 会调
    `WindowCompat.setDecorFitsSystemWindows(window, false)`，此后 `adjustResize/adjustPan` 都失效，
    键盘会直接盖住页面，只能靠脚本滚动兜底。
- 以后凡是「Dialog / BottomSheet 里放 WebView 或输入框」，都要检查「谁是焦点 View」这一条。

### 8.2 调色盘
- `style/.../widgets/PopUpColorPicker.kt` 对外 API 没变
  （`initColor(Int)` / `setOnColorSelectListener` / `OnColorSelectedListener.onSelected(Int)`），
  两个调用点（`TimetableDetailActivity`、`FragmentTimetablePanel`）无需改动。
- 新增同模块的 `ColorMath.kt`（纯函数：HSV 互转、`#RGB/#RRGGBB/#AARRGGBB` 解析、`#RRGGBB` 格式化）与
  `ColorWheelView.kt`（色盘：角度=色相、半径=饱和度）、`ColorSliderView.kt`（渐变滑杆）。
- 两个自绘 View 在 `ACTION_DOWN` 都会 `requestDisallowInterceptTouchEvent(true)`，
  否则拖动会被 `BottomSheetBehavior` 当成收起弹窗。
- `FragmentTimetablePanel` 第一次打开时传进来的 `unifyColor` 是 `0`（全透明黑），
  `ColorMath.normalizeInputColor()` 负责补成不透明，别再让调色盘开在一个看不见的颜色上。

### 8.3 后台保活（通知按时送达）
- `KeepAlivePlan.kt`（纯函数）：触发时刻、7 天窗口、**每天一次**的保活重排间隔、精确闹钟判定。
- 保活三件套：
  1. **每天重排**（`ReminderScheduler.scheduleInternal` 用 `KeepAlivePlan.nextRearmAt(now)`）——
     旧实现是 6 天才排一次，那一颗闹钟一丢就永久失效；
  2. **前台服务** `utils/KeepAliveService.kt`（默认关闭，在「通知提醒」里开关，渠道 `yixian_channel_keepalive`，
     每 15 分钟自检重排），`BootReceiver` / `MainActivity.onStart` 会按偏好自动拉起；
  3. **短时唤醒锁**：`AlarmReceiver` 在 `goAsync()` 之后持有 30s `PARTIAL_WAKE_LOCK`，
     保证「读库 → 发通知」跑完；`ACTION_RESCHEDULE` 分支不持锁（重排是异步的）。
- 新增 UI：通知设置面板底部「后台保活」开关 +「闹钟与提醒权限」状态行
  （`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`）+「电池优化白名单」入口。
  **Android 14 起 targetSdk<33 的应用默认拿不到精确闹钟权限**，那时 `setAlarmSafely` 会静默退化成
  `setAndAllowWhileIdle`（可能晚几十分钟）—— 这一行就是给用户看状态的。
- `BootReceiver` 现在还监听 `TIME_SET` / `TIMEZONE_CHANGED` /
  `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`。
- 提醒的 PendingIntent 现在带 `data`（`yixian://remind/<index>/<eventId>`）：
  只靠请求码时，两个 id 的 `hashCode` 低 16 位相同会互相覆盖。
  旧形态（无 `data`）在 `legacyPendingIntent()` 里保留，用于升级后清理幽灵闹钟 —— **别删**。
