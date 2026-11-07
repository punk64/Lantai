# FORK_BASE — 上游基线与差异台账

> 本文件由兰台维护，上游没有。**2026-10-31 起上游同步已终止**（ADR-020 全项目改名），本文只承担两件事：记录基线来源，登记与上游的差异。

## 当前基线

| 项 | 值 |
|---|---|
| 本仓库 | [punk64/Lantai](https://github.com/punk64/Lantai)（唯一官方地址） |
| 上游项目 | [gotson/komga](https://github.com/gotson/komga)（MIT） |
| 基线版本 | **1.29.2** |
| 上游 tag | `1.29.2` |
| 上游 commit SHA | `829f75884b013037a0ed74c6d8966c8857b35d9c` |
| 上游发布日期 | 2026-10-28 |
| 本地获取日期 | 2026-10-30 |
| 获取通道 | `codeload.github.com` 的 tar.gz 源码包 |
| 改名日期 | 2026-10-31（ADR-020），改名前的完整快照留在 `backup/pre-rename` 分支 |

## 为什么不再同步上游

- ADR-020 把包名、配置前缀、模块目录、类名全换了，3942 行与上游偏离，**git merge 不可能再走通**
- 项目定位为独立发展：上游只借鉴功能思路，代码按需参考、按需重写
- 需要看上游新功能时，下载源码包单独阅读，**不落进本仓库**，更不做 merge

## 源码获取通道（实测 2026-10-30，需要参考上游时仍可用）

| 通道 | 结果 |
|---|---|
| `https://github.com/...` 直连 | 超时（curl 返回 000，21s 无响应） |
| 经本机代理访问 github.com | `CONNECT tunnel failed, response 502`（代理不放行） |
| `https://gitclone.com/github.com/gotson/komga.git` | 可用但仅约 35 KB/s |
| `https://codeload.github.com/gotson/komga/tar.gz/refs/tags/<tag>` | **可用，6.8 MB/s** |
| `https://api.github.com/...` | 可用，用于查 tag 列表与 commit SHA |

## 分支约定

| 引用 | 含义 | 纪律 |
|---|---|---|
| `lantai/main` | **唯一开发主线** | 所有改动都在这里 |
| `backup/pre-rename` | 改名前的完整快照（2026-10-31） | 只读，用于回溯改名前的代码 |
| `main` | 旧上游快照 | 冻结，不再更新 |

## Flyway 迁移号段约定

上游用时间戳式迁移号，例：

```
lantai/src/flyway/resources/db/migration/sqlite/V20261020111142__recompute_koreader_hash.sql
```

兰台自定义迁移遵守：

1. **格式跟上游一致**：`V<yyyyMMddHHmmss>__lantai_<描述>.sql`，时间取实际开发时刻
2. **描述里带 `lantai_` 前缀**，一眼区分自有迁移与上游迁移
3. **必须确认 Flyway 允许 out-of-order**：上游可能补一个时间戳更早的脚本，`outOfOrder=false` 时会直接启动失败；现状见主文档风险清单第 1 条
4. **动了表就必须重跑 jOOQ 代码生成**，否则编译期类型对不上

## 差异台账

| 日期 | 文件 | 性质 | 说明 |
|---|---|---|---|
| 2026-10-30 | `FORK_BASE.md` | 新增 | 本文件，上游没有 |
| 2026-10-30 | `lantai/src/main/kotlin/org/lantai/domain/service/BookLifecycle.kt` | **修改上游核心行为** | `markProgression` 边界误判修复（ADR-012）：客户端报的 `progression` 落在资源最后一个 position 之后时，原逻辑因 `after == null` 抛出 `Invalid progression` → 400 丢进度；改为回退到最近的 position，仅保留真正的顺序错乱判定 |
| 2026-10-31 | `domain/model/Library.kt` · `infrastructure/jooq/main/LibraryDao.kt` · `interfaces/api/rest/dto/LibraryDto.kt` | 上游表加列 | `Library.type`（`LOCAL`/`ONLINE`），三处 DAO 映射与 DTO 透出（ADR-016） |
| 2026-10-31 | `domain/service/LibraryContentLifecycle.kt` | **修改上游核心行为** | `scanRootFolder` 入口对 `type == ONLINE` 直接返回，避免扫描末尾按 URL 集合把虚拟书整批软删（ADR-016） |
| 2026-10-31 | `interfaces/api/rest/BookController.kt` | 上游端点插桩 | `getBookById` / `getBookPages` 前置 `onlineReadService.ensurePages(bookId)`；`getBookThumbnail` 前置 `ensureBookThumbnail(bookId)`。本地书两次调用立即返回，零额外查询 |
| 2026-10-31 | `interfaces/api/CommonBookController.kt` | **修改上游核心行为** | `getBookPageInternal` 在内容限制检查后插入在线页分流：`pageOrNull` 命中即直返 Suwayomi 字节，未命中走原逻辑；故障返 503 + `Retry-After`，越界返 400（ADR-017） |
| 2026-10-31 | `infrastructure/online/**` · `domain/model/OnlineManga.kt` · `OnlineChapter.kt` · 两个 Repository 与 jOOQ DAO · `interfaces/api/rest/LantaiOnlineController.kt` | 新增 | 在线库全链，含 `OnlineDownloadService`（下载存档，ADR-018） |
| 2026-10-31 | `lantai/src/main/resources/application.yml` | 新增配置段 | `lantai.online.*`（在线库开关、Suwayomi 连接、下载目录与超时） |
| 2026-10-31 | `lantai/src/flyway/.../V20261031071500__lantai_online_library.sql` | 新增迁移 | `LIBRARY.TYPE` + `ONLINE_MANGA` + `ONLINE_CHAPTER` |
| 2026-10-31 | 全仓库 | **全量改名**（ADR-020） | 包 `org.gotson.komga` → `org.lantai`；配置前缀 `komga` → `lantai`；模块目录 `komga`/`komga-webui`/`komga-tray` → `lantai*`；20 个 `Komga*` 类与 24 个前端 plugin 改名；`KOMGA_*` 环境变量 → `LANTAI_*` |
| 2026-10-31 | `interfaces/api/opds/v1/OpdsController.kt` · `interfaces/api/OpdsGenerator.kt` · `application-dev.yml` · `build.gradle.kts` | 去掉上游站点链接 | OPDS 作者 URI 置空、help 链接改 `/`、dev CORS 去掉上游域名、jreleaser 去掉 homepage；均为死链，指向不存在的站点 |
| 2026-11-01 | `infrastructure/online/SuwayomiConfigWriter.kt` · `SuwayomiProcessManager.kt` · `ExtensionIndexParser.kt` · `SuwayomiInstaller.kt` · `SuwayomiAdminService.kt` | 新增 | 兰台托管 Suwayomi 进程、生成 `server.conf`、代持凭据、自解析 keiyoushi `index.pb`、发行版下载（ADR-019 / R13 §12） |
| 2026-11-01 | `infrastructure/online/SuwayomiClient.kt` · `SuwayomiModels.kt` · `OnlineProperties.kt` · `OnlineLibraryService.kt` | 上游/自有代码增强 | 客户端补 `about` / `settings` / `setSettings` / 扩展与仓库操作 / multipart 安装；凭据缺失时从 `server.conf` 懒加载；`ApplicationReadyEvent` 后由 `suwayomi-bootstrap` 守护线程拉起子进程 |
| 2026-11-01 | `interfaces/api/rest/LantaiSuwayomiController.kt` · `lantai/src/main/resources/application.yml` | 新增 | `api/lantai/v1/suwayomi/**` 全 ADMIN 端点（status / settings / lifecycle / extensions / stores / release / jar / password）；`lantai.online.*` 补 11 个配置键 |
| 2026-11-01 | `lantai-webui/src/services/lantai-suwayomi.service.ts` · `plugins/lantai-suwayomi.plugin.ts` · `views/SettingsOnline.vue` · `main.ts` · `router.ts` · `views/HomeView.vue` | 新增 | 旧 Vue UI 的「设置 → 在线源」页：运行状态 / 版本与安装 / 参数 / 插件四卡 |
| 2026-11-01 | 全仓库（Kotlin 13 个 Controller + 旧 UI 10 个 service） | **端点路径变更** | 自有端点 `api/v1/lantai/**` → `api/lantai/v1/**`，旧路径已 404；`SecurityConfiguration` 由 `/api/**` 统一覆盖，仅 `bulk/**` 保留独立 chain |
| 2026-11-03 | `domain/service/FileSystemScanner.kt` | **修改上游核心行为** | **书库根目录不再作为一个系列**（ADR-022）：`preVisitDirectory` 遇到 `dir == root` 直接跳过建 series；`postVisitDirectory` 对 root 下的散书按文件各建一个 `oneshot` 系列。上游原行为是「以 root 目录名为名建一个系列，收纳 root 下全部散书」 |
| 2026-11-03 | `infrastructure/runtime/install/RuntimeEnvironment.kt` · `CalibreInstallerAdapter.kt` | 检测逻辑修正 | 新增 `isMusl()`；Calibre 环境自检在 musl（alpine）下直接判定 `libc` 不满足并给出明确结论，不再用系统 `ldconfig` 误报缺 `libQt6Core` / `libxcb-cursor` 等库 |
| 2026-11-03 | 仓库根 · `lantai/src/main/resources/public/` · `lantai-tray/` | **删除上游内容** | 上游三套前端全部移除：`lantai-webui`（Vue 2）、`next-ui`（Vite）连同 `public/` 下 232 个构建产物；桌面托盘程序 `lantai-tray` 一并删除。`IndexController` 去掉 `/classic` 与 `/next` 两个映射，`/` 固定返回 `index-lantai`；`LantaiSettingsProvider.nextUiAsDefault` 与 `Settings` 枚举项 `NEXTUI_AS_DEFAULT` 删除；`build.gradle.kts` 去掉 `webuiCopyDist` / `webuiCopyIndex` / `nextuiCopyDist` / `nextuiCopyIndex` 四个任务，`settings.gradle` 去掉 `:lantai-tray`。`public/` 现只保留图标、`manifest.json`、`index-lantai.html` 与兰台前端产物 |
| 2026-11-03 | `infrastructure/mediacontainer/epub/EpubExtractor.kt` · `domain/service/BookAnalyzer.kt` | **修改上游核心行为** | 放宽 EPUB 的 DIVINA 判定（ADR-024）：上游要求「每页去重图片数 == spine 页数」才判图片合集，单页多图的合集 EPUB（图片文件夹/CBX 转来的）因此被当普通 EPUB，整本只剩 1 章。改为「所有 spine 页均含图且无文字超阈值页」时允许一页多图、按图片总数分页；「每页 1 图」的严格分支保持上游行为，另加「任一层无图即拒绝」防误判。`computePositions` 新增可选参数 `divinaPages`，fixed-layout 分支改按图片生成 position，使 position 数与 `Media.pageCount` 一致（上游按 spine 生成，两者会错位）；`BookAnalyzer` 调用处传入 `divinaPages` |
| 2026-11-04 | `interfaces/api/rest/ClaimController.kt` · `test/.../ClaimControllerTest.kt` · `infrastructure/security/SecurityConfiguration.kt` · `infrastructure/web/AuditInterceptor.kt` · `infrastructure/openapi/OpenApiConfiguration.kt` | **删除上游端点** | 移除 `GET/POST /api/v1/claim`。兰台首次启动由 `InitialUserController` 自动创建管理员（`admin-<8位>` + 24 位随机密码，打印日志），`POST claim` 的守卫 `countUsers() == 0` 在正常使用中永不成立 → 恒 400；且上游 `DELETE api/v2/users/{id}` 带 `#principal.user.id != #id`，唯一管理员删不掉自己，用户表无法归零。该端点此前仍在 `SecurityConfiguration` 的 `permitAll` 名单里，等于保留了一个匿名创建管理员的入口（仅当用户表被清空且进程未重启时可触发）。同步删掉审计排除名单里的路径与 OpenAPI 的 `Claim` 标签 |
| 2026-11-05 | `application/tasks/Task.kt` · `TaskEmitter.kt` · `TaskHandler.kt` · `domain/service/BookLifecycle.kt` | **修改上游核心行为** | 待分析书籍逐本串行（ADR-027）：`Task.AnalyzeBook` 增 `libraryId`；`analyzeUnknownAndOutdatedBooks` 由「整库全排」改为只提交第一条，其余由每条 `AnalyzeBook` 在 `finally` 里调 `analyzeNextBook` 自我续跑，队列中 `AnalyzeBook` 恒为 1 条；分析抛未预期异常时经 `BookLifecycle.markAnalysisFailed` 置 `Media.Status.ERROR`（`ERR_TASK_FAILED`）后再抛出，避免同一本书被反复取到 |
| 2026-11-05 | `domain/model/SearchContext.kt` · `infrastructure/jooq/BookSearchHelper.kt` · `interfaces/api/rest/BookController.kt` · `SeriesController.kt` · `infrastructure/app/AppHomeService.kt` · `infrastructure/jooq/main/BookCommonDao.kt` · `BookDtoDao.kt` · `interfaces/api/persistence/BookDtoRepository.kt` | **修改上游核心行为** | 读者视图过滤（ADR-028）：`SearchContext` 增 `readerView`（默认 `false`），`BookSearchHelper` 在该开关下追加 `MEDIA.STATUS IN ('READY','UNSUPPORTED')`；`GET /api/v1/books`、`POST /api/v1/books/list`、`/books/latest`、`/books/ondeck`、`/series/{id}/books` 新增 `readerView` 查询参数（默认关闭，管理端、OPDS 与第三方行为不变）；首页 `api/lantai/v1/app/home` 的四个书籍查询固定开启 |
| 2026-11-05 | `interfaces/api/rest/LantaiBookLetterController.kt` · `infrastructure/jooq/main/LantaiBookLetterDao.kt` · `LantaiSeriesLetterDao.kt` · `LantaiSeriesLetterController.kt` · `infrastructure/jooq/tasks/LantaiTaskQueryDao.kt` · `interfaces/api/rest/LantaiTaskController.kt` | 新增 | 书籍首字母分页端点 `books/letter-books`（内存算首字母 + 30 秒缓存，补库过滤与删除过滤）；系列首字母 `letter-ids` 补 `libraryId` 与删除过滤；任务中心新增 `PATCH tasks/{id}` 修改优先级（仅排队中，0..8），分页上限放开到 2000，任务 DTO 增对象字段（书籍名/系列名 + 路径） |
| 2026-11-06 | `application/tasks/TaskEmitter.kt` · `TaskHandler.kt` | **修改上游核心行为** | 待分析书籍改为**一次性全量入队**（ADR-030，supersedes ADR-027）：`analyzePendingBooks` 分页收集全部 `UNKNOWN`/`OUTDATED`（每页 5000，以 `tasks.size >= totalElements` 或空页终止）后一次 `submitTasks`；删除 `AnalyzeBook` 的 `finally { analyzeNextBook }` 续跑；`submitTasks` 日志改为数量 + 按类型分组，避免逐条打印数千行。幂等由 `TasksDao.toQuery` 既有的 `onDuplicateKeyUpdate` 与 `ANALYZE_BOOK_$bookId` 保证，重复扫描不冲突 |
| 2026-11-06 | `interfaces/api/rest/LantaiCatalogController.kt` · `infrastructure/jooq/main/LantaiCatalogDao.kt` | 新增 | `GET api/lantai/v1/catalog/ready` 返回「至少有一本 READY 书」的 `seriesIds` 与该口径下的 `bookCount`（`BOOK` join `MEDIA`，排除 `DELETED_DATE`/`EXCLUDED_DATE`）。用于书库页隐藏尚无可读书的系列卡 —— `GET /api/v1/series` 不支持 `media_status`，且 `SearchCondition` 没有跨表书状态条件，故不扩上游端点 |
| 2026-11-06 | `infrastructure/jooq/main/BookMetadataDao.kt` | **修改上游核心行为** | 作者落库统一清洗：`insertAuthors` 写入前经 `AuthorNameCleaner.cleanAuthors`（剥角色后缀、剥国籍前缀、折叠空白、按 name+role 去重）。该方法是 `BOOK_METADATA_AUTHOR` 的唯一写入点（`insert` 与 `update` 的「先删后插」都汇于此），改一处即覆盖 epub/comicinfo/pdf/刮削/手动编辑/外部 agent 全部来源 |
| 2026-11-06 | `domain/service/MetadataApplier.kt` · `SeriesMetadataLifecycle.kt` · `infrastructure/metadata/epub/EpubMetadataProvider.kt` | **修改上游核心行为 / 修上游缺陷** | 系列「地区」链路原本三处断点，全部打通：① `EpubMetadataProvider.getSeriesMetadataFromBook` 由 `region = null` 改为从 `dc:creator` 的国籍前缀提取（`AuthorNameCleaner.regionOfAuthors`）；② `SeriesMetadataLifecycle.handlePatchForSeriesMetadata` 聚合由 `region = null` 改为 `patches.mostFrequent { it.region }`；③ **`MetadataApplier.apply(SeriesMetadataPatch, SeriesMetadata)` 上游根本没写 `region` 分支**，补 `region = getIfNotLocked(region, patch.region, regionLock)`，否则前两处给了值也落不了库 |
| 2026-11-06 | `infrastructure/metadata/AuthorNameCleaner.kt` | 新增 | 从 `infrastructure/metadata/scrape/` 移到 `infrastructure/metadata/`，供 DAO 与 Provider 共用；新增 `cleanAuthors(List<Author>)` / `regionOfAuthorList(List<Author>)`（`Author` 非 data class，无 `copy`，按构造重建） |
| 2026-11-06 | `infrastructure/metadata/SeriesTitleCleaner.kt` · `domain/service/FileSystemScanner.kt` · `LibraryContentLifecycle.kt` · `SeriesMetadataLifecycle.kt` | 新增 + **修改上游核心行为** | 入库按方括号分段清洗系列名与册名（ADR-031）：`SeriesTitleCleaner` 丢弃卷号/状态版本词/纯拉丁组名/与作者同名的段，剩余取最长并要求至少长 1.5 倍，不确定则不改；`FileSystemScanner` 的 `Series.name`（目录名）与 `Book.name`（文件名去扩展名）各过一遍；`LibraryContentLifecycle` 重扫时同步已有 `Series`/`Book` 的 `name`（不必等 `fileLastModified` 变化）；`SeriesMetadataLifecycle.refreshMetadata` 末尾兜底清洗未加锁的脏 `TITLE`/`TITLE_SORT`，覆盖存量 |
| 2026-11-06 | `domain/service/FileSystemScanner.kt` · `domain/model/Book.kt` · `domain/model/Library.kt` · `domain/service/SeriesLifecycle.kt` · `LibraryContentLifecycle.kt` · `infrastructure/jooq/main/BookDao.kt` · `LibraryDao.kt` · `BookDtoDao.kt` · `interfaces/api/rest/dto/BookDto.kt` · `LibraryDto.kt` · `LibraryUpdateDto.kt` · `LibraryCreationDto.kt` · `LibraryController.kt` · `interfaces/api/rest/LantaiLibraryVolumeController.kt` · `flyway/.../V20261106224500__lantai_book_volume.sql` | **修改上游核心行为** + 新增 | **分卷：系列 → 卷 → 册**（ADR-034）。① 扫描由「`postVisitDirectory` 里边走边建系列」改为两阶段：`collect()` 只收集，`resolveVolumeGroups()` 判定后再建；② 库级开关 `LIBRARY.VOLUME_GROUPING`（默认关）开启时，若父目录不含书且 ≥2 个含书子目录共享名称前缀（或都用 `第N卷`/`Vol N` 命名），父目录成为系列、子目录成为卷，系列 URL 仍取真实父目录；③ `BOOK` 新增 `VOLUME`（`NOT NULL DEFAULT ''`）+ `idx__book__series_id_volume`，`Book`/`BookDao`/`BookDto` 全链路透传；④ `sortBooks` 排序键改为「卷名自然序 → 册名自然序」，`NUMBER` 仍全系列连续；⑤ 新增 `POST api/lantai/v1/libraries/{id}/volume-preview` 干跑预演（只扫描不落库）。上游行为是「一个含书目录 = 一个系列」，没有卷概念 |
