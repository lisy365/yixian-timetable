# 逸仙课表 · 维护交接说明（HANDOVER）

> 这份文档写给「下一次继续开发」的人（或 AI 助手）。读完它就能在这个环境里改代码、构建、测试、发版。
> 最近的会话已经把环境、脚手架、发布链路全部搭好，**不要重新搭**。

---

## 1. 项目位置与技术栈

| 项 | 值 |
| --- | --- |
| 项目源码 | `C:\dshproject\hita\sysu`（Android Studio 可直接打开） |
| 包名 / 版本 | `com.stupidtree.hitax.yixian`，versionName `1.0.2`，versionCode `1000200` |
| 语言 / 构建 | Kotlin 1.9.20 + AGP 8.5.2 + Gradle 8.7 + JDK 17 |
| SDK | compileSdk 35 / minSdk 23 / targetSdk 33 |
| 仓库 | https://github.com/lisy365/yixian-timetable |
| 发布产物 | 仓库内 `release/yixian-timetable-<tag>.apk` + GitHub Release 资产 |

模块：`app`（主应用）、`component`、`style`、`sync`、`user`、`theta`（θ社区，入口已隐藏但保留代码）。

中山大学教务适配层全部在 **`app/src/main/java/com/stupidtree/hitax/data/source/web/sysu/`**：

- `SysuApi.kt` —— jwxt 接口封装（请求头/Cookie/错误码）
- `SysuSession.kt` —— 统一身份认证回调与 Cookie 解析
- `SysuTimetableParser.kt` —— 课表单元格解析（键值串 / 周次 / 节次），**纯函数，可单测**
- `SysuWebSource.kt` —— `EASService` 实现：登录校验、学期、课表、成绩、考试

新增功能所在位置：课表背景 `data/source/preference/TimetableBackgroundSource.kt` + `ui/main/timetable/panel/`；
待办 `ui/task/`（`FragmentTask` 是底部导航页，`TaskManagerActivity` 是全屏管理页）；
提醒通知 `utils/NotificationUtils.kt`、`utils/ReminderScheduler.kt`、`utils/AlarmReceiver.kt`、`utils/BootReceiver.kt`、`ui/settings/`。

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

---

## 3. 自动化测试（改完代码务必跑）

脚手架在 `C:\dshproject\hita\_scratch\harness`，共两类：

### (a) 离线端到端（mock 教务 + 真实生产代码）

```powershell
# 编译 harness（用 kotlinc-embeddable 直接编译生产源码 + 测试桩）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\dshproject\hita\_scratch\harness\compile.ps1"
# 启动 mock jwxt 服务并跑断言
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\dshproject\hita\_scratch\harness\run.ps1"
```

覆盖：会话/Cookie 解析、周次解析、课表单元格解析（真实 `kcmc:…;;rkjs:…;;skrq:第N周/…` 格式）、逐周合并、成绩/考试解析、待办模型、通知文案模板、提醒触发时间计算、中大默认作息表。当前 **98 项断言全绿**。

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

标准发版流程：

```powershell
# 0) 改版本号：sysu/app/build.gradle 的 versionCode / versionName
# 1) 构建并放到 release 目录（ASCII 文件名，避免 Release 资产名乱码）
cmd /c "C:\dshproject\hita\build.cmd" --no-daemon :app:assembleRelease
Copy-Item "C:\dshproject\hita\sysu\app\build\outputs\apk\release\app-release.apk" `
          "C:\dshproject\hita\sysu\release\yixian-timetable-v1.0.3.apk" -Force
# 2) 更新 README 里的版本徽章与下载链接（1.0.2 -> 1.0.3）
# 3) 同步 + 建 Release 上传 APK（脚本会自动删掉旧版本的 release 与旧 APK）
$env:GH_TOKEN='<你的 PAT>'
cd C:\dshproject\hita\_scratch
node gh_sync.js       lisy365 yixian-timetable v1.0.3 "C:\dshproject\hita\sysu\release\yixian-timetable-v1.0.3.apk"
node gh_sync_hash.js  lisy365 yixian-timetable v1.0.3    # 二次校验，确保"同字节数改动"也不漏
```

**Token**：需要一个勾选 `repo` 权限的 GitHub Personal Access Token（细粒度 token 还要勾 `Administration: Read and write` 才能建仓）。
用环境变量传入、**不要写进任何文件**；用完请到 GitHub 设置里删除。以后可以把它存到系统环境变量 `GH_TOKEN` 里，避免每次粘贴。

---

## 5. 下次怎么让 DSH 继续改

新会话不会自带这次对话的记忆，所以第一句话把上下文交代清楚即可，例如：

> 工作区 `C:\dshproject\hita`。先读 `HANDOVER.md`，然后按需求改 `sysu` 项目：
> 1）…… 2）……；改完自己跑测试、构建、修掉问题，最后同步 GitHub 并发新版（版本号 1.0.3）。
> GitHub token 用环境变量 `GH_TOKEN`。

要点：
- 让它**先读 `HANDOVER.md`**（本文），环境/流程/坑都在里面；
- 明确「自主测试、自主修复、完成后同步 GitHub」——这样它会自己跑 harness、构建、校验、发版；
- 需求里写清「保持原 UI 风格」，否则容易被改得不一致；
- 需要真机验证的功能（通知、背景、WebView 登录），**你装包实测后把现象/日志发回来**，环境里没有模拟器，AI 无法替代这一步；
- 长任务可以直接说「这是一个长期目标」，DSH 会建 goal 并跨轮次推进。

---

## 6. 已知坑与注意事项

1. **`AppDatabase` 的 `@TypeConverters` 必须写全限定名** `@androidx.room.TypeConverters(AppTypeConverters::class)`：
   项目自己的转换器对象已改名为 `AppTypeConverters`，否则会和 `androidx.room.TypeConverters` 注解同名冲突，Room 报 “Cannot figure out how to save Timestamp”。
2. **数据库版本**：当前 `version = 2`（v1→v2 增加了 `note`、`done` 两列）。**再改实体一定要加 `Migration` 并升版本**，否则老用户覆盖安装会崩。
3. **待办复用 `events` 表**：`type = OTHER`、`subjectId = 'YIXIAN_TASK'`、`timetableId = ''`。
   课表删除用的是 `timetableId in (...)`，所以待办不会被误删；查询待办统一用 `subjectId is 'YIXIAN_TASK'`。
4. **作息时间只有一份真源**：`Timetable.getDefaultTimeStructure()`；`SysuTimetableParser.buildScheduleStructureFromSections()` 直接复用它，
   手动新建课表与教务导入课表必须保持一致，改一处即可。
5. **课表背景按「课表 id」存图**（`files/backgrounds/<id>.jpg`），取当前课表用 `TimetableRepository.getTimetableAt(ts)`
   （内部是 `MAX(startTime) <= ts`，早于所有课表时回退到最早一套 —— 这就是「第一周/假期背景不显示」的修复点）。
6. **提醒用 AlarmManager + 滚动窗口（7 天）重排**：任何会影响日程/待办/设置的操作后都应调用 `ReminderScheduler.rescheduleAll()`；
   新增这类入口时别忘了补这一句。
7. **不要提交**：`local.properties`、`*/build/`、`_scratch/`、`_toolchain/`、`_gradlehome/`、`_androidhome/`、`_home/`、`_npmcache/`（`.gitignore` 已覆盖）。
   `release/*.apk` 需要提交（Release 下载链接指向仓库内文件）。
8. 打包前确认 `applicationId` 仍是 `com.stupidtree.hitax.yixian`，否则用户覆盖安装会变成两个 App。

---

## 7. 版本历史

| 版本 | 内容 |
| --- | --- |
| v1.0.0 | SYSU 适配首版：统一身份认证 WebView 登录、按周抓取并合并的课表导入、成绩、考试、本地搜索、逸仙课表品牌与中大绿主题 |
| v1.0.1 | 新增课表背景自定义、待办事项管理、提醒通知（可自定义提前量/重复/文案模板）；修复登录弹窗缺「登录」按钮、开屏与关于页仍用原项目图标 |
| v1.0.2 | 修复第一周及开学前背景不显示；通知提醒移入「功能中心」设置菜单；待办移至底部导航栏；新增一键统一科目颜色（自选颜色）；导入课表默认作息修正为中大标准时间表；README 写入作者的话 |
