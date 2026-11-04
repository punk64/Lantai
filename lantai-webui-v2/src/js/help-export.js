(function () {
  const EN_FIELDS = [
    ['title', 'Title', 'text', 'Series title', 'writable'],
    ['summary', 'Summary', 'text', 'Multi-line, use \\n for line breaks', 'writable'],
    ['publisher', 'Publisher', 'text', '', 'writable'],
    ['language', 'Language', 'text', 'BCP-47 such as zh-CN; zh and zh-CN are the same language', 'writable'],
    ['genres', 'Genres', 'array or delimited text', 'Fantasy、Adventure', 'writable'],
    ['authors', 'Authors', 'array or delimited text', 'Ryoko Kui', 'writable'],
    ['releaseDate', 'Release date', 'YYYY-MM-DD or YYYY', 'Written to every book in scope', 'writable'],
    ['isbn', 'ISBN', 'text', '', 'writable'],
    ['tags', 'Tags', 'array or delimited text', 'Completed、Full color', 'writable'],
    ['coverUrl', 'Cover URL', 'URL', 'URL only, no file upload', 'writable'],
    ['ageRating', 'Age rating', 'number', 'Not written in this version', 'ignored'],
  ];

  const EN_CLIENTS = [
    ['Mihon / Tachiyomi', 'Comics (online + local CBZ)', 'EPUB / TXT'],
    ['Komelia (KMP native)', 'Comics + collections + read lists', 'Ebook reflow'],
    ['KOReader', 'EPUB / PDF / TXT', '—'],
    ['Lantai web', 'All formats + annotations + stats + online sources', '—'],
    ['Kobo', 'KEPUB · Kobo sync (needs access key)', '—'],
  ];

  const CN_CLIENTS = [
    ['Mihon / Tachiyomi 系', '漫画库（在线 + 本地 CBZ）', 'EPUB / TXT'],
    ['Komelia（KMP 原生）', '漫画 + 合集 + 阅读清单', '电子书重排'],
    ['KOReader', 'EPUB / PDF / TXT', '—'],
    ['兰台网页端', '全部格式 + 批注 + 统计 + 在线源', '—'],
    ['Kobo', 'KEPUB · Kobo 同步（需访问密钥）', '—'],
  ];

  function langGroup() {
    const l = (window.LantaiI18n && LantaiI18n.lang()) || 'zh-Hans';
    if (l === 'zh-Hans') return 'zh';
    if (l === 'zh-Hant') return 'zht';
    return 'en';
  }

  function mdTable(head, rows) {
    const line = a => '| ' + a.join(' | ') + ' |';
    return [line(head), '|' + head.map(() => ' --- ').join('|') + '|'].concat(rows.map(line)).join('\n');
  }

  function fieldRows(g) {
    const src = g === 'en' ? EN_FIELDS : SC_META_FIELDS;
    return src.map(f => [f[0], f[1], f[2], f[3] || '—', f[4]]);
  }

  function docFeature(g) {
    if (g === 'en') {
      return [
        '# Scraping guide · How it works',
        '',
        'Scraping fills in series metadata: title, summary, publisher, language, genres, authors, release date, ISBN, tags and cover.',
        'The rule is: embedded metadata wins, only missing fields are filled, conflicts are never overwritten silently.',
        '',
        '## Where the queue comes from',
        mdTable(['Source', 'Notes'], [
          ['Single / multi select', 'Tick series in the library and choose Add to scrape queue; in per-book mode only the ticked books are registered'],
          ['Whole library', 'Start it from scrape settings or from the library page'],
          ['Missing metadata', 'Automatically picks series with incomplete data, capped at 200'],
        ]),
        '',
        '## The four states',
        mdTable(['State', 'Meaning', 'Next step'], [
          ['Queued', 'Registered, waiting to run', 'Press Start scraping'],
          ['Running', 'Extracting embedded data, querying sources, diffing', 'Wait for the progress bar'],
          ['Needs review', 'Some fields differ from the current values', 'Expand and tick field by field'],
          ['Failed', 'Source error or no result', 'Retry or add another source'],
        ]),
        '',
        '## Life of one entry',
        'Add to queue → extract embedded metadata → query sources → field-level diff → write directly and leave the queue when the whole series is clean, or move to Needs review when there are conflicts or a book scope is set. Locked fields are always skipped.',
      ].join('\n');
    }
    if (g === 'zht') {
      return [
        '# 刮削說明 · 功能',
        '',
        '刮削是給系列補後設資料：標題、簡介、出版社、語言、分類、作者、出版日期、ISBN、標籤、封面。',
        '蘭台的原則是內嵌資訊優先、只補缺失、衝突不自動覆蓋。',
        '',
        '## 名單從哪來',
        mdTable(['來源', '說明'], [
          ['單本 / 多選', '書庫頁勾選系列後點「加入刮削名單」；切到「按書籍」勾選具體冊時只登記選中的冊'],
          ['整庫', '刮削設定或書庫頁對某個庫整體發起'],
          ['缺後設資料', '自動挑出資訊不全的系列，上限 200 條'],
        ]),
        '',
        '## 四種狀態',
        mdTable(['狀態', '含義', '下一步'], [
          ['待刮', '已入列，等待處理', '點「開始刮削」'],
          ['刮削中', '正在提取內嵌資訊、查詢資料來源、比對差異', '等進度走完'],
          ['待核對', '存在與現有值不一致的欄位', '展開逐欄位勾選'],
          ['失敗', '資料來源報錯或無結果', '重試或加資料來源'],
        ]),
        '',
        '## 一個條目的完整一生',
        '加入名單 → 提取內嵌後設資料 → 查資料來源 → 欄位級差異比對 → 整系列且無衝突時直接寫入並移出名單；有衝突、或按冊登記時轉「待核對」由人決定寫哪幾冊。已鎖定的欄位永遠跳過。',
      ].join('\n');
    }
    return [
      '# 刮削说明 · 功能',
      '',
      '刮削是给系列补元数据：标题、简介、出版社、语言、分类、作者、出版日期、ISBN、标签、封面。',
      '兰台的原则是**内嵌信息优先、只补缺失、冲突不自动覆盖**。',
      '',
      '## 名单从哪来',
      mdTable(['来源', '说明'], [
        ['单本 / 多选', '书库页勾选系列后点「加入刮削名单」；切到「按书籍」勾选具体册时只登记选中的册，同系列其它册不受影响'],
        ['整库', '刮削设置或书库页对某个库整体发起'],
        ['缺元数据', '自动挑出信息不全的系列，上限 200 条'],
      ]),
      '',
      '## 四种状态',
      mdTable(['状态', '含义', '下一步'], [
        ['待刮', '已入队，等待处理', '点「开始刮削」'],
        ['刮削中', '正在提取内嵌信息、查询数据源、比对差异', '等进度走完'],
        ['待核对', '存在与现有值不一致的字段，需要人工决定', '展开逐字段勾选'],
        ['失败', '数据源报错或无结果', '重试或加数据源'],
      ]),
      '',
      '## 一个条目的完整一生',
      '加入名单 → 提取内嵌元数据 → 查数据源 → 字段级差异比对 → 整系列且无冲突时直接写入并移出名单 / 有冲突、或按册登记时转「待核对」由人决定写哪几册。已锁定的字段永远跳过。',
    ].join('\n');
  }

  function docUsage(g) {
    if (g === 'en') {
      return [
        '# Scraping guide · Workflows',
        '',
        '## Option 1 · Automatic with built-in sources',
        '1. Tick series in the library and add them to the queue, or start a whole library from scrape settings. In per-book mode only the ticked books are registered.',
        '2. Open Scrape settings → Sources, enable at least one source and check today quota.',
        '3. Go back to the queue and press Start scraping. Progress streams while the page is open.',
        '4. Entries with conflicts move to Needs review. Expand them and tick fields before applying.',
        '',
        '## Option 2 · Export, fill outside, import back',
        '1. Press Export queue, pick a scope and a format (JSON for AI, CSV for spreadsheets).',
        '2. Fill it in following the format guide. **Do not change the id column** (it is the queue entry id used to resolve the series). Filling seriesId directly also works. Leave unneeded fields empty.',
        '3. Press Import, paste or choose a file, check the preview, then confirm.',
        '4. Import always overwrites and never enters review. Series that were in the queue leave it automatically.',
        '',
        '## Option 3 · API integration',
        '1. Go to Scrape settings → Scrape token and generate one. Its scopes are fixed to read queue plus submit result.',
        '2. Pull scrape/agent/queue to get entry ids, then fetch each entry detail.',
        '3. POST the result to scrape/agent/queue/{id}/result.',
        '4. See the API tab for details.',
      ].join('\n');
    }
    if (g === 'zht') {
      return [
        '# 刮削說明 · 使用',
        '',
        '## 方式一 · 內建資料來源自動刮',
        '1. 在書庫勾選系列加進名單，或在刮削設定裡對整庫發起；切到「按書籍」勾選具體冊時只登記那幾冊',
        '2. 開啟「刮削設定 → 資料來源」，至少啟用一個來源，並確認今日額度夠用',
        '3. 回名單點「開始刮削」；開著頁面能即時看到進度',
        '4. 有衝突的條目會進「待核對」，展開後逐欄位勾選再套用',
        '',
        '## 方式二 · 匯出 → 外部填寫 → 匯入',
        '1. 點「匯出名單」，選範圍和格式（JSON 適合交給 AI，CSV 適合表格軟體）',
        '2. 按格式說明填寫，**id 欄不要改動**（它是名單條目 id，匯入時按它反查系列），也可以直接填 seriesId，不需要的欄位留空',
        '3. 點「匯入資料」，貼上或選檔案，先看解析預覽再確認寫入',
        '4. 寫入一律直接覆蓋，不進待核對；原本在名單裡的系列會自動移出名單',
        '',
        '## 方式三 · API 對接外部程式',
        '1. 「刮削設定 → 刮削權杖 → 產生刮削權杖」拿一個權杖，權限固定為讀取名單 + 提交結果',
        '2. 腳本先拉 scrape/agent/queue 拿條目 id，再逐條取詳情',
        '3. 把結果 POST 到 scrape/agent/queue/{id}/result',
        '4. 詳見「API 介面」頁籤',
      ].join('\n');
    }
    return [
      '# 刮削说明 · 使用',
      '',
      '## 方式一 · 内置数据源自动刮',
      '1. 在书库勾选系列加进名单，或在刮削设置里对整库发起；切到「按书籍」勾选具体册时只登记那几册',
      '2. 打开「刮削设置 → 数据源」，至少启用一个源，并确认今日额度够用',
      '3. 回名单点「开始刮削」；开着页面能实时看到进度',
      '4. 有冲突的条目会进「待核对」，展开后逐字段勾选再应用',
      '',
      '## 方式二 · 导出 → 外部填写 → 导入',
      '1. 点「导出名单」，选范围和格式（JSON 适合交给 AI，CSV 适合表格软件）',
      '2. 按格式说明填写，**id 列不要改动**（它是名单条目 id，导入时按它反查系列），也可以直接填 `seriesId`，不需要的字段留空',
      '3. 点「导入数据」，粘贴或选文件，先看解析预览再确认写入',
      '4. 写入一律直接覆盖，不进待核对；原本在名单里的系列会自动移出名单',
      '',
      '## 方式三 · API 对接外部程序',
      '1. 「刮削设置 → 刮削令牌 → 生成刮削令牌」拿一个令牌，权限固定为读取名单 + 提交结果',
      '2. 脚本先拉 `scrape/agent/queue` 拿条目 id，再逐条取详情',
      '3. 把结果 `POST` 到 `scrape/agent/queue/{id}/result`',
      '4. 详见「API 接口」页签',
    ].join('\n');
  }

  function docFormat(g) {
    const csvHead = SC_CSV_HEAD.join(',');
    if (g === 'en') {
      return [
        '# Scraping guide · Formats',
        '',
        'Both formats carry the same data. JSON nests fields under metadata; CSV is one flat row.',
        '',
        '## JSON structure',
        '```json',
        '{',
        '  "version": 1,',
        '  "count": 2,',
        '  "items": [',
        '    {',
        '      "id": "0K3M8Q1T7XGE5",',
        '      "seriesId": "0K3M8Q1T7XGE5",',
        '      "seriesTitle": "Delicious in Dungeon",',
        '      "status": "QUEUED",',
        '      "metadata": {',
        '        "title": "Delicious in Dungeon",',
        '        "summary": "A culinary adventure in a dungeon.",',
        '        "publisher": "Example Press",',
        '        "language": "zh-CN",',
        '        "genres": ["Fantasy", "Adventure"],',
        '        "authors": ["Ryoko Kui"],',
        '        "releaseDate": "2015-01-01",',
        '        "isbn": "9789863562305",',
        '        "tags": ["Completed"],',
        '        "coverUrl": ""',
        '      }',
        '    }',
        '  ]',
        '}',
        '```',
        '',
        'A single object or a bare array also works; the parser accepts both.',
        '',
        '## CSV columns',
        '```',
        csvHead,
        '0K3M8Q1T7XGE5,0K3M8Q1T7XGE5,Delicious in Dungeon,QUEUED,Delicious in Dungeon,A culinary adventure.,Example Press,zh-CN,Fantasy、Adventure,Ryoko Kui,2015-01-01,9789863562305,Completed,',
        '```',
        '',
        'The first row must be the header. Array fields accept 、, comma or pipe as separators. Quote cells containing commas with double quotes. Save as UTF-8.',
        '',
        '## Fields',
        mdTable(['Field', 'Name', 'Type', 'Notes', 'Write'], fieldRows('en')),
        '',
        '## Aliases',
        'description → summary, categories → genres, author → authors, publishDate → releaseDate, cover → coverUrl, queueId → id.',
        '',
        '## Three hard rules',
        mdTable(['Rule', 'Notes'], [
          ['Empty does not clear', 'An empty field or empty array means keep the current value. Clear values manually in the library.'],
          ['Import overwrites', 'Manual import writes straight by seriesId and never enters review. Importing twice overwrites. Locked fields are still skipped.'],
          ['Queue entries are cleaned', 'If an imported series is still queued, it leaves the queue after a successful write.'],
        ]),
        '',
        '## Limits',
        'No hard limit on row count, but import writes serially, so hundreds of rows take a while. Covers accept URLs only. ageRating is not written in this version. The importer is recorded as the signed-in account.',
      ].join('\n');
    }
    if (g === 'zht') {
      return [
        '# 刮削說明 · 格式',
        '',
        '兩種格式內容等價，任選一種。JSON 欄位是嵌套的 metadata 物件，CSV 是平鋪的一行。',
        '',
        '## JSON 結構',
        '```json',
        '{',
        '  "version": 1,',
        '  "count": 2,',
        '  "items": [',
        '    {',
        '      "id": "0K3M8Q1T7XGE5",',
        '      "seriesId": "0K3M8Q1T7XGE5",',
        '      "seriesTitle": "迷宮飯",',
        '      "status": "QUEUED",',
        '      "metadata": {',
        '        "title": "迷宮飯",',
        '        "summary": "地下迷宮裡的美食冒險。",',
        '        "publisher": "青文",',
        '        "language": "zh-CN",',
        '        "genres": ["奇幻", "冒險"],',
        '        "authors": ["九井諒子"],',
        '        "releaseDate": "2015-01-01",',
        '        "isbn": "9789863562305",',
        '        "tags": ["已完結"],',
        '        "coverUrl": ""',
        '      }',
        '    }',
        '  ]',
        '}',
        '```',
        '',
        '直接傳單一物件、或直接傳陣列也可以，解析時會自動相容。',
        '',
        '## CSV 欄位',
        '```',
        csvHead,
        '0K3M8Q1T7XGE5,0K3M8Q1T7XGE5,迷宮飯,QUEUED,迷宮飯,地下迷宮裡的美食冒險。,青文,zh-CN,奇幻、冒險,九井諒子,2015-01-01,9789863562305,已完結,',
        '```',
        '',
        '第一行必須是表頭。陣列欄位用頓號、逗號或豎線分隔都可以。含逗號的儲存格要用英文雙引號包起來。檔案存 UTF-8。',
        '',
        '## 欄位說明',
        mdTable(['欄位', '中文', '類型', '說明', '寫入'], fieldRows('zht')),
        '',
        '## 別名',
        'description → 簡介、categories → 分類、author → 作者、publishDate → 出版日期、cover → 封面位址、queueId → id。',
        '',
        '## 三條硬規則',
        mdTable(['規則', '說明'], [
          ['留空不覆蓋', '欄位為空或空陣列時視為「保持一致」，不會清空現有值。想清空只能到書庫裡手動改。'],
          ['重複匯入即覆蓋', '手動匯入一律按 seriesId 直接覆蓋寫入，不進待核對；同一條匯入多次，後一次覆蓋前一次。已鎖定的欄位仍然跳過。'],
          ['名單條目自動清理', '匯入的系列若還在刮削名單裡，寫入成功後自動移出名單。'],
        ]),
        '',
        '## 限制',
        '條數沒有硬上限，但匯入是逐條串行寫入，幾百條以上會比較慢；封面只接受圖片位址，不支援上傳檔案；ageRating 目前版本不寫入；匯入來源記為目前登入帳號。',
      ].join('\n');
    }
    return [
      '# 刮削说明 · 格式',
      '',
      '两种格式内容等价，任选一种。JSON 字段是嵌套的 `metadata` 对象，CSV 是平铺的一行。',
      '',
      '## JSON 结构',
      '```json',
      '{',
      '  "version": 1,',
      '  "count": 2,',
      '  "items": [',
      '    {',
      '      "id": "0K3M8Q1T7XGE5",',
      '      "seriesId": "0K3M8Q1T7XGE5",',
      '      "seriesTitle": "迷宫饭",',
      '      "status": "QUEUED",',
      '      "metadata": {',
      '        "title": "迷宫饭",',
      '        "summary": "地下迷宫里的美食冒险。",',
      '        "publisher": "青文",',
      '        "language": "zh-CN",',
      '        "genres": ["奇幻", "冒险"],',
      '        "authors": ["九井谅子"],',
      '        "releaseDate": "2015-01-01",',
      '        "isbn": "9789863562305",',
      '        "tags": ["已完结"],',
      '        "coverUrl": ""',
      '      }',
      '    }',
      '  ]',
      '}',
      '```',
      '',
      '直接传单个对象、或直接传数组也可以，解析时会自动兼容。',
      '',
      '## CSV 列',
      '```',
      csvHead,
      '0K3M8Q1T7XGE5,0K3M8Q1T7XGE5,迷宫饭,QUEUED,迷宫饭,地下迷宫里的美食冒险。,青文,zh-CN,奇幻、冒险,九井谅子,2015-01-01,9789863562305,已完结,',
      '```',
      '',
      '第一行必须是表头。数组字段用顿号、逗号或竖线分隔都可以。含逗号的单元格要用英文双引号包起来。文件存 UTF-8。',
      '',
      '## 字段说明',
      mdTable(['字段', '中文', '类型', '说明', '写入'], fieldRows('zh')),
      '',
      '## 别名',
      '`description` → 简介，`categories` → 分类，`author` → 作者，`publishDate` → 出版日期，`cover` → 封面地址，`queueId` → id。',
      '',
      '## 三条硬规则',
      mdTable(['规则', '说明'], [
        ['留空不覆盖', '字段为空或空数组时，该字段视为「保持一致」，不会清空现有值。想清空只能到书库里手动改。'],
        ['重复导入即覆盖', '手动导入一律按 seriesId 直接覆盖写入，不进待核对；同一条导入多次，后一次覆盖前一次。已锁定的字段仍然跳过。'],
        ['名单条目自动清理', '导入的系列若还在刮削名单里，写入成功后自动移出名单，不留待核对条目。'],
      ]),
      '',
      '## 限制',
      '条数没有硬上限，但导入是逐条串行写入，几百条以上会比较慢；封面只接受图片地址，不支持上传文件；`ageRating` 当前版本不写入；导入来源记为当前登录账号。',
    ].join('\n');
  }

  function docApi(g) {
    const base = 'http://127.0.0.1:25600/api/lantai/v1/';
    if (g === 'en') {
      return [
        '# Scraping guide · API',
        '',
        'Base URL `' + base + '`. Only the four endpoints below are exposed. Tokens are created in Scrape settings → Scrape token and are fixed to `scrape:read` and `scrape:submit`; they cannot read anything else.',
        '',
        'Send the token in a header, either `X-API-Token: lt_xxx` or `Authorization: Bearer lt_xxx`. The browser session cookie `LANTAI-SESSION` also works, but scripts should not rely on it.',
        '',
        '## Endpoints',
        mdTable(['Method', 'Path', 'Purpose', 'Scope'], [
          ['GET', 'scrape/agent/queue', 'One page of the queue: entry id, series id and title, state, attempts', 'scrape:read'],
          ['GET', 'scrape/agent/queue/{id}', 'Entry detail: current values, embedded values, book list, last payload', 'scrape:read'],
          ['POST', 'scrape/agent/queue/{id}/result', 'Submit a result; an entry id diffs, a seriesId overwrites', 'scrape:submit'],
          ['GET', 'scrape/agent/stats', 'Queue entry count and pending inbox count', 'scrape:read'],
        ]),
        '',
        '## List the queue',
        '```bash',
        'curl -H "X-API-Token: lt_xxxxxxxx" \\',
        '  "' + base + 'scrape/agent/queue?offset=0&limit=50"',
        '```',
        '`offset` defaults to 0; `limit` defaults to 20 and caps at 200. `total` and `counts` (queued / running / review / failed) count **queue entries** — one row per series, not books. Each item carries `id`, `seriesId`, `seriesTitle`, `status`, `attempt`, `createdDate`, `lastError` and `bookIds` (empty means the whole series, otherwise only those books).',
        '',
        '## Entry detail',
        '```bash',
        'curl -H "X-API-Token: lt_xxxxxxxx" \\',
        '  "' + base + 'scrape/agent/queue/0K3M8Q1T7XGE5"',
        '```',
        'Returns current values, embedded values, the `books` list (each with bookId, fileName, title, number, authors, releaseDate, isbn, tags, summary, publisher, genres, language and the titleLock / numberLock / authorsLock flags) and `external`, the last submitted payload or null.',
        '',
        '## Submit a result',
        '```bash',
        'curl -X POST -H "X-API-Token: lt_xxxxxxxx" -H "Content-Type: application/json" \\',
        '  -d \'{"metadata":{"title":"Delicious in Dungeon","authors":["Ryoko Kui"],"genres":["Fantasy","Adventure"]}}\' \\',
        '  "' + base + 'scrape/agent/queue/0K3M8Q1T7XGE5/result"',
        '```',
        'Fields can sit under `metadata` or at the top level. Arrays accept JSON arrays or delimited strings. Aliases work: description, categories, author, publishDate, cover.',
        '`status` is `DONE` when applied and removed from the queue, `REVIEW` when it needs a human. `appliedFields` lists what was written; `message` explains skips. Empty values never clear existing data.',
        '',
        '## Per-book writes',
        '```json',
        '{',
        '  "metadata": { "publisher": "Example Press" },',
        '  "books": [',
        '    { "bookId": "0K3M8Q1T7XGE5", "fields": ["title", "authors", "isbn"], "title": "Vol 1", "authors": ["Ryoko Kui"], "isbn": "9789863562305" }',
        '  ]',
        '}',
        '```',
        'Only the listed books are written. Omitting `fields` writes title, number, authors, releaseDate, isbn, tags, summary, publisher, genres and language. Book ids outside the queue entry scope are ignored; locked fields are skipped.',
        '',
        '## Stats',
        '```bash',
        'curl -H "X-API-Token: lt_xxxxxxxx" "' + base + 'scrape/agent/stats"',
        '{"total":2,"queued":1,"review":1,"failed":0,"inboxReceived":1}',
        '```',
        'Counts are queue entries. There is no running counter, so running entries are excluded from all four numbers. `inboxReceived` is the number of received but unprocessed results.',
      ].join('\n');
    }
    if (g === 'zht') {
      return [
        '# 刮削說明 · API',
        '',
        '基址 `' + base + '`。對外只開放下面四個介面，權杖在「刮削設定 → 刮削權杖」產生，權限寫死為 `scrape:read` 與 `scrape:submit`，讀不到書庫其它介面。',
        '',
        '外部程式用請求標頭傳權杖，二選一：`X-API-Token: lt_xxx` 或 `Authorization: Bearer lt_xxx`。瀏覽器登入態的 `LANTAI-SESSION` Cookie 也能通，但腳本不要依賴它。',
        '',
        '## 介面一覽',
        mdTable(['方法', '路徑', '說明', '所需權限'], [
          ['GET', 'scrape/agent/queue', '拉一頁名單：條目 id、系列 id 與標題、狀態、嘗試次數', 'scrape:read'],
          ['GET', 'scrape/agent/queue/{id}', '取單條詳情：現有值、內嵌值、冊清單、上次回填', 'scrape:read'],
          ['POST', 'scrape/agent/queue/{id}/result', '提交結果；id 給條目 id 走比對，給 seriesId 直接覆蓋', 'scrape:submit'],
          ['GET', 'scrape/agent/stats', '名單條目數與待處理回填數', 'scrape:read'],
        ]),
        '',
        '## 拉名單',
        '```bash',
        'curl -H "X-API-Token: lt_xxxxxxxx" \\',
        '  "' + base + 'scrape/agent/queue?offset=0&limit=50"',
        '```',
        '`offset` 預設 0，`limit` 預設 20、最大 200。回傳的 `total` 與 `counts`（queued / running / review / failed）都是**名單條目數**——一個系列一行，不是冊數；`items` 每條含 `id`、`seriesId`、`seriesTitle`、`status`、`attempt`、`createdDate`、`lastError`，以及 `bookIds`——空陣列表示整系列，非空表示只刮這幾冊。',
        '',
        '## 取單條詳情',
        '```bash',
        'curl -H "X-API-Token: lt_xxxxxxxx" \\',
        '  "' + base + 'scrape/agent/queue/0K3M8Q1T7XGE5"',
        '```',
        '回傳現有值、內嵌值、`books` 冊清單（每冊含 bookId、fileName、title、number、authors、releaseDate、isbn、tags、summary、publisher、genres、language 與三個鎖定標記 titleLock / numberLock / authorsLock），以及 `external`——上次回填的原始 payload，沒有則為 null。',
        '',
        '## 回傳結果',
        '```bash',
        'curl -X POST -H "X-API-Token: lt_xxxxxxxx" -H "Content-Type: application/json" \\',
        '  -d \'{"metadata":{"title":"迷宮飯","authors":["九井諒子"],"genres":["奇幻","冒險"]}}\' \\',
        '  "' + base + 'scrape/agent/queue/0K3M8Q1T7XGE5/result"',
        '```',
        'payload 可以套一層 `metadata`，也可以把欄位平鋪在頂層。陣列欄位用 JSON 陣列，或用頓號 / 逗號 / 豎線分隔的字串。別名照樣識別。',
        '返回 `status`：`DONE` 已套用並移出名單，`REVIEW` 進入待核對；`appliedFields` 是真正寫入的欄位，`message` 說明跳過或失敗原因。空值不會被當成「清空」。',
        '',
        '## 按冊寫',
        '```json',
        '{',
        '  "metadata": { "publisher": "青文" },',
        '  "books": [',
        '    { "bookId": "0K3M8Q1T7XGE5", "fields": ["title", "authors", "isbn"], "title": "迷宮飯 1", "authors": ["九井諒子"], "isbn": "9789863562305" }',
        '  ]',
        '}',
        '```',
        '帶 `books` 時只寫列出的冊；`fields` 省略則預設寫 title、number、authors、releaseDate、isbn、tags、summary、publisher、genres、language。佇列條目本身帶冊範圍時，範圍外的 `bookId` 會被忽略；已鎖定的欄位跳過。',
        '',
        '## 查統計',
        '```bash',
        'curl -H "X-API-Token: lt_xxxxxxxx" "' + base + 'scrape/agent/stats"',
        '{"total":2,"queued":1,"review":1,"failed":0,"inboxReceived":1}',
        '```',
        '同樣按條目數統計；沒有 running 一項，正在跑的條目這四個數字都不計。`inboxReceived` 是已收到但還沒處理完的回填數量。',
      ].join('\n');
    }
    return [
      '# 刮削说明 · API',
      '',
      '基地址 `' + base + '`。对外只开放下面四个接口，令牌在「刮削设置 → 刮削令牌」生成，权限写死为 `scrape:read` 与 `scrape:submit`，读不到书库其它接口。',
      '',
      '外部程序用请求头传令牌，二选一：`X-API-Token: lt_xxx` 或 `Authorization: Bearer lt_xxx`。浏览器登录态的 `LANTAI-SESSION` Cookie 也能通，但脚本不要依赖它。',
      '',
      '## 接口一览',
      mdTable(['方法', '路径', '说明', '所需权限'], [
        ['GET', 'scrape/agent/queue', '拉一页名单：条目 id、系列 id 与标题、状态、尝试次数', 'scrape:read'],
        ['GET', 'scrape/agent/queue/{id}', '取单条详情：现有值、内嵌值、册清单、上次回填', 'scrape:read'],
        ['POST', 'scrape/agent/queue/{id}/result', '提交结果；id 给条目 id 走比对，给 seriesId 直接覆盖', 'scrape:submit'],
        ['GET', 'scrape/agent/stats', '名单条目数与待处理回填数', 'scrape:read'],
      ]),
      '',
      '## 拉名单',
      '```bash',
      'curl -H "X-API-Token: lt_xxxxxxxx" \\',
      '  "' + base + 'scrape/agent/queue?offset=0&limit=50"',
      '```',
      '`offset` 默认 0，`limit` 默认 20、最大 200。返回的 `total` 与 `counts`（queued / running / review / failed）都是**名单条目数**——一个系列一行，不是册数；`items` 每条含 `id`、`seriesId`、`seriesTitle`、`status`、`attempt`、`createdDate`、`lastError`，以及 `bookIds`——空数组表示整系列，非空表示只刮这几册，长度即册数。',
      '',
      '## 取单条详情',
      '```bash',
      'curl -H "X-API-Token: lt_xxxxxxxx" \\',
      '  "' + base + 'scrape/agent/queue/0K3M8Q1T7XGE5"',
      '```',
      '返回现有值（summary / publisher / authors / genres / hasCover）、内嵌值（embeddedAuthors / embeddedPublisher）、`books` 册清单（每册含 bookId、fileName、title、number、authors、releaseDate、isbn、tags、summary、publisher、genres、language 与三个锁定标记 titleLock / numberLock / authorsLock），以及 `external`——上次回填的原始 payload，没有则为 null。',
      '',
      '## 回传结果',
      '```bash',
      'curl -X POST -H "X-API-Token: lt_xxxxxxxx" -H "Content-Type: application/json" \\',
      '  -d \'{"metadata":{"title":"迷宫饭","authors":["九井谅子"],"genres":["奇幻","冒险"]}}\' \\',
      '  "' + base + 'scrape/agent/queue/0K3M8Q1T7XGE5/result"',
      '```',
      'payload 可以套一层 `metadata`，也可以把字段平铺在顶层。数组字段用 JSON 数组，或用顿号 / 逗号 / 竖线分隔的字符串。别名照样识别：description → 简介、categories → 分类、author → 作者、publishDate → 出版日期、cover → 封面地址。',
      '返回 `status`：`DONE` 已应用并移出名单，`REVIEW` 进入待核对；`appliedFields` 是真正写入的字段，`message` 说明跳过或失败原因。空值不会被当成「清空」，只表示该字段保持一致。',
      '',
      '## 按册写',
      '```json',
      '{',
      '  "metadata": { "publisher": "青文" },',
      '  "books": [',
      '    { "bookId": "0K3M8Q1T7XGE5", "fields": ["title", "authors", "isbn"], "title": "迷宫饭 1", "authors": ["九井谅子"], "isbn": "9789863562305" }',
      '  ]',
      '}',
      '```',
      '带 `books` 时只写列出的册；`fields` 省略则默认写 title、number、authors、releaseDate、isbn、tags、summary、publisher、genres、language。队列条目本身带册范围时，范围外的 `bookId` 会被忽略；已锁定的字段跳过。',
      '',
      '## 查统计',
      '```bash',
      'curl -H "X-API-Token: lt_xxxxxxxx" "' + base + 'scrape/agent/stats"',
      '{"total":2,"queued":1,"review":1,"failed":0,"inboxReceived":1}',
      '```',
      '同样按条目数统计；没有 running 一项，正在跑的条目这四个数字都不计。`inboxReceived` 是已收到但还没处理完的回填数量。',
    ].join('\n');
  }

  const DOCS = {
    feature: { zh: docFeature, zht: docFeature, en: docFeature },
    usage: { zh: docUsage, zht: docUsage, en: docUsage },
    format: { zh: docFormat, zht: docFormat, en: docFormat },
    api: { zh: docApi, zht: docApi, en: docApi },
  };

  function scrapeHelpText(tab) {
    const g = langGroup();
    const set = DOCS[tab] || DOCS.feature;
    return set[g]() + '\n';
  }

  function scrapeHelpFileName(tab) {
    const l = (window.LantaiI18n && LantaiI18n.lang()) || 'zh-Hans';
    return 'lantai-scrape-' + (tab || 'feature') + '-' + l + '.md';
  }

  function helpCopy(tab) {
    const text = scrapeHelpText(tab);
    const done = () => toast(L.t('help.copied'), 'ok');
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, () => helpCopyFallback(text));
      return;
    }
    helpCopyFallback(text);
  }

  function helpCopyFallback(text) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    ta.remove();
    toast(ok ? L.t('help.copied') : L.t('help.copyFail'), ok ? 'ok' : 'warn');
  }

  function helpExport(tab) {
    const name = scrapeHelpFileName(tab);
    scDownload(scrapeHelpText(tab), name, 'text/markdown;charset=utf-8');
    toast(L.t('help.exported', { name: name }), 'ok');
  }

  window.ScrapeHelpDoc = {
    text: scrapeHelpText,
    copy: helpCopy,
    exportMd: helpExport,
    fileName: scrapeHelpFileName,
    clients: { zh: CN_CLIENTS, en: EN_CLIENTS },
  };
})();
