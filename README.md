# 逸仙课表 · Yixian Timetable

> 一个面向 **中山大学（SYSU）** 的课表 / 日程 App —— 基于开源项目 [HITA_X](https://github.com/StupidTrees/HITA_X)（HITA课表 · HITSZ助手重构版）适配与二次开发。

<p align="left">
  <img src="docs/icon.png" width="120" alt="逸仙课表图标"/>
</p>

<p align="left">
  <img alt="version" src="https://img.shields.io/badge/version-1.0.2-00693E"/>
  <img alt="platform" src="https://img.shields.io/badge/Android-6.0%2B%20(API%2023%2B)-00693E"/>
  <img alt="license" src="https://img.shields.io/badge/license-MIT-00693E"/>
  <img alt="build" src="https://img.shields.io/badge/build-Gradle%208.7%20%2B%20JDK%2017-00693E"/>
</p>

安装包：[release/yixian-timetable-v1.0.2.apk](release/yixian-timetable-v1.0.2.apk) ｜ [Release 页面](https://github.com/lisy365/yixian-timetable/releases/tag/v1.0.2)

---

## 项目背景

中山大学的课表分散在教务系统里：网页版按周查询、界面为 PC 设计，手机上查看与整理都很别扭；而教务系统的登录又要经过统一身份认证（NetID + 滑块验证），第三方工具想接入并不容易。

开源项目 [HITA_X](https://github.com/StupidTrees/HITA_X) 是哈尔滨工业大学（深圳）的同学做的一套非常成熟的课表 App：极简 UI、支持换色与深色模式、课表/日程/小组件、以及完整的「教务导入」链路。但它面向的是 HITSZ 的教务系统（EAS），在中大无法直接使用。

本项目在获得原作者 MIT 许可的前提下，**完整复用了 HITA_X 的 UI 与基础架构**，把数据源整体替换为中山大学教务系统（`jwxt.sysu.edu.cn`），并针对中大的学制、作息、成绩与考试规则做了适配，让中大的同学也能用上同样体验的课表 App。

技术上的主要难点与解法：

1. **登录**：中大统一身份认证的 `doLogin` 由前端 SDK 承载，并带有风控与滑块验证码，无法脱离浏览器直接 POST。因此改为在 App 内用 WebView 打开官方登录页，用户完成验证后自动提取会话 Cookie，再回注到原生请求中——既尊重了学校的风控，也拿到了稳定的会话。
2. **课表**：中大教务只提供**按周**查询的接口，返回的是「节次 × 星期」二维表，单元格里是用 `;;` 拼起来的键值串（`kcmc` 课程名 / `rkjs` 教师 / `skdd` 地点 / `skrq` 上课日期…）。导入时需要逐周抓取、解析这一非标准格式，再按「课程名＋教师＋地点＋星期」合并周次、把连续节次拼成整段课程，最后映射到「第 N 周星期 X 第 A-B 节」的时间轴事件。
3. **学期与开学日期**：教务只返回当前学期，需要按学年补齐秋/春/夏三个学期；开学日期取自教学日历，并强制对齐到当周周一，保证「第 1 周」与课表视图一致。

---

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 🔐 统一身份认证登录 | WebView 内完成 NetID 登录（含滑块验证），自动保存会话；也支持手动粘贴 Cookie 兜底 |
| 📥 一键导入课表 | 选择学期 → 确认开学日期 → 导入；自动合并跨周课程、合并连续节次 |
| 📅 课表视图 | 周次切换、当前时间线、点击查看课程详情、多套课表管理 |
| 🖼 课表背景自定义 | 每套课表可选一张图片作为底图，支持不透明度与压暗程度调节，课程卡片半透明覆盖其上 |
| ✅ 待办事项管理 | 新建 / 编辑 / 删除待办，支持优先级（普通 / 重要 / 紧急）、截止时间、备注、完成勾选、按截止时间或优先级排序、一键清理已完成 |
| 🔔 提醒通知 | 上课 / 日程开始前、待办 DDL 前发送系统通知；提前量、重复次数、重复间隔、通知标题与内容模板、提示音、震动、是否仅上课日均可自定义，并提供一键恢复预设 |
| ⏰ 日程（今日） | 按时间轴展示当天课程、待办与自定义日程；待办可长按快捷操作（编辑 / 完成 / 删除） |
| 🎨 主题与深色模式 | 默认中大绿 `#00693E`，内置多套配色，支持跟随系统深色模式 |
| 📊 成绩查询 | 按学期查询成绩，等级制成绩（优秀/良好/通过…）自动映射为百分制展示 |
| 📝 考试安排 | 缓补考 / 10-17周结课考 / 18-19周期末考，考试时间与地点 |
| 🔍 本地搜索 | 按课程名 / 任课教师 / 上课地点检索本地课表 |
| 🧩 桌面小组件 | 「今日课程」普通版与精简版两种小组件 |

适用范围：中山大学各校区（作息时间内置为中大常用作息，导入页可逐节自行微调）。

### 提醒通知的默认预设

| 项 | 默认值 |
| --- | --- |
| 总开关 | 开启 |
| 上课前提醒 | 开启，提前 **15 分钟** |
| 日程开始前提醒 | 开启，提前 **15 分钟** |
| 待办 / DDL 提醒 | 开启，提前 **60 分钟** |
| 重复提醒 | 额外 **1 次**（共 2 次），间隔 **5 分钟** |
| 通知标题 | `{time} {name}` → 例如「08:00 高等数学」 |
| 通知内容 | `{minutes}后开始 · {place} {teacher}` → 例如「15 分钟后开始 · 第五教学楼逸401 邹雄」 |
| 待办标题 | `待办提醒：{name}` |
| 待办内容 | `距截止还有 {minutes}{note}` |

文案模板支持变量：`{name}` 名称、`{time}` 开始时间、`{place}` 地点、`{teacher}` 教师、`{minutes}` 剩余时间、`{note}` 备注。
单个待办还可以单独设置提前量（覆盖全局设置）或设为「不提醒」；设置页底部有「发送测试通知」可一键验证通知链路。

提醒采用 **AlarmManager + 滚动窗口重排**：App 启动、导入课表、增删改日程与待办、修改提醒设置、开机、应用更新后都会重新扫描未来 7 天的事件并安排闹钟（Android 12+ 未授予精确闹钟权限时自动降级为非精确提醒，不会漏提醒）。

---

## 作者的话

> 2026 今年大一刚入学，作者发现隔壁哈工深有一个这么好的课表软件，到处搜了一圈居然没有找到 SYSU 有这种项目，非常生气。无奈作者能力有限，没办法从零开始构建一个这样的项目，我能做的只有站在巨人的肩膀上，再加上一点小小的 vibe coding，于是这个项目诞生了。项目草创，肯定有很多 bug，希望在 SYSU 学习之后能够把这个项目完善吧。
>
> 欢迎加群讨论：**1124204923**　｜　作者邮箱：**2788410557@qq.com**
>
> —— 作者：[@lisy365](https://github.com/lisy365)

---

## 与原项目的差异

| 模块 | 原项目（HITSZ / HITA_X） | 本项目（SYSU / 逸仙课表） |
| --- | --- | --- |
| 教务登录 | 账号密码 POST 到 `ids.hit.edu.cn` | 统一身份认证（CAS）WebView 登录 + Cookie 复用 |
| 课表接口 | `xszykb/queryxszybkzong` 一次拉全学期 | `timetable-search/classTableInfo/selectStudentClassTable` 按周拉取后合并 |
| 课表结构 | 教务返回 `component/queryKbjg` | 内置中大作息（1~14 节），导入页可调整 |
| 学期列表 | 教务返回学年学期列表 | 由当前学期 + 学年补齐秋/春/夏 |
| 开学日期 | `component/queryRlZcSj` | `base-info/school-calender?weekly=1` |

<details>
<summary>其余差异（点击展开）</summary>

| 模块 | 说明 |
| --- | --- |
| 成绩 | `achievement-manage/score-check/list`（`scoSchoolYear` / `scoSemester` 参数） |
| 考试 | `schedule/agg/commonScheduleExamTime/queryExamWeekName` + POST `examination-manage/classroomResource/queryStuEaxmInfo`（mk 微应用） |
| 空教室 | 中大教务未开放对应接口，入口保留并给出提示 |
| θ社区 / 校园新闻 / 讲座 | 依赖原作者自建服务端，已隐藏入口 |
| 教师主页搜索 | 依赖 HITSZ 教师站点，改为本地课表搜索 |
| 在线检查更新 | 原项目的 `hita.store` 服务不适用于本项目，已停用，关于页改为跳转 GitHub |
| 应用图标 / 主题色 | 更换为逸仙课表图标与中大绿 `#00693E` |

</details>

---

## 用到的开源库

沿用 HITA_X 的技术选型，并按其 MIT 许可继续使用：

| 开源库 | 用途 |
| --- | --- |
| [LoadingButtonAndroid](https://github.com/leandroBorgesFerreira/LoadingButtonAndroid) | 带加载动画的按钮（导入 / 登录） |
| [MZBannerView](https://github.com/pinguo-zhouwei/MZBannerView) | 校园页仿魅族 Banner |
| [multiline-collapsingtoolbar](https://github.com/opacapp/multiline-collapsingtoolbar) | 支持多行的 CollapsingToolbarLayout |
| [Luban](https://github.com/Curzibn/Luban) | 图片压缩 |
| [PullLoadXiaochengxu](https://github.com/LucianZhang/PullLoadXiaochengxu) | 今日页仿微信下拉 |
| [TimelineView](https://github.com/alorma/TimelineView) | 时间轴 |
| [CircleProgress](https://github.com/lzyzsd/CircleProgress) | 圆形进度 |
| [ExpandableLayout](https://github.com/cachapa/ExpandableLayout) | 可折叠布局 |
| [ChipsLayoutManager](https://github.com/beloo/flowlayout) | 流式布局 |
| [WheelView](https://github.com/cncoderx/WheelView) | 滚轮选择器 |
| [SmoothBottomBar](https://github.com/ibrahimsn98/SmoothBottomBar) | 底部导航 |
| [ExplosionField](https://github.com/tyrantgit/ExplosionField) | 爆炸动画 |
| [DslSpan](https://github.com/angcyo/DslSpan) | 富文本 Span |
| [PhotoView](https://github.com/bm-photoview/library) | 图片查看 |
| [Jsoup](https://jsoup.org/) | HTML / 教务接口解析 |
| [Retrofit](https://square.github.io/retrofit/) + [Gson](https://github.com/google/gson) | 网络请求与 JSON |
| [Room](https://developer.android.com/topic/libraries/architecture/room) | 本地数据库 |
| [Glide](https://github.com/bumptech/glide) | 图片加载 |
| [ical4j](https://github.com/ical4j/ical4j) | 日历（iCal）导出 |
| [AndroidX](https://developer.android.com/jetpack/androidx) / [Material Components](https://github.com/material-components/material-components-android) | 基础组件 |

### 仿照的原项目

- **[StupidTrees/HITA_X](https://github.com/StupidTrees/HITA_X)** — HITA课表（HITSZ助手重构版），作者 **Stupid Tree**，MIT License。
  本项目的 UI 设计、交互流程、主题系统、课表渲染、日程与小组件等绝大多数代码与素材均来自该项目，
  仅将教务数据源替换为中山大学教务系统，并做了必要的品牌与功能调整。**请在使用与分发时保留原作者的版权声明。**

---

## 构建

环境要求：JDK 17、Android SDK Platform 35、Build-Tools 35.0.0、Gradle 8.7（或使用项目自带 wrapper）。

```bash
# 1) 配置 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 构建 release（已配置签名，见 app/build.gradle）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名信息（开源演示用，公开可见）：

| 项 | 值 |
| --- | --- |
| keystore | `keystore/yixian.jks` |
| alias | `yixian` |
| storePassword / keyPassword | `yixian2026` |

> ⚠️ 由于该 keystore 随仓库公开，**请勿用于正式上架**。若需发布到应用商店，请替换为你自己的签名文件。

---

## 维护与二次开发

本项目的**环境配置、构建 / 测试 / 发版流程、已知坑**都记录在 [`HANDOVER.md`](HANDOVER.md) 里，
想继续开发（或交给 AI 助手继续开发）请先读它，可以少走很多弯路。

Quick start：

```bash
# 构建 release APK
./gradlew :app:assembleRelease     # 产物：app/build/outputs/apk/release/app-release.apk
```

---
## 项目结构

```
.
├── app/                  # 主应用：UI、教务适配层（data/source/web/sysu）、Room 数据层
├── component/            # 通用组件（DataState / Trigger / 网络基类）
├── style/                # UI 基础库（BaseActivity / BaseListAdapter / 各种弹窗）
├── sync/                 # 历史记录同步模块（本地）
├── user/                 # 账号模块（本项目未启用在线账号）
├── theta/                # θ社区模块（入口已隐藏，保留代码）
├── keystore/             # 演示用签名文件
├── docs/                 # 文档与图标
└── release/              # 发布产物（APK）
```

中山大学教务适配层位于：

```
app/src/main/java/com/stupidtree/hitax/data/source/web/sysu/
├── SysuApi.kt               # jwxt 接口封装（统一请求头 / Cookie / 错误码）
├── SysuSession.kt           # 统一身份认证回调与 Cookie 解析
├── SysuTimetableParser.kt   # 课表单元格解析（键值串 / 周次 / 节次）——纯函数，可单测
└── SysuWebSource.kt         # EASService 实现：登录校验、学期、课表、成绩、考试
```

---

## 已知限制

- **登录必须在统一身份认证页面内完成**：中大的 `doLogin` 带风控与滑块验证，无法脱离浏览器直接请求，因此登录以 WebView 承载，滑块由用户完成。
- **导入耗时**：中大教务只提供按周接口，导入一学期需要逐周抓取，约 10~30 秒。
- 空教室查询：中大教务未开放接口。
- θ社区、校园新闻、讲座、教师主页：依赖 HITSZ 服务端，已隐藏入口。
- 作息时间：内置为中大常用作息，各校区/学期可能略有差异，可在导入页逐节调整。

---

## 免责声明

本项目为**非官方**的学生开源工具，与中山大学官方无关；仅用于个人学习与课表整理。请遵守学校网络与信息系统使用规定，不要用于高频请求或商业用途。使用本工具产生的任何后果由使用者自行承担。

---

## License

[MIT](LICENSE) © Stupid Tree（原始项目 HITA_X）；本适配版本同样以 MIT 协议开源。
