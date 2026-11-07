(function () {
  const EN_FIELDS = [
    ['title', 'Title', 'text', 'Series title', 'writable'],
    ['summary', 'Summary', 'text', 'Multi-line, use \\n for line breaks', 'writable'],
    ['publisher', 'Publisher', 'text', '', 'writable'],
    ['region', 'Region', 'text', 'ISO 3166-1 alpha-2, e.g. JP, KR, CN, TW; leave empty when unknown', 'writable'],
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
        'Scraping fills in series metadata: title, summary, publisher, region, language, genres, authors, release date, ISBN, tags and cover.',
        'Region records where the work comes from, as an ISO 3166-1 alpha-2 code (JP, KR, CN, TW and so on). Sources only report it when they actually know it; nothing is guessed from publisher names.',
        'The rule is: embedded metadata wins, only missing fields are filled, conflicts are never overwritten silently.',
        '',
        '## Where the queue comes from',
        mdTable(['Source', 'Notes'], [
          ['Single / multi select', 'Tick series in the library and choose Add to scrape queue; in per-book mode only the ticked books are registered'],
          ['Whole library', 'Start it from scrape settings or from the library page'],
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
        '刮削是給系列補後設資料：標題、簡介、出版社、地區、語言、分類、作者、出版日期、ISBN、標籤、封面。',
        '地區記錄作品來源地，用兩位國家代碼表示（日本 JP、韓國 KR、中國大陸 CN、中國台灣 TW 等）。資料來源只在確有依據時給出，取不到就留空，不從出版社名稱猜測。',
        '蘭台的原則是內嵌資訊優先、只補缺失、衝突不自動覆蓋。',
        '',
        '## 名單從哪來',
        mdTable(['來源', '說明'], [
          ['單本 / 多選', '書庫頁勾選系列後點「加入刮削名單」；切到「按書籍」勾選具體冊時只登記選中的冊'],
          ['整庫', '刮削設定或書庫頁對某個庫整體發起'],
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
      '刮削是给系列补元数据：标题、简介、出版社、地区、语言、分类、作者、出版日期、ISBN、标签、封面。',
      '地区记录作品来源地，用两位国家代码表示（日本 JP、韩国 KR、中国大陆 CN、中国台湾 TW 等）。数据源只在确有依据时给出，取不到就留空，不从出版社名称猜测。',
      '兰台的原则是**内嵌信息优先、只补缺失、冲突不自动覆盖**。',
      '',
      '## 名单从哪来',
      mdTable(['来源', '说明'], [
        ['单本 / 多选', '书库页勾选系列后点「加入刮削名单」；切到「按书籍」勾选具体册时只登记选中的册，同系列其它册不受影响'],
        ['整库', '刮削设置或书库页对某个库整体发起'],
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
        '',
        '## Option 4 · One book at a time, filled by AI',
        '1. Open a series or a book and press Edit metadata; the prompt buttons are at the bottom left of the dialog.',
        '2. Press Export prompt. It builds a Chinese prompt with the book parameters, the full metadata table (empty fields included), search keywords and a return template, then copies it to the clipboard.',
        '3. Paste it into any AI chat and send. The AI answers with one JSON object.',
        '4. Back in the dialog press Import result, paste the answer or choose the .txt file, then press Done to fill the form.',
        '5. Review the values and press Save. Nothing is written before that.',
        '',
        '**Length limit**: one-click copy and a single import are both capped at 20000 characters (roughly 6-8k tokens). A prompt is usually far below that. When it exceeds the cap, Export prompt downloads `meta-*.txt` instead and you load it with Import result → Choose file. Import only fills the fields present in the form; links, notable entities and alternate titles appear in the prompt for the AI but are never written back.',
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
        '',
        '## 方式四 · 單本交給 AI 補全',
        '1. 在系列或書籍詳情點「編輯中繼資料」，彈窗左下角有「匯出提示詞」',
        '2. 點它會產生一段中文提示詞：書籍參數、完整中繼資料表格（空欄位也列出）、檢索關鍵詞、回傳範本，並自動複製到剪貼簿',
        '3. 貼到任意 AI 對話框直接傳送，AI 按範本回傳完整 JSON',
        '4. 回到彈窗點「匯入結果」，貼上或選檔案，點「完成」把值填進表單',
        '5. 核對無誤再點「儲存」；不點儲存不會寫入任何東西',
        '',
        '**長度上限**：一鍵複製與單次匯入都以 20000 字元（約 6~8k token）為界。提示詞通常遠小於這個值；簡介極長等情況下會超出，此時「匯出提示詞」自動改為下載 `meta-*.txt`，用「匯入結果 → 選擇檔案」載回。匯入只填介面上有的欄位，連結、顯著實體、別名等僅出現在提示詞裡供 AI 參考，不會寫入。',
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
      '',
      '## 方式四 · 单本交给 AI 补全',
      '1. 在系列或书籍详情点「编辑元数据」，弹窗左下角有「导出提示词」',
      '2. 点它会生成一段中文提示词：书籍参数、完整元数据表格（空字段也列出）、检索关键词、返回模板，并自动复制到剪贴板',
      '3. 粘贴到任意 AI 对话框直接发送，AI 按模板返回完整 JSON',
      '4. 回到弹窗点「导入结果」，粘贴或选文件，点「完成」把值填进表单',
      '5. 核对无误再点「保存」；不点保存不会写入任何东西',
      '',
      '**文本上限**：一键复制与单次导入都以 20000 字符（约 6~8k token）为界。提示词通常远小于这个值；简介极长等情况下会超出，此时「导出提示词」自动改为下载 `meta-*.txt`，用「导入结果 → 选择文件」载回。导入只填界面上有的字段，链接、显著实体、别名等仅出现在提示词里供 AI 参考，不会写入。',
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
        '        "region": "JP",',
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
        '0K3M8Q1T7XGE5,0K3M8Q1T7XGE5,Delicious in Dungeon,QUEUED,Delicious in Dungeon,A culinary adventure.,Example Press,JP,zh-CN,Fantasy、Adventure,Ryoko Kui,2015-01-01,9789863562305,Completed,',
        '```',
        '',
        'The first row must be the header. Array fields accept 、, comma or pipe as separators. Quote cells containing commas with double quotes. Save as UTF-8.',
        '',
        '## Fields',
        mdTable(['Field', 'Name', 'Type', 'Notes', 'Write'], fieldRows('en')),
        '',
        '## Aliases',
        'description → summary, categories → genres, author → authors, publishDate → releaseDate, cover → coverUrl, country → region, queueId → id. Region is normalised to an uppercase two-letter code: send JP, not jp or Japan.',
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
        '        "region": "JP",',
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
        '0K3M8Q1T7XGE5,0K3M8Q1T7XGE5,迷宮飯,QUEUED,迷宮飯,地下迷宮裡的美食冒險。,青文,JP,zh-CN,奇幻、冒險,九井諒子,2015-01-01,9789863562305,已完結,',
        '```',
        '',
        '第一行必須是表頭。陣列欄位用頓號、逗號或豎線分隔都可以。含逗號的儲存格要用英文雙引號包起來。檔案存 UTF-8。',
        '',
        '## 欄位說明',
        mdTable(['欄位', '中文', '類型', '說明', '寫入'], fieldRows('zht')),
        '',
        '## 別名',
        'description → 簡介、categories → 分類、author → 作者、publishDate → 出版日期、cover → 封面位址、country → 地區、queueId → id。地區一律歸一成大寫兩位代碼，請寫 JP，不要寫 jp 或 Japan。',
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
      '        "region": "JP",',
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
      '0K3M8Q1T7XGE5,0K3M8Q1T7XGE5,迷宫饭,QUEUED,迷宫饭,地下迷宫里的美食冒险。,青文,JP,zh-CN,奇幻、冒险,九井谅子,2015-01-01,9789863562305,已完结,',
      '```',
      '',
      '第一行必须是表头。数组字段用顿号、逗号或竖线分隔都可以。含逗号的单元格要用英文双引号包起来。文件存 UTF-8。',
      '',
      '## 字段说明',
      mdTable(['字段', '中文', '类型', '说明', '写入'], fieldRows('zh')),
      '',
      '## 别名',
      '`description` → 简介，`categories` → 分类，`author` → 作者，`publishDate` → 出版日期，`cover` → 封面地址，`country` → 地区，`queueId` → id。地区一律归一成大写两位代码，请写 JP，不要写 jp 或 Japan。',
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
    const base = 'http(s)://xxx.xxx.xxx.xxx:xxxxx/api/lantai/v1/';
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
          ['GET', 'scrape/agent/queue/{id}', 'Entry detail: values already stored, book list, last payload', 'scrape:read'],
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
        'Returns the current values (summary, publisher, region, authors, genres, hasCover), the `books` list (each with bookId, fileName, title, number, authors, releaseDate, isbn, tags, summary, publisher, genres, language and the titleLock / numberLock / authorsLock flags) and `external`, the last submitted payload or null. Only values already stored in the database are returned: the server never opens the book files here, so the call answers in milliseconds. Deciding what the file really contains is the caller\'s job.',
        '',
        '## Submit a result',
        '```bash',
        'curl -X POST -H "X-API-Token: lt_xxxxxxxx" -H "Content-Type: application/json" \\',
        '  -d \'{"metadata":{"title":"Delicious in Dungeon","authors":["Ryoko Kui"],"genres":["Fantasy","Adventure"]}}\' \\',
        '  "' + base + 'scrape/agent/queue/0K3M8Q1T7XGE5/result"',
        '```',
        'Fields can sit under `metadata` or at the top level. Arrays accept JSON arrays or delimited strings. Aliases work: description, categories, author, publishDate, cover, country. Region is series level and must be an uppercase two-letter code.',
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
          ['GET', 'scrape/agent/queue/{id}', '取單條詳情：庫裡已存的現有值、冊清單、上次回填', 'scrape:read'],
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
        '回傳現有值（summary / publisher / region / authors / genres / hasCover）、`books` 冊清單（每冊含 bookId、fileName、title、number、authors、releaseDate、isbn、tags、summary、publisher、genres、language 與三個鎖定標記 titleLock / numberLock / authorsLock），以及 `external`——上次回填的原始 payload，沒有則為 null。這裡只回傳資料庫裡已經存下的參數，**不會打開書籍檔案解析內嵌中繼資料**，所以是毫秒級回傳；書裡到底寫了什麼，交給外部工具或人工判斷後再回傳。',
        '',
        '## 回傳結果',
        '```bash',
        'curl -X POST -H "X-API-Token: lt_xxxxxxxx" -H "Content-Type: application/json" \\',
        '  -d \'{"metadata":{"title":"迷宮飯","authors":["九井諒子"],"genres":["奇幻","冒險"]}}\' \\',
        '  "' + base + 'scrape/agent/queue/0K3M8Q1T7XGE5/result"',
        '```',
        'payload 可以套一層 `metadata`，也可以把欄位平鋪在頂層。陣列欄位用 JSON 陣列，或用頓號 / 逗號 / 豎線分隔的字串。別名照樣識別：description → 簡介、categories → 分類、author → 作者、publishDate → 出版日期、cover → 封面位址、country → 地區。地區屬系列級欄位，必須是歸一後的大寫兩位代碼。',
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
        ['GET', 'scrape/agent/queue/{id}', '取单条详情：库里已存的现有值、册清单、上次回填', 'scrape:read'],
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
      '返回现有值（summary / publisher / region / authors / genres / hasCover）、`books` 册清单（每册含 bookId、fileName、title、number、authors、releaseDate、isbn、tags、summary、publisher、genres、language 与三个锁定标记 titleLock / numberLock / authorsLock），以及 `external`——上次回填的原始 payload，没有则为 null。这里只返回数据库里已经存下的参数，**不会打开书籍文件解析内嵌元数据**，所以是毫秒级返回；书里到底写了什么，交给外部工具或人工判断后再回传。',
      '',
      '## 回传结果',
      '```bash',
      'curl -X POST -H "X-API-Token: lt_xxxxxxxx" -H "Content-Type: application/json" \\',
      '  -d \'{"metadata":{"title":"迷宫饭","authors":["九井谅子"],"genres":["奇幻","冒险"]}}\' \\',
      '  "' + base + 'scrape/agent/queue/0K3M8Q1T7XGE5/result"',
      '```',
      'payload 可以套一层 `metadata`，也可以把字段平铺在顶层。数组字段用 JSON 数组，或用顿号 / 逗号 / 竖线分隔的字符串。别名照样识别：description → 简介、categories → 分类、author → 作者、publishDate → 出版日期、cover → 封面地址、country → 地区。地区属系列级字段，必须是归一后的大写两位代码。',
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
    return set[g](g) + '\n';
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

const SC_META_FIELDS = [
  ['title', '标题', '文本', '系列标题', '可写'],
  ['summary', '简介', '文本', '可多行，换行用 \\n', '可写'],
  ['publisher', '出版社', '文本', '', '可写'],
  ['region', '地区', '文本', '两位国家代码，如 JP、KR、CN、TW，取不到留空', '可写'],
  ['language', '语言', '文本', 'BCP-47，如 zh-CN；zh 与 zh-CN 视为同一语言', '可写'],
  ['genres', '分类', '数组或分隔文本', '奇幻、冒险', '可写'],
  ['authors', '作者', '数组或分隔文本', '九井谅子', '可写'],
  ['releaseDate', '出版日期', 'YYYY-MM-DD 或 YYYY', '写给本次范围内各册', '可写'],
  ['isbn', 'ISBN', '文本', '', '可写'],
  ['tags', '标签', '数组或分隔文本', '已完结、全彩', '可写'],
  ['coverUrl', '封面地址', 'URL', '只接地址，不接文件', '可写'],
  ['ageRating', '年龄分级', '数字', '当前版本不写入', '忽略'],
];

const SC_CSV_HEAD = ['id', 'seriesId', 'seriesTitle', 'status', 'title', 'summary', 'publisher', 'region', 'language', 'genres', 'authors', 'releaseDate', 'isbn', 'tags', 'coverUrl'];

function scText(v) {
  return v == null ? '' : String(v).trim();
}

function scSplitList(v) {
  if (Array.isArray(v)) return v.map(x => scText(x)).filter(Boolean);
  if (v == null) return [];
  return String(v).split(/[,、|/；;]/).map(x => x.trim()).filter(Boolean);
}

function scEmptyMetadata() {
  return {
    title: '', summary: '', publisher: '', region: '', language: '',
    genres: [], authors: [], releaseDate: '', isbn: '', tags: [], coverUrl: '',
  };
}

function scCsvCell(v) {
  const t = v == null ? '' : String(v);
  return /[",\r\n]/.test(t) ? '"' + t.replace(/"/g, '""') + '"' : t;
}

function scCsvText(items) {
  const lines = [SC_CSV_HEAD.join(',')];
  items.forEach(x => {
    const m = x.metadata || {};
    lines.push([
      x.id, x.seriesId, x.seriesTitle, x.status,
      m.title, m.summary, m.publisher, m.region, m.language,
      Array.isArray(m.genres) ? m.genres.join('、') : m.genres,
      Array.isArray(m.authors) ? m.authors.join('、') : m.authors,
      m.releaseDate, m.isbn,
      Array.isArray(m.tags) ? m.tags.join('、') : m.tags,
      m.coverUrl,
    ].map(scCsvCell).join(','));
  });
  return '\ufeff' + lines.join('\r\n');
}

function scJsonText(items) {
  return JSON.stringify({
    version: 1,
    exportedAt: new Date().toISOString().slice(0, 19),
    count: items.length,
    items: items,
  }, null, 2);
}

function scCsvRows(text) {
  const src = String(text || '').replace(/^\ufeff/, '');
  const rows = [];
  let row = [], cell = '', quoted = false;
  for (let i = 0; i < src.length; i++) {
    const c = src.charAt(i);
    if (quoted) {
      if (c === '"') {
        if (src.charAt(i + 1) === '"') { cell += '"'; i++; }
        else quoted = false;
      } else cell += c;
      continue;
    }
    if (c === '"') { quoted = true; continue; }
    if (c === ',') { row.push(cell); cell = ''; continue; }
    if (c === '\r') continue;
    if (c === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; continue; }
    cell += c;
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row); }
  return rows.filter(r => r.some(v => scText(v) !== ''));
}

function scNormalizeDate(v) {
  const t = scText(v);
  if (!t) return '';
  const full = t.match(/^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/);
  if (full) return full[1] + '-' + String(full[2]).padStart(2, '0') + '-' + String(full[3]).padStart(2, '0');
  const year = t.match(/^(\d{4})$/);
  if (year) return year[1] + '-01-01';
  return '';
}

function scNormalizeEntry(raw) {
  if (!raw || typeof raw !== 'object') return { ok: false, reason: '不是有效的条目对象' };
  const id = scText(raw.id || raw.queueId);
  const seriesId = scText(raw.seriesId);
  if (!id && !seriesId) return { ok: false, reason: '缺少 id 与 seriesId' };
  const source = raw.metadata && typeof raw.metadata === 'object' ? raw.metadata : raw;
  const metadata = {
    title: scText(source.title),
    summary: scText(source.summary || source.description),
    publisher: scText(source.publisher),
    region: scText(source.region || source.country).toUpperCase(),
    language: scText(source.language),
    genres: scSplitList(source.genres != null ? source.genres : source.categories),
    authors: scSplitList(source.authors != null ? source.authors : source.author),
    releaseDate: scNormalizeDate(source.releaseDate || source.publishDate),
    isbn: scText(source.isbn),
    tags: scSplitList(source.tags),
    coverUrl: scText(source.coverUrl || source.cover),
  };
  const age = source.ageRating;
  if (age != null && age !== '') metadata.ageRating = Number(age) || null;
  const filled = metadata.title || metadata.summary || metadata.publisher || metadata.region || metadata.language ||
    metadata.genres.length || metadata.authors.length || metadata.releaseDate ||
    metadata.isbn || metadata.tags.length || metadata.coverUrl;
  if (!filled) return { ok: false, reason: '所有字段均为空' };
  return {
    ok: true,
    value: {
      id: id, seriesId: seriesId, seriesTitle: scText(raw.seriesTitle),
      status: scText(raw.status), metadata: metadata,
    },
  };
}

function scImportJson(text) {
  let doc;
  try { doc = JSON.parse(text); } catch (e) { return { items: [], skipped: [{ line: 0, reason: 'JSON 解析失败：' + e.message }] }; }
  const list = Array.isArray(doc) ? doc : (doc && Array.isArray(doc.items) ? doc.items : (doc && doc.items ? [doc.items] : [doc]));
  const items = [], skipped = [];
  list.forEach((raw, i) => {
    const one = scNormalizeEntry(raw);
    if (one.ok) items.push(one.value); else skipped.push({ line: i + 1, reason: one.reason });
  });
  return { items: items, skipped: skipped };
}

function scImportCsv(text) {
  const rows = scCsvRows(text);
  if (!rows.length) return { items: [], skipped: [{ line: 0, reason: '没有可解析的行' }] };
  const head = rows[0].map(x => scText(x));
  if (head.indexOf('id') < 0 && head.indexOf('seriesId') < 0) {
    return { items: [], skipped: [{ line: 1, reason: '表头必须包含 id 或 seriesId 列' }] };
  }
  const items = [], skipped = [];
  for (let i = 1; i < rows.length; i++) {
    const obj = {};
    head.forEach((k, j) => { if (k) obj[k] = scText(rows[i][j]); });
    const one = scNormalizeEntry({
      id: obj.id, seriesId: obj.seriesId, seriesTitle: obj.seriesTitle, status: obj.status,
      metadata: {
        title: obj.title, summary: obj.summary, publisher: obj.publisher, region: obj.region, language: obj.language,
        genres: obj.genres, authors: obj.authors, releaseDate: obj.releaseDate,
        isbn: obj.isbn, tags: obj.tags, coverUrl: obj.coverUrl, ageRating: obj.ageRating,
      },
    });
    if (one.ok) items.push(one.value); else skipped.push({ line: i + 1, reason: one.reason });
  }
  return { items: items, skipped: skipped };
}

function scImportEntries(text) {
  const raw = scText(text);
  if (!raw) return { items: [], skipped: [{ line: 0, reason: '内容为空' }] };
  const first = raw.charAt(0);
  if (first === '{' || first === '[') return scImportJson(raw);
  return scImportCsv(raw);
}

function scFieldTable() {
  return '<div class="tbl-wrap" style="margin-top:12px"><table class="tbl"><thead><tr><th style="width:110px">字段</th><th style="width:78px">中文</th><th style="width:130px">类型</th><th>说明</th><th style="width:64px">写入</th></tr></thead><tbody>' +
    SC_META_FIELDS.map(f => '<tr><td class="mono mini">' + f[0] + '</td><td class="mini">' + f[1] + '</td><td class="mini">' + f[2] + '</td><td class="mini">' + (f[3] || '—') + '</td><td><span class="badge' + (f[4] === '可写' ? ' moss' : '') + '">' + f[4] + '</span></td></tr>').join('') +
    '</tbody></table></div>';
}

function scHelpFeature() {
  return '<div class="note">刮削是给系列补元数据：标题、简介、出版社、地区、语言、分类、作者、出版日期、ISBN、标签、封面。地区记录作品来源地，用两位国家代码表示（如日本 JP、韩国 KR、中国大陆 CN），各地数据源只在确有依据时给出，取不到就留空，不做猜测性映射。兰台的原则是<b>内嵌信息优先、只补缺失、冲突不自动覆盖</b>。</div>' +
    '<h4 style="margin:16px 0 8px">名单从哪来</h4>' +
    '<div class="tbl-wrap"><table class="tbl"><thead><tr><th style="width:130px">来源</th><th>说明</th></tr></thead><tbody>' +
    '<tr><td>单本 / 多选</td><td class="mini">书库页勾选系列后点「加入刮削名单」；切到「按书籍」勾选具体册时只登记选中的册，同系列其它册不受影响</td></tr>' +
    '<tr><td>整库</td><td class="mini">刮削设置或书库页对某个库整体发起</td></tr>' +
    '</tbody></table></div>' +
    '<h4 style="margin:16px 0 8px">四种状态</h4>' +
    '<div class="tbl-wrap"><table class="tbl"><thead><tr><th style="width:110px">状态</th><th>含义</th><th style="width:170px">下一步</th></tr></thead><tbody>' +
    '<tr><td><span class="badge">待刮</span></td><td class="mini">已入队，等待处理</td><td class="mini">点「开始刮削」</td></tr>' +
    '<tr><td><span class="badge warn">刮削中</span></td><td class="mini">正在提取内嵌信息、查询数据源、比对差异</td><td class="mini">等进度走完</td></tr>' +
    '<tr><td><span class="badge warn">待核对</span></td><td class="mini">存在与现有值不一致的字段，需要人工决定</td><td class="mini">展开逐字段勾选</td></tr>' +
    '<tr><td><span class="badge bad">失败</span></td><td class="mini">数据源报错或无结果</td><td class="mini">重试或加数据源</td></tr>' +
    '</tbody></table></div>' +
    '<div class="note" style="margin-top:14px">一个条目的完整一生：加入名单 → 提取内嵌元数据 → 查数据源 → 字段级差异比对 → 整系列且无冲突时直接写入并移出名单 / 有冲突、或按册登记时转「待核对」由人决定写哪几册。已锁定的字段永远跳过。</div>';
}

function scHelpUsage() {
  return '<div class="card" style="margin-bottom:14px"><div class="card-h"><h3>方式一 · 内置数据源自动刮</h3></div>' +
    '<ol class="mini" style="margin:0;padding-left:20px;line-height:2">' +
    '<li>在书库勾选系列加进名单，或在刮削设置里对整库发起；切到「按书籍」勾选具体册时只登记那几册</li>' +
    '<li>打开「刮削设置 → 数据源」，至少启用一个源，并确认今日额度够用</li>' +
    '<li>回名单点「开始刮削」；开着页面能实时看到进度</li>' +
    '<li>有冲突的条目会进「待核对」，展开后逐字段勾选再应用</li>' +
    '</ol></div>' +
    '<div class="card" style="margin-bottom:14px"><div class="card-h"><h3>方式二 · 导出 → 外部填写 → 导入</h3></div>' +
    '<ol class="mini" style="margin:0;padding-left:20px;line-height:2">' +
    '<li>点「导出名单」，选范围和格式（JSON 适合交给 AI，CSV 适合表格软件）</li>' +
    '<li>按格式说明填写，<b>id 列不要改动</b>（它是名单条目 id，导入时按它反查系列），也可以直接填 <span class="mono">seriesId</span>，不需要的字段留空</li>' +
    '<li>点「导入数据」，粘贴或选文件，先看解析预览再确认写入</li>' +
    '<li>写入一律直接覆盖，不进待核对；原本在名单里的系列会自动移出名单</li>' +
    '</ol></div>' +
    '<div class="card"><div class="card-h"><h3>方式三 · API 对接外部程序</h3></div>' +
    '<ol class="mini" style="margin:0;padding-left:20px;line-height:2">' +
    '<li>「刮削设置 → 刮削令牌 → 生成刮削令牌」拿一个令牌，权限固定为读取名单 + 提交结果</li>' +
    '<li>脚本先拉 <span class="mono">scrape/agent/queue</span> 拿条目 id，再逐条取详情</li>' +
    '<li>把结果 <span class="mono">POST</span> 到 <span class="mono">scrape/agent/queue/{id}/result</span></li>' +
    '<li>详见「API 接口」页签</li>' +
    '</ol></div>' +
    '<div class="card" style="margin-top:14px"><div class="card-h"><h3>方式四 · 单本交给 AI 补全</h3></div>' +
    '<ol class="mini" style="margin:0;padding-left:20px;line-height:2">' +
    '<li>在系列或书籍详情点「编辑元数据」，弹窗左下角有「导出提示词」</li>' +
    '<li>点它会生成一段中文提示词：书籍参数、完整元数据表格（空字段也列出）、检索关键词、返回模板，并自动复制到剪贴板</li>' +
    '<li>粘贴到任意 AI 对话框直接发送，AI 按模板返回完整 JSON</li>' +
    '<li>回到弹窗点「导入结果」，粘贴或选文件，点「完成」把值填进表单</li>' +
    '<li>核对无误再点「保存」；不点保存不会写入任何东西</li>' +
    '</ol>' +
    '<div class="note" style="margin-top:10px">文本上限：一键复制与单次导入都以 <b>20000 字符</b>（约 6~8k token）为界。提示词通常远小于这个值；简介极长等情况下会超出，此时「导出提示词」自动改为下载 <span class="mono">meta-*.txt</span>，「导入结果 → 选择文件」可直接载回，不需要手动复制。导入只填界面上有的字段，链接、显著实体、别名等仅出现在提示词里供 AI 参考，不会写入。</div></div>' +
    '<div class="card" style="margin-top:14px"><div class="card-h"><h3>方式五 · 整份名单交给 AI 自己跑</h3></div>' +
    '<ol class="mini" style="margin:0;padding-left:20px;line-height:2">' +
    '<li>先在「刮削设置 → 刮削令牌」准备一个令牌：只能读名单、只能提交结果</li>' +
    '<li>回名单点「AI 提示词」，选处理范围（不限条数，或只扫前 N 条），点「生成提示词」</li>' +
    '<li>把提示词里的 <span class="mono">&lt;你的刮削令牌&gt;</span> 换成真实令牌，整段粘给能发 HTTP 请求的 AI（本机脚本、命令行 agent）</li>' +
    '<li>AI 自己拉名单、逐条取详情、检索资料、逐条回写，跑完给你一张汇总表</li>' +
    '<li>回名单核对：一致的条目已自动移出，进「待核对」的展开逐字段确认</li>' +
    '</ol>' +
    '<div class="note" style="margin-top:10px">网页版对话框一般发不了请求，也访问不到本机端口。这种情况勾选「附上名单快照」：提示词里直接列出待刮条目，AI 只算不写，返回 JSON 后走「导入数据」写回，且只能填系列级字段。令牌不会写进提示词，避免明文跟着聊天记录外流。</div></div>';
}

function scHelpFormat() {
  return '<div class="note">两种格式内容等价，任选一种。JSON 字段是嵌套的 <span class="mono">metadata</span> 对象，CSV 是平铺的一行。</div>' +
    '<h4 style="margin:16px 0 8px">JSON 结构</h4>' +
    '<pre class="mono" style="font-size:11.5px;line-height:1.75;background:var(--bg-2);padding:14px;border-radius:8px;overflow:auto">' +
    '{\n  "version": 1,\n  "count": 2,\n  "items": [\n    {\n      "id": "0K3M8Q1T7XGE5",\n      "seriesId": "0K3M8Q1T7XGE5",\n      "seriesTitle": "迷宫饭",\n      "status": "QUEUED",\n      "metadata": {\n        "title": "迷宫饭",\n        "summary": "地下迷宫里的美食冒险。",\n        "publisher": "青文",\n        "region": "JP",\n        "language": "zh-CN",\n        "genres": ["奇幻", "冒险"],\n        "authors": ["九井谅子"],\n        "releaseDate": "2015-01-01",\n        "isbn": "9789863562305",\n        "tags": ["已完结"],\n        "coverUrl": ""\n      }\n    }\n  ]\n}' +
    '</pre>' +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">直接传单个对象、或直接传数组也可以，解析时会自动兼容。</div>' +
    '<h4 style="margin:16px 0 8px">CSV 列</h4>' +
    '<pre class="mono" style="font-size:11.5px;line-height:1.75;background:var(--bg-2);padding:14px;border-radius:8px;overflow:auto">' +
    SC_CSV_HEAD.join(',') + '\n' +
    '0K3M8Q1T7XGE5,0K3M8Q1T7XGE5,迷宫饭,QUEUED,迷宫饭,地下迷宫里的美食冒险。,青文,JP,zh-CN,奇幻、冒险,九井谅子,2015-01-01,9789863562305,已完结,' +
    '</pre>' +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">第一行必须是表头。数组字段用顿号、逗号或竖线分隔都可以。含逗号的单元格要用英文双引号包起来。文件存 UTF-8。</div>' +
    '<h4 style="margin:16px 0 8px">字段说明</h4>' +
    scFieldTable() +
    '<h4 style="margin:16px 0 8px">别名</h4>' +
    '<div class="note">填写时下面这些写法都能识别：<span class="mono">description</span> → 简介，<span class="mono">categories</span> → 分类，<span class="mono">author</span> → 作者，<span class="mono">publishDate</span> → 出版日期，<span class="mono">cover</span> → 封面地址，<span class="mono">country</span> → 地区，<span class="mono">queueId</span> → id。地区一律归一成大写两位代码，如 <span class="mono">jp</span>、<span class="mono">Japan</span> 需自行写成 <span class="mono">JP</span>。</div>' +
    '<h4 style="margin:16px 0 8px">三条硬规则</h4>' +
    '<div class="tbl-wrap"><table class="tbl"><thead><tr><th style="width:150px">规则</th><th>说明</th></tr></thead><tbody>' +
    '<tr><td>留空不覆盖</td><td class="mini">字段为空或空数组时，该字段视为「保持一致」，不会清空现有值。想清空只能到书库里手动改。</td></tr>' +
    '<tr><td>重复导入即覆盖</td><td class="mini">手动导入一律按 <span class="mono">seriesId</span> 直接覆盖写入，不进待核对；同一条导入多次，后一次覆盖前一次。已锁定的字段仍然跳过。</td></tr>' +
    '<tr><td>名单条目自动清理</td><td class="mini">导入的系列若还在刮削名单里，写入成功后自动移出名单，不留待核对条目。</td></tr>' +
    '</tbody></table></div>' +
    '<h4 style="margin:16px 0 8px">限制</h4>' +
    '<div class="note">条数没有硬上限，但导入是逐条串行写入，几百条以上会比较慢；封面只接受图片地址，不支持上传文件；<span class="mono">ageRating</span> 当前版本不写入；导入来源记为当前登录账号。</div>';
}

function scHelpApi() {
  const rows = [
    ['GET', 'scrape/agent/queue', '拉一页名单：条目 id、系列 id 与标题、状态、尝试次数', 'scrape:read'],
    ['GET', 'scrape/agent/queue/{id}', '取单条详情：库里已存的现有值、册清单、上次回填', 'scrape:read'],
    ['POST', 'scrape/agent/queue/{id}/result', '提交结果；id 给条目 id 走比对，给 seriesId 直接覆盖', 'scrape:submit'],
    ['GET', 'scrape/agent/stats', '名单条目数与待处理回填数', 'scrape:read'],
  ];
  const box = s => '<pre class="mono" style="font-size:11.5px;line-height:1.75;background:var(--bg-2);padding:14px;border-radius:8px;overflow:auto">' + s + '</pre>';
  return '<div class="note">基地址 <span class="mono">http(s)://xxx.xxx.xxx.xxx:xxxxx/api/lantai/v1/</span>，把 <span class="mono">xxx</span> 换成实际的主机与端口。对外只开放下面四个接口，令牌在「刮削设置 → 刮削令牌」生成，权限写死为 <span class="mono">scrape:read</span> 与 <span class="mono">scrape:submit</span>，读不到书库其它接口。</div>' +
    '<div class="note" style="margin-top:8px">外部程序用请求头传令牌，二选一：<span class="mono">X-API-Token: lt_xxx</span> 或 <span class="mono">Authorization: Bearer lt_xxx</span>。浏览器登录态的 <span class="mono">LANTAI-SESSION</span> Cookie 也能通，但脚本不要依赖它。</div>' +
    '<div class="tbl-wrap" style="margin-top:12px"><table class="tbl"><thead><tr><th style="width:64px">方法</th><th style="width:290px">路径</th><th>说明</th><th style="width:110px">所需权限</th></tr></thead><tbody>' +
    rows.map(r => '<tr><td><span class="badge">' + r[0] + '</span></td><td class="mono mini">' + r[1] + '</td><td class="mini">' + r[2] + '</td><td class="mini mono">' + r[3] + '</td></tr>').join('') +
    '</tbody></table></div>' +
    '<h4 style="margin:16px 0 8px">拉名单</h4>' +
    box('curl -H "X-API-Token: lt_xxxxxxxx" \\\n  "http(s)://xxx.xxx.xxx.xxx:xxxxx/api/lantai/v1/scrape/agent/queue?offset=0&amp;limit=50"') +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)"><span class="mono">offset</span> 默认 0，<span class="mono">limit</span> 默认 20、最大 200。返回的 <span class="mono">total</span> 与 <span class="mono">counts</span>（queued / running / review / failed）都是<b>名单条目数</b>——一个系列一行，不是册数；<span class="mono">items</span> 每条含 <span class="mono">id</span>、<span class="mono">seriesId</span>、<span class="mono">seriesTitle</span>、<span class="mono">status</span>、<span class="mono">attempt</span>、<span class="mono">createdDate</span>、<span class="mono">lastError</span>，以及 <span class="mono">bookIds</span>——空数组表示整系列，非空表示只刮这几册，长度即册数。</div>' +
    '<h4 style="margin:16px 0 8px">取单条详情</h4>' +
    box('curl -H "X-API-Token: lt_xxxxxxxx" \\\n  "http(s)://xxx.xxx.xxx.xxx:xxxxx/api/lantai/v1/scrape/agent/queue/0K3M8Q1T7XGE5"') +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">返回现有值（<span class="mono">summary</span> / <span class="mono">publisher</span> / <span class="mono">region</span> / <span class="mono">authors</span> / <span class="mono">genres</span> / <span class="mono">hasCover</span>）、<span class="mono">books</span> 册清单（每册含 <span class="mono">bookId</span>、<span class="mono">fileName</span>、<span class="mono">title</span>、<span class="mono">number</span>、<span class="mono">authors</span>、<span class="mono">releaseDate</span>、<span class="mono">isbn</span>、<span class="mono">tags</span>、<span class="mono">summary</span>、<span class="mono">publisher</span>、<span class="mono">genres</span>、<span class="mono">language</span> 与三个锁定标记 <span class="mono">titleLock</span> / <span class="mono">numberLock</span> / <span class="mono">authorsLock</span>），以及 <span class="mono">external</span>——上次回填的原始 payload，没有则为 null。</div>' +
    '<div class="mini" style="margin-top:6px;color:var(--tx-3)">详情只读数据库里已经存下的参数，<b>不会打开书籍文件解析内嵌元数据</b>，所以是毫秒级返回；书里到底写了什么，交给外部工具或人工判断后再回传。</div>' +
    '<h4 style="margin:16px 0 8px">回传结果</h4>' +
    box('curl -X POST -H "X-API-Token: lt_xxxxxxxx" -H "Content-Type: application/json" \\\n  -d \'{"metadata":{"title":"迷宫饭","authors":["九井谅子"],"genres":["奇幻","冒险"]}}\' \\\n  "http(s)://xxx.xxx.xxx.xxx:xxxxx/api/lantai/v1/scrape/agent/queue/0K3M8Q1T7XGE5/result"') +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">payload 可以套一层 <span class="mono">metadata</span>，也可以把字段平铺在顶层。数组字段用 JSON 数组，或用顿号 / 逗号 / 竖线分隔的字符串。别名照样识别：<span class="mono">description</span> → 简介、<span class="mono">categories</span> → 分类、<span class="mono">author</span> → 作者、<span class="mono">publishDate</span> → 出版日期、<span class="mono">cover</span> → 封面地址。</div>' +
    '<div class="mini" style="margin-top:6px;color:var(--tx-3)">返回 <span class="mono">status</span>：<span class="mono">DONE</span> 已应用并移出名单，<span class="mono">REVIEW</span> 进入待核对；<span class="mono">appliedFields</span> 是真正写入的字段，<span class="mono">message</span> 说明跳过或失败原因。空值不会被当成「清空」，只表示该字段保持一致。</div>' +
    '<h4 style="margin:16px 0 8px">按册写</h4>' +
    box('{\n  "metadata": { "publisher": "青文" },\n  "books": [\n    { "bookId": "0K3M8Q1T7XGE5", "fields": ["title", "authors", "isbn"], "title": "迷宫饭 1", "authors": ["九井谅子"], "isbn": "9789863562305" }\n  ]\n}') +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">带 <span class="mono">books</span> 时只写列出的册；<span class="mono">fields</span> 省略则默认写 <span class="mono">title</span>、<span class="mono">number</span>、<span class="mono">authors</span>、<span class="mono">releaseDate</span>、<span class="mono">isbn</span>、<span class="mono">tags</span>、<span class="mono">summary</span>、<span class="mono">publisher</span>、<span class="mono">genres</span>、<span class="mono">language</span>。队列条目本身带册范围时，范围外的 <span class="mono">bookId</span> 会被忽略；已锁定的字段跳过。</div>' +
    '<h4 style="margin:16px 0 8px">查统计</h4>' +
    box('curl -H "X-API-Token: lt_xxxxxxxx" "http(s)://xxx.xxx.xxx.xxx:xxxxx/api/lantai/v1/scrape/agent/stats"\n\n{"total":2,"queued":1,"review":1,"failed":0,"inboxReceived":1}') +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">同样按条目数统计；没有 running 一项，正在跑的条目这四个数字都不计。<span class="mono">inboxReceived</span> 是已收到但还没处理完的回填数量。</div>';
}

const SC_HELP_TABS = [
  ['feature', '功能', scHelpFeature],
  ['usage', '使用', scHelpUsage],
  ['format', '格式', scHelpFormat],
  ['api', 'API 接口', scHelpApi],
];

function scrapeHelpHtml(tab) {
  const hit = SC_HELP_TABS.filter(t => t[0] === tab)[0] || SC_HELP_TABS[0];
  return '<div class="rowflex" style="margin-bottom:14px;gap:8px;flex-wrap:wrap">' +
    '<div class="seg" style="margin:0">' +
    SC_HELP_TABS.map(t => '<button class="' + (hit[0] === t[0] ? 'on' : '') + '" data-sc-help-tab="' + t[0] + '">' + t[1] + '</button>').join('') +
    '</div>' +
    '<div class="rowflex" style="margin-left:auto;gap:8px">' +
    '<button class="btn sm" data-sc-help-copy>' + ico('copy') + L.t('help.copy') + '</button>' +
    '<button class="btn sm" data-sc-help-export>' + ico('dl') + L.t('help.export') + '</button>' +
    '</div></div><div>' + hit[2]() + '</div>';
}

let scHelpTab = 'feature';

function scHelpModal(tab) {
  scHelpTab = tab || 'feature';
  openModal({
    title: '刮削说明', width: 900,
    body: '<div id="scHelp"></div>',
    foot: '<button class="btn" data-x>关闭</button>',
    after() {
      $('#modal [data-x]').onclick = closeModal;
      paintScHelp();
    },
  });
}

function paintScHelp() {
  const box = $('#scHelp');
  if (!box) return;
  box.innerHTML = scrapeHelpHtml(scHelpTab);
  $$('#scHelp [data-sc-help-tab]').forEach(el => el.onclick = () => { scHelpTab = el.dataset.scHelpTab; paintScHelp(); });
  const cp = $('#scHelp [data-sc-help-copy]');
  if (cp) cp.onclick = () => ScrapeHelpDoc.copy(scHelpTab);
  const ex = $('#scHelp [data-sc-help-export]');
  if (ex) ex.onclick = () => ScrapeHelpDoc.exportMd(scHelpTab);
}

function scBytes(n) {
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(2) + ' MB';
}

function scDownload(text, name, mime) {
  const url = URL.createObjectURL(new Blob([text], { type: mime || 'text/plain;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1500);
}

function scQueuePages(status, onProgress) {
  const pageSize = 200;
  const all = [];
  let total = 0;
  let guard = 0;
  const step = () => {
    guard++;
    if (guard > 40) return Promise.resolve(all);
    const qs = 'offset=' + all.length + '&limit=' + pageSize + (status ? '&status=' + encodeURIComponent(status) : '');
    return LantaiRuntime.request('api/lantai/v1/scrape/queue?' + qs).then(page => {
      const items = (page && page.items) || [];
      total = (page && page.total) || 0;
      items.forEach(x => all.push(x));
      if (onProgress) onProgress(all.length, total);
      if (items.length < pageSize || (total && all.length >= total)) return all;
      return step();
    });
  };
  return step();
}

function scProgBox(done, total, label) {
  const pct = total ? Math.min(100, Math.floor(done * 100 / total)) : 0;
  return '<div class="card" style="margin-top:14px"><div class="mini">' + esc(label) + ' · ' + done + (total ? ' / ' + total : '') + '</div>' +
    '<div class="uprog" style="margin-top:8px"><i style="width:' + pct + '%"></i></div></div>';
}

let scExpScope = 'all', scExpFormat = 'json', scExpOut = null, scExpBusy = false;

function scExportModal() {
  scExpScope = scFilter ? 'filter' : 'all';
  scExpFormat = 'json';
  scExpOut = null;
  scExpBusy = false;
  openModal({
    title: '导出刮削名单', width: 720,
    body: '<div id="scExp"></div>',
    foot: '<button class="btn" data-x>关闭</button>',
    after() {
      $('#modal [data-x]').onclick = closeModal;
      paintScExport();
    },
  });
}

function paintScExport() {
  const box = $('#scExp');
  if (!box) return;
  const c = scCounts();
  const filterName = (SC_STATUS[scFilter] || ['', '当前筛选'])[1];
  const filterCount = { QUEUED: c.queued, RUNNING: c.running, REVIEW: c.review, FAILED: c.failed }[scFilter] || 0;
  box.innerHTML = '<div class="note">导出的是当前刮削名单。填写后导入即可写回，<b>导入会直接覆盖</b>；<b>id 列不要改动</b>，留空的字段不会覆盖现有值。</div>' +
    '<div class="field" style="margin-top:14px"><label>范围</label><div class="seg">' +
    '<button data-sc-exp-scope="all" class="' + (scExpScope === 'all' ? 'on' : '') + '">全部 ' + (c.total || 0) + ' 条</button>' +
    (scFilter ? '<button data-sc-exp-scope="filter" class="' + (scExpScope === 'filter' ? 'on' : '') + '">仅' + filterName + ' ' + filterCount + ' 条</button>' : '') +
    '</div></div>' +
    '<div class="field"><label>格式</label><div class="seg">' +
    '<button data-sc-exp-fmt="json" class="' + (scExpFormat === 'json' ? 'on' : '') + '">JSON</button>' +
    '<button data-sc-exp-fmt="csv" class="' + (scExpFormat === 'csv' ? 'on' : '') + '">CSV</button>' +
    '</div><div class="mini" style="margin-top:6px;color:var(--tx-3)">JSON 保留嵌套结构，适合交给 AI；CSV 适合表格软件，数组字段用顿号分隔。</div></div>' +
    '<div class="rowflex" style="margin-top:14px"><button class="btn pri" data-sc-exp-run' + (scExpBusy ? ' disabled' : '') + '>' + ico('dl') + '生成文件</button>' +
    '<button class="btn ghost" data-sc-exp-help>' + ico('file') + '格式说明</button></div>' +
    '<div id="scExpOut"></div>';
  $$('#scExp [data-sc-exp-scope]').forEach(el => el.onclick = () => { scExpScope = el.dataset.scExpScope; scExpOut = null; paintScExport(); });
  $$('#scExp [data-sc-exp-fmt]').forEach(el => el.onclick = () => { scExpFormat = el.dataset.scExpFmt; scExpOut = null; paintScExport(); });
  const run = $('#scExp [data-sc-exp-run]');
  if (run) run.onclick = scExportBuild;
  const help = $('#scExp [data-sc-exp-help]');
  if (help) help.onclick = () => scHelpModal('format');
  paintScExpOut();
}

function paintScExpOut() {
  const out = $('#scExpOut');
  if (!out) return;
  if (!scExpOut) { out.innerHTML = ''; return; }
  const text = scExpOut.text;
  const preview = text.length > 4000 ? text.slice(0, 4000) + '\n…（已截断，完整内容请下载）' : text;
  out.innerHTML = '<div class="card" style="margin-top:14px"><div class="card-h"><h3>' + ico('file') + ' 已生成 ' + scExpOut.count + ' 条</h3>' +
    '<div class="r"><span class="badge">' + scBytes(new Blob([text]).size) + '</span></div></div>' +
    '<div class="rowflex" style="gap:8px"><button class="btn pri" data-sc-exp-dl>' + ico('dl') + '下载 ' + esc(scExpOut.name) + '</button>' +
    '<button class="btn" data-sc-exp-copy>' + ico('copy') + '复制全部</button></div>' +
    '<textarea class="inp mono" readonly spellcheck="false" style="height:220px;margin-top:12px;font-size:11.5px">' + esc(preview) + '</textarea></div>';
  const dl = $('#scExp [data-sc-exp-dl]');
  if (dl) dl.onclick = () => scDownload(text, scExpOut.name, scExpFormat === 'csv' ? 'text/csv;charset=utf-8' : 'application/json;charset=utf-8');
  const cp = $('#scExp [data-sc-exp-copy]');
  if (cp) cp.onclick = () => copyText(text, '名单');
}

function scExportBuild() {
  if (scExpBusy) return;
  const status = scExpScope === 'filter' ? scFilter : '';
  scExpBusy = true;
  paintScExport();
  const out = $('#scExpOut');
  if (out) out.innerHTML = scProgBox(0, 0, '正在读取名单');
  scQueuePages(status, (n, total) => {
    const box = $('#scExpOut');
    if (box) box.innerHTML = scProgBox(n, total, '正在读取名单');
  }).then(items => {
    scExpBusy = false;
    const list = items.map(x => ({ id: x.id, seriesId: x.seriesId, seriesTitle: x.seriesTitle, status: x.status, metadata: scEmptyMetadata() }));
    if (!list.length) { paintScExport(); return toast('没有可导出的条目', 'err'); }
    const text = scExpFormat === 'csv' ? scCsvText(list) : scJsonText(list);
    const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, '');
    scExpOut = { text: text, count: list.length, name: 'lantai-scrape-' + stamp + '.' + (scExpFormat === 'csv' ? 'csv' : 'json') };
    paintScExport();
    toast('已生成 ' + list.length + ' 条', 'ok');
  }).catch(e => {
    scExpBusy = false;
    paintScExport();
    toast('导出失败：' + onlineErrText(e), 'err');
  });
}

let scImpPlan = null, scImpSkipped = [], scImpBusy = false, scImpResult = null;

function scImportModal() {
  scImpPlan = null;
  scImpSkipped = [];
  scImpBusy = false;
  scImpResult = null;
  openModal({
    title: '导入刮削数据', width: 860,
    body: '<div class="note">支持 JSON 与 CSV。先解析预览，确认后才写入。写入直接覆盖，不进待核对；留空的字段不覆盖。</div>' +
      '<div class="rowflex" style="gap:8px;margin-top:12px;align-items:center">' +
      '<button class="btn" data-sc-imp-file>' + ico('upload') + '选择文件</button>' +
      '<span class="mini" id="scImpName">未选择文件</span>' +
      '<input type="file" id="scImpFile" accept=".json,.csv,.txt,application/json,text/csv,text/plain" style="display:none">' +
      '</div>' +
      '<div class="field" style="margin-top:12px"><label>或直接粘贴</label>' +
      '<textarea class="inp mono" id="scImpText" placeholder="粘贴 JSON 或 CSV 全文" spellcheck="false" style="height:150px;font-size:11.5px"></textarea></div>' +
      '<div class="rowflex" style="gap:8px;align-items:center"><button class="btn pri" data-sc-imp-check>' + ico('check') + '解析并预览</button>' +
      '<button class="btn ghost" data-sc-imp-help>' + ico('file') + '格式说明</button>' +
      '<span class="mini" id="scImpHint"></span></div>' +
      '<div id="scImpOut"></div>',
    foot: '<button class="btn" data-x>关闭</button>',
    after() {
      $('#modal [data-x]').onclick = closeModal;
      const pick = $('#modal [data-sc-imp-file]');
      const file = $('#modal #scImpFile');
      if (pick && file) pick.onclick = () => file.click();
      if (file) file.onchange = () => {
        const f = file.files[0];
        if (!f) return;
        const nameEl = $('#scImpName');
        if (nameEl) nameEl.textContent = f.name + ' · ' + scBytes(f.size);
        const reader = new FileReader();
        reader.onload = () => {
          const area = $('#scImpText');
          if (area) area.value = String(reader.result || '');
          scImportPreview();
        };
        reader.onerror = () => toast('文件读取失败', 'err');
        reader.readAsText(f, 'utf-8');
      };
      const check = $('#modal [data-sc-imp-check]');
      if (check) check.onclick = scImportPreview;
      const help = $('#modal [data-sc-imp-help]');
      if (help) help.onclick = () => scHelpModal('format');
    },
  });
}

function scImportPreview() {
  if (scImpBusy) return;
  const area = $('#scImpText');
  const text = area ? area.value : '';
  const out = $('#scImpOut');
  const hint = $('#scImpHint');
  if (!scText(text)) {
    if (out) out.innerHTML = '<div class="note red" style="margin-top:14px">还没有内容，粘贴文本或选择文件后再试。</div>';
    return;
  }
  const parsed = scImportEntries(text);
  if (!parsed.items.length) {
    const lines = parsed.skipped.slice(0, 6).map(s => (s.line ? '第 ' + s.line + ' 条：' : '') + s.reason).join('；');
    if (out) out.innerHTML = '<div class="note red" style="margin-top:14px">没有可写入的条目。' + esc(lines) + '</div>';
    return;
  }
  scImpBusy = true;
  if (hint) hint.textContent = '正在核对名单…';
  if (out) out.innerHTML = scProgBox(0, 0, '正在核对名单');
  scQueuePages('', (n, total) => {
    const box = $('#scImpOut');
    if (box) box.innerHTML = scProgBox(n, total, '正在核对名单');
  }).then(items => {
    const byId = {}, bySeries = {};
    items.forEach(x => { byId[x.id] = x; bySeries[x.seriesId] = x; });
    const plan = [];
    const skipped = parsed.skipped.slice();
    parsed.items.forEach((entry, i) => {
      const hit = byId[entry.id] || (entry.seriesId ? bySeries[entry.seriesId] : null);
      const seriesId = entry.seriesId || (hit ? hit.seriesId : '');
      if (!seriesId) { skipped.push({ line: i + 1, reason: '无法定位系列：缺 seriesId，且 id 不在当前名单' }); return; }
      plan.push({ entry: entry, target: seriesId, mode: 'cover', queued: !!hit });
    });
    scImpPlan = plan;
    scImpSkipped = skipped;
    scImpBusy = false;
    if (hint) hint.textContent = '';
    paintScImportResult();
  }).catch(e => {
    scImpBusy = false;
    if (hint) hint.textContent = '';
    if (out) out.innerHTML = '<div class="note red" style="margin-top:14px">核对名单失败：' + esc(onlineErrText(e)) + '</div>';
  });
}

function paintScImportResult() {
  const out = $('#scImpOut');
  if (!out) return;
  if (scImpResult) {
    const r = scImpResult;
    out.innerHTML = '<div class="card" style="margin-top:14px"><div class="card-h"><h3>' + ico('check') + ' 导入完成</h3></div>' +
      '<div class="note">已应用 ' + r.ok + ' 条 · 进入待核对 ' + r.review + ' 条 · 失败 ' + r.fail + ' 条。</div>' +
      (r.fails.length ? '<div class="note red"><b>失败明细</b><br>' + r.fails.slice(0, 20).map(f => esc(f.title) + '：' + esc(f.reason)).join('<br>') + (r.fails.length > 20 ? '<br>…' : '') + '</div>' : '') +
      '<div class="rowflex" style="gap:8px;margin-top:14px"><button class="btn pri" data-sc-imp-again>再导入一批</button>' +
      '<button class="btn" data-sc-imp-close>关闭并刷新名单</button></div></div>';
    const again = $('#scImpOut [data-sc-imp-again]');
    if (again) again.onclick = () => {
      scImpPlan = null; scImpSkipped = []; scImpResult = null;
      const area = $('#scImpText');
      if (area) area.value = '';
      const nameEl = $('#scImpName');
      if (nameEl) nameEl.textContent = '未选择文件';
      const file = $('#scImpFile');
      if (file) file.value = '';
      const box = $('#scImpOut');
      if (box) box.innerHTML = '';
    };
    const close = $('#scImpOut [data-sc-imp-close]');
    if (close) close.onclick = () => { closeModal(); loadScrape(); };
    return;
  }
  const plan = scImpPlan || [];
  const skipped = scImpSkipped || [];
  if (!plan.length) {
    const lines = skipped.slice(0, 6).map(s => (s.line ? '第 ' + s.line + ' 条：' : '') + s.reason).join('；');
    out.innerHTML = '<div class="note red" style="margin-top:14px">没有可写入的条目。' + esc(lines) + '</div>';
    return;
  }
  const inQueue = plan.filter(p => p.queued).length;
  let html = '<div class="card" style="margin-top:14px"><div class="card-h"><h3>' + ico('check') + ' 解析结果</h3>' +
    '<div class="r"><span class="badge moss">可写入 ' + plan.length + '</span>' + (skipped.length ? '<span class="badge warn">跳过 ' + skipped.length + '</span>' : '') + '</div></div>' +
    '<div class="note">导入一律<b>直接覆盖写入</b>，不进入待核对；留空的字段不覆盖。' +
    (inQueue ? '其中 ' + inQueue + ' 条原本在刮削名单内，写入后自动移出名单。' : '') + '</div>' +
    '<div class="tbl-wrap" style="margin-top:12px"><table class="tbl"><thead><tr><th style="width:40px">#</th><th>系列</th><th style="width:110px">方式</th><th style="width:230px">字段</th></tr></thead><tbody>';
  plan.slice(0, 50).forEach((p, i) => {
    const names = SC_META_FIELDS.filter(f => f[4] === '可写').filter(f => {
      const v = p.entry.metadata[f[0]];
      return Array.isArray(v) ? v.length > 0 : !!v;
    }).map(f => f[1]);
    html += '<tr><td class="mini mono">' + (i + 1) + '</td>' +
      '<td style="font-size:13px">' + esc(p.entry.seriesTitle || p.entry.seriesId || p.entry.id) + '</td>' +
      '<td><span class="badge' + (p.queued ? ' warn' : '') + '">' + (p.queued ? '覆盖 · 名单内' : '覆盖') + '</span></td>' +
      '<td class="mini">' + esc(names.join('、') || '—') + '</td></tr>';
  });
  html += '</tbody></table></div>';
  if (plan.length > 50) html += '<div class="mini" style="margin-top:8px;color:var(--tx-3)">仅列出前 50 条，其余同样会写入。</div>';
  html += '<div class="rowflex" style="gap:8px;margin-top:14px"><button class="btn pri" data-sc-imp-run>' + ico('upload') + '写入 ' + plan.length + ' 条</button></div>';
  if (skipped.length) {
    html += '<div class="note" style="margin-top:12px"><b>跳过 ' + skipped.length + ' 条</b><br>' +
      skipped.slice(0, 10).map(s => (s.line ? '第 ' + s.line + ' 条：' : '') + esc(s.reason)).join('<br>') +
      (skipped.length > 10 ? '<br>…' : '') + '</div>';
  }
  html += '</div>';
  out.innerHTML = html;
  const run = $('#scImpOut [data-sc-imp-run]');
  if (run) run.onclick = scImportRun;
}

function scImportRun() {
  const plan = scImpPlan || [];
  if (!plan.length || scImpBusy) return;
  scImpBusy = true;
  const stats = { ok: 0, review: 0, fail: 0, fails: [] };
  let done = 0;
  const box = $('#scImpOut');
  if (box) box.innerHTML = scProgBox(0, plan.length, '正在写入');
  const next = i => {
    if (i >= plan.length) {
      scImpBusy = false;
      scImpResult = stats;
      paintScImportResult();
      toast('导入完成：成功 ' + (stats.ok + stats.review) + ' 条' + (stats.fail ? '，失败 ' + stats.fail + ' 条' : ''), stats.fail ? 'warn' : 'ok');
      return;
    }
    const p = plan[i];
    LantaiRuntime.request('api/lantai/v1/scrape/agent/queue/' + encodeURIComponent(p.target) + '/result', { method: 'POST', body: { metadata: p.entry.metadata } })
      .then(res => { if (res && res.status === 'REVIEW') stats.review++; else stats.ok++; })
      .catch(e => { stats.fail++; stats.fails.push({ title: p.entry.seriesTitle || p.entry.seriesId || p.entry.id, reason: onlineErrText(e) }); })
      .then(() => {
        done++;
        const prog = $('#scImpOut');
        if (prog) prog.innerHTML = scProgBox(done, plan.length, '正在写入');
        next(i + 1);
      });
  };
  next(0);
}

const SC_AI_PROMPT_MAX = 20000;
const SC_AI_SNAP_CAP = 200;
const SC_AI_SNAP_CONCURRENCY = 6;

let scAiScope = 'all';
let scAiLimit = 20;
let scAiSnapshot = false;
let scAiBusy = false;
let scAiText = '';
let scAiSnap = null;
let scAiSnapTotal = 0;
let scAiBaseUrl = '';
let scAiApiToken = '';
let scAiPolicy = 'review';

function scAiPolicyText() {
  if (scAiPolicy === 'overwrite') return 'overwrite —— 本地已有值且与你给的不同时，直接用你的值覆盖';
  if (scAiPolicy === 'keep') return 'keep —— 本地已有值且与你给的不同时，保留本地值，其余照写';
  return 'review —— 本地已有值且与你给的不同时，这个字段不写入，条目留在待核对；能补的字段会先写入';
}

function scAiDefaultBase() {
  const o = String((window.location && window.location.origin) || '');
  if (!o || o === 'null' || o === 'file://') return '';
  return o;
}

function scAiBase() {
  const raw = String(scAiBaseUrl || '').trim();
  if (!raw) return 'http://<服务地址>:<端口>/api/lantai/v1';
  let u = raw.replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(u)) u = 'http://' + u;
  if (/\/api\/lantai\/v1$/i.test(u)) return u;
  return u + '/api/lantai/v1';
}

function scAiApplyBase(v) {
  scAiBaseUrl = String(v == null ? '' : v).trim();
  scAiText = scAiSnapshot && !scAiSnap ? '' : scAiPromptText();
  paintScAi();
}

function scAiApplyToken(v) {
  scAiApiToken = String(v == null ? '' : v).trim();
  scAiText = scAiSnapshot && !scAiSnap ? '' : scAiPromptText();
  paintScAi();
}

function scAiTokenText() {
  return String(scAiApiToken || '').trim();
}

function scAiSnapFlat(v) {
  return String(v == null ? '' : v).replace(/[\r\n]+/g, ' ').replace(/\|/g, '/').trim();
}

function scAiSnapArr(v) {
  return (Array.isArray(v) ? v : []).map(scAiSnapFlat).filter(Boolean).join('、');
}

function scAiSnapLocks(b) {
  const out = [];
  if (b.titleLock) out.push('title');
  if (b.numberLock) out.push('number');
  if (b.authorsLock) out.push('authors');
  return 'lock=' + (out.length ? out.join(',') : '无');
}

function scAiSnapText(items) {
  const blocks = [];
  let used = 0;
  let listed = 0;
  for (let i = 0; i < items.length; i++) {
    const x = items[i];
    const books = Array.isArray(x.books) ? x.books : [];
    const head = '【' + (i + 1) + '】' + (scAiSnapFlat(x.seriesTitle) || '(未命名系列)');
    const meta =
      'id=' + scAiSnapFlat(x.id) + ' | seriesId=' + scAiSnapFlat(x.seriesId) +
      ' | 状态=' + scAiSnapFlat(x.status) + ' | 册数=' + (books.length || x.bookCount || 0);
    const lines = [head, meta];
    if (books.length) {
      lines.push('册：');
      books.forEach(b => {
        lines.push(
          '- ' + scAiSnapFlat(b.bookId) +
          ' | 文件=' + scAiSnapFlat(b.fileName) +
          ' | 书名=' + scAiSnapFlat(b.title) +
          ' | 序号=' + scAiSnapFlat(b.number) +
          ' | 作者=' + scAiSnapArr(b.authors) +
          ' | 日期=' + scAiSnapFlat(b.releaseDate) +
          ' | ISBN=' + scAiSnapFlat(b.isbn) +
          ' | ' + scAiSnapLocks(b)
        );
      });
    } else {
      lines.push('册：未能读取');
    }
    const block = lines.join('\n');
    blocks.push(block);
    used += block.length + 2;
    listed++;
  }
  return { text: blocks.join('\n\n'), listed: listed, used: used };
}

function scAiPromptText() {
  const base = scAiBase();
  const lang = L.name(L.lang());
  const lines = [];
  lines.push('你是兰台（Lantai）的刮削助手。名单一条对应一个系列，status=QUEUED 表示待刮。任务：读名单、检索权威资料、按固定格式回写，不编造。');
  lines.push('');
  lines.push('【接入】基地址 ' + base + '，下文路径都相对它。');
  const tok = scAiTokenText();
  if (tok) {
    lines.push('鉴权：请求头 X-API-Token: ' + tok + '（也接受 Authorization: Bearer ' + tok + '）。整段提示词就是凭证，别外发、别提交到公共仓库。');
  } else {
    lines.push('鉴权：请求头 X-API-Token: <你的刮削令牌>（也接受 Authorization: Bearer <令牌>）。令牌在 兰台 → 刮削 → 设置 → 刮削令牌 生成，只能读名单、读详情、提交结果；本段没有令牌，执行前替换占位符。');
  }
  lines.push('网络：客户端要能直达该地址。' +
    (base.indexOf('<') >= 0 ? '基地址里的 <服务地址>、<端口> 是占位符，先换真实值；' : '') +
    '若它指向本机或局域网而 AI 在云端，就连不上，换公网反代域名。');
  lines.push('');
  lines.push('【范围】' + (scAiScope === 'limit'
    ? '本次最多处理 ' + scAiLimit + ' 条：按名单顺序取前 ' + scAiLimit + ' 条 QUEUED，处理完就停。'
    : '本次不限条数：一直拉到名单里没有 QUEUED 条目为止。'));
  lines.push('');
  lines.push('【流程】');
  lines.push('1. GET /scrape/agent/stats 看 queued 有多少条。');
  lines.push('2. GET /scrape/agent/queue?offset=0&limit=200 分页拉名单（offset 递增，limit≤200；接口不按状态筛选，自己过滤 queued）。条目含 id、seriesId、seriesTitle、status、bookIds（空＝整系列，非空＝只刮这几册）。');
  lines.push('3. 逐条：GET /scrape/agent/queue/{id} 取现有值、册清单、锁定标记（只读数据库）→ 用 seriesTitle 与册的 fileName、title 检索确认同一作品 → POST /scrape/agent/queue/{id}/result 提交。');
  lines.push('4. 看返回：DONE 已写入出队；REVIEW 待核对；appliedFields 实际写入、keptFields 保留本地、conflicts 冲突字段名；message 是跳过原因。');
  lines.push('5. 收尾汇总表：系列 | 结果 | 写入字段 | 未取到 | 失败原因。');
  lines.push('');
  lines.push('【提交格式】系列级（整个系列共用一份）：');
  lines.push('{"metadata":{"title":"","summary":"","publisher":"","region":"","language":"","genres":[],"authors":[],"releaseDate":"","isbn":"","tags":[],"coverUrl":""}}');
  lines.push('按册（书名、序号、作者、日期、ISBN 各册不同时）：');
  lines.push('{"metadata":{"publisher":""},"books":[{"bookId":"","fields":["title","number","authors","releaseDate","isbn"],"title":"","number":"","authors":[],"releaseDate":"","isbn":""}]}');
  lines.push('可套一层 metadata，也可平铺顶层；数组给 JSON 数组或顿号、逗号分隔字符串。字段：title、summary（150~300 字）、publisher、region（两位国家码）、language（原文 BCP-47）、genres（最多 6 个）、authors（纯姓名）、releaseDate（YYYY-MM-DD）、isbn（纯数字）、tags、coverUrl（图片直链）；取不到留空。');
  lines.push('');
  lines.push('【硬规则】');
  lines.push('1. 只提交资料支持的值，取不到给空；空值不会清空本地。');
  lines.push('2. id 是名单条目 id，一个字都不能改，改了就写到别的系列。');
  lines.push('3. xxxLock 为 true 的字段跳过；条目 bookIds 非空时只写这几册。');
  lines.push('4. 一次一条，间隔≥1 秒；403、429、5xx 停 30 秒重试，最多 2 次。');
  lines.push('5. 只请求上面四个 agent 接口，其它 /api/ 路径令牌没有权限。');
  lines.push('6. 文案语言：' + lang + '；language 字段仍填作品原文语言。');
  lines.push('');
  lines.push('【冲突处理】当前策略：' + scAiPolicyText() + '。');
  lines.push('接口不返回本地已有值，这是有意的：按资料给出全量结果，不要试图对齐本地。status=REVIEW 表示还有字段等人工处理，别重复提交同一份数据去绕过。');
  lines.push('');
  if (scAiSnap && scAiSnap.length) {
    const snap = scAiSnapText(scAiSnap);
    lines.push('【不能发 HTTP 请求时】别伪造提交，直接返回 JSON：{"items":[{"id":"","seriesId":"","metadata":{...}}]}。系列级照常填；册级参数见下方快照（文件名、现有书名与序号、ISBN、锁定字段），有把握的按册填 books，没把握的留空。用户会把这段 JSON 粘回 兰台 → 刮削 → 导入数据。');
    lines.push('名单快照（待刮共 ' + scAiSnapTotal + ' 条，本次列出 ' + snap.listed + ' 条）：');
    lines.push(snap.text);
  } else {
    lines.push('【提醒】本提示词不含名单，名单自己拉取；发不了请求就重新生成并勾选「附名单快照」。');
  }
  return lines.join('\n');
}

function scAiPromptModal() {
  scAiScope = 'all';
  scAiLimit = 20;
  scAiSnapshot = false;
  scAiBusy = false;
  scAiText = '';
  scAiSnap = null;
  scAiSnapTotal = 0;
  scAiBaseUrl = scAiDefaultBase();
  scAiApiToken = '';
  openDrawer({
    title: 'AI 提示词', width: 720,
    body: '<div id="scAi"></div>',
    after() { paintScAi(); scAiLoadPolicy(); },
  });
}

function paintScAi() {
  const box = $('#scAi');
  if (!box) return;
  if (!scAiText && !scAiSnapshot && !scAiBusy) scAiText = scAiPromptText();
  const over = scAiText.length > SC_AI_PROMPT_MAX;
  const has = !!scAiText;
  const preview = over ? scAiText.slice(0, SC_AI_PROMPT_MAX) : scAiText;
  box.innerHTML =
    '<div class="note">把整段粘给能发 HTTP 请求的 AI（本机脚本、命令行 agent）。它自己拉名单、逐条读详情、检索资料、回写结果。' +
    (scAiTokenText() ? '令牌已写入这段提示词，整段就是凭证，别外发。' : '令牌没有写进提示词，粘之前把 <span class="mono">&lt;你的刮削令牌&gt;</span> 换成真实值。') + '</div>' +
    '<div class="field" style="margin-top:14px"><label>服务地址</label>' +
    '<div class="rowflex" style="gap:8px;align-items:center">' +
    '<input class="inp mono" id="scAiBase" spellcheck="false" placeholder="http://&lt;服务地址&gt;:&lt;端口&gt;" value="' + esc(scAiBaseUrl) + '" style="flex:1;min-width:0">' +
    '<button class="btn sm ghost" data-sc-ai-base-reset style="flex:none">当前地址</button></div>' +
    '<div class="mini" style="margin-top:6px;color:var(--tx-3)">默认取浏览器地址栏。换成局域网 IP 或公网域名后提示词自动重算；清空则写成通配符。</div></div>' +
    '<div class="field" style="margin-top:12px"><label>刮削令牌（可选）</label>' +
    '<input class="inp mono" id="scAiToken" spellcheck="false" autocomplete="off" placeholder="留空则只写占位符" value="' + esc(scAiApiToken) + '">' +
    '<div class="mini" style="margin-top:6px;color:var(--tx-3)">填了就替换提示词里的 &lt;你的刮削令牌&gt; 与 &lt;令牌&gt;；整段从此带凭证，别发给不可信的服务。</div></div>' +
    '<div class="field" style="margin-top:14px"><label>处理范围</label><div class="rowflex" style="gap:8px;align-items:center">' +
    '<div class="seg" style="margin:0">' +
    '<button data-sc-ai-scope="all" class="' + (scAiScope === 'all' ? 'on' : '') + '">不限条数</button>' +
    '<button data-sc-ai-scope="limit" class="' + (scAiScope === 'limit' ? 'on' : '') + '">只扫前</button>' +
    '</div>' +
    '<input class="inp mono" id="scAiLimit" type="number" min="1" max="2000" value="' + scAiLimit + '" style="width:88px;height:30px;text-align:right"' + (scAiScope === 'limit' ? '' : ' disabled') + '>' +
    '<span class="mini">条</span></div>' +
    '<div class="mini" style="margin-top:6px;color:var(--tx-3)">不限条数＝一直处理到名单里没有待刮条目为止。</div></div>' +
    '<div class="rowflex" style="gap:10px;align-items:flex-start;margin-top:16px">' +
    '<label class="sw"><input type="checkbox" id="scAiSnap"' + (scAiSnapshot ? ' checked' : '') + '><i></i></label>' +
    '<div><div style="font-size:13px">附上名单快照</div>' +
    '<div class="mini" style="color:var(--tx-3)">给发不了请求的网页版 AI 用：提示词里直接列出待刮条目，AI 只算不写，结果用「导入数据」回写。' +
    (scAiSnapshot && scAiSnap ? '本次快照 ' + scAiSnap.length + ' 条。' : '勾选后点「生成提示词」会先读一次名单。') + '</div></div></div>' +
    '<div class="rowflex" style="gap:8px;margin-top:16px;align-items:center">' +
    '<button class="btn pri" data-sc-ai-build' + (scAiBusy ? ' disabled' : '') + '>' + ico('cpu') + '生成提示词</button>' +
    '<button class="btn sm" data-sc-ai-copy' + (has ? '' : ' disabled') + '>' + ico('copy') + '复制</button>' +
    '<button class="btn sm ghost" data-sc-ai-dl' + (has ? '' : ' disabled') + '>' + ico('dl') + '下载 txt</button>' +
    '<button class="btn sm ghost" data-sc-ai-imp>' + ico('upload') + '去导入数据</button>' +
    '<span class="mini" id="scAiLen" style="margin-left:auto">' + (has ? (over ? '完整 ' + num(scAiText.length) + ' 字符 · 预览前 20,000' : num(scAiText.length) + ' 字符') : '') + '</span></div>' +
    '<div class="mini" id="scAiProg" style="margin-top:8px;color:var(--tx-3)"></div>' +
    (over ? '<div class="note gold" style="margin-top:10px">完整文本 ' + num(scAiText.length) + ' 字符，下方只预览前 ' + num(SC_AI_PROMPT_MAX) + ' 字符；「复制」和「下载 txt」取的都是完整内容。</div>' : '') +
    '<textarea class="inp mono" id="scAiText" readonly spellcheck="false" style="height:300px;margin-top:12px;font-size:11.5px">' + esc(preview) + '</textarea>';
  $$('#scAi [data-sc-ai-scope]').forEach(el => el.onclick = () => {
    if (scAiScope === el.dataset.scAiScope) return;
    scAiScope = el.dataset.scAiScope;
    scAiText = '';
    scAiSnap = null;
    paintScAi();
  });
  const baseInp = $('#scAiBase');
  if (baseInp) {
    baseInp.onchange = () => scAiApplyBase(baseInp.value);
    baseInp.onkeydown = e => {
      if (e.key !== 'Enter' || e.isComposing) return;
      e.preventDefault();
      scAiApplyBase(baseInp.value);
    };
  }
  const baseRst = $('#scAi [data-sc-ai-base-reset]');
  if (baseRst) baseRst.onclick = () => scAiApplyBase(scAiDefaultBase());
  const tokInp = $('#scAiToken');
  if (tokInp) {
    tokInp.onchange = () => scAiApplyToken(tokInp.value);
    tokInp.onkeydown = e => {
      if (e.key !== 'Enter' || e.isComposing) return;
      e.preventDefault();
      scAiApplyToken(tokInp.value);
    };
  }
  const lim = $('#scAiLimit');
  if (lim) lim.onchange = () => {
    scAiLimit = Math.max(1, Math.min(2000, Math.floor(Number(lim.value)) || 20));
    scAiText = '';
    paintScAi();
  };
  const snap = $('#scAiSnap');
  if (snap) snap.onchange = () => {
    scAiSnapshot = snap.checked;
    scAiText = '';
    scAiSnap = null;
    paintScAi();
  };
  const build = $('#scAi [data-sc-ai-build]');
  if (build) build.onclick = scAiBuild;
  const cp = $('#scAi [data-sc-ai-copy]');
  if (cp) cp.onclick = () => copyText(scAiText, '提示词');
  const dl = $('#scAi [data-sc-ai-dl]');
  if (dl) dl.onclick = () => scDownload(scAiText, 'lantai-ai-scrape.txt');
  const imp = $('#scAi [data-sc-ai-imp]');
  if (imp) imp.onclick = () => { closeDrawer(); scImportModal(); };
}

function scAiLoadPolicy() {
  return LantaiRuntime.request('api/lantai/v1/scrape/settings')
    .then(v => {
      const p = (v && v.conflictPolicy) || 'review';
      if (p === scAiPolicy) return;
      scAiPolicy = p;
      if (scAiBusy) return;
      scAiText = scAiSnapshot && !scAiSnap ? '' : scAiPromptText();
      paintScAi();
    })
    .catch(() => { });
}

function scAiLoadBooks(items, onProgress) {
  let cursor = 0;
  let done = 0;
  const total = items.length;
  const step = () => {
    if (cursor >= total) return Promise.resolve();
    const item = items[cursor++];
    return LantaiRuntime.request('api/lantai/v1/scrape/queue/' + encodeURIComponent(item.id) + '/books')
      .then(list => { item.books = Array.isArray(list) ? list : []; })
      .catch(() => { item.books = []; })
      .then(() => {
        done++;
        if (onProgress) onProgress(done, total);
        return step();
      });
  };
  const workers = [];
  for (let i = 0; i < Math.min(SC_AI_SNAP_CONCURRENCY, total); i++) workers.push(step());
  return Promise.all(workers);
}

function scAiBuild() {
  if (scAiBusy) return;
  if (!scAiSnapshot) {
    scAiText = scAiPromptText();
    paintScAi();
    copyText(scAiText, '提示词');
    return;
  }
  scAiBusy = true;
  scAiSnap = null;
  paintScAi();
  scQueuePages('', (n, total) => {
    const p = document.getElementById('scAiProg');
    if (p) p.textContent = '正在读取名单 ' + n + (total ? ' / ' + total : '');
  }).then(all => {
    const pending = (all || []).filter(x => String(x.status || '').toUpperCase() === 'QUEUED');
    scAiSnapTotal = pending.length;
    if (!pending.length) {
      scAiBusy = false;
      scAiText = '';
      paintScAi();
      const p0 = document.getElementById('scAiProg');
      if (p0) p0.textContent = '名单里没有待刮条目';
      toast('名单里没有待刮条目', 'warn');
      return null;
    }
    const cap = scAiScope === 'limit' ? Math.min(scAiLimit, SC_AI_SNAP_CAP) : SC_AI_SNAP_CAP;
    const slice = pending.slice(0, cap);
    return scAiLoadBooks(slice, (n, total) => {
      const p = document.getElementById('scAiProg');
      if (p) p.textContent = '正在读取册参数 ' + n + ' / ' + total;
    }).then(() => {
      const snap = scAiSnapText(slice);
      scAiSnap = slice;
      scAiBusy = false;
      scAiText = scAiPromptText();
      paintScAi();
      const p = document.getElementById('scAiProg');
      if (p) p.textContent = '已生成：待刮 ' + pending.length + ' 条，快照列出 ' + snap.listed + ' 条' + (pending.length > slice.length ? '（单次上限 ' + SC_AI_SNAP_CAP + ' 条）' : '') + '，完整 ' + num(scAiText.length) + ' 字符';
    });
  }).catch(e => {
    scAiBusy = false;
    scAiText = '';
    paintScAi();
    toast('读取名单失败：' + onlineErrText(e), 'err');
  });
}

function scMatchHistoryRefresh() {
  const box = $('#scHist');
  if (!box) return;
  LantaiRuntime.request('api/lantai/v1/scrape/matches?status=APPLIED')
    .then(list => { paintScMatchHistory(list || []); })
    .catch(e => { box.innerHTML = '<div class="note red">读取失败：' + esc(onlineErrText(e)) + '</div>'; });
}

function paintScMatchHistory(list) {
  const box = $('#scHist');
  if (!box) return;
  if (!list.length) {
    box.innerHTML = '<div class="empty" style="padding:26px"><h4>还没有写入记录</h4><p>每次刮削写入都会留一份快照，可以在这里回滚。</p></div>';
    return;
  }
  box.innerHTML =
    '<div class="note">回滚把系列元数据恢复成该次写入之前的快照。同一次回滚重复执行没有意义。</div>' +
    '<div class="tbl-wrap" style="margin-top:12px"><table class="tbl"><thead><tr><th>系列</th><th style="width:130px">来源</th><th>写入字段</th><th style="width:96px"></th></tr></thead><tbody>' +
    list.map(m => '<tr><td><b>' + esc(m.seriesTitle || '—') + '</b></td>' +
      '<td class="mini">' + esc(m.source || '—') + '</td>' +
      '<td class="mini">' + ((m.appliedFields || []).map(esc).join('、') || '—') + '</td>' +
      '<td style="text-align:right"><button class="btn xs danger" data-sc-rb="' + esc(m.id) + '">回滚</button></td></tr>').join('') +
    '</tbody></table></div>';
  $$('#scHist [data-sc-rb]').forEach(el => {
    el.onclick = () => {
      if (el.dataset.armed !== '1') {
        el.dataset.armed = '1';
        el.textContent = '再点一次';
        setTimeout(() => {
          if (!el.isConnected) return;
          el.dataset.armed = '';
          el.textContent = '回滚';
        }, 4000);
        return;
      }
      el.disabled = true;
      LantaiRuntime.request('api/lantai/v1/scrape/matches/' + encodeURIComponent(el.dataset.scRb) + '/rollback', { method: 'POST' })
        .then(() => { toast('已回滚', 'ok'); scMatchHistoryRefresh(); loadScrape(); })
        .catch(e => {
          el.disabled = false;
          el.dataset.armed = '';
          el.textContent = '回滚';
          toast('回滚失败：' + onlineErrText(e), 'err');
        });
    };
  });
}

function scMatchHistoryModal() {
  openModal({
    title: '刮削历史与回滚', width: 900,
    body: '<div id="scHist"></div>',
    foot: '<button class="btn" data-x>关闭</button>',
    after() {
      $('#modal [data-x]').onclick = closeModal;
      scMatchHistoryRefresh();
    }
  });
}

(function () {
  window.LANTAI_API_I18N = {
    "zh-Hans": {
      "Delete all sync points": "删除所有同步点",
      "Directory listing": "目录列表",
      "List all libraries": "列出所有书库",
      "Add an extension store": "添加扩展商店",
      "Add book poster": "添加书籍海报",
      "Add collection poster": "添加合集海报",
      "Add readlist poster": "添加阅读清单海报",
      "Add series poster": "添加系列海报",
      "Add series, a whole library or every series missing metadata to the queue": "将系列、整个书库或所有缺元数据的系列加入队列",
      "Aggregate reading devices from session history": "从会话历史汇总阅读设备",
      "Aggregated home payload for clients": "客户端首页聚合数据",
      "Analyze a library": "分析书库",
      "Analyze book": "分析书籍",
      "Analyze series": "分析系列",
      "Analyze transient book": "分析临时书籍",
      "Apply selected fields of a match and keep a rollback snapshot": "应用匹配的所选字段并保留回滚快照",
      "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "应用队列条目的所选字段，条目离开队列时返回 204",
      "Ask the running batch to stop after the current series": "请求正在运行的批次在当前系列后停止",
      "Available versions and download sources": "可用版本与下载来源",
      "Book counts grouped by computed first letter": "按首字母统计书籍数量",
      "Book cover resized on demand": "按需缩放的书籍封面",
      "Book ids whose computed first letter matches": "首字母匹配的书籍 ID",
      "Bring back a book previously removed from the library": "将移出书库的书籍放回",
      "Bring back a series previously removed from the library": "将移出书库的系列放回",
      "Browse a source by popular or latest, or search inside it": "按热门或最新浏览图源，或在其中搜索",
      "Browse installable extensions": "浏览可安装扩展",
      "Calibre availability, version and conversion mode": "Calibre 可用性、版本与转换模式",
      "Change logger levels at runtime, use INHERIT to restore the default": "运行时修改日志级别，用 INHERIT 恢复默认",
      "Clear backoff and circuit state of a source": "清除图源的退避与熔断状态",
      "Clear queue entries by scope: queued, review, failed or all": "按范围清空队列条目：排队、待核对、失败或全部",
      "Clear task queue": "清空任务队列",
      "Clear the pinned executable and fall back to the managed installation": "清除固定的可执行文件，回退到受管安装",
      "Client capability negotiation": "客户端能力协商",
      "Compute the field level difference of a queue entry": "计算队列条目的字段级差异",
      "Convert a single book and queue re-analysis": "转换单本书并排队重新分析",
      "Convert pending books of a library": "转换书库中待转换的书籍",
      "Count books still waiting for conversion in a library": "统计书库中仍待转换的书籍数量",
      "Counters for the scrape queue and the inbox": "刮削队列与收件箱的计数",
      "Create API key": "创建 API 密钥",
      "Create a library": "创建书库",
      "Create a scrape only API token with fixed scopes": "创建仅有刮削固定权限的 API 令牌",
      "Create an annotation with a locator and a text fallback": "用定位符与文本回退创建标注",
      "Create collection": "创建合集",
      "Create readlist": "创建阅读清单",
      "Create user": "创建用户",
      "Current install progress": "当前安装进度",
      "Current jar location and whether it can be replaced in place": "当前 jar 位置及是否可原地替换",
      "Delete API key": "删除 API 密钥",
      "Delete a library": "删除书库",
      "Delete a single task by id": "按 ID 删除单个任务",
      "Delete all duplicate pages by hash": "按哈希删除所有重复页",
      "Delete an API key of a user": "删除用户的 API 密钥",
      "Delete book file": "删除书籍文件",
      "Delete book poster": "删除书籍海报",
      "Delete collection": "删除合集",
      "Delete collection poster": "删除合集海报",
      "Delete downloaded cache of an online series, chapters remain readable through the source": "删除在线系列的已下载缓存，章节仍可通过图源阅读",
      "Delete every finished task record": "删除所有已完成任务记录",
      "Delete every package downloaded from the update feed": "删除从更新源下载的所有安装包",
      "Delete every queued task; running ones are kept": "删除所有排队任务，运行中的保留",
      "Delete global settings": "删除全局设置",
      "Delete my annotation": "删除我的标注",
      "Delete readlist": "删除阅读清单",
      "Delete readlist poster": "删除阅读清单海报",
      "Delete series files": "删除系列文件",
      "Delete series poster": "删除系列海报",
      "Delete specific duplicate page": "删除指定的重复页",
      "Delete user": "删除用户",
      "Delete user settings": "删除用户设置",
      "Discard the staged package": "丢弃已暂存的安装包",
      "Download CSS file": "下载 CSS 文件",
      "Download a raw log file": "下载原始日志文件",
      "Download a release package, verify it and stage it for install": "下载发行包、校验并暂存待安装",
      "Download an online chapter as a local CBZ and re-analyze it": "将在线章节下载为本地 CBZ 并重新分析",
      "Download and install the latest Calibre portable build": "下载并安装最新的 Calibre 便携版",
      "Download and install the latest Suwayomi release": "下载并安装最新的 Suwayomi 发行版",
      "Download book file": "下载书籍文件",
      "Download font file": "下载字体文件",
      "Download from the official source and install in the background": "从官方源下载并在后台安装",
      "Download readlist": "下载阅读清单",
      "Download series": "下载系列",
      "Echo build and runtime information": "返回构建与运行时信息",
      "Empty the trash of one or all libraries": "清空一个或全部书库的回收站",
      "Empty trash for a library": "清空书库回收站",
      "Enable or disable a runtime": "启用或停用运行时",
      "Enable or disable a user": "启用或停用用户",
      "Environment and dependency check for an external runtime": "外部运行时的环境与依赖检查",
      "Equivalent shell steps for a manual install": "手动安装的等价命令步骤",
      "Expire one or all sessions of a user": "使用户的某个或全部会话失效",
      "Export a page range as zip": "导出页范围打包为 zip",
      "Export audit entries as CSV": "导出审计条目为 CSV",
      "Export the library inventory as JSON": "导出书库清单为 JSON",
      "Get Epub resource": "获取 Epub 资源",
      "Get Lantai reading capabilities": "获取兰台阅读能力",
      "Get book details": "获取书籍详情",
      "Get book page image": "获取书籍页图片",
      "Get book page thumbnail": "获取书籍页缩略图",
      "Get book poster image": "获取书籍海报图片",
      "Get book progression": "获取书籍阅读进度",
      "Get book's WebPub manifest": "获取书籍的 WebPub 清单",
      "Get book's WebPub manifest (DiViNa)": "获取书籍的 WebPub 清单（DiViNa）",
      "Get book's WebPub manifest (Epub)": "获取书籍的 WebPub 清单（Epub）",
      "Get book's WebPub manifest (PDF)": "获取书籍的 WebPub 清单（PDF）",
      "Get book's poster image": "获取书籍海报图片",
      "Get collection details": "获取合集详情",
      "Get collection poster image": "获取合集海报图片",
      "Get collection's poster image": "获取合集海报图片",
      "Get details for a single library": "获取单个书库详情",
      "Get known duplicate image thumbnail": "获取已知重复图片的缩略图",
      "Get next book in readlist": "获取阅读清单中的下一本",
      "Get next book in series": "获取系列中的下一本",
      "Get previous book in readlist": "获取阅读清单中的上一本",
      "Get previous book in series": "获取系列中的上一本",
      "Get raw book page": "获取书籍原始页面",
      "Get reading statistics for the current user": "获取当前用户的阅读统计",
      "Get readlist details": "获取阅读清单详情",
      "Get readlist poster image": "获取阅读清单海报图片",
      "Get readlist read progress (Mihon)": "获取阅读清单阅读进度（Mihon）",
      "Get readlist's poster image": "获取阅读清单海报图片",
      "Get series details": "获取系列详情",
      "Get series poster image": "获取系列海报图片",
      "Get series read progress (Mihon)": "获取系列阅读进度（Mihon）",
      "Get series' poster image": "获取系列海报图片",
      "Get the archive status of the current user's reading statistics": "获取当前用户阅读统计的存档状态",
      "Get the reading time of one book for the current user": "获取当前用户某本书的阅读时长",
      "Get transient book page": "获取临时书籍页面",
      "Get unknown duplicate image thumbnail": "获取未知重复图片的缩略图",
      "Import a Suwayomi jar manually": "手动导入 Suwayomi jar",
      "Import a locally downloaded Calibre portable installer": "导入本地下载的 Calibre 便携版安装包",
      "Import books": "导入书籍",
      "Include or exclude a source from search and entry points": "将图源纳入或排除搜索与入口",
      "Install an extension from an uploaded package": "从上传的安装包安装扩展",
      "Install an extension from the catalog": "从清单安装扩展",
      "Latest Calibre portable release": "最新的 Calibre 便携版",
      "Latest Suwayomi release": "最新的 Suwayomi 发行版",
      "List active reading sessions for the current user": "列出当前用户的活动阅读会话",
      "List age ratings": "列出年龄分级",
      "List and search users": "列出并搜索用户",
      "List applied scrape records of the series behind a queue entry": "列出队列条目对应系列已应用的刮削记录",
      "List assignable roles": "列出可分配角色",
      "List authors": "列出作者",
      "List authors names": "列出作者姓名",
      "List authors roles": "列出作者角色",
      "List authors' names": "列出作者姓名",
      "List authors' roles": "列出作者角色",
      "List available log files": "列出可用日志文件",
      "List book pages": "列出书籍页面",
      "List book posters": "列出书籍海报",
      "List book tags": "列出书籍标签",
      "List book's positions": "列出书籍中的定位",
      "List book's readlists": "列出书籍所在的阅读清单",
      "List books": "列出书籍",
      "List books on deck": "列出待续读书籍",
      "List books removed from library but still present on disk": "列出已移出书库但仍在磁盘上的书籍",
      "List collection's posters": "列出合集海报",
      "List collection's series": "列出合集中的系列",
      "List collections": "列出合集",
      "List duplicate books": "列出重复书籍",
      "List duplicate matches": "列出重复匹配",
      "List extension stores": "列出扩展商店",
      "List finished tasks, paged": "分页列出已完成任务",
      "List font families": "列出字体族",
      "List genres": "列出题材",
      "List historical events": "列出历史事件",
      "List installed extensions": "列出已安装扩展",
      "List known duplicates": "列出已知重复",
      "List languages": "列出语言",
      "List latest books": "列出最新书籍",
      "List latest series": "列出最新系列",
      "List loggers and their levels": "列出日志器及其级别",
      "List match candidates with their current and proposed values": "列出匹配候选及其当前值与建议值",
      "List match jobs of this session": "列出本次会话的匹配任务",
      "List materialized chapters of a subscription with download state": "列出订阅已落地的章节及下载状态",
      "List my annotations of a book": "列出我对某本书的标注",
      "List new series": "列出新系列",
      "List notable entities": "列出值得关注的实体",
      "List notable entities kinds": "列出值得关注的实体类型",
      "List notable entities names": "列出值得关注的实体名称",
      "List online metadata sources and their daily usage": "列出在线元数据源及其当日用量",
      "List publishers": "列出出版社",
      "List queued and running tasks, paged": "分页列出排队与运行中的任务",
      "List reading sessions": "列出阅读会话",
      "List readlist's books": "列出阅读清单中的书籍",
      "List readlist's posters": "列出阅读清单海报",
      "List readlists": "列出阅读清单",
      "List registered OAuth2 providers": "列出已注册的 OAuth2 提供方",
      "List releases": "列出发行版",
      "List series": "列出系列",
      "List series groups": "列出系列分组",
      "List series posters": "列出系列海报",
      "List series release dates": "列出系列发行日期",
      "List series release years": "列出系列发行年份",
      "List series removed from library but still present on disk": "列出已移出书库但仍在磁盘上的系列",
      "List series tags": "列出系列标签",
      "List series' books": "列出系列中的书籍",
      "List series' collections": "列出系列所属合集",
      "List sharing labels": "列出共享标签",
      "List sources with their search/entry toggle state": "列出图源及其搜索/入口开关状态",
      "List subscribed online mangas": "列出已订阅的在线漫画",
      "List tags": "列出标签",
      "List the API keys of a user": "列出用户的 API 密钥",
      "List the actions present in the audit trail": "列出审计记录中出现过的操作",
      "List the active sessions of a user": "列出用户的活动会话",
      "List the books of the series behind a queue entry with their current metadata": "列出队列条目对应系列的书籍及其当前元数据",
      "List the runtime filters exposed by a source": "列出图源暴露的运行时筛选",
      "List the scrape only API tokens": "列出仅刮削权限的 API 令牌",
      "List the sources provided by an extension": "列出扩展提供的图源",
      "List trash entries of one or all libraries": "列出一个或全部书库的回收站条目",
      "List unknown duplicates": "列出未知重复",
      "List updated series": "列出已更新系列",
      "List users": "列出用户",
      "Mark a match as skipped": "将匹配标记为跳过",
      "Mark announcements as read": "将公告标记为已读",
      "Mark book as unread": "将书籍标记为未读",
      "Mark book poster as selected": "将书籍海报设为选中",
      "Mark book progression": "标记书籍阅读进度",
      "Mark book's read progress": "标记书籍阅读进度",
      "Mark collection poster as selected": "将合集海报设为选中",
      "Mark duplicate page as known": "将重复页标记为已知",
      "Mark readlist poster as selected": "将阅读清单海报设为选中",
      "Mark series as read": "将系列标记为已读",
      "Mark series as unread": "将系列标记为未读",
      "Mark series poster as selected": "将系列海报设为选中",
      "Match ComicRack list": "匹配 ComicRack 清单",
      "Match a single series against enabled sources": "将单个系列与启用的图源匹配",
      "Packages already downloaded into the config directory": "已下载到配置目录的安装包",
      "Per-library series/book counts and total size": "各书库的系列/书籍数量与总大小",
      "Permanently delete a trash entry and its files": "永久删除回收站条目及其文件",
      "Pin an existing ebook-convert executable": "固定已有的 ebook-convert 可执行文件",
      "Poll a match job": "轮询匹配任务",
      "Probe an ebook-convert executable without saving it": "探测 ebook-convert 可执行文件但不保存",
      "Probe every external runtime managed by Lantai": "探测兰台管理的所有外部运行时",
      "Probe one external runtime": "探测单个外部运行时",
      "Progress of the package download currently running": "当前安装包下载进度",
      "Progress of the package validation currently running": "当前安装包校验进度",
      "Proxy the remote cover of a search result": "代理搜索结果的远程封面",
      "Purge reading sessions beyond the retention window": "清除超出保留窗口的阅读会话",
      "Re-verify the most recent downloaded package": "重新校验最近下载的安装包",
      "Read Suwayomi settings": "读取 Suwayomi 设置",
      "Read bootstrap payload": "读取阅读启动数据",
      "Read chapter bundle": "读取章节内容包",
      "Read chapter outline page": "读取章节目录页",
      "Read chapter window": "读取章节窗口",
      "Read image resource": "读取图片资源",
      "Read one page of the global scrape queue": "读取全局刮削队列的一页",
      "Read one page of the scrape queue for an external agent": "为外部代理读取刮削队列的一页",
      "Read one queue entry with the hints an external agent needs": "读取单个队列条目及外部代理所需提示",
      "Read page thumbnail": "读取页缩略图",
      "Read scrape settings": "读取刮削设置",
      "Read structured log entries, cursor based": "读取结构化日志条目（游标分页）",
      "Read the configurable preferences of a source": "读取图源的可配置偏好",
      "Read the remote detail of a single manga": "读取单本漫画的远端详情",
      "Read the update feed and report the latest release plus any downloaded package": "读取更新源并返回最新发行版与已下载安装包",
      "Read trash settings": "读取回收站设置",
      "Rebuild the daily reading statistics of the current user": "重建当前用户的每日阅读统计",
      "Recompute first letters for every series": "重算所有系列的首字母",
      "Refresh book metadata": "刷新书籍元数据",
      "Refresh installed extension metadata": "刷新已安装扩展的元数据",
      "Refresh metadata for a library": "刷新书库的元数据",
      "Refresh series metadata": "刷新系列元数据",
      "Regenerate books posters": "重新生成书籍海报",
      "Remove a book from the library, keeping its file untouched": "将书籍移出书库，文件保持不动",
      "Remove a series from the library, keeping its files untouched": "将系列移出书库，文件保持不动",
      "Remove a single queue entry": "移除单个队列条目",
      "Replace the roles of a user": "替换用户的角色",
      "Replace the running jar with the staged one and restart": "用已暂存的 jar 替换正在运行的版本并重启",
      "Report a reading heartbeat": "上报阅读心跳",
      "Reset a queue entry back to queued": "将队列条目重置为排队",
      "Reset the password of a user and expire its sessions": "重置用户密码并使其会话失效",
      "Restore a trash entry back to its library": "将回收站条目还原到书库",
      "Restore logger levels to their default": "将日志级别恢复为默认",
      "Restore the last backup jar and restart": "还原上一次备份的 jar 并重启",
      "Resume task processing": "恢复任务处理",
      "Retrieve API keys": "读取 API 密钥",
      "Retrieve a single audit entry": "读取单条审计记录",
      "Retrieve a user with its activity summary": "读取用户及其活动摘要",
      "Retrieve announcements": "读取公告",
      "Retrieve authentication activity": "读取认证活动",
      "Retrieve authentication activity for the current user": "读取当前用户的认证活动",
      "Retrieve current user": "读取当前用户",
      "Retrieve global client settings": "读取全局客户端设置",
      "Retrieve latest authentication activity for a user": "读取用户最近的认证活动",
      "Retrieve server settings": "读取服务器设置",
      "Retrieve user client settings": "读取用户客户端设置",
      "Revoke a scrape only API token": "吊销仅刮削权限的 API 令牌",
      "Rotate the managed Suwayomi credentials": "轮换受管的 Suwayomi 凭据",
      "Run the automatic install for the given runtimes": "对指定运行时执行自动安装",
      "Runtime and storage status": "运行时与存储状态",
      "Save global settings": "保存全局设置",
      "Save user settings": "保存用户设置",
      "Scan a library": "扫描书库",
      "Scan folder for transient books": "扫描目录中的临时书籍",
      "Search audit entries": "搜索审计记录",
      "Search inside a book": "在书籍内搜索",
      "Search mangas across enabled sources": "跨启用的图源搜索漫画",
      "Search metadata candidates from one or all enabled sources": "从一个或全部启用的图源搜索元数据候选",
      "Series ids whose computed first letter matches": "首字母匹配的系列 ID",
      "Set cookie": "设置 Cookie",
      "Set the conversion mode: never, on-demand or eager": "设置转换模式：从不、按需或预转换",
      "Set the trash quota of a library, zero means inheriting the global default": "设置书库回收站配额，0 表示继承全局默认",
      "Snapshot of the automatic install performed at startup": "启动时自动安装的快照",
      "Snapshot the main and tasks SQLite databases": "备份主库与任务 SQLite 数据库",
      "Start a background auto-match job for a library": "为书库启动后台自动匹配任务",
      "Start an external runtime": "启动外部运行时",
      "Start or resume scraping the queue": "开始或继续刮削队列",
      "Start, stop or restart Suwayomi": "启动、停止或重启 Suwayomi",
      "Stop an external runtime": "停止外部运行时",
      "Stop taking new tasks; running tasks finish": "停止接收新任务，运行中的任务照常完成",
      "Stop the current reading session": "结束当前阅读会话",
      "Stream new log entries": "流式推送新日志条目",
      "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "为队列条目提交刮削结果，或传系列 ID 覆盖已应用的结果",
      "Subscribe to a remote manga and materialize it as a series": "订阅远端漫画并落地为系列",
      "Suwayomi runtime status": "Suwayomi 运行时状态",
      "Sync offline reading sessions": "同步离线阅读会话",
      "Synchronize chapters of a subscribed series": "同步已订阅系列的章节",
      "Undo an applied match using its snapshot": "用快照撤销已应用的匹配",
      "Uninstall a runtime, data is removed only when purge is requested": "卸载运行时，仅在请求清除时删除数据",
      "Uninstall an extension": "卸载扩展",
      "Unsubscribe a series and remove its placeholder files": "取消订阅系列并删除其占位文件",
      "Update Suwayomi settings": "更新 Suwayomi 设置",
      "Update a library": "更新书库",
      "Update an installed extension": "更新已安装扩展",
      "Update book metadata": "更新书籍元数据",
      "Update book metadata in bulk": "批量更新书籍元数据",
      "Update collection": "更新合集",
      "Update current user's password": "更新当前用户密码",
      "Update note, color or marker of my annotation": "更新我的标注的备注、颜色或标记",
      "Update one preference of a source and return the refreshed list": "更新图源的一项偏好并返回刷新后的列表",
      "Update readlist": "更新阅读清单",
      "Update readlist read progress (Mihon)": "更新阅读清单阅读进度（Mihon）",
      "Update scrape settings, trusting the results requires an explicit confirmation": "更新刮削设置，信任结果需显式确认",
      "Update series metadata": "更新系列元数据",
      "Update series read progress (Mihon)": "更新系列阅读进度（Mihon）",
      "Update server settings": "更新服务器设置",
      "Update the username and email of a user": "更新用户的用户名与邮箱",
      "Update trash settings": "更新回收站设置",
      "Update user": "更新用户",
      "Update user's password": "更新用户密码",
      "Upload a package and install it in the background": "上传安装包并在后台安装",
      "Upload an upgrade package and validate it without touching the running jar": "上传升级包并校验，不改动正在运行的 jar",
      "Add a scraping source": "添加一个刮削源",
      "Apply bulk series metadata from a text payload": "以文本载荷批量应用系列元数据",
      "Authenticate a KOReader user": "验证 KOReader 用户",
      "Browse a library": "浏览书库",
      "Check Kobo connectivity": "检查 Kobo 连通性",
      "Count entries awaiting bulk metadata": "统计待补元数据的条目数",
      "Create an API token": "创建 API 令牌",
      "Delete a scraping source": "删除刮削源",
      "Delete an API token": "删除 API 令牌",
      "Download a book file for Kobo": "为 Kobo 下载书籍文件",
      "Download the metadata package template": "下载元数据包模板",
      "Enable or disable a scraping source": "启用或停用刮削源",
      "Exchange a Kobo device token": "交换 Kobo 设备令牌",
      "Export series metadata as a text file": "将系列元数据导出为文本文件",
      "Get Kobo metadata for a book": "获取书籍的 Kobo 元数据",
      "Get Kobo reading state for a book": "获取书籍的 Kobo 阅读状态",
      "Get a book cover for Kobo": "获取 Kobo 书籍封面",
      "Get a book page image": "获取书页图片",
      "Get a book page thumbnail": "获取书页缩略图",
      "Get a book thumbnail": "获取书籍缩略图",
      "Get one collection": "获取单个合集",
      "Get one library": "获取单个书库",
      "Get one reading list": "获取单个阅读清单",
      "Get one series with its books": "获取单个系列及其书籍",
      "Get reading progress for a document": "获取文档的阅读进度",
      "Get scraping source details": "获取刮削源详情",
      "Get the OPDS authentication document": "获取 OPDS 认证文档",
      "Get the OPDS search description": "获取 OPDS 搜索描述",
      "Get the OPDS v1 catalog": "获取 OPDS v1 目录",
      "Get the OPDS v2 catalog": "获取 OPDS v2 目录",
      "Get the WebPub manifest for a DIVINA book": "获取 DIVINA 书籍的 WebPub 清单",
      "Get the WebPub manifest for a PDF": "获取 PDF 的 WebPub 清单",
      "Get the WebPub manifest for a book": "获取书籍的 WebPub 清单",
      "Get the WebPub manifest for an EPUB": "获取 EPUB 的 WebPub 清单",
      "Import a series metadata package": "导入系列元数据包",
      "Initialize a Kobo device": "初始化 Kobo 设备",
      "List API tokens": "列出 API 令牌",
      "List all series": "列出所有系列",
      "List available API token scopes": "列出可用的 API 令牌权限范围",
      "List books currently being read": "列出正在阅读的书籍",
      "List books to keep reading": "列出待继续阅读的书籍",
      "List collections in a library": "列出书库中的合集",
      "List entries awaiting bulk metadata": "列出待补元数据的条目",
      "List libraries": "列出书库",
      "List reading lists": "列出阅读清单",
      "List reading lists in a library": "列出书库中的阅读清单",
      "List recently added books": "列出最近添加的书籍",
      "List recently added series": "列出最近添加的系列",
      "Proxy unmatched Kobo requests": "代理未匹配的 Kobo 请求",
      "Register a KOReader user (disabled)": "注册 KOReader 用户（已禁用）",
      "Search the catalog": "搜索目录",
      "Submit bulk metadata for application": "提交批量元数据以供应用",
      "Sync the Kobo library": "同步 Kobo 书库",
      "Test a scraping source connection": "测试刮削源连接",
      "Update Kobo reading state for a book": "更新书籍的 Kobo 阅读状态",
      "Update a scraping source": "更新刮削源",
      "Update an API token": "更新 API 令牌",
      "Update reading progress for a document": "更新文档的阅读进度",
      "Serve the web interface": "提供网页界面",
      "Stream server-sent events": "推送服务端事件流",
    },
    "zh-Hant": {
      "Delete all sync points": "刪除所有同步點",
      "Directory listing": "目錄列表",
      "List all libraries": "列出所有書庫",
      "Add an extension store": "新增擴充商店",
      "Add book poster": "新增書籍海報",
      "Add collection poster": "新增合集海報",
      "Add readlist poster": "新增閱讀清單海報",
      "Add series poster": "新增系列海報",
      "Add series, a whole library or every series missing metadata to the queue": "將系列、整個書庫或所有缺中繼資料的系列加入佇列",
      "Aggregate reading devices from session history": "從工作階段歷史彙總閱讀裝置",
      "Aggregated home payload for clients": "用戶端首頁彙總資料",
      "Analyze a library": "分析書庫",
      "Analyze book": "分析書籍",
      "Analyze series": "分析系列",
      "Analyze transient book": "分析暫存書籍",
      "Apply selected fields of a match and keep a rollback snapshot": "套用符合項目的所選欄位並保留復原快照",
      "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "套用佇列項目的所選欄位，項目離開佇列時回傳 204",
      "Ask the running batch to stop after the current series": "要求執行中的批次在目前系列後停止",
      "Available versions and download sources": "可用版本與下載來源",
      "Book counts grouped by computed first letter": "依首字母統計書籍數量",
      "Book cover resized on demand": "依需求縮放的書籍封面",
      "Book ids whose computed first letter matches": "首字母相符的書籍 ID",
      "Bring back a book previously removed from the library": "將移出書庫的書籍放回",
      "Bring back a series previously removed from the library": "將移出書庫的系列放回",
      "Browse a source by popular or latest, or search inside it": "依熱門或最新瀏覽圖源，或在其內搜尋",
      "Browse installable extensions": "瀏覽可安裝的擴充",
      "Calibre availability, version and conversion mode": "Calibre 可用性、版本與轉換模式",
      "Change logger levels at runtime, use INHERIT to restore the default": "於執行期間修改日誌層級，用 INHERIT 還原預設",
      "Clear backoff and circuit state of a source": "清除圖源的退避與斷路狀態",
      "Clear queue entries by scope: queued, review, failed or all": "依範圍清空佇列項目：排隊、待核對、失敗或全部",
      "Clear task queue": "清空任務佇列",
      "Clear the pinned executable and fall back to the managed installation": "清除固定的執行檔，改回受管安裝",
      "Client capability negotiation": "用戶端能力協商",
      "Compute the field level difference of a queue entry": "計算佇列項目的欄位層級差異",
      "Convert a single book and queue re-analysis": "轉換單一本書並排入重新分析",
      "Convert pending books of a library": "轉換書庫中待轉換的書籍",
      "Count books still waiting for conversion in a library": "統計書庫中仍待轉換的書籍數量",
      "Counters for the scrape queue and the inbox": "刮削佇列與收件匣的計數",
      "Create API key": "建立 API 金鑰",
      "Create a library": "建立書庫",
      "Create a scrape only API token with fixed scopes": "建立僅具刮削固定權限的 API 權杖",
      "Create an annotation with a locator and a text fallback": "以定位符與文字備援建立註記",
      "Create collection": "建立合集",
      "Create readlist": "建立閱讀清單",
      "Create user": "建立使用者",
      "Current install progress": "目前安裝進度",
      "Current jar location and whether it can be replaced in place": "目前 jar 位置及是否可原地取代",
      "Delete API key": "刪除 API 金鑰",
      "Delete a library": "刪除書庫",
      "Delete a single task by id": "依 ID 刪除單一任務",
      "Delete all duplicate pages by hash": "依雜湊刪除所有重複頁",
      "Delete an API key of a user": "刪除使用者的 API 金鑰",
      "Delete book file": "刪除書籍檔案",
      "Delete book poster": "刪除書籍海報",
      "Delete collection": "刪除合集",
      "Delete collection poster": "刪除合集海報",
      "Delete downloaded cache of an online series, chapters remain readable through the source": "刪除線上系列的已下載快取，章節仍可透過圖源閱讀",
      "Delete every finished task record": "刪除所有已完成任務記錄",
      "Delete every package downloaded from the update feed": "刪除從更新來源下載的所有安裝包",
      "Delete every queued task; running ones are kept": "刪除所有排隊任務，執行中的保留",
      "Delete global settings": "刪除全域設定",
      "Delete my annotation": "刪除我的註記",
      "Delete readlist": "刪除閱讀清單",
      "Delete readlist poster": "刪除閱讀清單海報",
      "Delete series files": "刪除系列檔案",
      "Delete series poster": "刪除系列海報",
      "Delete specific duplicate page": "刪除指定的重複頁",
      "Delete user": "刪除使用者",
      "Delete user settings": "刪除使用者設定",
      "Discard the staged package": "捨棄已暫存的安裝包",
      "Download CSS file": "下載 CSS 檔案",
      "Download a raw log file": "下載原始日誌檔案",
      "Download a release package, verify it and stage it for install": "下載發行包、驗證並暫存待安裝",
      "Download an online chapter as a local CBZ and re-analyze it": "將線上章節下載為本機 CBZ 並重新分析",
      "Download and install the latest Calibre portable build": "下載並安裝最新的 Calibre 可攜版",
      "Download and install the latest Suwayomi release": "下載並安裝最新的 Suwayomi 發行版",
      "Download book file": "下載書籍檔案",
      "Download font file": "下載字型檔案",
      "Download from the official source and install in the background": "從官方來源下載並於背景安裝",
      "Download readlist": "下載閱讀清單",
      "Download series": "下載系列",
      "Echo build and runtime information": "回傳建置與執行環境資訊",
      "Empty the trash of one or all libraries": "清空一個或全部書庫的回收桶",
      "Empty trash for a library": "清空書庫回收桶",
      "Enable or disable a runtime": "啟用或停用執行環境",
      "Enable or disable a user": "啟用或停用使用者",
      "Environment and dependency check for an external runtime": "外部執行環境的環境與相依性檢查",
      "Equivalent shell steps for a manual install": "手動安裝的等效命令步驟",
      "Expire one or all sessions of a user": "使使用者的某個或全部工作階段失效",
      "Export a page range as zip": "匯出頁面範圍打包為 zip",
      "Export audit entries as CSV": "匯出稽核項目為 CSV",
      "Export the library inventory as JSON": "匯出書庫清單為 JSON",
      "Get Epub resource": "取得 Epub 資源",
      "Get Lantai reading capabilities": "取得蘭台閱讀能力",
      "Get book details": "取得書籍詳情",
      "Get book page image": "取得書籍頁圖片",
      "Get book page thumbnail": "取得書籍頁縮圖",
      "Get book poster image": "取得書籍海報圖片",
      "Get book progression": "取得書籍閱讀進度",
      "Get book's WebPub manifest": "取得書籍的 WebPub 清單",
      "Get book's WebPub manifest (DiViNa)": "取得書籍的 WebPub 清單（DiViNa）",
      "Get book's WebPub manifest (Epub)": "取得書籍的 WebPub 清單（Epub）",
      "Get book's WebPub manifest (PDF)": "取得書籍的 WebPub 清單（PDF）",
      "Get book's poster image": "取得書籍海報圖片",
      "Get collection details": "取得合集詳情",
      "Get collection poster image": "取得合集海報圖片",
      "Get collection's poster image": "取得合集海報圖片",
      "Get details for a single library": "取得單一書庫詳情",
      "Get known duplicate image thumbnail": "取得已知重複圖片的縮圖",
      "Get next book in readlist": "取得閱讀清單中的下一本",
      "Get next book in series": "取得系列中的下一本",
      "Get previous book in readlist": "取得閱讀清單中的上一本",
      "Get previous book in series": "取得系列中的上一本",
      "Get raw book page": "取得書籍原始頁面",
      "Get reading statistics for the current user": "取得目前使用者的閱讀統計",
      "Get readlist details": "取得閱讀清單詳情",
      "Get readlist poster image": "取得閱讀清單海報圖片",
      "Get readlist read progress (Mihon)": "取得閱讀清單閱讀進度（Mihon）",
      "Get readlist's poster image": "取得閱讀清單海報圖片",
      "Get series details": "取得系列詳情",
      "Get series poster image": "取得系列海報圖片",
      "Get series read progress (Mihon)": "取得系列閱讀進度（Mihon）",
      "Get series' poster image": "取得系列海報圖片",
      "Get the archive status of the current user's reading statistics": "取得目前使用者閱讀統計的封存狀態",
      "Get the reading time of one book for the current user": "取得目前使用者某本書的閱讀時長",
      "Get transient book page": "取得暫存書籍頁面",
      "Get unknown duplicate image thumbnail": "取得未知重複圖片的縮圖",
      "Import a Suwayomi jar manually": "手動匯入 Suwayomi jar",
      "Import a locally downloaded Calibre portable installer": "匯入本機下載的 Calibre 可攜版安裝包",
      "Import books": "匯入書籍",
      "Include or exclude a source from search and entry points": "將圖源納入或排除搜尋與入口",
      "Install an extension from an uploaded package": "從上傳的安裝包安裝擴充",
      "Install an extension from the catalog": "從清單安裝擴充",
      "Latest Calibre portable release": "最新的 Calibre 可攜版",
      "Latest Suwayomi release": "最新的 Suwayomi 發行版",
      "List active reading sessions for the current user": "列出目前使用者的作用中閱讀工作階段",
      "List age ratings": "列出年齡分級",
      "List and search users": "列出並搜尋使用者",
      "List applied scrape records of the series behind a queue entry": "列出佇列項目對應系列已套用的刮削記錄",
      "List assignable roles": "列出可指派角色",
      "List authors": "列出作者",
      "List authors names": "列出作者姓名",
      "List authors roles": "列出作者角色",
      "List authors' names": "列出作者姓名",
      "List authors' roles": "列出作者角色",
      "List available log files": "列出可用日誌檔案",
      "List book pages": "列出書籍頁面",
      "List book posters": "列出書籍海報",
      "List book tags": "列出書籍標籤",
      "List book's positions": "列出書籍中的定位",
      "List book's readlists": "列出書籍所在的閱讀清單",
      "List books": "列出書籍",
      "List books on deck": "列出待續讀書籍",
      "List books removed from library but still present on disk": "列出已移出書庫但仍在磁碟上的書籍",
      "List collection's posters": "列出合集海報",
      "List collection's series": "列出合集中的系列",
      "List collections": "列出合集",
      "List duplicate books": "列出重複書籍",
      "List duplicate matches": "列出重複符合",
      "List extension stores": "列出擴充商店",
      "List finished tasks, paged": "分頁列出已完成任務",
      "List font families": "列出字型家族",
      "List genres": "列出類型",
      "List historical events": "列出歷史事件",
      "List installed extensions": "列出已安裝擴充",
      "List known duplicates": "列出已知重複",
      "List languages": "列出語言",
      "List latest books": "列出最新書籍",
      "List latest series": "列出最新系列",
      "List loggers and their levels": "列出日誌記錄器及其層級",
      "List match candidates with their current and proposed values": "列出符合候選及其目前值與建議值",
      "List match jobs of this session": "列出本次工作階段的符合任務",
      "List materialized chapters of a subscription with download state": "列出訂閱已落地的章節及下載狀態",
      "List my annotations of a book": "列出我對某本書的註記",
      "List new series": "列出新系列",
      "List notable entities": "列出值得關注的實體",
      "List notable entities kinds": "列出值得關注的實體類型",
      "List notable entities names": "列出值得關注的實體名稱",
      "List online metadata sources and their daily usage": "列出線上中繼資料來源及其當日用量",
      "List publishers": "列出出版社",
      "List queued and running tasks, paged": "分頁列出排隊與執行中的任務",
      "List reading sessions": "列出閱讀工作階段",
      "List readlist's books": "列出閱讀清單中的書籍",
      "List readlist's posters": "列出閱讀清單海報",
      "List readlists": "列出閱讀清單",
      "List registered OAuth2 providers": "列出已註冊的 OAuth2 提供者",
      "List releases": "列出發行版",
      "List series": "列出系列",
      "List series groups": "列出系列群組",
      "List series posters": "列出系列海報",
      "List series release dates": "列出系列發行日期",
      "List series release years": "列出系列發行年份",
      "List series removed from library but still present on disk": "列出已移出書庫但仍在磁碟上的系列",
      "List series tags": "列出系列標籤",
      "List series' books": "列出系列中的書籍",
      "List series' collections": "列出系列所屬合集",
      "List sharing labels": "列出共用標籤",
      "List sources with their search/entry toggle state": "列出圖源及其搜尋/入口開關狀態",
      "List subscribed online mangas": "列出已訂閱的線上漫畫",
      "List tags": "列出標籤",
      "List the API keys of a user": "列出使用者的 API 金鑰",
      "List the actions present in the audit trail": "列出稽核記錄中出現過的動作",
      "List the active sessions of a user": "列出使用者的作用中工作階段",
      "List the books of the series behind a queue entry with their current metadata": "列出佇列項目對應系列的書籍及其目前中繼資料",
      "List the runtime filters exposed by a source": "列出圖源提供的執行期間篩選",
      "List the scrape only API tokens": "列出僅具刮削權限的 API 權杖",
      "List the sources provided by an extension": "列出擴充提供的圖源",
      "List trash entries of one or all libraries": "列出一個或全部書庫的回收桶項目",
      "List unknown duplicates": "列出未知重複",
      "List updated series": "列出已更新系列",
      "List users": "列出使用者",
      "Mark a match as skipped": "將符合標記為略過",
      "Mark announcements as read": "將公告標記為已讀",
      "Mark book as unread": "將書籍標記為未讀",
      "Mark book poster as selected": "將書籍海報設為選取",
      "Mark book progression": "標記書籍閱讀進度",
      "Mark book's read progress": "標記書籍閱讀進度",
      "Mark collection poster as selected": "將合集海報設為選取",
      "Mark duplicate page as known": "將重複頁標記為已知",
      "Mark readlist poster as selected": "將閱讀清單海報設為選取",
      "Mark series as read": "將系列標記為已讀",
      "Mark series as unread": "將系列標記為未讀",
      "Mark series poster as selected": "將系列海報設為選取",
      "Match ComicRack list": "比對 ComicRack 清單",
      "Match a single series against enabled sources": "將單一系列與啟用的圖源比對",
      "Packages already downloaded into the config directory": "已下載至設定目錄的安裝包",
      "Per-library series/book counts and total size": "各書庫的系列/書籍數量與總大小",
      "Permanently delete a trash entry and its files": "永久刪除回收桶項目及其檔案",
      "Pin an existing ebook-convert executable": "固定既有的 ebook-convert 執行檔",
      "Poll a match job": "輪詢符合任務",
      "Probe an ebook-convert executable without saving it": "探測 ebook-convert 執行檔但不儲存",
      "Probe every external runtime managed by Lantai": "探測蘭台管理的所有外部執行環境",
      "Probe one external runtime": "探測單一外部執行環境",
      "Progress of the package download currently running": "目前安裝包下載進度",
      "Progress of the package validation currently running": "目前安裝包驗證進度",
      "Proxy the remote cover of a search result": "代理搜尋結果的遠端封面",
      "Purge reading sessions beyond the retention window": "清除超出保留範圍的閱讀工作階段",
      "Re-verify the most recent downloaded package": "重新驗證最近下載的安裝包",
      "Read Suwayomi settings": "讀取 Suwayomi 設定",
      "Read bootstrap payload": "讀取閱讀啟動資料",
      "Read chapter bundle": "讀取章節內容包",
      "Read chapter outline page": "讀取章節目錄頁",
      "Read chapter window": "讀取章節視窗",
      "Read image resource": "讀取圖片資源",
      "Read one page of the global scrape queue": "讀取全域刮削佇列的一頁",
      "Read one page of the scrape queue for an external agent": "為外部代理讀取刮削佇列的一頁",
      "Read one queue entry with the hints an external agent needs": "讀取單一佇列項目及外部代理所需提示",
      "Read page thumbnail": "讀取頁縮圖",
      "Read scrape settings": "讀取刮削設定",
      "Read structured log entries, cursor based": "讀取結構化日誌項目（游標分頁）",
      "Read the configurable preferences of a source": "讀取圖源的可設定偏好",
      "Read the remote detail of a single manga": "讀取單本漫畫的遠端詳情",
      "Read the update feed and report the latest release plus any downloaded package": "讀取更新來源並回傳最新發行版與已下載安裝包",
      "Read trash settings": "讀取回收桶設定",
      "Rebuild the daily reading statistics of the current user": "重建目前使用者的每日閱讀統計",
      "Recompute first letters for every series": "重算所有系列的首字母",
      "Refresh book metadata": "重新整理書籍中繼資料",
      "Refresh installed extension metadata": "重新整理已安裝擴充的中繼資料",
      "Refresh metadata for a library": "重新整理書庫的中繼資料",
      "Refresh series metadata": "重新整理系列中繼資料",
      "Regenerate books posters": "重新產生書籍海報",
      "Remove a book from the library, keeping its file untouched": "將書籍移出書庫，檔案保持不動",
      "Remove a series from the library, keeping its files untouched": "將系列移出書庫，檔案保持不動",
      "Remove a single queue entry": "移除單一佇列項目",
      "Replace the roles of a user": "取代使用者的角色",
      "Replace the running jar with the staged one and restart": "以已暫存的 jar 取代執行中的版本並重新啟動",
      "Report a reading heartbeat": "回報閱讀心跳",
      "Reset a queue entry back to queued": "將佇列項目重設為排隊",
      "Reset the password of a user and expire its sessions": "重設使用者密碼並使其工作階段失效",
      "Restore a trash entry back to its library": "將回收桶項目還原至書庫",
      "Restore logger levels to their default": "將日誌層級還原為預設",
      "Restore the last backup jar and restart": "還原上一次備份的 jar 並重新啟動",
      "Resume task processing": "恢復任務處理",
      "Retrieve API keys": "讀取 API 金鑰",
      "Retrieve a single audit entry": "讀取單筆稽核記錄",
      "Retrieve a user with its activity summary": "讀取使用者及其活動摘要",
      "Retrieve announcements": "讀取公告",
      "Retrieve authentication activity": "讀取驗證活動",
      "Retrieve authentication activity for the current user": "讀取目前使用者的驗證活動",
      "Retrieve current user": "讀取目前使用者",
      "Retrieve global client settings": "讀取全域用戶端設定",
      "Retrieve latest authentication activity for a user": "讀取使用者最近的驗證活動",
      "Retrieve server settings": "讀取伺服器設定",
      "Retrieve user client settings": "讀取使用者用戶端設定",
      "Revoke a scrape only API token": "撤銷僅具刮削權限的 API 權杖",
      "Rotate the managed Suwayomi credentials": "輪換受管的 Suwayomi 憑證",
      "Run the automatic install for the given runtimes": "對指定執行環境執行自動安裝",
      "Runtime and storage status": "執行環境與儲存狀態",
      "Save global settings": "儲存全域設定",
      "Save user settings": "儲存使用者設定",
      "Scan a library": "掃描書庫",
      "Scan folder for transient books": "掃描目錄中的暫存書籍",
      "Search audit entries": "搜尋稽核記錄",
      "Search inside a book": "在書籍內搜尋",
      "Search mangas across enabled sources": "跨啟用的圖源搜尋漫畫",
      "Search metadata candidates from one or all enabled sources": "從一個或全部啟用的圖源搜尋中繼資料候選",
      "Series ids whose computed first letter matches": "首字母相符的系列 ID",
      "Set cookie": "設定 Cookie",
      "Set the conversion mode: never, on-demand or eager": "設定轉換模式：從不、依需求或預先轉換",
      "Set the trash quota of a library, zero means inheriting the global default": "設定書庫回收桶配額，0 表示繼承全域預設",
      "Snapshot of the automatic install performed at startup": "啟動時自動安裝的快照",
      "Snapshot the main and tasks SQLite databases": "備份主資料庫與任務 SQLite 資料庫",
      "Start a background auto-match job for a library": "為書庫啟動背景自動比對任務",
      "Start an external runtime": "啟動外部執行環境",
      "Start or resume scraping the queue": "開始或繼續刮削佇列",
      "Start, stop or restart Suwayomi": "啟動、停止或重新啟動 Suwayomi",
      "Stop an external runtime": "停止外部執行環境",
      "Stop taking new tasks; running tasks finish": "停止接收新任務，執行中的任務照常完成",
      "Stop the current reading session": "結束目前閱讀工作階段",
      "Stream new log entries": "串流推送新日誌項目",
      "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "為佇列項目提交刮削結果，或傳系列 ID 覆寫已套用的結果",
      "Subscribe to a remote manga and materialize it as a series": "訂閱遠端漫畫並落地為系列",
      "Suwayomi runtime status": "Suwayomi 執行環境狀態",
      "Sync offline reading sessions": "同步離線閱讀工作階段",
      "Synchronize chapters of a subscribed series": "同步已訂閱系列的章節",
      "Undo an applied match using its snapshot": "以快照復原已套用的比對",
      "Uninstall a runtime, data is removed only when purge is requested": "解除安裝執行環境，僅在要求清除時刪除資料",
      "Uninstall an extension": "解除安裝擴充",
      "Unsubscribe a series and remove its placeholder files": "取消訂閱系列並刪除其佔位檔案",
      "Update Suwayomi settings": "更新 Suwayomi 設定",
      "Update a library": "更新書庫",
      "Update an installed extension": "更新已安裝擴充",
      "Update book metadata": "更新書籍中繼資料",
      "Update book metadata in bulk": "批次更新書籍中繼資料",
      "Update collection": "更新合集",
      "Update current user's password": "更新目前使用者密碼",
      "Update note, color or marker of my annotation": "更新我的註記的備註、顏色或標記",
      "Update one preference of a source and return the refreshed list": "更新圖源的一項偏好並回傳更新後的清單",
      "Update readlist": "更新閱讀清單",
      "Update readlist read progress (Mihon)": "更新閱讀清單閱讀進度（Mihon）",
      "Update scrape settings, trusting the results requires an explicit confirmation": "更新刮削設定，信任結果需明確確認",
      "Update series metadata": "更新系列中繼資料",
      "Update series read progress (Mihon)": "更新系列閱讀進度（Mihon）",
      "Update server settings": "更新伺服器設定",
      "Update the username and email of a user": "更新使用者的使用者名稱與電子郵件",
      "Update trash settings": "更新回收桶設定",
      "Update user": "更新使用者",
      "Update user's password": "更新使用者密碼",
      "Upload a package and install it in the background": "上傳安裝包並於背景安裝",
      "Upload an upgrade package and validate it without touching the running jar": "上傳升級包並驗證，不改動執行中的 jar",
      "Add a scraping source": "新增一個刮削源",
      "Apply bulk series metadata from a text payload": "以文字承載批量套用系列中繼資料",
      "Authenticate a KOReader user": "驗證 KOReader 使用者",
      "Browse a library": "瀏覽書庫",
      "Check Kobo connectivity": "檢查 Kobo 連線",
      "Count entries awaiting bulk metadata": "統計待補中繼資料的條目數",
      "Create an API token": "建立 API 權杖",
      "Delete a scraping source": "刪除刮削源",
      "Delete an API token": "刪除 API 權杖",
      "Download a book file for Kobo": "為 Kobo 下載書籍檔案",
      "Download the metadata package template": "下載中繼資料包範本",
      "Enable or disable a scraping source": "啟用或停用刮削源",
      "Exchange a Kobo device token": "交換 Kobo 裝置權杖",
      "Export series metadata as a text file": "將系列中繼資料匯出為文字檔",
      "Get Kobo metadata for a book": "取得書籍的 Kobo 中繼資料",
      "Get Kobo reading state for a book": "取得書籍的 Kobo 閱讀狀態",
      "Get a book cover for Kobo": "取得 Kobo 書籍封面",
      "Get a book page image": "取得書頁圖片",
      "Get a book page thumbnail": "取得書頁縮圖",
      "Get a book thumbnail": "取得書籍縮圖",
      "Get one collection": "取得單一合集",
      "Get one library": "取得單一書庫",
      "Get one reading list": "取得單一閱讀清單",
      "Get one series with its books": "取得單一系列及其書籍",
      "Get reading progress for a document": "取得文件的閱讀進度",
      "Get scraping source details": "取得刮削源詳情",
      "Get the OPDS authentication document": "取得 OPDS 驗證文件",
      "Get the OPDS search description": "取得 OPDS 搜尋描述",
      "Get the OPDS v1 catalog": "取得 OPDS v1 目錄",
      "Get the OPDS v2 catalog": "取得 OPDS v2 目錄",
      "Get the WebPub manifest for a DIVINA book": "取得 DIVINA 書籍的 WebPub 清單",
      "Get the WebPub manifest for a PDF": "取得 PDF 的 WebPub 清單",
      "Get the WebPub manifest for a book": "取得書籍的 WebPub 清單",
      "Get the WebPub manifest for an EPUB": "取得 EPUB 的 WebPub 清單",
      "Import a series metadata package": "匯入系列中繼資料包",
      "Initialize a Kobo device": "初始化 Kobo 裝置",
      "List API tokens": "列出 API 權杖",
      "List all series": "列出所有系列",
      "List available API token scopes": "列出可用的 API 權杖權限範圍",
      "List books currently being read": "列出正在閱讀的書籍",
      "List books to keep reading": "列出待繼續閱讀的書籍",
      "List collections in a library": "列出書庫中的合集",
      "List entries awaiting bulk metadata": "列出待補中繼資料的條目",
      "List libraries": "列出書庫",
      "List reading lists": "列出閱讀清單",
      "List reading lists in a library": "列出書庫中的閱讀清單",
      "List recently added books": "列出最近新增的書籍",
      "List recently added series": "列出最近新增的系列",
      "Proxy unmatched Kobo requests": "代理未匹配的 Kobo 請求",
      "Register a KOReader user (disabled)": "註冊 KOReader 使用者（已停用）",
      "Search the catalog": "搜尋目錄",
      "Submit bulk metadata for application": "提交批量中繼資料以供套用",
      "Sync the Kobo library": "同步 Kobo 書庫",
      "Test a scraping source connection": "測試刮削源連線",
      "Update Kobo reading state for a book": "更新書籍的 Kobo 閱讀狀態",
      "Update a scraping source": "更新刮削源",
      "Update an API token": "更新 API 權杖",
      "Update reading progress for a document": "更新文件的閱讀進度",
      "Serve the web interface": "提供網頁介面",
      "Stream server-sent events": "推送伺服器事件流",
    }
  };
})();

(function () {
  var B = window.LANTAI_API_I18N;
  B["ja"] = {
    "Delete all sync points": "すべての同期ポイントを削除",
    "Directory listing": "ディレクトリ一覧",
    "List all libraries": "すべてのライブラリ一覧",
    "Add an extension store": "拡張ストアを追加",
    "Add book poster": "書籍ポスターを追加",
    "Add collection poster": "コレクションポスターを追加",
    "Add readlist poster": "リーディングリストポスターを追加",
    "Add series poster": "シリーズポスターを追加",
    "Add series, a whole library or every series missing metadata to the queue": "シリーズ、ライブラリ全体、またはメタデータが不足しているすべてのシリーズをキューに追加",
    "Aggregate reading devices from session history": "セッション履歴から読書デバイスを集計",
    "Aggregated home payload for clients": "クライアント向けホームの集約データ",
    "Analyze a library": "ライブラリを解析",
    "Analyze book": "書籍を解析",
    "Analyze series": "シリーズを解析",
    "Analyze transient book": "一時書籍を解析",
    "Apply selected fields of a match and keep a rollback snapshot": "一致項目の選択フィールドを適用し、ロールバックスナップショットを保持",
    "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "キュー項目の選択フィールドを適用。項目がキューから外れると 204 を返す",
    "Ask the running batch to stop after the current series": "実行中のバッチに現在のシリーズ終了後の停止を要求",
    "Available versions and download sources": "利用可能なバージョンとダウンロード元",
    "Book counts grouped by computed first letter": "算出された頭文字ごとの書籍数",
    "Book cover resized on demand": "要求に応じて縮小される書籍カバー",
    "Book ids whose computed first letter matches": "算出された頭文字が一致する書籍 ID",
    "Bring back a book previously removed from the library": "以前ライブラリから削除した書籍を復元",
    "Bring back a series previously removed from the library": "以前ライブラリから削除したシリーズを復元",
    "Browse a source by popular or latest, or search inside it": "人気順または最新順でソースを閲覧、またはソース内を検索",
    "Browse installable extensions": "インストール可能な拡張機能を閲覧",
    "Calibre availability, version and conversion mode": "Calibre の可用性、バージョン、変換モード",
    "Change logger levels at runtime, use INHERIT to restore the default": "実行時にロガーレベルを変更。INHERIT で既定値に戻す",
    "Clear backoff and circuit state of a source": "ソースのバックオフとサーキット状態をクリア",
    "Clear queue entries by scope: queued, review, failed or all": "スコープ別にキュー項目をクリア：待機、レビュー、失敗、すべて",
    "Clear task queue": "タスクキューをクリア",
    "Clear the pinned executable and fall back to the managed installation": "固定した実行ファイルを解除し、管理インストールに戻す",
    "Client capability negotiation": "クライアント機能のネゴシエーション",
    "Compute the field level difference of a queue entry": "キュー項目のフィールド単位の差分を計算",
    "Convert a single book and queue re-analysis": "単一の書籍を変換し、再解析をキューに追加",
    "Convert pending books of a library": "ライブラリ内の未変換書籍を変換",
    "Count books still waiting for conversion in a library": "ライブラリ内で変換待ちの書籍数を集計",
    "Counters for the scrape queue and the inbox": "スクレイプキューと受信箱のカウンター",
    "Create API key": "API キーを作成",
    "Create a library": "ライブラリを作成",
    "Create a scrape only API token with fixed scopes": "固定スコープのスクレイプ専用 API トークンを作成",
    "Create an annotation with a locator and a text fallback": "ロケーターとテキストフォールバック付きの注釈を作成",
    "Create collection": "コレクションを作成",
    "Create readlist": "リーディングリストを作成",
    "Create user": "ユーザーを作成",
    "Current install progress": "現在のインストール進捗",
    "Current jar location and whether it can be replaced in place": "現在の jar の場所とインプレース置換の可否",
    "Delete API key": "API キーを削除",
    "Delete a library": "ライブラリを削除",
    "Delete a single task by id": "ID 指定で単一タスクを削除",
    "Delete all duplicate pages by hash": "ハッシュ別に重複ページをすべて削除",
    "Delete an API key of a user": "ユーザーの API キーを削除",
    "Delete book file": "書籍ファイルを削除",
    "Delete book poster": "書籍ポスターを削除",
    "Delete collection": "コレクションを削除",
    "Delete collection poster": "コレクションポスターを削除",
    "Delete downloaded cache of an online series, chapters remain readable through the source": "オンラインシリーズのダウンロードキャッシュを削除。チャプターはソース経由で引き続き読める",
    "Delete every finished task record": "完了したタスク記録をすべて削除",
    "Delete every package downloaded from the update feed": "更新フィードからダウンロードしたパッケージをすべて削除",
    "Delete every queued task; running ones are kept": "待機中のタスクをすべて削除。実行中のものは保持",
    "Delete global settings": "グローバル設定を削除",
    "Delete my annotation": "自分の注釈を削除",
    "Delete readlist": "リーディングリストを削除",
    "Delete readlist poster": "リーディングリストポスターを削除",
    "Delete series files": "シリーズファイルを削除",
    "Delete series poster": "シリーズポスターを削除",
    "Delete specific duplicate page": "特定の重複ページを削除",
    "Delete user": "ユーザーを削除",
    "Delete user settings": "ユーザー設定を削除",
    "Discard the staged package": "ステージ済みパッケージを破棄",
    "Download CSS file": "CSS ファイルをダウンロード",
    "Download a raw log file": "生ログファイルをダウンロード",
    "Download a release package, verify it and stage it for install": "リリースパッケージをダウンロードして検証し、インストール用にステージ",
    "Download an online chapter as a local CBZ and re-analyze it": "オンラインチャプターをローカル CBZ としてダウンロードし再解析",
    "Download and install the latest Calibre portable build": "最新の Calibre ポータブル版をダウンロードしてインストール",
    "Download and install the latest Suwayomi release": "最新の Suwayomi リリースをダウンロードしてインストール",
    "Download book file": "書籍ファイルをダウンロード",
    "Download font file": "フォントファイルをダウンロード",
    "Download from the official source and install in the background": "公式ソースからダウンロードし、バックグラウンドでインストール",
    "Download readlist": "リーディングリストをダウンロード",
    "Download series": "シリーズをダウンロード",
    "Echo build and runtime information": "ビルドと実行環境の情報を返す",
    "Empty the trash of one or all libraries": "1 つまたはすべてのライブラリのゴミ箱を空にする",
    "Empty trash for a library": "ライブラリのゴミ箱を空にする",
    "Enable or disable a runtime": "ランタイムを有効化／無効化",
    "Enable or disable a user": "ユーザーを有効化／無効化",
    "Environment and dependency check for an external runtime": "外部ランタイムの環境と依存関係をチェック",
    "Equivalent shell steps for a manual install": "手動インストール用の同等シェル手順",
    "Expire one or all sessions of a user": "ユーザーの 1 つまたはすべてのセッションを失効",
    "Export a page range as zip": "ページ範囲を zip としてエクスポート",
    "Export audit entries as CSV": "監査エントリを CSV としてエクスポート",
    "Export the library inventory as JSON": "ライブラリのインベントリを JSON としてエクスポート",
    "Get Epub resource": "Epub リソースを取得",
    "Get Lantai reading capabilities": "Lantai の読書機能を取得",
    "Get book details": "書籍の詳細を取得",
    "Get book page image": "書籍ページ画像を取得",
    "Get book page thumbnail": "書籍ページのサムネイルを取得",
    "Get book poster image": "書籍ポスター画像を取得",
    "Get book progression": "書籍の進捗を取得",
    "Get book's WebPub manifest": "書籍の WebPub マニフェストを取得",
    "Get book's WebPub manifest (DiViNa)": "書籍の WebPub マニフェストを取得（DiViNa）",
    "Get book's WebPub manifest (Epub)": "書籍の WebPub マニフェストを取得（Epub）",
    "Get book's WebPub manifest (PDF)": "書籍の WebPub マニフェストを取得（PDF）",
    "Get book's poster image": "書籍のポスター画像を取得",
    "Get collection details": "コレクションの詳細を取得",
    "Get collection poster image": "コレクションポスター画像を取得",
    "Get collection's poster image": "コレクションのポスター画像を取得",
    "Get details for a single library": "単一ライブラリの詳細を取得",
    "Get known duplicate image thumbnail": "既知の重複画像のサムネイルを取得",
    "Get next book in readlist": "リーディングリストの次の書籍を取得",
    "Get next book in series": "シリーズの次の書籍を取得",
    "Get previous book in readlist": "リーディングリストの前の書籍を取得",
    "Get previous book in series": "シリーズの前の書籍を取得",
    "Get raw book page": "生の書籍ページを取得",
    "Get reading statistics for the current user": "現在のユーザーの読書統計を取得",
    "Get readlist details": "リーディングリストの詳細を取得",
    "Get readlist poster image": "リーディングリストポスター画像を取得",
    "Get readlist read progress (Mihon)": "リーディングリストの読書進捗を取得（Mihon）",
    "Get readlist's poster image": "リーディングリストのポスター画像を取得",
    "Get series details": "シリーズの詳細を取得",
    "Get series poster image": "シリーズポスター画像を取得",
    "Get series read progress (Mihon)": "シリーズの読書進捗を取得（Mihon）",
    "Get series' poster image": "シリーズのポスター画像を取得",
    "Get the archive status of the current user's reading statistics": "現在のユーザーの読書統計のアーカイブ状態を取得",
    "Get the reading time of one book for the current user": "現在のユーザーの 1 冊の読書時間を取得",
    "Get transient book page": "一時書籍のページを取得",
    "Get unknown duplicate image thumbnail": "未知の重複画像のサムネイルを取得",
    "Import a Suwayomi jar manually": "Suwayomi の jar を手動でインポート",
    "Import a locally downloaded Calibre portable installer": "ローカルにダウンロードした Calibre ポータブルインストーラーをインポート",
    "Import books": "書籍をインポート",
    "Include or exclude a source from search and entry points": "検索とエントリポイントからソースを含める／除外する",
    "Install an extension from an uploaded package": "アップロードしたパッケージから拡張機能をインストール",
    "Install an extension from the catalog": "カタログから拡張機能をインストール",
    "Latest Calibre portable release": "最新の Calibre ポータブルリリース",
    "Latest Suwayomi release": "最新の Suwayomi リリース",
    "List active reading sessions for the current user": "現在のユーザーのアクティブな読書セッション一覧",
    "List age ratings": "年齢区分一覧",
    "List and search users": "ユーザーの一覧と検索",
    "List applied scrape records of the series behind a queue entry": "キュー項目の対象シリーズに適用済みのスクレイプ記録一覧",
    "List assignable roles": "割り当て可能なロール一覧",
    "List authors": "著者一覧",
    "List authors names": "著者名一覧",
    "List authors roles": "著者の役割一覧",
    "List authors' names": "著者名一覧",
    "List authors' roles": "著者の役割一覧",
    "List available log files": "利用可能なログファイル一覧",
    "List book pages": "書籍ページ一覧",
    "List book posters": "書籍ポスター一覧",
    "List book tags": "書籍タグ一覧",
    "List book's positions": "書籍の位置一覧",
    "List book's readlists": "書籍のリーディングリスト一覧",
    "List books": "書籍一覧",
    "List books on deck": "デッキ上の書籍一覧",
    "List books removed from library but still present on disk": "ライブラリから削除されたがディスクに残っている書籍一覧",
    "List collection's posters": "コレクションのポスター一覧",
    "List collection's series": "コレクションのシリーズ一覧",
    "List collections": "コレクション一覧",
    "List duplicate books": "重複書籍一覧",
    "List duplicate matches": "重複一致一覧",
    "List extension stores": "拡張ストア一覧",
    "List finished tasks, paged": "完了タスク一覧（ページング）",
    "List font families": "フォントファミリー一覧",
    "List genres": "ジャンル一覧",
    "List historical events": "履歴イベント一覧",
    "List installed extensions": "インストール済み拡張機能一覧",
    "List known duplicates": "既知の重複一覧",
    "List languages": "言語一覧",
    "List latest books": "最新書籍一覧",
    "List latest series": "最新シリーズ一覧",
    "List loggers and their levels": "ロガーとそのレベルの一覧",
    "List match candidates with their current and proposed values": "現在値と提案値付きの一致候補一覧",
    "List match jobs of this session": "このセッションの一致ジョブ一覧",
    "List materialized chapters of a subscription with download state": "ダウンロード状態付きの購読の実体化チャプター一覧",
    "List my annotations of a book": "書籍に対する自分の注釈一覧",
    "List new series": "新着シリーズ一覧",
    "List notable entities": "注目エンティティ一覧",
    "List notable entities kinds": "注目エンティティの種類一覧",
    "List notable entities names": "注目エンティティ名一覧",
    "List online metadata sources and their daily usage": "オンラインメタデータソースとその日次使用量一覧",
    "List publishers": "出版社一覧",
    "List queued and running tasks, paged": "待機中・実行中タスク一覧（ページング）",
    "List reading sessions": "読書セッション一覧",
    "List readlist's books": "リーディングリストの書籍一覧",
    "List readlist's posters": "リーディングリストのポスター一覧",
    "List readlists": "リーディングリスト一覧",
    "List registered OAuth2 providers": "登録済み OAuth2 プロバイダー一覧",
    "List releases": "リリース一覧",
    "List series": "シリーズ一覧",
    "List series groups": "シリーズグループ一覧",
    "List series posters": "シリーズポスター一覧",
    "List series release dates": "シリーズ発売日一覧",
    "List series release years": "シリーズ発売年一覧",
    "List series removed from library but still present on disk": "ライブラリから削除されたがディスクに残っているシリーズ一覧",
    "List series tags": "シリーズタグ一覧",
    "List series' books": "シリーズの書籍一覧",
    "List series' collections": "シリーズのコレクション一覧",
    "List sharing labels": "共有ラベル一覧",
    "List sources with their search/entry toggle state": "検索・エントリの切替状態付きのソース一覧",
    "List subscribed online mangas": "購読中のオンライン漫画一覧",
    "List tags": "タグ一覧",
    "List the API keys of a user": "ユーザーの API キー一覧",
    "List the actions present in the audit trail": "監査証跡に含まれるアクション一覧",
    "List the active sessions of a user": "ユーザーのアクティブセッション一覧",
    "List the books of the series behind a queue entry with their current metadata": "キュー項目の対象シリーズの書籍を現在のメタデータ付きで一覧",
    "List the runtime filters exposed by a source": "ソースが公開するランタイムフィルター一覧",
    "List the scrape only API tokens": "スクレイプ専用 API トークン一覧",
    "List the sources provided by an extension": "拡張機能が提供するソース一覧",
    "List trash entries of one or all libraries": "1 つまたはすべてのライブラリのゴミ箱項目一覧",
    "List unknown duplicates": "未知の重複一覧",
    "List updated series": "更新されたシリーズ一覧",
    "List users": "ユーザー一覧",
    "Mark a match as skipped": "一致をスキップとしてマーク",
    "Mark announcements as read": "お知らせを既読にする",
    "Mark book as unread": "書籍を未読にする",
    "Mark book poster as selected": "書籍ポスターを選択済みにする",
    "Mark book progression": "書籍の進捗を記録",
    "Mark book's read progress": "書籍の読書進捗を記録",
    "Mark collection poster as selected": "コレクションポスターを選択済みにする",
    "Mark duplicate page as known": "重複ページを既知にする",
    "Mark readlist poster as selected": "リーディングリストポスターを選択済みにする",
    "Mark series as read": "シリーズを既読にする",
    "Mark series as unread": "シリーズを未読にする",
    "Mark series poster as selected": "シリーズポスターを選択済みにする",
    "Match ComicRack list": "ComicRack リストを照合",
    "Match a single series against enabled sources": "単一シリーズを有効なソースと照合",
    "Packages already downloaded into the config directory": "設定ディレクトリにダウンロード済みのパッケージ",
    "Per-library series/book counts and total size": "ライブラリ別のシリーズ数・書籍数と合計サイズ",
    "Permanently delete a trash entry and its files": "ゴミ箱項目とそのファイルを完全に削除",
    "Pin an existing ebook-convert executable": "既存の ebook-convert 実行ファイルを固定",
    "Poll a match job": "一致ジョブをポーリング",
    "Probe an ebook-convert executable without saving it": "保存せずに ebook-convert 実行ファイルを検査",
    "Probe every external runtime managed by Lantai": "Lantai が管理するすべての外部ランタイムを検査",
    "Probe one external runtime": "1 つの外部ランタイムを検査",
    "Progress of the package download currently running": "実行中のパッケージダウンロードの進捗",
    "Progress of the package validation currently running": "実行中のパッケージ検証の進捗",
    "Proxy the remote cover of a search result": "検索結果のリモートカバーをプロキシ",
    "Purge reading sessions beyond the retention window": "保持期間を超えた読書セッションを削除",
    "Re-verify the most recent downloaded package": "最新のダウンロード済みパッケージを再検証",
    "Read Suwayomi settings": "Suwayomi 設定を読み取る",
    "Read bootstrap payload": "ブートストラップデータを読み取る",
    "Read chapter bundle": "チャプターバンドルを読み取る",
    "Read chapter outline page": "チャプターアウトラインページを読み取る",
    "Read chapter window": "チャプターウィンドウを読み取る",
    "Read image resource": "画像リソースを読み取る",
    "Read one page of the global scrape queue": "グローバルスクレイプキューの 1 ページを読み取る",
    "Read one page of the scrape queue for an external agent": "外部エージェント向けスクレイプキューの 1 ページを読み取る",
    "Read one queue entry with the hints an external agent needs": "外部エージェントに必要なヒント付きで 1 つのキュー項目を読み取る",
    "Read page thumbnail": "ページサムネイルを読み取る",
    "Read scrape settings": "スクレイプ設定を読み取る",
    "Read structured log entries, cursor based": "構造化ログエントリをカーソル方式で読み取る",
    "Read the configurable preferences of a source": "ソースの設定可能な項目を読み取る",
    "Read the remote detail of a single manga": "単一漫画のリモート詳細を読み取る",
    "Read the update feed and report the latest release plus any downloaded package": "更新フィードを読み取り、最新リリースとダウンロード済みパッケージを報告",
    "Read trash settings": "ゴミ箱設定を読み取る",
    "Rebuild the daily reading statistics of the current user": "現在のユーザーの日次読書統計を再構築",
    "Recompute first letters for every series": "すべてのシリーズの頭文字を再計算",
    "Refresh book metadata": "書籍メタデータを再読み込み",
    "Refresh installed extension metadata": "インストール済み拡張機能のメタデータを再読み込み",
    "Refresh metadata for a library": "ライブラリのメタデータを再読み込み",
    "Refresh series metadata": "シリーズメタデータを再読み込み",
    "Regenerate books posters": "書籍ポスターを再生成",
    "Remove a book from the library, keeping its file untouched": "ファイルを変更せずに書籍をライブラリから除外",
    "Remove a series from the library, keeping its files untouched": "ファイルを変更せずにシリーズをライブラリから除外",
    "Remove a single queue entry": "単一のキュー項目を削除",
    "Replace the roles of a user": "ユーザーのロールを置き換え",
    "Replace the running jar with the staged one and restart": "実行中の jar をステージ済みのものに置き換えて再起動",
    "Report a reading heartbeat": "読書ハートビートを送信",
    "Reset a queue entry back to queued": "キュー項目を待機状態に戻す",
    "Reset the password of a user and expire its sessions": "ユーザーのパスワードをリセットし、セッションを失効",
    "Restore a trash entry back to its library": "ゴミ箱項目をライブラリに復元",
    "Restore logger levels to their default": "ロガーレベルを既定値に戻す",
    "Restore the last backup jar and restart": "最後のバックアップ jar を復元して再起動",
    "Resume task processing": "タスク処理を再開",
    "Retrieve API keys": "API キーを取得",
    "Retrieve a single audit entry": "単一の監査エントリを取得",
    "Retrieve a user with its activity summary": "アクティビティ概要付きでユーザーを取得",
    "Retrieve announcements": "お知らせを取得",
    "Retrieve authentication activity": "認証アクティビティを取得",
    "Retrieve authentication activity for the current user": "現在のユーザーの認証アクティビティを取得",
    "Retrieve current user": "現在のユーザーを取得",
    "Retrieve global client settings": "グローバルクライアント設定を取得",
    "Retrieve latest authentication activity for a user": "ユーザーの最新認証アクティビティを取得",
    "Retrieve server settings": "サーバー設定を取得",
    "Retrieve user client settings": "ユーザークライアント設定を取得",
    "Revoke a scrape only API token": "スクレイプ専用 API トークンを取り消す",
    "Rotate the managed Suwayomi credentials": "管理されている Suwayomi 認証情報をローテーション",
    "Run the automatic install for the given runtimes": "指定したランタイムの自動インストールを実行",
    "Runtime and storage status": "ランタイムとストレージの状態",
    "Save global settings": "グローバル設定を保存",
    "Save user settings": "ユーザー設定を保存",
    "Scan a library": "ライブラリをスキャン",
    "Scan folder for transient books": "フォルダをスキャンして一時書籍を検出",
    "Search audit entries": "監査エントリを検索",
    "Search inside a book": "書籍内を検索",
    "Search mangas across enabled sources": "有効なソース全体で漫画を検索",
    "Search metadata candidates from one or all enabled sources": "1 つまたはすべての有効なソースからメタデータ候補を検索",
    "Series ids whose computed first letter matches": "算出された頭文字が一致するシリーズ ID",
    "Set cookie": "Cookie を設定",
    "Set the conversion mode: never, on-demand or eager": "変換モードを設定：なし、オンデマンド、即時",
    "Set the trash quota of a library, zero means inheriting the global default": "ライブラリのゴミ箱容量を設定。0 はグローバル既定値を継承",
    "Snapshot of the automatic install performed at startup": "起動時に実行された自動インストールのスナップショット",
    "Snapshot the main and tasks SQLite databases": "メインとタスクの SQLite データベースをスナップショット",
    "Start a background auto-match job for a library": "ライブラリのバックグラウンド自動照合ジョブを開始",
    "Start an external runtime": "外部ランタイムを起動",
    "Start or resume scraping the queue": "キューのスクレイプを開始または再開",
    "Start, stop or restart Suwayomi": "Suwayomi を起動・停止・再起動",
    "Stop an external runtime": "外部ランタイムを停止",
    "Stop taking new tasks; running tasks finish": "新規タスクの受け付けを停止。実行中のものは完了まで継続",
    "Stop the current reading session": "現在の読書セッションを終了",
    "Stream new log entries": "新しいログエントリをストリーム配信",
    "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "キュー項目のスクレイプ結果を送信。またはシリーズ ID を渡して適用済み結果を上書き",
    "Subscribe to a remote manga and materialize it as a series": "リモート漫画を購読し、シリーズとして実体化",
    "Suwayomi runtime status": "Suwayomi ランタイムの状態",
    "Sync offline reading sessions": "オフライン読書セッションを同期",
    "Synchronize chapters of a subscribed series": "購読シリーズのチャプターを同期",
    "Undo an applied match using its snapshot": "スナップショットを使って適用済みの一致を元に戻す",
    "Uninstall a runtime, data is removed only when purge is requested": "ランタイムをアンインストール。データは削除指定時のみ削除",
    "Uninstall an extension": "拡張機能をアンインストール",
    "Unsubscribe a series and remove its placeholder files": "シリーズの購読を解除し、プレースホルダーファイルを削除",
    "Update Suwayomi settings": "Suwayomi 設定を更新",
    "Update a library": "ライブラリを更新",
    "Update an installed extension": "インストール済み拡張機能を更新",
    "Update book metadata": "書籍メタデータを更新",
    "Update book metadata in bulk": "書籍メタデータを一括更新",
    "Update collection": "コレクションを更新",
    "Update current user's password": "現在のユーザーのパスワードを更新",
    "Update note, color or marker of my annotation": "自分の注釈のメモ・色・マーカーを更新",
    "Update one preference of a source and return the refreshed list": "ソースの設定項目を 1 つ更新し、更新後の一覧を返す",
    "Update readlist": "リーディングリストを更新",
    "Update readlist read progress (Mihon)": "リーディングリストの読書進捗を更新（Mihon）",
    "Update scrape settings, trusting the results requires an explicit confirmation": "スクレイプ設定を更新。結果を信頼するには明示的な確認が必要",
    "Update series metadata": "シリーズメタデータを更新",
    "Update series read progress (Mihon)": "シリーズの読書進捗を更新（Mihon）",
    "Update server settings": "サーバー設定を更新",
    "Update the username and email of a user": "ユーザーのユーザー名とメールアドレスを更新",
    "Update trash settings": "ゴミ箱設定を更新",
    "Update user": "ユーザーを更新",
    "Update user's password": "ユーザーのパスワードを更新",
    "Upload a package and install it in the background": "パッケージをアップロードし、バックグラウンドでインストール",
    "Upload an upgrade package and validate it without touching the running jar": "アップグレードパッケージをアップロードし、実行中の jar に触れずに検証",
      "Add a scraping source": "スクレイピングソースを追加",
      "Apply bulk series metadata from a text payload": "テキストからシリーズメタデータを一括適用",
      "Authenticate a KOReader user": "KOReader ユーザーを認証",
      "Browse a library": "ライブラリを閲覧",
      "Check Kobo connectivity": "Kobo の接続を確認",
      "Count entries awaiting bulk metadata": "メタデータ待ちの項目を数える",
      "Create an API token": "API トークンを作成",
      "Delete a scraping source": "スクレイピングソースを削除",
      "Delete an API token": "API トークンを削除",
      "Download a book file for Kobo": "Kobo 用の書籍ファイルをダウンロード",
      "Download the metadata package template": "メタデータパッケージのテンプレートをダウンロード",
      "Enable or disable a scraping source": "スクレイピングソースを有効化または無効化",
      "Exchange a Kobo device token": "Kobo デバイストークンを交換",
      "Export series metadata as a text file": "シリーズメタデータをテキストファイルに書き出す",
      "Get Kobo metadata for a book": "書籍の Kobo メタデータを取得",
      "Get Kobo reading state for a book": "書籍の Kobo 読書状態を取得",
      "Get a book cover for Kobo": "Kobo 用の書籍表紙を取得",
      "Get a book page image": "書面画像を取得",
      "Get a book page thumbnail": "書面サムネイルを取得",
      "Get a book thumbnail": "書籍サムネイルを取得",
      "Get one collection": "コレクションを取得",
      "Get one library": "ライブラリを取得",
      "Get one reading list": "読書リストを取得",
      "Get one series with its books": "シリーズとその書籍を取得",
      "Get reading progress for a document": "文書の読書進捗を取得",
      "Get scraping source details": "スクレイピングソースの詳細を取得",
      "Get the OPDS authentication document": "OPDS 認証ドキュメントを取得",
      "Get the OPDS search description": "OPDS 検索記述を取得",
      "Get the OPDS v1 catalog": "OPDS v1 カタログを取得",
      "Get the OPDS v2 catalog": "OPDS v2 カタログを取得",
      "Get the WebPub manifest for a DIVINA book": "DIVINA 書籍の WebPub マニフェストを取得",
      "Get the WebPub manifest for a PDF": "PDF の WebPub マニフェストを取得",
      "Get the WebPub manifest for a book": "書籍の WebPub マニフェストを取得",
      "Get the WebPub manifest for an EPUB": "EPUB の WebPub マニフェストを取得",
      "Import a series metadata package": "シリーズメタデータパッケージを読み込む",
      "Initialize a Kobo device": "Kobo デバイスを初期化",
      "List API tokens": "API トークンを一覧",
      "List all series": "すべてのシリーズを一覧",
      "List available API token scopes": "利用可能な API トークンスコープを一覧",
      "List books currently being read": "読書中の書籍を一覧",
      "List books to keep reading": "続きを読む書籍を一覧",
      "List collections in a library": "ライブラリ内のコレクションを一覧",
      "List entries awaiting bulk metadata": "メタデータ待ちの項目を一覧",
      "List libraries": "ライブラリを一覧",
      "List reading lists": "読書リストを一覧",
      "List reading lists in a library": "ライブラリ内の読書リストを一覧",
      "List recently added books": "最近追加された書籍を一覧",
      "List recently added series": "最近追加されたシリーズを一覧",
      "Proxy unmatched Kobo requests": "一致しない Kobo リクエストを転送",
      "Register a KOReader user (disabled)": "KOReader ユーザーを登録（無効）",
      "Search the catalog": "カタログを検索",
      "Submit bulk metadata for application": "一括メタデータを適用のために送信",
      "Sync the Kobo library": "Kobo ライブラリを同期",
      "Test a scraping source connection": "スクレイピングソースの接続をテスト",
      "Update Kobo reading state for a book": "書籍の Kobo 読書状態を更新",
      "Update a scraping source": "スクレイピングソースを更新",
      "Update an API token": "API トークンを更新",
      "Update reading progress for a document": "文書の読書進捗を更新",
    "Serve the web interface": "Web インターフェースを提供",
    "Stream server-sent events": "サーバー送信イベントを配信",
  };
  B["ko"] = {
    "Delete all sync points": "모든 동기화 지점 삭제",
    "Directory listing": "디렉터리 목록",
    "List all libraries": "모든 라이브러리 목록",
    "Add an extension store": "확장 스토어 추가",
    "Add book poster": "도서 포스터 추가",
    "Add collection poster": "컬렉션 포스터 추가",
    "Add readlist poster": "읽기 목록 포스터 추가",
    "Add series poster": "시리즈 포스터 추가",
    "Add series, a whole library or every series missing metadata to the queue": "시리즈, 전체 라이브러리 또는 메타데이터가 없는 모든 시리즈를 대기열에 추가",
    "Aggregate reading devices from session history": "세션 기록에서 읽기 장치 집계",
    "Aggregated home payload for clients": "클라이언트용 홈 통합 데이터",
    "Analyze a library": "라이브러리 분석",
    "Analyze book": "도서 분석",
    "Analyze series": "시리즈 분석",
    "Analyze transient book": "임시 도서 분석",
    "Apply selected fields of a match and keep a rollback snapshot": "일치 항목의 선택 필드를 적용하고 롤백 스냅샷 유지",
    "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "대기열 항목의 선택 필드를 적용하고, 항목이 대기열을 벗어나면 204 반환",
    "Ask the running batch to stop after the current series": "실행 중인 배치에 현재 시리즈 이후 중지 요청",
    "Available versions and download sources": "사용 가능한 버전 및 다운로드 소스",
    "Book counts grouped by computed first letter": "계산된 머리글자별 도서 수",
    "Book cover resized on demand": "요청 시 크기 조정되는 도서 표지",
    "Book ids whose computed first letter matches": "계산된 머리글자가 일치하는 도서 ID",
    "Bring back a book previously removed from the library": "이전에 라이브러리에서 제거한 도서 복원",
    "Bring back a series previously removed from the library": "이전에 라이브러리에서 제거한 시리즈 복원",
    "Browse a source by popular or latest, or search inside it": "인기순 또는 최신순으로 소스 탐색, 또는 소스 내 검색",
    "Browse installable extensions": "설치 가능한 확장 탐색",
    "Calibre availability, version and conversion mode": "Calibre 사용 가능 여부, 버전 및 변환 모드",
    "Change logger levels at runtime, use INHERIT to restore the default": "런타임에 로거 수준 변경, 기본값 복원은 INHERIT 사용",
    "Clear backoff and circuit state of a source": "소스의 백오프 및 서킷 상태 초기화",
    "Clear queue entries by scope: queued, review, failed or all": "범위별 대기열 항목 초기화: 대기, 검토, 실패 또는 전체",
    "Clear task queue": "작업 대기열 초기화",
    "Clear the pinned executable and fall back to the managed installation": "고정된 실행 파일 해제 후 관리 설치로 대체",
    "Client capability negotiation": "클라이언트 기능 협상",
    "Compute the field level difference of a queue entry": "대기열 항목의 필드 수준 차이 계산",
    "Convert a single book and queue re-analysis": "단일 도서를 변환하고 재분석을 대기열에 추가",
    "Convert pending books of a library": "라이브러리의 대기 중인 도서 변환",
    "Count books still waiting for conversion in a library": "라이브러리에서 변환 대기 중인 도서 수 집계",
    "Counters for the scrape queue and the inbox": "스크레이프 대기열 및 수신함 카운터",
    "Create API key": "API 키 생성",
    "Create a library": "라이브러리 생성",
    "Create a scrape only API token with fixed scopes": "고정 범위의 스크레이프 전용 API 토큰 생성",
    "Create an annotation with a locator and a text fallback": "로케이터와 텍스트 대체를 사용한 주석 생성",
    "Create collection": "컬렉션 생성",
    "Create readlist": "읽기 목록 생성",
    "Create user": "사용자 생성",
    "Current install progress": "현재 설치 진행률",
    "Current jar location and whether it can be replaced in place": "현재 jar 위치 및 제자리 교체 가능 여부",
    "Delete API key": "API 키 삭제",
    "Delete a library": "라이브러리 삭제",
    "Delete a single task by id": "ID로 단일 작업 삭제",
    "Delete all duplicate pages by hash": "해시별 중복 페이지 모두 삭제",
    "Delete an API key of a user": "사용자의 API 키 삭제",
    "Delete book file": "도서 파일 삭제",
    "Delete book poster": "도서 포스터 삭제",
    "Delete collection": "컬렉션 삭제",
    "Delete collection poster": "컬렉션 포스터 삭제",
    "Delete downloaded cache of an online series, chapters remain readable through the source": "온라인 시리즈의 다운로드 캐시 삭제, 챕터는 소스를 통해 계속 읽을 수 있음",
    "Delete every finished task record": "완료된 모든 작업 기록 삭제",
    "Delete every package downloaded from the update feed": "업데이트 피드에서 다운로드한 모든 패키지 삭제",
    "Delete every queued task; running ones are kept": "대기 중인 모든 작업 삭제, 실행 중인 작업은 유지",
    "Delete global settings": "전역 설정 삭제",
    "Delete my annotation": "내 주석 삭제",
    "Delete readlist": "읽기 목록 삭제",
    "Delete readlist poster": "읽기 목록 포스터 삭제",
    "Delete series files": "시리즈 파일 삭제",
    "Delete series poster": "시리즈 포스터 삭제",
    "Delete specific duplicate page": "특정 중복 페이지 삭제",
    "Delete user": "사용자 삭제",
    "Delete user settings": "사용자 설정 삭제",
    "Discard the staged package": "스테이징된 패키지 폐기",
    "Download CSS file": "CSS 파일 다운로드",
    "Download a raw log file": "원본 로그 파일 다운로드",
    "Download a release package, verify it and stage it for install": "릴리스 패키지를 다운로드하고 검증한 뒤 설치용으로 스테이징",
    "Download an online chapter as a local CBZ and re-analyze it": "온라인 챕터를 로컬 CBZ로 다운로드하고 재분석",
    "Download and install the latest Calibre portable build": "최신 Calibre 포터블 빌드 다운로드 및 설치",
    "Download and install the latest Suwayomi release": "최신 Suwayomi 릴리스 다운로드 및 설치",
    "Download book file": "도서 파일 다운로드",
    "Download font file": "글꼴 파일 다운로드",
    "Download from the official source and install in the background": "공식 소스에서 다운로드하여 백그라운드에서 설치",
    "Download readlist": "읽기 목록 다운로드",
    "Download series": "시리즈 다운로드",
    "Echo build and runtime information": "빌드 및 런타임 정보 반환",
    "Empty the trash of one or all libraries": "하나 또는 모든 라이브러리의 휴지통 비우기",
    "Empty trash for a library": "라이브러리의 휴지통 비우기",
    "Enable or disable a runtime": "런타임 활성화 또는 비활성화",
    "Enable or disable a user": "사용자 활성화 또는 비활성화",
    "Environment and dependency check for an external runtime": "외부 런타임의 환경 및 종속성 확인",
    "Equivalent shell steps for a manual install": "수동 설치에 해당하는 셸 단계",
    "Expire one or all sessions of a user": "사용자의 하나 또는 모든 세션 만료",
    "Export a page range as zip": "페이지 범위를 zip으로 내보내기",
    "Export audit entries as CSV": "감사 항목을 CSV로 내보내기",
    "Export the library inventory as JSON": "라이브러리 인벤토리를 JSON으로 내보내기",
    "Get Epub resource": "Epub 리소스 가져오기",
    "Get Lantai reading capabilities": "Lantai 읽기 기능 가져오기",
    "Get book details": "도서 상세 정보 가져오기",
    "Get book page image": "도서 페이지 이미지 가져오기",
    "Get book page thumbnail": "도서 페이지 썸네일 가져오기",
    "Get book poster image": "도서 포스터 이미지 가져오기",
    "Get book progression": "도서 진행률 가져오기",
    "Get book's WebPub manifest": "도서의 WebPub 매니페스트 가져오기",
    "Get book's WebPub manifest (DiViNa)": "도서의 WebPub 매니페스트 가져오기 (DiViNa)",
    "Get book's WebPub manifest (Epub)": "도서의 WebPub 매니페스트 가져오기 (Epub)",
    "Get book's WebPub manifest (PDF)": "도서의 WebPub 매니페스트 가져오기 (PDF)",
    "Get book's poster image": "도서의 포스터 이미지 가져오기",
    "Get collection details": "컬렉션 상세 정보 가져오기",
    "Get collection poster image": "컬렉션 포스터 이미지 가져오기",
    "Get collection's poster image": "컬렉션의 포스터 이미지 가져오기",
    "Get details for a single library": "단일 라이브러리의 상세 정보 가져오기",
    "Get known duplicate image thumbnail": "알려진 중복 이미지 썸네일 가져오기",
    "Get next book in readlist": "읽기 목록의 다음 도서 가져오기",
    "Get next book in series": "시리즈의 다음 도서 가져오기",
    "Get previous book in readlist": "읽기 목록의 이전 도서 가져오기",
    "Get previous book in series": "시리즈의 이전 도서 가져오기",
    "Get raw book page": "원본 도서 페이지 가져오기",
    "Get reading statistics for the current user": "현재 사용자의 읽기 통계 가져오기",
    "Get readlist details": "읽기 목록 상세 정보 가져오기",
    "Get readlist poster image": "읽기 목록 포스터 이미지 가져오기",
    "Get readlist read progress (Mihon)": "읽기 목록 읽기 진행률 가져오기 (Mihon)",
    "Get readlist's poster image": "읽기 목록의 포스터 이미지 가져오기",
    "Get series details": "시리즈 상세 정보 가져오기",
    "Get series poster image": "시리즈 포스터 이미지 가져오기",
    "Get series read progress (Mihon)": "시리즈 읽기 진행률 가져오기 (Mihon)",
    "Get series' poster image": "시리즈의 포스터 이미지 가져오기",
    "Get the archive status of the current user's reading statistics": "현재 사용자 읽기 통계의 아카이브 상태 가져오기",
    "Get the reading time of one book for the current user": "현재 사용자의 단일 도서 읽기 시간 가져오기",
    "Get transient book page": "임시 도서 페이지 가져오기",
    "Get unknown duplicate image thumbnail": "알 수 없는 중복 이미지 썸네일 가져오기",
    "Import a Suwayomi jar manually": "Suwayomi jar 수동 가져오기",
    "Import a locally downloaded Calibre portable installer": "로컬에 다운로드한 Calibre 포터블 설치 프로그램 가져오기",
    "Import books": "도서 가져오기",
    "Include or exclude a source from search and entry points": "검색 및 진입점에서 소스 포함 또는 제외",
    "Install an extension from an uploaded package": "업로드한 패키지에서 확장 설치",
    "Install an extension from the catalog": "카탈로그에서 확장 설치",
    "Latest Calibre portable release": "최신 Calibre 포터블 릴리스",
    "Latest Suwayomi release": "최신 Suwayomi 릴리스",
    "List active reading sessions for the current user": "현재 사용자의 활성 읽기 세션 목록",
    "List age ratings": "연령 등급 목록",
    "List and search users": "사용자 목록 및 검색",
    "List applied scrape records of the series behind a queue entry": "대기열 항목 뒤의 시리즈에 적용된 스크레이프 기록 목록",
    "List assignable roles": "할당 가능한 역할 목록",
    "List authors": "저자 목록",
    "List authors names": "저자 이름 목록",
    "List authors roles": "저자 역할 목록",
    "List authors' names": "저자의 이름 목록",
    "List authors' roles": "저자의 역할 목록",
    "List available log files": "사용 가능한 로그 파일 목록",
    "List book pages": "도서 페이지 목록",
    "List book posters": "도서 포스터 목록",
    "List book tags": "도서 태그 목록",
    "List book's positions": "도서 위치 목록",
    "List book's readlists": "도서의 읽기 목록 목록",
    "List books": "도서 목록",
    "List books on deck": "덱에 있는 도서 목록",
    "List books removed from library but still present on disk": "라이브러리에서 제거되었지만 디스크에 남아 있는 도서 목록",
    "List collection's posters": "컬렉션의 포스터 목록",
    "List collection's series": "컬렉션의 시리즈 목록",
    "List collections": "컬렉션 목록",
    "List duplicate books": "중복 도서 목록",
    "List duplicate matches": "중복 일치 목록",
    "List extension stores": "확장 스토어 목록",
    "List finished tasks, paged": "완료된 작업 목록 (페이지 단위)",
    "List font families": "글꼴 패밀리 목록",
    "List genres": "장르 목록",
    "List historical events": "기록 이벤트 목록",
    "List installed extensions": "설치된 확장 목록",
    "List known duplicates": "알려진 중복 목록",
    "List languages": "언어 목록",
    "List latest books": "최신 도서 목록",
    "List latest series": "최신 시리즈 목록",
    "List loggers and their levels": "로거 및 수준 목록",
    "List match candidates with their current and proposed values": "현재 값과 제안 값이 포함된 일치 후보 목록",
    "List match jobs of this session": "이 세션의 일치 작업 목록",
    "List materialized chapters of a subscription with download state": "다운로드 상태가 포함된 구독의 구체화된 챕터 목록",
    "List my annotations of a book": "도서에 대한 내 주석 목록",
    "List new series": "신규 시리즈 목록",
    "List notable entities": "주목할 엔터티 목록",
    "List notable entities kinds": "주목할 엔터티 종류 목록",
    "List notable entities names": "주목할 엔터티 이름 목록",
    "List online metadata sources and their daily usage": "온라인 메타데이터 소스 및 일일 사용량 목록",
    "List publishers": "출판사 목록",
    "List queued and running tasks, paged": "대기 중 및 실행 중인 작업 목록 (페이지 단위)",
    "List reading sessions": "읽기 세션 목록",
    "List readlist's books": "읽기 목록의 도서 목록",
    "List readlist's posters": "읽기 목록의 포스터 목록",
    "List readlists": "읽기 목록 목록",
    "List registered OAuth2 providers": "등록된 OAuth2 제공자 목록",
    "List releases": "릴리스 목록",
    "List series": "시리즈 목록",
    "List series groups": "시리즈 그룹 목록",
    "List series posters": "시리즈 포스터 목록",
    "List series release dates": "시리즈 출시일 목록",
    "List series release years": "시리즈 출시 연도 목록",
    "List series removed from library but still present on disk": "라이브러리에서 제거되었지만 디스크에 남아 있는 시리즈 목록",
    "List series tags": "시리즈 태그 목록",
    "List series' books": "시리즈의 도서 목록",
    "List series' collections": "시리즈의 컬렉션 목록",
    "List sharing labels": "공유 레이블 목록",
    "List sources with their search/entry toggle state": "검색/진입 전환 상태가 포함된 소스 목록",
    "List subscribed online mangas": "구독 중인 온라인 만화 목록",
    "List tags": "태그 목록",
    "List the API keys of a user": "사용자의 API 키 목록",
    "List the actions present in the audit trail": "감사 추적에 있는 작업 목록",
    "List the active sessions of a user": "사용자의 활성 세션 목록",
    "List the books of the series behind a queue entry with their current metadata": "대기열 항목 뒤의 시리즈 도서를 현재 메타데이터와 함께 목록",
    "List the runtime filters exposed by a source": "소스가 노출하는 런타임 필터 목록",
    "List the scrape only API tokens": "스크레이프 전용 API 토큰 목록",
    "List the sources provided by an extension": "확장이 제공하는 소스 목록",
    "List trash entries of one or all libraries": "하나 또는 모든 라이브러리의 휴지통 항목 목록",
    "List unknown duplicates": "알 수 없는 중복 목록",
    "List updated series": "업데이트된 시리즈 목록",
    "List users": "사용자 목록",
    "Mark a match as skipped": "일치를 건너뜀으로 표시",
    "Mark announcements as read": "공지 사항을 읽음으로 표시",
    "Mark book as unread": "도서를 읽지 않음으로 표시",
    "Mark book poster as selected": "도서 포스터를 선택됨으로 표시",
    "Mark book progression": "도서 진행률 기록",
    "Mark book's read progress": "도서의 읽기 진행률 기록",
    "Mark collection poster as selected": "컬렉션 포스터를 선택됨으로 표시",
    "Mark duplicate page as known": "중복 페이지를 알려진 것으로 표시",
    "Mark readlist poster as selected": "읽기 목록 포스터를 선택됨으로 표시",
    "Mark series as read": "시리즈를 읽음으로 표시",
    "Mark series as unread": "시리즈를 읽지 않음으로 표시",
    "Mark series poster as selected": "시리즈 포스터를 선택됨으로 표시",
    "Match ComicRack list": "ComicRack 목록 일치",
    "Match a single series against enabled sources": "단일 시리즈를 활성화된 소스와 일치",
    "Packages already downloaded into the config directory": "설정 디렉터리에 이미 다운로드된 패키지",
    "Per-library series/book counts and total size": "라이브러리별 시리즈/도서 수 및 총 크기",
    "Permanently delete a trash entry and its files": "휴지통 항목과 해당 파일을 영구 삭제",
    "Pin an existing ebook-convert executable": "기존 ebook-convert 실행 파일 고정",
    "Poll a match job": "일치 작업 폴링",
    "Probe an ebook-convert executable without saving it": "저장하지 않고 ebook-convert 실행 파일 검사",
    "Probe every external runtime managed by Lantai": "Lantai가 관리하는 모든 외부 런타임 검사",
    "Probe one external runtime": "외부 런타임 하나 검사",
    "Progress of the package download currently running": "현재 실행 중인 패키지 다운로드 진행률",
    "Progress of the package validation currently running": "현재 실행 중인 패키지 검증 진행률",
    "Proxy the remote cover of a search result": "검색 결과의 원격 표지를 프록시",
    "Purge reading sessions beyond the retention window": "보존 기간을 초과한 읽기 세션 제거",
    "Re-verify the most recent downloaded package": "가장 최근에 다운로드한 패키지 재검증",
    "Read Suwayomi settings": "Suwayomi 설정 읽기",
    "Read bootstrap payload": "부트스트랩 데이터 읽기",
    "Read chapter bundle": "챕터 번들 읽기",
    "Read chapter outline page": "챕터 개요 페이지 읽기",
    "Read chapter window": "챕터 창 읽기",
    "Read image resource": "이미지 리소스 읽기",
    "Read one page of the global scrape queue": "전역 스크레이프 대기열의 한 페이지 읽기",
    "Read one page of the scrape queue for an external agent": "외부 에이전트용 스크레이프 대기열의 한 페이지 읽기",
    "Read one queue entry with the hints an external agent needs": "외부 에이전트에 필요한 힌트와 함께 대기열 항목 하나 읽기",
    "Read page thumbnail": "페이지 썸네일 읽기",
    "Read scrape settings": "스크레이프 설정 읽기",
    "Read structured log entries, cursor based": "커서 기반 구조화 로그 항목 읽기",
    "Read the configurable preferences of a source": "소스의 구성 가능한 기본 설정 읽기",
    "Read the remote detail of a single manga": "단일 만화의 원격 상세 정보 읽기",
    "Read the update feed and report the latest release plus any downloaded package": "업데이트 피드를 읽고 최신 릴리스와 다운로드한 패키지 보고",
    "Read trash settings": "휴지통 설정 읽기",
    "Rebuild the daily reading statistics of the current user": "현재 사용자의 일일 읽기 통계 재구축",
    "Recompute first letters for every series": "모든 시리즈의 머리글자 재계산",
    "Refresh book metadata": "도서 메타데이터 새로 고침",
    "Refresh installed extension metadata": "설치된 확장 메타데이터 새로 고침",
    "Refresh metadata for a library": "라이브러리 메타데이터 새로 고침",
    "Refresh series metadata": "시리즈 메타데이터 새로 고침",
    "Regenerate books posters": "도서 포스터 재생성",
    "Remove a book from the library, keeping its file untouched": "파일은 그대로 두고 라이브러리에서 도서 제거",
    "Remove a series from the library, keeping its files untouched": "파일은 그대로 두고 라이브러리에서 시리즈 제거",
    "Remove a single queue entry": "단일 대기열 항목 제거",
    "Replace the roles of a user": "사용자의 역할 교체",
    "Replace the running jar with the staged one and restart": "실행 중인 jar를 스테이징된 것으로 교체하고 재시작",
    "Report a reading heartbeat": "읽기 하트비트 보고",
    "Reset a queue entry back to queued": "대기열 항목을 대기 상태로 되돌리기",
    "Reset the password of a user and expire its sessions": "사용자의 비밀번호를 재설정하고 세션 만료",
    "Restore a trash entry back to its library": "휴지통 항목을 라이브러리로 복원",
    "Restore logger levels to their default": "로거 수준을 기본값으로 복원",
    "Restore the last backup jar and restart": "마지막 백업 jar를 복원하고 재시작",
    "Resume task processing": "작업 처리 재개",
    "Retrieve API keys": "API 키 가져오기",
    "Retrieve a single audit entry": "단일 감사 항목 가져오기",
    "Retrieve a user with its activity summary": "활동 요약과 함께 사용자 가져오기",
    "Retrieve announcements": "공지 사항 가져오기",
    "Retrieve authentication activity": "인증 활동 가져오기",
    "Retrieve authentication activity for the current user": "현재 사용자의 인증 활동 가져오기",
    "Retrieve current user": "현재 사용자 가져오기",
    "Retrieve global client settings": "전역 클라이언트 설정 가져오기",
    "Retrieve latest authentication activity for a user": "사용자의 최신 인증 활동 가져오기",
    "Retrieve server settings": "서버 설정 가져오기",
    "Retrieve user client settings": "사용자 클라이언트 설정 가져오기",
    "Revoke a scrape only API token": "스크레이프 전용 API 토큰 취소",
    "Rotate the managed Suwayomi credentials": "관리되는 Suwayomi 자격 증명 로테이션",
    "Run the automatic install for the given runtimes": "지정된 런타임에 대한 자동 설치 실행",
    "Runtime and storage status": "런타임 및 저장소 상태",
    "Save global settings": "전역 설정 저장",
    "Save user settings": "사용자 설정 저장",
    "Scan a library": "라이브러리 스캔",
    "Scan folder for transient books": "폴더를 스캔하여 임시 도서 감지",
    "Search audit entries": "감사 항목 검색",
    "Search inside a book": "도서 내부 검색",
    "Search mangas across enabled sources": "활성화된 소스 전체에서 만화 검색",
    "Search metadata candidates from one or all enabled sources": "하나 또는 모든 활성화된 소스에서 메타데이터 후보 검색",
    "Series ids whose computed first letter matches": "계산된 머리글자가 일치하는 시리즈 ID",
    "Set cookie": "쿠키 설정",
    "Set the conversion mode: never, on-demand or eager": "변환 모드 설정: 안 함, 요청 시 또는 즉시",
    "Set the trash quota of a library, zero means inheriting the global default": "라이브러리의 휴지통 할당량 설정, 0은 전역 기본값 상속",
    "Snapshot of the automatic install performed at startup": "시작 시 수행된 자동 설치 스냅샷",
    "Snapshot the main and tasks SQLite databases": "기본 및 작업 SQLite 데이터베이스 스냅샷",
    "Start a background auto-match job for a library": "라이브러리의 백그라운드 자동 일치 작업 시작",
    "Start an external runtime": "외부 런타임 시작",
    "Start or resume scraping the queue": "대기열 스크레이프 시작 또는 재개",
    "Start, stop or restart Suwayomi": "Suwayomi 시작, 중지 또는 재시작",
    "Stop an external runtime": "외부 런타임 중지",
    "Stop taking new tasks; running tasks finish": "새 작업 수락 중지, 실행 중인 작업은 완료",
    "Stop the current reading session": "현재 읽기 세션 종료",
    "Stream new log entries": "새 로그 항목 스트리밍",
    "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "대기열 항목에 대한 스크레이프 결과 제출, 또는 시리즈 ID를 전달하여 이미 적용된 결과 덮어쓰기",
    "Subscribe to a remote manga and materialize it as a series": "원격 만화를 구독하고 시리즈로 구체화",
    "Suwayomi runtime status": "Suwayomi 런타임 상태",
    "Sync offline reading sessions": "오프라인 읽기 세션 동기화",
    "Synchronize chapters of a subscribed series": "구독한 시리즈의 챕터 동기화",
    "Undo an applied match using its snapshot": "스냅샷을 사용하여 적용된 일치 실행 취소",
    "Uninstall a runtime, data is removed only when purge is requested": "런타임 제거, 데이터는 제거 요청 시에만 삭제",
    "Uninstall an extension": "확장 제거",
    "Unsubscribe a series and remove its placeholder files": "시리즈 구독 취소 및 자리 표시자 파일 제거",
    "Update Suwayomi settings": "Suwayomi 설정 업데이트",
    "Update a library": "라이브러리 업데이트",
    "Update an installed extension": "설치된 확장 업데이트",
    "Update book metadata": "도서 메타데이터 업데이트",
    "Update book metadata in bulk": "도서 메타데이터 일괄 업데이트",
    "Update collection": "컬렉션 업데이트",
    "Update current user's password": "현재 사용자의 비밀번호 업데이트",
    "Update note, color or marker of my annotation": "내 주석의 메모, 색상 또는 마커 업데이트",
    "Update one preference of a source and return the refreshed list": "소스의 기본 설정 하나를 업데이트하고 갱신된 목록 반환",
    "Update readlist": "읽기 목록 업데이트",
    "Update readlist read progress (Mihon)": "읽기 목록 읽기 진행률 업데이트 (Mihon)",
    "Update scrape settings, trusting the results requires an explicit confirmation": "스크레이프 설정 업데이트, 결과를 신뢰하려면 명시적 확인 필요",
    "Update series metadata": "시리즈 메타데이터 업데이트",
    "Update series read progress (Mihon)": "시리즈 읽기 진행률 업데이트 (Mihon)",
    "Update server settings": "서버 설정 업데이트",
    "Update the username and email of a user": "사용자의 사용자 이름과 이메일 업데이트",
    "Update trash settings": "휴지통 설정 업데이트",
    "Update user": "사용자 업데이트",
    "Update user's password": "사용자의 비밀번호 업데이트",
    "Upload a package and install it in the background": "패키지를 업로드하고 백그라운드에서 설치",
    "Upload an upgrade package and validate it without touching the running jar": "업그레이드 패키지를 업로드하고 실행 중인 jar를 건드리지 않고 검증",
      "Add a scraping source": "스크래핑 소스 추가",
      "Apply bulk series metadata from a text payload": "텍스트에서 시리즈 메타데이터 일괄 적용",
      "Authenticate a KOReader user": "KOReader 사용자 인증",
      "Browse a library": "서재 둘러보기",
      "Check Kobo connectivity": "Kobo 연결 확인",
      "Count entries awaiting bulk metadata": "메타데이터 대기 항목 수 집계",
      "Create an API token": "API 토큰 생성",
      "Delete a scraping source": "스크래핑 소스 삭제",
      "Delete an API token": "API 토큰 삭제",
      "Download a book file for Kobo": "Kobo용 도서 파일 다운로드",
      "Download the metadata package template": "메타데이터 패키지 템플릿 다운로드",
      "Enable or disable a scraping source": "스크래핑 소스 활성화 또는 비활성화",
      "Exchange a Kobo device token": "Kobo 기기 토큰 교환",
      "Export series metadata as a text file": "시리즈 메타데이터를 텍스트 파일로 내보내기",
      "Get Kobo metadata for a book": "도서의 Kobo 메타데이터 가져오기",
      "Get Kobo reading state for a book": "도서의 Kobo 읽기 상태 가져오기",
      "Get a book cover for Kobo": "Kobo용 도서 표지 가져오기",
      "Get a book page image": "도서 페이지 이미지 가져오기",
      "Get a book page thumbnail": "도서 페이지 썸네일 가져오기",
      "Get a book thumbnail": "도서 썸네일 가져오기",
      "Get one collection": "모음집 가져오기",
      "Get one library": "서재 가져오기",
      "Get one reading list": "읽기 목록 가져오기",
      "Get one series with its books": "시리즈와 해당 도서 가져오기",
      "Get reading progress for a document": "문서의 읽기 진행률 가져오기",
      "Get scraping source details": "스크래핑 소스 세부 정보 가져오기",
      "Get the OPDS authentication document": "OPDS 인증 문서 가져오기",
      "Get the OPDS search description": "OPDS 검색 설명 가져오기",
      "Get the OPDS v1 catalog": "OPDS v1 카탈로그 가져오기",
      "Get the OPDS v2 catalog": "OPDS v2 카탈로그 가져오기",
      "Get the WebPub manifest for a DIVINA book": "DIVINA 도서의 WebPub 매니페스트 가져오기",
      "Get the WebPub manifest for a PDF": "PDF의 WebPub 매니페스트 가져오기",
      "Get the WebPub manifest for a book": "도서의 WebPub 매니페스트 가져오기",
      "Get the WebPub manifest for an EPUB": "EPUB의 WebPub 매니페스트 가져오기",
      "Import a series metadata package": "시리즈 메타데이터 패키지 가져오기",
      "Initialize a Kobo device": "Kobo 기기 초기화",
      "List API tokens": "API 토큰 목록",
      "List all series": "모든 시리즈 목록",
      "List available API token scopes": "사용 가능한 API 토큰 범위 목록",
      "List books currently being read": "현재 읽는 중인 도서 목록",
      "List books to keep reading": "이어 읽을 도서 목록",
      "List collections in a library": "서재의 모음집 목록",
      "List entries awaiting bulk metadata": "메타데이터 대기 항목 목록",
      "List libraries": "서재 목록",
      "List reading lists": "읽기 목록 목록",
      "List reading lists in a library": "서재의 읽기 목록 목록",
      "List recently added books": "최근 추가된 도서 목록",
      "List recently added series": "최근 추가된 시리즈 목록",
      "Proxy unmatched Kobo requests": "일치하지 않는 Kobo 요청 프록시",
      "Register a KOReader user (disabled)": "KOReader 사용자 등록(비활성)",
      "Search the catalog": "카탈로그 검색",
      "Submit bulk metadata for application": "적용할 일괄 메타데이터 제출",
      "Sync the Kobo library": "Kobo 서재 동기화",
      "Test a scraping source connection": "스크래핑 소스 연결 테스트",
      "Update Kobo reading state for a book": "도서의 Kobo 읽기 상태 업데이트",
      "Update a scraping source": "스크래핑 소스 업데이트",
      "Update an API token": "API 토큰 업데이트",
      "Update reading progress for a document": "문서의 읽기 진행률 업데이트",
    "Serve the web interface": "웹 인터페이스 제공",
    "Stream server-sent events": "서버 전송 이벤트 스트리밍",
  };
  B["fr"] = {
    "Delete all sync points": "Supprimer tous les points de synchronisation",
    "Directory listing": "Liste du répertoire",
    "List all libraries": "Lister toutes les bibliothèques",
    "Add an extension store": "Ajouter une boutique d'extensions",
    "Add book poster": "Ajouter une affiche de livre",
    "Add collection poster": "Ajouter une affiche de collection",
    "Add readlist poster": "Ajouter une affiche de liste de lecture",
    "Add series poster": "Ajouter une affiche de série",
    "Add series, a whole library or every series missing metadata to the queue": "Ajouter une série, une bibliothèque entière ou toutes les séries sans métadonnées à la file",
    "Aggregate reading devices from session history": "Agréger les appareils de lecture à partir de l'historique des sessions",
    "Aggregated home payload for clients": "Charge utile d'accueil agrégée pour les clients",
    "Analyze a library": "Analyser une bibliothèque",
    "Analyze book": "Analyser un livre",
    "Analyze series": "Analyser une série",
    "Analyze transient book": "Analyser un livre transitoire",
    "Apply selected fields of a match and keep a rollback snapshot": "Appliquer les champs sélectionnés d'une correspondance et conserver un instantané de restauration",
    "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "Appliquer les champs choisis d'une entrée de file, renvoie 204 lorsque l'entrée quitte la file",
    "Ask the running batch to stop after the current series": "Demander au lot en cours de s'arrêter après la série actuelle",
    "Available versions and download sources": "Versions disponibles et sources de téléchargement",
    "Book counts grouped by computed first letter": "Nombre de livres regroupés par initiale calculée",
    "Book cover resized on demand": "Couverture de livre redimensionnée à la demande",
    "Book ids whose computed first letter matches": "ID de livres dont l'initiale calculée correspond",
    "Bring back a book previously removed from the library": "Restaurer un livre précédemment retiré de la bibliothèque",
    "Bring back a series previously removed from the library": "Restaurer une série précédemment retirée de la bibliothèque",
    "Browse a source by popular or latest, or search inside it": "Parcourir une source par popularité ou nouveauté, ou y effectuer une recherche",
    "Browse installable extensions": "Parcourir les extensions installables",
    "Calibre availability, version and conversion mode": "Disponibilité, version et mode de conversion de Calibre",
    "Change logger levels at runtime, use INHERIT to restore the default": "Modifier les niveaux de journalisation à l'exécution, utiliser INHERIT pour restaurer la valeur par défaut",
    "Clear backoff and circuit state of a source": "Réinitialiser l'état de repli et de circuit d'une source",
    "Clear queue entries by scope: queued, review, failed or all": "Vider les entrées de file par portée : en attente, en revue, en échec ou toutes",
    "Clear task queue": "Vider la file de tâches",
    "Clear the pinned executable and fall back to the managed installation": "Effacer l'exécutable épinglé et revenir à l'installation gérée",
    "Client capability negotiation": "Négociation des capacités du client",
    "Compute the field level difference of a queue entry": "Calculer la différence au niveau des champs d'une entrée de file",
    "Convert a single book and queue re-analysis": "Convertir un seul livre et planifier une ré-analyse",
    "Convert pending books of a library": "Convertir les livres en attente d'une bibliothèque",
    "Count books still waiting for conversion in a library": "Compter les livres encore en attente de conversion dans une bibliothèque",
    "Counters for the scrape queue and the inbox": "Compteurs de la file de scraping et de la boîte de réception",
    "Create API key": "Créer une clé API",
    "Create a library": "Créer une bibliothèque",
    "Create a scrape only API token with fixed scopes": "Créer un jeton API dédié au scraping avec des portées fixes",
    "Create an annotation with a locator and a text fallback": "Créer une annotation avec un localisateur et un texte de secours",
    "Create collection": "Créer une collection",
    "Create readlist": "Créer une liste de lecture",
    "Create user": "Créer un utilisateur",
    "Current install progress": "Progression de l'installation en cours",
    "Current jar location and whether it can be replaced in place": "Emplacement actuel du jar et possibilité de remplacement sur place",
    "Delete API key": "Supprimer une clé API",
    "Delete a library": "Supprimer une bibliothèque",
    "Delete a single task by id": "Supprimer une tâche unique par ID",
    "Delete all duplicate pages by hash": "Supprimer toutes les pages en double par empreinte",
    "Delete an API key of a user": "Supprimer une clé API d'un utilisateur",
    "Delete book file": "Supprimer le fichier du livre",
    "Delete book poster": "Supprimer l'affiche du livre",
    "Delete collection": "Supprimer une collection",
    "Delete collection poster": "Supprimer l'affiche de la collection",
    "Delete downloaded cache of an online series, chapters remain readable through the source": "Supprimer le cache téléchargé d'une série en ligne, les chapitres restent lisibles via la source",
    "Delete every finished task record": "Supprimer tous les enregistrements de tâches terminées",
    "Delete every package downloaded from the update feed": "Supprimer tous les paquets téléchargés depuis le flux de mise à jour",
    "Delete every queued task; running ones are kept": "Supprimer toutes les tâches en attente, celles en cours sont conservées",
    "Delete global settings": "Supprimer les paramètres globaux",
    "Delete my annotation": "Supprimer ma propre annotation",
    "Delete readlist": "Supprimer une liste de lecture",
    "Delete readlist poster": "Supprimer l'affiche de la liste de lecture",
    "Delete series files": "Supprimer les fichiers de la série",
    "Delete series poster": "Supprimer l'affiche de la série",
    "Delete specific duplicate page": "Supprimer une page en double spécifique",
    "Delete user": "Supprimer un utilisateur",
    "Delete user settings": "Supprimer les paramètres utilisateur",
    "Discard the staged package": "Annuler le paquet préparé",
    "Download CSS file": "Télécharger le fichier CSS",
    "Download a raw log file": "Télécharger un fichier journal brut",
    "Download a release package, verify it and stage it for install": "Télécharger un paquet de version, le vérifier et le préparer pour l'installation",
    "Download an online chapter as a local CBZ and re-analyze it": "Télécharger un chapitre en ligne au format CBZ local et le ré-analyser",
    "Download and install the latest Calibre portable build": "Télécharger et installer la dernière version portable de Calibre",
    "Download and install the latest Suwayomi release": "Télécharger et installer la dernière version de Suwayomi",
    "Download book file": "Télécharger le fichier du livre",
    "Download font file": "Télécharger le fichier de police",
    "Download from the official source and install in the background": "Télécharger depuis la source officielle et installer en arrière-plan",
    "Download readlist": "Télécharger la liste de lecture",
    "Download series": "Télécharger la série",
    "Echo build and runtime information": "Renvoyer les informations de compilation et d'exécution",
    "Empty the trash of one or all libraries": "Vider la corbeille d'une ou de toutes les bibliothèques",
    "Empty trash for a library": "Vider la corbeille d'une bibliothèque",
    "Enable or disable a runtime": "Activer ou désactiver un environnement d'exécution",
    "Enable or disable a user": "Activer ou désactiver un utilisateur",
    "Environment and dependency check for an external runtime": "Vérification de l'environnement et des dépendances d'un environnement d'exécution externe",
    "Equivalent shell steps for a manual install": "Étapes shell équivalentes pour une installation manuelle",
    "Expire one or all sessions of a user": "Expirer une ou toutes les sessions d'un utilisateur",
    "Export a page range as zip": "Exporter une plage de pages en zip",
    "Export audit entries as CSV": "Exporter les entrées d'audit au format CSV",
    "Export the library inventory as JSON": "Exporter l'inventaire de la bibliothèque au format JSON",
    "Get Epub resource": "Obtenir une ressource Epub",
    "Get Lantai reading capabilities": "Obtenir les capacités de lecture de Lantai",
    "Get book details": "Obtenir les détails d'un livre",
    "Get book page image": "Obtenir l'image d'une page de livre",
    "Get book page thumbnail": "Obtenir la vignette d'une page de livre",
    "Get book poster image": "Obtenir l'image d'affiche d'un livre",
    "Get book progression": "Obtenir la progression d'un livre",
    "Get book's WebPub manifest": "Obtenir le manifeste WebPub d'un livre",
    "Get book's WebPub manifest (DiViNa)": "Obtenir le manifeste WebPub d'un livre (DiViNa)",
    "Get book's WebPub manifest (Epub)": "Obtenir le manifeste WebPub d'un livre (Epub)",
    "Get book's WebPub manifest (PDF)": "Obtenir le manifeste WebPub d'un livre (PDF)",
    "Get book's poster image": "Obtenir l'image d'affiche du livre",
    "Get collection details": "Obtenir les détails d'une collection",
    "Get collection poster image": "Obtenir l'image d'affiche d'une collection",
    "Get collection's poster image": "Obtenir l'image d'affiche de la collection",
    "Get details for a single library": "Obtenir les détails d'une seule bibliothèque",
    "Get known duplicate image thumbnail": "Obtenir la vignette d'une image en double connue",
    "Get next book in readlist": "Obtenir le livre suivant dans la liste de lecture",
    "Get next book in series": "Obtenir le livre suivant dans la série",
    "Get previous book in readlist": "Obtenir le livre précédent dans la liste de lecture",
    "Get previous book in series": "Obtenir le livre précédent dans la série",
    "Get raw book page": "Obtenir une page brute de livre",
    "Get reading statistics for the current user": "Obtenir les statistiques de lecture de l'utilisateur actuel",
    "Get readlist details": "Obtenir les détails d'une liste de lecture",
    "Get readlist poster image": "Obtenir l'image d'affiche d'une liste de lecture",
    "Get readlist read progress (Mihon)": "Obtenir la progression de lecture d'une liste de lecture (Mihon)",
    "Get readlist's poster image": "Obtenir l'image d'affiche de la liste de lecture",
    "Get series details": "Obtenir les détails d'une série",
    "Get series poster image": "Obtenir l'image d'affiche d'une série",
    "Get series read progress (Mihon)": "Obtenir la progression de lecture d'une série (Mihon)",
    "Get series' poster image": "Obtenir l'image d'affiche de la série",
    "Get the archive status of the current user's reading statistics": "Obtenir l'état d'archivage des statistiques de lecture de l'utilisateur actuel",
    "Get the reading time of one book for the current user": "Obtenir le temps de lecture d'un livre pour l'utilisateur actuel",
    "Get transient book page": "Obtenir une page de livre transitoire",
    "Get unknown duplicate image thumbnail": "Obtenir la vignette d'une image en double inconnue",
    "Import a Suwayomi jar manually": "Importer un jar Suwayomi manuellement",
    "Import a locally downloaded Calibre portable installer": "Importer un installateur portable Calibre téléchargé localement",
    "Import books": "Importer des livres",
    "Include or exclude a source from search and entry points": "Inclure ou exclure une source de la recherche et des points d'entrée",
    "Install an extension from an uploaded package": "Installer une extension depuis un paquet téléversé",
    "Install an extension from the catalog": "Installer une extension depuis le catalogue",
    "Latest Calibre portable release": "Dernière version portable de Calibre",
    "Latest Suwayomi release": "Dernière version de Suwayomi",
    "List active reading sessions for the current user": "Lister les sessions de lecture actives de l'utilisateur actuel",
    "List age ratings": "Lister les classifications par âge",
    "List and search users": "Lister et rechercher les utilisateurs",
    "List applied scrape records of the series behind a queue entry": "Lister les enregistrements de scraping appliqués de la série derrière une entrée de file",
    "List assignable roles": "Lister les rôles attribuables",
    "List authors": "Lister les auteurs",
    "List authors names": "Lister les noms d'auteurs",
    "List authors roles": "Lister les rôles d'auteurs",
    "List authors' names": "Lister les noms des auteurs",
    "List authors' roles": "Lister les rôles des auteurs",
    "List available log files": "Lister les fichiers journaux disponibles",
    "List book pages": "Lister les pages d'un livre",
    "List book posters": "Lister les affiches d'un livre",
    "List book tags": "Lister les étiquettes d'un livre",
    "List book's positions": "Lister les positions d'un livre",
    "List book's readlists": "Lister les listes de lecture d'un livre",
    "List books": "Lister les livres",
    "List books on deck": "Lister les livres sur le pont",
    "List books removed from library but still present on disk": "Lister les livres retirés de la bibliothèque mais toujours présents sur le disque",
    "List collection's posters": "Lister les affiches d'une collection",
    "List collection's series": "Lister les séries d'une collection",
    "List collections": "Lister les collections",
    "List duplicate books": "Lister les livres en double",
    "List duplicate matches": "Lister les correspondances en double",
    "List extension stores": "Lister les boutiques d'extensions",
    "List finished tasks, paged": "Lister les tâches terminées, paginé",
    "List font families": "Lister les familles de polices",
    "List genres": "Lister les genres",
    "List historical events": "Lister les événements historiques",
    "List installed extensions": "Lister les extensions installées",
    "List known duplicates": "Lister les doublons connus",
    "List languages": "Lister les langues",
    "List latest books": "Lister les derniers livres",
    "List latest series": "Lister les dernières séries",
    "List loggers and their levels": "Lister les journaliseurs et leurs niveaux",
    "List match candidates with their current and proposed values": "Lister les candidats de correspondance avec leurs valeurs actuelles et proposées",
    "List match jobs of this session": "Lister les tâches de correspondance de cette session",
    "List materialized chapters of a subscription with download state": "Lister les chapitres matérialisés d'un abonnement avec leur état de téléchargement",
    "List my annotations of a book": "Lister mes annotations d'un livre",
    "List new series": "Lister les nouvelles séries",
    "List notable entities": "Lister les entités notables",
    "List notable entities kinds": "Lister les types d'entités notables",
    "List notable entities names": "Lister les noms d'entités notables",
    "List online metadata sources and their daily usage": "Lister les sources de métadonnées en ligne et leur usage quotidien",
    "List publishers": "Lister les éditeurs",
    "List queued and running tasks, paged": "Lister les tâches en attente et en cours, paginé",
    "List reading sessions": "Lister les sessions de lecture",
    "List readlist's books": "Lister les livres d'une liste de lecture",
    "List readlist's posters": "Lister les affiches d'une liste de lecture",
    "List readlists": "Lister les listes de lecture",
    "List registered OAuth2 providers": "Lister les fournisseurs OAuth2 enregistrés",
    "List releases": "Lister les versions",
    "List series": "Lister les séries",
    "List series groups": "Lister les groupes de séries",
    "List series posters": "Lister les affiches de séries",
    "List series release dates": "Lister les dates de parution des séries",
    "List series release years": "Lister les années de parution des séries",
    "List series removed from library but still present on disk": "Lister les séries retirées de la bibliothèque mais toujours présentes sur le disque",
    "List series tags": "Lister les étiquettes de séries",
    "List series' books": "Lister les livres d'une série",
    "List series' collections": "Lister les collections d'une série",
    "List sharing labels": "Lister les libellés de partage",
    "List sources with their search/entry toggle state": "Lister les sources avec leur état d'activation pour la recherche et l'entrée",
    "List subscribed online mangas": "Lister les mangas en ligne abonnés",
    "List tags": "Lister les étiquettes",
    "List the API keys of a user": "Lister les clés API d'un utilisateur",
    "List the actions present in the audit trail": "Lister les actions présentes dans la piste d'audit",
    "List the active sessions of a user": "Lister les sessions actives d'un utilisateur",
    "List the books of the series behind a queue entry with their current metadata": "Lister les livres de la série derrière une entrée de file avec leurs métadonnées actuelles",
    "List the runtime filters exposed by a source": "Lister les filtres d'exécution exposés par une source",
    "List the scrape only API tokens": "Lister les jetons API dédiés au scraping",
    "List the sources provided by an extension": "Lister les sources fournies par une extension",
    "List trash entries of one or all libraries": "Lister les entrées de corbeille d'une ou de toutes les bibliothèques",
    "List unknown duplicates": "Lister les doublons inconnus",
    "List updated series": "Lister les séries mises à jour",
    "List users": "Lister les utilisateurs",
    "Mark a match as skipped": "Marquer une correspondance comme ignorée",
    "Mark announcements as read": "Marquer les annonces comme lues",
    "Mark book as unread": "Marquer le livre comme non lu",
    "Mark book poster as selected": "Marquer l'affiche du livre comme sélectionnée",
    "Mark book progression": "Enregistrer la progression d'un livre",
    "Mark book's read progress": "Enregistrer la progression de lecture d'un livre",
    "Mark collection poster as selected": "Marquer l'affiche de la collection comme sélectionnée",
    "Mark duplicate page as known": "Marquer une page en double comme connue",
    "Mark readlist poster as selected": "Marquer l'affiche de la liste de lecture comme sélectionnée",
    "Mark series as read": "Marquer la série comme lue",
    "Mark series as unread": "Marquer la série comme non lue",
    "Mark series poster as selected": "Marquer l'affiche de la série comme sélectionnée",
    "Match ComicRack list": "Faire correspondre une liste ComicRack",
    "Match a single series against enabled sources": "Faire correspondre une seule série aux sources activées",
    "Packages already downloaded into the config directory": "Paquets déjà téléchargés dans le répertoire de configuration",
    "Per-library series/book counts and total size": "Nombre de séries/livres et taille totale par bibliothèque",
    "Permanently delete a trash entry and its files": "Supprimer définitivement une entrée de corbeille et ses fichiers",
    "Pin an existing ebook-convert executable": "Épingler un exécutable ebook-convert existant",
    "Poll a match job": "Interroger une tâche de correspondance",
    "Probe an ebook-convert executable without saving it": "Sonder un exécutable ebook-convert sans l'enregistrer",
    "Probe every external runtime managed by Lantai": "Sonder tous les environnements d'exécution externes gérés par Lantai",
    "Probe one external runtime": "Sonder un environnement d'exécution externe",
    "Progress of the package download currently running": "Progression du téléchargement de paquet en cours",
    "Progress of the package validation currently running": "Progression de la validation de paquet en cours",
    "Proxy the remote cover of a search result": "Servir de proxy pour la couverture distante d'un résultat de recherche",
    "Purge reading sessions beyond the retention window": "Purger les sessions de lecture au-delà de la fenêtre de rétention",
    "Re-verify the most recent downloaded package": "Re-vérifier le paquet téléchargé le plus récent",
    "Read Suwayomi settings": "Lire les paramètres Suwayomi",
    "Read bootstrap payload": "Lire la charge utile d'amorçage",
    "Read chapter bundle": "Lire le paquet de chapitres",
    "Read chapter outline page": "Lire la page de plan d'un chapitre",
    "Read chapter window": "Lire la fenêtre de chapitre",
    "Read image resource": "Lire une ressource image",
    "Read one page of the global scrape queue": "Lire une page de la file de scraping globale",
    "Read one page of the scrape queue for an external agent": "Lire une page de la file de scraping pour un agent externe",
    "Read one queue entry with the hints an external agent needs": "Lire une entrée de file avec les indications dont un agent externe a besoin",
    "Read page thumbnail": "Lire la vignette d'une page",
    "Read scrape settings": "Lire les paramètres de scraping",
    "Read structured log entries, cursor based": "Lire les entrées de journal structurées, par curseur",
    "Read the configurable preferences of a source": "Lire les préférences configurables d'une source",
    "Read the remote detail of a single manga": "Lire le détail distant d'un seul manga",
    "Read the update feed and report the latest release plus any downloaded package": "Lire le flux de mise à jour et signaler la dernière version ainsi que tout paquet téléchargé",
    "Read trash settings": "Lire les paramètres de la corbeille",
    "Rebuild the daily reading statistics of the current user": "Reconstruire les statistiques de lecture quotidiennes de l'utilisateur actuel",
    "Recompute first letters for every series": "Recalculer les initiales de toutes les séries",
    "Refresh book metadata": "Recharger les métadonnées d'un livre",
    "Refresh installed extension metadata": "Recharger les métadonnées des extensions installées",
    "Refresh metadata for a library": "Recharger les métadonnées d'une bibliothèque",
    "Refresh series metadata": "Recharger les métadonnées d'une série",
    "Regenerate books posters": "Régénérer les affiches des livres",
    "Remove a book from the library, keeping its file untouched": "Retirer un livre de la bibliothèque sans toucher à son fichier",
    "Remove a series from the library, keeping its files untouched": "Retirer une série de la bibliothèque sans toucher à ses fichiers",
    "Remove a single queue entry": "Retirer une seule entrée de file",
    "Replace the roles of a user": "Remplacer les rôles d'un utilisateur",
    "Replace the running jar with the staged one and restart": "Remplacer le jar en cours par celui préparé et redémarrer",
    "Report a reading heartbeat": "Signaler un battement de lecture",
    "Reset a queue entry back to queued": "Remettre une entrée de file en attente",
    "Reset the password of a user and expire its sessions": "Réinitialiser le mot de passe d'un utilisateur et expirer ses sessions",
    "Restore a trash entry back to its library": "Restaurer une entrée de corbeille dans sa bibliothèque",
    "Restore logger levels to their default": "Restaurer les niveaux de journalisation par défaut",
    "Restore the last backup jar and restart": "Restaurer le dernier jar de sauvegarde et redémarrer",
    "Resume task processing": "Reprendre le traitement des tâches",
    "Retrieve API keys": "Récupérer les clés API",
    "Retrieve a single audit entry": "Récupérer une seule entrée d'audit",
    "Retrieve a user with its activity summary": "Récupérer un utilisateur avec son résumé d'activité",
    "Retrieve announcements": "Récupérer les annonces",
    "Retrieve authentication activity": "Récupérer l'activité d'authentification",
    "Retrieve authentication activity for the current user": "Récupérer l'activité d'authentification de l'utilisateur actuel",
    "Retrieve current user": "Récupérer l'utilisateur actuel",
    "Retrieve global client settings": "Récupérer les paramètres client globaux",
    "Retrieve latest authentication activity for a user": "Récupérer la dernière activité d'authentification d'un utilisateur",
    "Retrieve server settings": "Récupérer les paramètres du serveur",
    "Retrieve user client settings": "Récupérer les paramètres client de l'utilisateur",
    "Revoke a scrape only API token": "Révoquer un jeton API dédié au scraping",
    "Rotate the managed Suwayomi credentials": "Renouveler les identifiants Suwayomi gérés",
    "Run the automatic install for the given runtimes": "Lancer l'installation automatique des environnements d'exécution indiqués",
    "Runtime and storage status": "État de l'environnement d'exécution et du stockage",
    "Save global settings": "Enregistrer les paramètres globaux",
    "Save user settings": "Enregistrer les paramètres utilisateur",
    "Scan a library": "Scanner une bibliothèque",
    "Scan folder for transient books": "Scanner le dossier pour les livres transitoires",
    "Search audit entries": "Rechercher dans les entrées d'audit",
    "Search inside a book": "Rechercher dans un livre",
    "Search mangas across enabled sources": "Rechercher des mangas sur les sources activées",
    "Search metadata candidates from one or all enabled sources": "Rechercher des candidats de métadonnées depuis une ou toutes les sources activées",
    "Series ids whose computed first letter matches": "ID de séries dont l'initiale calculée correspond",
    "Set cookie": "Définir le cookie",
    "Set the conversion mode: never, on-demand or eager": "Définir le mode de conversion : jamais, à la demande ou immédiat",
    "Set the trash quota of a library, zero means inheriting the global default": "Définir le quota de corbeille d'une bibliothèque, zéro signifie hériter de la valeur globale",
    "Snapshot of the automatic install performed at startup": "Instantané de l'installation automatique effectuée au démarrage",
    "Snapshot the main and tasks SQLite databases": "Instantané des bases SQLite principale et des tâches",
    "Start a background auto-match job for a library": "Démarrer une tâche de correspondance automatique en arrière-plan pour une bibliothèque",
    "Start an external runtime": "Démarrer un environnement d'exécution externe",
    "Start or resume scraping the queue": "Démarrer ou reprendre le scraping de la file",
    "Start, stop or restart Suwayomi": "Démarrer, arrêter ou redémarrer Suwayomi",
    "Stop an external runtime": "Arrêter un environnement d'exécution externe",
    "Stop taking new tasks; running tasks finish": "Arrêter d'accepter de nouvelles tâches, celles en cours se terminent",
    "Stop the current reading session": "Arrêter la session de lecture en cours",
    "Stream new log entries": "Diffuser les nouvelles entrées de journal",
    "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "Soumettre un résultat de scraping pour une entrée de file, ou passer un ID de série pour écraser un résultat déjà appliqué",
    "Subscribe to a remote manga and materialize it as a series": "S'abonner à un manga distant et le matérialiser en série",
    "Suwayomi runtime status": "État de l'environnement d'exécution Suwayomi",
    "Sync offline reading sessions": "Synchroniser les sessions de lecture hors ligne",
    "Synchronize chapters of a subscribed series": "Synchroniser les chapitres d'une série abonnée",
    "Undo an applied match using its snapshot": "Annuler une correspondance appliquée à l'aide de son instantané",
    "Uninstall a runtime, data is removed only when purge is requested": "Désinstaller un environnement d'exécution, les données ne sont supprimées que si la purge est demandée",
    "Uninstall an extension": "Désinstaller une extension",
    "Unsubscribe a series and remove its placeholder files": "Se désabonner d'une série et supprimer ses fichiers de remplacement",
    "Update Suwayomi settings": "Mettre à jour les paramètres Suwayomi",
    "Update a library": "Mettre à jour une bibliothèque",
    "Update an installed extension": "Mettre à jour une extension installée",
    "Update book metadata": "Mettre à jour les métadonnées d'un livre",
    "Update book metadata in bulk": "Mettre à jour les métadonnées de livres en masse",
    "Update collection": "Mettre à jour une collection",
    "Update current user's password": "Mettre à jour le mot de passe de l'utilisateur actuel",
    "Update note, color or marker of my annotation": "Mettre à jour la note, la couleur ou le marqueur de mon annotation",
    "Update one preference of a source and return the refreshed list": "Mettre à jour une préférence d'une source et renvoyer la liste actualisée",
    "Update readlist": "Mettre à jour une liste de lecture",
    "Update readlist read progress (Mihon)": "Mettre à jour la progression de lecture d'une liste de lecture (Mihon)",
    "Update scrape settings, trusting the results requires an explicit confirmation": "Mettre à jour les paramètres de scraping, faire confiance aux résultats exige une confirmation explicite",
    "Update series metadata": "Mettre à jour les métadonnées d'une série",
    "Update series read progress (Mihon)": "Mettre à jour la progression de lecture d'une série (Mihon)",
    "Update server settings": "Mettre à jour les paramètres du serveur",
    "Update the username and email of a user": "Mettre à jour le nom d'utilisateur et l'e-mail d'un utilisateur",
    "Update trash settings": "Mettre à jour les paramètres de la corbeille",
    "Update user": "Mettre à jour un utilisateur",
    "Update user's password": "Mettre à jour le mot de passe d'un utilisateur",
    "Upload a package and install it in the background": "Téléverser un paquet et l'installer en arrière-plan",
    "Upload an upgrade package and validate it without touching the running jar": "Téléverser un paquet de mise à niveau et le valider sans toucher au jar en cours",
      "Add a scraping source": "Ajouter une source de scraping",
      "Apply bulk series metadata from a text payload": "Appliquer des métadonnées de série en masse depuis un texte",
      "Authenticate a KOReader user": "Authentifier un utilisateur KOReader",
      "Browse a library": "Parcourir une bibliothèque",
      "Check Kobo connectivity": "Vérifier la connectivité Kobo",
      "Count entries awaiting bulk metadata": "Compter les entrées en attente de métadonnées",
      "Create an API token": "Créer un jeton d'API",
      "Delete a scraping source": "Supprimer une source de scraping",
      "Delete an API token": "Supprimer un jeton d'API",
      "Download a book file for Kobo": "Télécharger un fichier de livre pour Kobo",
      "Download the metadata package template": "Télécharger le modèle de paquet de métadonnées",
      "Enable or disable a scraping source": "Activer ou désactiver une source de scraping",
      "Exchange a Kobo device token": "Échanger un jeton d'appareil Kobo",
      "Export series metadata as a text file": "Exporter les métadonnées de série en fichier texte",
      "Get Kobo metadata for a book": "Obtenir les métadonnées Kobo d'un livre",
      "Get Kobo reading state for a book": "Obtenir l'état de lecture Kobo d'un livre",
      "Get a book cover for Kobo": "Obtenir une couverture de livre pour Kobo",
      "Get a book page image": "Obtenir l'image d'une page de livre",
      "Get a book page thumbnail": "Obtenir la vignette d'une page de livre",
      "Get a book thumbnail": "Obtenir la vignette d'un livre",
      "Get one collection": "Obtenir une collection",
      "Get one library": "Obtenir une bibliothèque",
      "Get one reading list": "Obtenir une liste de lecture",
      "Get one series with its books": "Obtenir une série avec ses livres",
      "Get reading progress for a document": "Obtenir la progression de lecture d'un document",
      "Get scraping source details": "Obtenir les détails d'une source de scraping",
      "Get the OPDS authentication document": "Obtenir le document d'authentification OPDS",
      "Get the OPDS search description": "Obtenir la description de recherche OPDS",
      "Get the OPDS v1 catalog": "Obtenir le catalogue OPDS v1",
      "Get the OPDS v2 catalog": "Obtenir le catalogue OPDS v2",
      "Get the WebPub manifest for a DIVINA book": "Obtenir le manifeste WebPub d'un livre DIVINA",
      "Get the WebPub manifest for a PDF": "Obtenir le manifeste WebPub d'un PDF",
      "Get the WebPub manifest for a book": "Obtenir le manifeste WebPub d'un livre",
      "Get the WebPub manifest for an EPUB": "Obtenir le manifeste WebPub d'un EPUB",
      "Import a series metadata package": "Importer un paquet de métadonnées de série",
      "Initialize a Kobo device": "Initialiser un appareil Kobo",
      "List API tokens": "Lister les jetons d'API",
      "List all series": "Lister toutes les séries",
      "List available API token scopes": "Lister les portées de jeton d'API disponibles",
      "List books currently being read": "Lister les livres en cours de lecture",
      "List books to keep reading": "Lister les livres à continuer",
      "List collections in a library": "Lister les collections d'une bibliothèque",
      "List entries awaiting bulk metadata": "Lister les entrées en attente de métadonnées",
      "List libraries": "Lister les bibliothèques",
      "List reading lists": "Lister les listes de lecture",
      "List reading lists in a library": "Lister les listes de lecture d'une bibliothèque",
      "List recently added books": "Lister les livres récemment ajoutés",
      "List recently added series": "Lister les séries récemment ajoutées",
      "Proxy unmatched Kobo requests": "Relayer les requêtes Kobo non reconnues",
      "Register a KOReader user (disabled)": "Enregistrer un utilisateur KOReader (désactivé)",
      "Search the catalog": "Rechercher dans le catalogue",
      "Submit bulk metadata for application": "Soumettre des métadonnées en masse pour application",
      "Sync the Kobo library": "Synchroniser la bibliothèque Kobo",
      "Test a scraping source connection": "Tester la connexion d'une source de scraping",
      "Update Kobo reading state for a book": "Mettre à jour l'état de lecture Kobo d'un livre",
      "Update a scraping source": "Mettre à jour une source de scraping",
      "Update an API token": "Mettre à jour un jeton d'API",
      "Update reading progress for a document": "Mettre à jour la progression de lecture d'un document",
    "Serve the web interface": "Servir l'interface web",
    "Stream server-sent events": "Diffuser des événements serveur",
  };
  B["de"] = {
    "Delete all sync points": "Alle Synchronisationspunkte löschen",
    "Directory listing": "Verzeichnisauflistung",
    "List all libraries": "Alle Bibliotheken auflisten",
    "Add an extension store": "Einen Erweiterungsspeicher hinzufügen",
    "Add book poster": "Buchposter hinzufügen",
    "Add collection poster": "Sammlungsposter hinzufügen",
    "Add readlist poster": "Leselistenposter hinzufügen",
    "Add series poster": "Serienposter hinzufügen",
    "Add series, a whole library or every series missing metadata to the queue": "Eine Serie, eine ganze Bibliothek oder alle Serien ohne Metadaten zur Warteschlange hinzufügen",
    "Aggregate reading devices from session history": "Lesegeräte aus dem Sitzungsverlauf zusammenfassen",
    "Aggregated home payload for clients": "Aggregierte Startnutzlast für Clients",
    "Analyze a library": "Eine Bibliothek analysieren",
    "Analyze book": "Buch analysieren",
    "Analyze series": "Serie analysieren",
    "Analyze transient book": "Temporäres Buch analysieren",
    "Apply selected fields of a match and keep a rollback snapshot": "Ausgewählte Felder einer Übereinstimmung anwenden und einen Rollback-Snapshot behalten",
    "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "Die gewählten Felder eines Warteschlangeneintrags anwenden, gibt 204 zurück, wenn der Eintrag die Warteschlange verlässt",
    "Ask the running batch to stop after the current series": "Den laufenden Stapel bitten, nach der aktuellen Serie zu stoppen",
    "Available versions and download sources": "Verfügbare Versionen und Downloadquellen",
    "Book counts grouped by computed first letter": "Buchanzahl gruppiert nach berechnetem Anfangsbuchstaben",
    "Book cover resized on demand": "Buchcover bei Bedarf skaliert",
    "Book ids whose computed first letter matches": "Buch-IDs, deren berechneter Anfangsbuchstabe übereinstimmt",
    "Bring back a book previously removed from the library": "Ein zuvor aus der Bibliothek entferntes Buch zurückholen",
    "Bring back a series previously removed from the library": "Eine zuvor aus der Bibliothek entfernte Serie zurückholen",
    "Browse a source by popular or latest, or search inside it": "Eine Quelle nach Beliebtheit oder Neuheit durchsuchen oder in ihr suchen",
    "Browse installable extensions": "Installierbare Erweiterungen durchsuchen",
    "Calibre availability, version and conversion mode": "Calibre-Verfügbarkeit, Version und Konvertierungsmodus",
    "Change logger levels at runtime, use INHERIT to restore the default": "Logger-Stufen zur Laufzeit ändern, INHERIT stellt den Standard wieder her",
    "Clear backoff and circuit state of a source": "Backoff- und Circuit-Status einer Quelle zurücksetzen",
    "Clear queue entries by scope: queued, review, failed or all": "Warteschlangeneinträge nach Bereich leeren: wartend, Prüfung, fehlgeschlagen oder alle",
    "Clear task queue": "Aufgabenwarteschlange leeren",
    "Clear the pinned executable and fall back to the managed installation": "Die festgelegte ausführbare Datei entfernen und auf die verwaltete Installation zurückfallen",
    "Client capability negotiation": "Aushandlung der Client-Fähigkeiten",
    "Compute the field level difference of a queue entry": "Die Feldunterschiede eines Warteschlangeneintrags berechnen",
    "Convert a single book and queue re-analysis": "Ein einzelnes Buch konvertieren und die erneute Analyse einreihen",
    "Convert pending books of a library": "Ausstehende Bücher einer Bibliothek konvertieren",
    "Count books still waiting for conversion in a library": "Bücher zählen, die in einer Bibliothek noch auf die Konvertierung warten",
    "Counters for the scrape queue and the inbox": "Zähler für die Scraping-Warteschlange und den Posteingang",
    "Create API key": "API-Schlüssel erstellen",
    "Create a library": "Eine Bibliothek erstellen",
    "Create a scrape only API token with fixed scopes": "Ein reines Scraping-API-Token mit festen Bereichen erstellen",
    "Create an annotation with a locator and a text fallback": "Eine Anmerkung mit Locator und Textfallback erstellen",
    "Create collection": "Sammlung erstellen",
    "Create readlist": "Leseliste erstellen",
    "Create user": "Benutzer erstellen",
    "Current install progress": "Aktueller Installationsfortschritt",
    "Current jar location and whether it can be replaced in place": "Aktueller jar-Speicherort und ob er an Ort und Stelle ersetzt werden kann",
    "Delete API key": "API-Schlüssel löschen",
    "Delete a library": "Eine Bibliothek löschen",
    "Delete a single task by id": "Eine einzelne Aufgabe nach ID löschen",
    "Delete all duplicate pages by hash": "Alle doppelten Seiten nach Hash löschen",
    "Delete an API key of a user": "Einen API-Schlüssel eines Benutzers löschen",
    "Delete book file": "Buchdatei löschen",
    "Delete book poster": "Buchposter löschen",
    "Delete collection": "Sammlung löschen",
    "Delete collection poster": "Sammlungsposter löschen",
    "Delete downloaded cache of an online series, chapters remain readable through the source": "Heruntergeladenen Cache einer Online-Serie löschen, Kapitel bleiben über die Quelle lesbar",
    "Delete every finished task record": "Alle abgeschlossenen Aufgabenaufzeichnungen löschen",
    "Delete every package downloaded from the update feed": "Alle aus dem Update-Feed heruntergeladenen Pakete löschen",
    "Delete every queued task; running ones are kept": "Alle wartenden Aufgaben löschen, laufende bleiben erhalten",
    "Delete global settings": "Globale Einstellungen löschen",
    "Delete my annotation": "Meine Anmerkung löschen",
    "Delete readlist": "Leseliste löschen",
    "Delete readlist poster": "Leselistenposter löschen",
    "Delete series files": "Seriendateien löschen",
    "Delete series poster": "Serienposter löschen",
    "Delete specific duplicate page": "Bestimmte doppelte Seite löschen",
    "Delete user": "Benutzer löschen",
    "Delete user settings": "Benutzereinstellungen löschen",
    "Discard the staged package": "Das bereitgestellte Paket verwerfen",
    "Download CSS file": "CSS-Datei herunterladen",
    "Download a raw log file": "Eine Rohprotokolldatei herunterladen",
    "Download a release package, verify it and stage it for install": "Ein Release-Paket herunterladen, prüfen und zur Installation bereitstellen",
    "Download an online chapter as a local CBZ and re-analyze it": "Ein Online-Kapitel als lokales CBZ herunterladen und erneut analysieren",
    "Download and install the latest Calibre portable build": "Die neueste portable Calibre-Version herunterladen und installieren",
    "Download and install the latest Suwayomi release": "Die neueste Suwayomi-Version herunterladen und installieren",
    "Download book file": "Buchdatei herunterladen",
    "Download font file": "Schriftdatei herunterladen",
    "Download from the official source and install in the background": "Von der offiziellen Quelle herunterladen und im Hintergrund installieren",
    "Download readlist": "Leseliste herunterladen",
    "Download series": "Serie herunterladen",
    "Echo build and runtime information": "Build- und Laufzeitinformationen zurückgeben",
    "Empty the trash of one or all libraries": "Den Papierkorb einer oder aller Bibliotheken leeren",
    "Empty trash for a library": "Den Papierkorb einer Bibliothek leeren",
    "Enable or disable a runtime": "Eine Laufzeit aktivieren oder deaktivieren",
    "Enable or disable a user": "Einen Benutzer aktivieren oder deaktivieren",
    "Environment and dependency check for an external runtime": "Umgebungs- und Abhängigkeitsprüfung für eine externe Laufzeit",
    "Equivalent shell steps for a manual install": "Entsprechende Shell-Schritte für eine manuelle Installation",
    "Expire one or all sessions of a user": "Eine oder alle Sitzungen eines Benutzers ablaufen lassen",
    "Export a page range as zip": "Einen Seitenbereich als ZIP exportieren",
    "Export audit entries as CSV": "Audit-Einträge als CSV exportieren",
    "Export the library inventory as JSON": "Das Bibliotheksinventar als JSON exportieren",
    "Get Epub resource": "Epub-Ressource abrufen",
    "Get Lantai reading capabilities": "Lantai-Lesefunktionen abrufen",
    "Get book details": "Buchdetails abrufen",
    "Get book page image": "Buchseitenbild abrufen",
    "Get book page thumbnail": "Buchseitenvorschaubild abrufen",
    "Get book poster image": "Buchposterbild abrufen",
    "Get book progression": "Buchfortschritt abrufen",
    "Get book's WebPub manifest": "WebPub-Manifest des Buches abrufen",
    "Get book's WebPub manifest (DiViNa)": "WebPub-Manifest des Buches abrufen (DiViNa)",
    "Get book's WebPub manifest (Epub)": "WebPub-Manifest des Buches abrufen (Epub)",
    "Get book's WebPub manifest (PDF)": "WebPub-Manifest des Buches abrufen (PDF)",
    "Get book's poster image": "Posterbild des Buches abrufen",
    "Get collection details": "Sammlungsdetails abrufen",
    "Get collection poster image": "Sammlungsposterbild abrufen",
    "Get collection's poster image": "Posterbild der Sammlung abrufen",
    "Get details for a single library": "Details einer einzelnen Bibliothek abrufen",
    "Get known duplicate image thumbnail": "Vorschaubild eines bekannten Duplikats abrufen",
    "Get next book in readlist": "Nächstes Buch in der Leseliste abrufen",
    "Get next book in series": "Nächstes Buch in der Serie abrufen",
    "Get previous book in readlist": "Vorheriges Buch in der Leseliste abrufen",
    "Get previous book in series": "Vorheriges Buch in der Serie abrufen",
    "Get raw book page": "Rohe Buchseite abrufen",
    "Get reading statistics for the current user": "Lesestatistiken des aktuellen Benutzers abrufen",
    "Get readlist details": "Leselistendetails abrufen",
    "Get readlist poster image": "Leselistenposterbild abrufen",
    "Get readlist read progress (Mihon)": "Lesefortschritt der Leseliste abrufen (Mihon)",
    "Get readlist's poster image": "Posterbild der Leseliste abrufen",
    "Get series details": "Seriendetails abrufen",
    "Get series poster image": "Serienposterbild abrufen",
    "Get series read progress (Mihon)": "Lesefortschritt der Serie abrufen (Mihon)",
    "Get series' poster image": "Posterbild der Serie abrufen",
    "Get the archive status of the current user's reading statistics": "Archivstatus der Lesestatistiken des aktuellen Benutzers abrufen",
    "Get the reading time of one book for the current user": "Lesezeit eines Buches für den aktuellen Benutzer abrufen",
    "Get transient book page": "Seite eines temporären Buches abrufen",
    "Get unknown duplicate image thumbnail": "Vorschaubild eines unbekannten Duplikats abrufen",
    "Import a Suwayomi jar manually": "Ein Suwayomi-jar manuell importieren",
    "Import a locally downloaded Calibre portable installer": "Einen lokal heruntergeladenen portablen Calibre-Installer importieren",
    "Import books": "Bücher importieren",
    "Include or exclude a source from search and entry points": "Eine Quelle in Suche und Einstiegspunkten einbeziehen oder ausschließen",
    "Install an extension from an uploaded package": "Eine Erweiterung aus einem hochgeladenen Paket installieren",
    "Install an extension from the catalog": "Eine Erweiterung aus dem Katalog installieren",
    "Latest Calibre portable release": "Neueste portable Calibre-Version",
    "Latest Suwayomi release": "Neueste Suwayomi-Version",
    "List active reading sessions for the current user": "Aktive Lesesitzungen des aktuellen Benutzers auflisten",
    "List age ratings": "Altersfreigaben auflisten",
    "List and search users": "Benutzer auflisten und durchsuchen",
    "List applied scrape records of the series behind a queue entry": "Angewendete Scraping-Datensätze der Serie hinter einem Warteschlangeneintrag auflisten",
    "List assignable roles": "Zuweisbare Rollen auflisten",
    "List authors": "Autoren auflisten",
    "List authors names": "Autorennamen auflisten",
    "List authors roles": "Autorenrollen auflisten",
    "List authors' names": "Namen der Autoren auflisten",
    "List authors' roles": "Rollen der Autoren auflisten",
    "List available log files": "Verfügbare Protokolldateien auflisten",
    "List book pages": "Buchseiten auflisten",
    "List book posters": "Buchposter auflisten",
    "List book tags": "Buch-Tags auflisten",
    "List book's positions": "Positionen des Buches auflisten",
    "List book's readlists": "Leselisten des Buches auflisten",
    "List books": "Bücher auflisten",
    "List books on deck": "Bücher auf dem Deck auflisten",
    "List books removed from library but still present on disk": "Bücher auflisten, die aus der Bibliothek entfernt wurden, aber noch auf der Festplatte liegen",
    "List collection's posters": "Poster der Sammlung auflisten",
    "List collection's series": "Serien der Sammlung auflisten",
    "List collections": "Sammlungen auflisten",
    "List duplicate books": "Doppelte Bücher auflisten",
    "List duplicate matches": "Doppelte Übereinstimmungen auflisten",
    "List extension stores": "Erweiterungsspeicher auflisten",
    "List finished tasks, paged": "Abgeschlossene Aufgaben auflisten, seitenweise",
    "List font families": "Schriftfamilien auflisten",
    "List genres": "Genres auflisten",
    "List historical events": "Historische Ereignisse auflisten",
    "List installed extensions": "Installierte Erweiterungen auflisten",
    "List known duplicates": "Bekannte Duplikate auflisten",
    "List languages": "Sprachen auflisten",
    "List latest books": "Neueste Bücher auflisten",
    "List latest series": "Neueste Serien auflisten",
    "List loggers and their levels": "Logger und ihre Stufen auflisten",
    "List match candidates with their current and proposed values": "Übereinstimmungskandidaten mit ihren aktuellen und vorgeschlagenen Werten auflisten",
    "List match jobs of this session": "Übereinstimmungsaufträge dieser Sitzung auflisten",
    "List materialized chapters of a subscription with download state": "Materialisierte Kapitel eines Abonnements mit Download-Status auflisten",
    "List my annotations of a book": "Meine Anmerkungen zu einem Buch auflisten",
    "List new series": "Neue Serien auflisten",
    "List notable entities": "Bemerkenswerte Entitäten auflisten",
    "List notable entities kinds": "Arten bemerkenswerter Entitäten auflisten",
    "List notable entities names": "Namen bemerkenswerter Entitäten auflisten",
    "List online metadata sources and their daily usage": "Online-Metadatenquellen und ihre tägliche Nutzung auflisten",
    "List publishers": "Herausgeber auflisten",
    "List queued and running tasks, paged": "Wartende und laufende Aufgaben auflisten, seitenweise",
    "List reading sessions": "Lesesitzungen auflisten",
    "List readlist's books": "Bücher der Leseliste auflisten",
    "List readlist's posters": "Poster der Leseliste auflisten",
    "List readlists": "Leselisten auflisten",
    "List registered OAuth2 providers": "Registrierte OAuth2-Anbieter auflisten",
    "List releases": "Versionen auflisten",
    "List series": "Serien auflisten",
    "List series groups": "Seriengruppen auflisten",
    "List series posters": "Serienposter auflisten",
    "List series release dates": "Erscheinungsdaten der Serien auflisten",
    "List series release years": "Erscheinungsjahre der Serien auflisten",
    "List series removed from library but still present on disk": "Serien auflisten, die aus der Bibliothek entfernt wurden, aber noch auf der Festplatte liegen",
    "List series tags": "Serien-Tags auflisten",
    "List series' books": "Bücher der Serie auflisten",
    "List series' collections": "Sammlungen der Serie auflisten",
    "List sharing labels": "Freigabe-Labels auflisten",
    "List sources with their search/entry toggle state": "Quellen mit ihrem Umschaltstatus für Suche und Einstieg auflisten",
    "List subscribed online mangas": "Abonnierte Online-Mangas auflisten",
    "List tags": "Tags auflisten",
    "List the API keys of a user": "Die API-Schlüssel eines Benutzers auflisten",
    "List the actions present in the audit trail": "Die im Audit-Trail vorhandenen Aktionen auflisten",
    "List the active sessions of a user": "Die aktiven Sitzungen eines Benutzers auflisten",
    "List the books of the series behind a queue entry with their current metadata": "Die Bücher der Serie hinter einem Warteschlangeneintrag mit ihren aktuellen Metadaten auflisten",
    "List the runtime filters exposed by a source": "Die von einer Quelle bereitgestellten Laufzeitfilter auflisten",
    "List the scrape only API tokens": "Die reinen Scraping-API-Token auflisten",
    "List the sources provided by an extension": "Die von einer Erweiterung bereitgestellten Quellen auflisten",
    "List trash entries of one or all libraries": "Papierkorbeinträge einer oder aller Bibliotheken auflisten",
    "List unknown duplicates": "Unbekannte Duplikate auflisten",
    "List updated series": "Aktualisierte Serien auflisten",
    "List users": "Benutzer auflisten",
    "Mark a match as skipped": "Eine Übereinstimmung als übersprungen markieren",
    "Mark announcements as read": "Ankündigungen als gelesen markieren",
    "Mark book as unread": "Buch als ungelesen markieren",
    "Mark book poster as selected": "Buchposter als ausgewählt markieren",
    "Mark book progression": "Buchfortschritt erfassen",
    "Mark book's read progress": "Lesefortschritt des Buches erfassen",
    "Mark collection poster as selected": "Sammlungsposter als ausgewählt markieren",
    "Mark duplicate page as known": "Doppelte Seite als bekannt markieren",
    "Mark readlist poster as selected": "Leselistenposter als ausgewählt markieren",
    "Mark series as read": "Serie als gelesen markieren",
    "Mark series as unread": "Serie als ungelesen markieren",
    "Mark series poster as selected": "Serienposter als ausgewählt markieren",
    "Match ComicRack list": "ComicRack-Liste abgleichen",
    "Match a single series against enabled sources": "Eine einzelne Serie mit aktivierten Quellen abgleichen",
    "Packages already downloaded into the config directory": "Bereits in das Konfigurationsverzeichnis heruntergeladene Pakete",
    "Per-library series/book counts and total size": "Serien-/Bücheranzahl und Gesamtgröße pro Bibliothek",
    "Permanently delete a trash entry and its files": "Einen Papierkorbeintrag und seine Dateien endgültig löschen",
    "Pin an existing ebook-convert executable": "Eine vorhandene ebook-convert-Datei festlegen",
    "Poll a match job": "Einen Übereinstimmungsauftrag abfragen",
    "Probe an ebook-convert executable without saving it": "Eine ebook-convert-Datei prüfen, ohne sie zu speichern",
    "Probe every external runtime managed by Lantai": "Alle von Lantai verwalteten externen Laufzeiten prüfen",
    "Probe one external runtime": "Eine externe Laufzeit prüfen",
    "Progress of the package download currently running": "Fortschritt des aktuell laufenden Paketdownloads",
    "Progress of the package validation currently running": "Fortschritt der aktuell laufenden Paketprüfung",
    "Proxy the remote cover of a search result": "Das Remote-Cover eines Suchergebnisses als Proxy bereitstellen",
    "Purge reading sessions beyond the retention window": "Lesesitzungen jenseits des Aufbewahrungsfensters löschen",
    "Re-verify the most recent downloaded package": "Das zuletzt heruntergeladene Paket erneut prüfen",
    "Read Suwayomi settings": "Suwayomi-Einstellungen lesen",
    "Read bootstrap payload": "Bootstrap-Nutzlast lesen",
    "Read chapter bundle": "Kapitelbündel lesen",
    "Read chapter outline page": "Kapitelübersichtsseite lesen",
    "Read chapter window": "Kapitel-Fenster lesen",
    "Read image resource": "Bildressource lesen",
    "Read one page of the global scrape queue": "Eine Seite der globalen Scraping-Warteschlange lesen",
    "Read one page of the scrape queue for an external agent": "Eine Seite der Scraping-Warteschlange für einen externen Agenten lesen",
    "Read one queue entry with the hints an external agent needs": "Einen Warteschlangeneintrag mit den für einen externen Agenten nötigen Hinweisen lesen",
    "Read page thumbnail": "Seitenvorschaubild lesen",
    "Read scrape settings": "Scraping-Einstellungen lesen",
    "Read structured log entries, cursor based": "Strukturierte Protokolleinträge cursor-basiert lesen",
    "Read the configurable preferences of a source": "Die konfigurierbaren Einstellungen einer Quelle lesen",
    "Read the remote detail of a single manga": "Die Remote-Details eines einzelnen Mangas lesen",
    "Read the update feed and report the latest release plus any downloaded package": "Den Update-Feed lesen und die neueste Version sowie jedes heruntergeladene Paket melden",
    "Read trash settings": "Papierkorb-Einstellungen lesen",
    "Rebuild the daily reading statistics of the current user": "Die täglichen Lesestatistiken des aktuellen Benutzers neu aufbauen",
    "Recompute first letters for every series": "Anfangsbuchstaben für jede Serie neu berechnen",
    "Refresh book metadata": "Buchmetadaten neu laden",
    "Refresh installed extension metadata": "Metadaten installierter Erweiterungen neu laden",
    "Refresh metadata for a library": "Metadaten einer Bibliothek neu laden",
    "Refresh series metadata": "Serienmetadaten neu laden",
    "Regenerate books posters": "Buchposter neu erzeugen",
    "Remove a book from the library, keeping its file untouched": "Ein Buch aus der Bibliothek entfernen, ohne seine Datei zu verändern",
    "Remove a series from the library, keeping its files untouched": "Eine Serie aus der Bibliothek entfernen, ohne ihre Dateien zu verändern",
    "Remove a single queue entry": "Einen einzelnen Warteschlangeneintrag entfernen",
    "Replace the roles of a user": "Die Rollen eines Benutzers ersetzen",
    "Replace the running jar with the staged one and restart": "Das laufende jar durch das bereitgestellte ersetzen und neu starten",
    "Report a reading heartbeat": "Einen Lese-Heartbeat melden",
    "Reset a queue entry back to queued": "Einen Warteschlangeneintrag in den Wartezustand zurücksetzen",
    "Reset the password of a user and expire its sessions": "Das Passwort eines Benutzers zurücksetzen und seine Sitzungen ablaufen lassen",
    "Restore a trash entry back to its library": "Einen Papierkorbeintrag in seine Bibliothek zurückversetzen",
    "Restore logger levels to their default": "Logger-Stufen auf ihren Standard zurücksetzen",
    "Restore the last backup jar and restart": "Das letzte Backup-jar wiederherstellen und neu starten",
    "Resume task processing": "Aufgabenverarbeitung fortsetzen",
    "Retrieve API keys": "API-Schlüssel abrufen",
    "Retrieve a single audit entry": "Einen einzelnen Audit-Eintrag abrufen",
    "Retrieve a user with its activity summary": "Einen Benutzer mit seiner Aktivitätsübersicht abrufen",
    "Retrieve announcements": "Ankündigungen abrufen",
    "Retrieve authentication activity": "Authentifizierungsaktivität abrufen",
    "Retrieve authentication activity for the current user": "Authentifizierungsaktivität des aktuellen Benutzers abrufen",
    "Retrieve current user": "Aktuellen Benutzer abrufen",
    "Retrieve global client settings": "Globale Client-Einstellungen abrufen",
    "Retrieve latest authentication activity for a user": "Die neueste Authentifizierungsaktivität eines Benutzers abrufen",
    "Retrieve server settings": "Servereinstellungen abrufen",
    "Retrieve user client settings": "Benutzer-Client-Einstellungen abrufen",
    "Revoke a scrape only API token": "Ein reines Scraping-API-Token widerrufen",
    "Rotate the managed Suwayomi credentials": "Die verwalteten Suwayomi-Anmeldedaten rotieren",
    "Run the automatic install for the given runtimes": "Die automatische Installation für die angegebenen Laufzeiten ausführen",
    "Runtime and storage status": "Laufzeit- und Speicherstatus",
    "Save global settings": "Globale Einstellungen speichern",
    "Save user settings": "Benutzereinstellungen speichern",
    "Scan a library": "Eine Bibliothek scannen",
    "Scan folder for transient books": "Ordner nach temporären Büchern scannen",
    "Search audit entries": "Audit-Einträge durchsuchen",
    "Search inside a book": "In einem Buch suchen",
    "Search mangas across enabled sources": "Mangas über aktivierte Quellen hinweg suchen",
    "Search metadata candidates from one or all enabled sources": "Metadatenkandidaten aus einer oder allen aktivierten Quellen suchen",
    "Series ids whose computed first letter matches": "Serien-IDs, deren berechneter Anfangsbuchstabe übereinstimmt",
    "Set cookie": "Cookie setzen",
    "Set the conversion mode: never, on-demand or eager": "Den Konvertierungsmodus festlegen: nie, bei Bedarf oder sofort",
    "Set the trash quota of a library, zero means inheriting the global default": "Das Papierkorb-Kontingent einer Bibliothek festlegen, null bedeutet den globalen Standard erben",
    "Snapshot of the automatic install performed at startup": "Snapshot der beim Start durchgeführten automatischen Installation",
    "Snapshot the main and tasks SQLite databases": "Die SQLite-Datenbanken für Haupt und Aufgaben als Snapshot sichern",
    "Start a background auto-match job for a library": "Einen automatischen Hintergrundabgleich für eine Bibliothek starten",
    "Start an external runtime": "Eine externe Laufzeit starten",
    "Start or resume scraping the queue": "Das Scraping der Warteschlange starten oder fortsetzen",
    "Start, stop or restart Suwayomi": "Suwayomi starten, stoppen oder neu starten",
    "Stop an external runtime": "Eine externe Laufzeit stoppen",
    "Stop taking new tasks; running tasks finish": "Keine neuen Aufgaben mehr annehmen, laufende werden abgeschlossen",
    "Stop the current reading session": "Die aktuelle Lesesitzung beenden",
    "Stream new log entries": "Neue Protokolleinträge streamen",
    "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "Ein Scraping-Ergebnis für einen Warteschlangeneintrag übermitteln oder eine Serien-ID übergeben, um ein bereits angewendetes Ergebnis zu überschreiben",
    "Subscribe to a remote manga and materialize it as a series": "Ein Remote-Manga abonnieren und als Serie materialisieren",
    "Suwayomi runtime status": "Suwayomi-Laufzeitstatus",
    "Sync offline reading sessions": "Offline-Lesesitzungen synchronisieren",
    "Synchronize chapters of a subscribed series": "Kapitel einer abonnierten Serie synchronisieren",
    "Undo an applied match using its snapshot": "Eine angewendete Übereinstimmung anhand ihres Snapshots rückgängig machen",
    "Uninstall a runtime, data is removed only when purge is requested": "Eine Laufzeit deinstallieren, Daten werden nur bei ausdrücklichem Löschwunsch entfernt",
    "Uninstall an extension": "Eine Erweiterung deinstallieren",
    "Unsubscribe a series and remove its placeholder files": "Eine Serie abbestellen und ihre Platzhalterdateien entfernen",
    "Update Suwayomi settings": "Suwayomi-Einstellungen aktualisieren",
    "Update a library": "Eine Bibliothek aktualisieren",
    "Update an installed extension": "Eine installierte Erweiterung aktualisieren",
    "Update book metadata": "Buchmetadaten aktualisieren",
    "Update book metadata in bulk": "Buchmetadaten in großen Mengen aktualisieren",
    "Update collection": "Sammlung aktualisieren",
    "Update current user's password": "Das Passwort des aktuellen Benutzers aktualisieren",
    "Update note, color or marker of my annotation": "Notiz, Farbe oder Markierung meiner Anmerkung aktualisieren",
    "Update one preference of a source and return the refreshed list": "Eine Einstellung einer Quelle aktualisieren und die aktualisierte Liste zurückgeben",
    "Update readlist": "Leseliste aktualisieren",
    "Update readlist read progress (Mihon)": "Lesefortschritt der Leseliste aktualisieren (Mihon)",
    "Update scrape settings, trusting the results requires an explicit confirmation": "Scraping-Einstellungen aktualisieren, das Vertrauen in die Ergebnisse erfordert eine ausdrückliche Bestätigung",
    "Update series metadata": "Serienmetadaten aktualisieren",
    "Update series read progress (Mihon)": "Lesefortschritt der Serie aktualisieren (Mihon)",
    "Update server settings": "Servereinstellungen aktualisieren",
    "Update the username and email of a user": "Den Benutzernamen und die E-Mail eines Benutzers aktualisieren",
    "Update trash settings": "Papierkorb-Einstellungen aktualisieren",
    "Update user": "Benutzer aktualisieren",
    "Update user's password": "Das Passwort eines Benutzers aktualisieren",
    "Upload a package and install it in the background": "Ein Paket hochladen und im Hintergrund installieren",
    "Upload an upgrade package and validate it without touching the running jar": "Ein Upgrade-Paket hochladen und prüfen, ohne das laufende jar zu berühren",
      "Add a scraping source": "Eine Scraping-Quelle hinzufügen",
      "Apply bulk series metadata from a text payload": "Serien-Metadaten aus einem Textinhalt gebündelt anwenden",
      "Authenticate a KOReader user": "Einen KOReader-Benutzer authentifizieren",
      "Browse a library": "Eine Bibliothek durchsuchen",
      "Check Kobo connectivity": "Kobo-Konnektivität prüfen",
      "Count entries awaiting bulk metadata": "Einträge ohne Metadaten zählen",
      "Create an API token": "Ein API-Token erstellen",
      "Delete a scraping source": "Eine Scraping-Quelle löschen",
      "Delete an API token": "Ein API-Token löschen",
      "Download a book file for Kobo": "Eine Buchdatei für Kobo herunterladen",
      "Download the metadata package template": "Die Metadaten-Paketvorlage herunterladen",
      "Enable or disable a scraping source": "Eine Scraping-Quelle aktivieren oder deaktivieren",
      "Exchange a Kobo device token": "Ein Kobo-Gerätetoken austauschen",
      "Export series metadata as a text file": "Serien-Metadaten als Textdatei exportieren",
      "Get Kobo metadata for a book": "Kobo-Metadaten für ein Buch abrufen",
      "Get Kobo reading state for a book": "Kobo-Lesestatus für ein Buch abrufen",
      "Get a book cover for Kobo": "Ein Buchcover für Kobo abrufen",
      "Get a book page image": "Ein Buchseitenbild abrufen",
      "Get a book page thumbnail": "Eine Buchseiten-Vorschau abrufen",
      "Get a book thumbnail": "Eine Buchvorschau abrufen",
      "Get one collection": "Eine Sammlung abrufen",
      "Get one library": "Eine Bibliothek abrufen",
      "Get one reading list": "Eine Leseliste abrufen",
      "Get one series with its books": "Eine Serie mit ihren Büchern abrufen",
      "Get reading progress for a document": "Lesefortschritt für ein Dokument abrufen",
      "Get scraping source details": "Details einer Scraping-Quelle abrufen",
      "Get the OPDS authentication document": "Das OPDS-Authentifizierungsdokument abrufen",
      "Get the OPDS search description": "Die OPDS-Suchbeschreibung abrufen",
      "Get the OPDS v1 catalog": "Den OPDS-v1-Katalog abrufen",
      "Get the OPDS v2 catalog": "Den OPDS-v2-Katalog abrufen",
      "Get the WebPub manifest for a DIVINA book": "Das WebPub-Manifest für ein DIVINA-Buch abrufen",
      "Get the WebPub manifest for a PDF": "Das WebPub-Manifest für ein PDF abrufen",
      "Get the WebPub manifest for a book": "Das WebPub-Manifest für ein Buch abrufen",
      "Get the WebPub manifest for an EPUB": "Das WebPub-Manifest für ein EPUB abrufen",
      "Import a series metadata package": "Ein Serien-Metadatenpaket importieren",
      "Initialize a Kobo device": "Ein Kobo-Gerät initialisieren",
      "List API tokens": "API-Token auflisten",
      "List all series": "Alle Serien auflisten",
      "List available API token scopes": "Verfügbare API-Token-Bereiche auflisten",
      "List books currently being read": "Aktuell gelesene Bücher auflisten",
      "List books to keep reading": "Bücher zum Weiterlesen auflisten",
      "List collections in a library": "Sammlungen in einer Bibliothek auflisten",
      "List entries awaiting bulk metadata": "Einträge ohne Metadaten auflisten",
      "List libraries": "Bibliotheken auflisten",
      "List reading lists": "Leselisten auflisten",
      "List reading lists in a library": "Leselisten in einer Bibliothek auflisten",
      "List recently added books": "Zuletzt hinzugefügte Bücher auflisten",
      "List recently added series": "Zuletzt hinzugefügte Serien auflisten",
      "Proxy unmatched Kobo requests": "Nicht zugeordnete Kobo-Anfragen weiterleiten",
      "Register a KOReader user (disabled)": "Einen KOReader-Benutzer registrieren (deaktiviert)",
      "Search the catalog": "Den Katalog durchsuchen",
      "Submit bulk metadata for application": "Gebündelte Metadaten zur Anwendung übermitteln",
      "Sync the Kobo library": "Die Kobo-Bibliothek synchronisieren",
      "Test a scraping source connection": "Die Verbindung einer Scraping-Quelle testen",
      "Update Kobo reading state for a book": "Den Kobo-Lesestatus für ein Buch aktualisieren",
      "Update a scraping source": "Eine Scraping-Quelle aktualisieren",
      "Update an API token": "Ein API-Token aktualisieren",
      "Update reading progress for a document": "Den Lesefortschritt für ein Dokument aktualisieren",
    "Serve the web interface": "Die Weboberfläche ausliefern",
    "Stream server-sent events": "Servergesendete Ereignisse übertragen",
  };
  B["es"] = {
    "Delete all sync points": "Eliminar todos los puntos de sincronización",
    "Directory listing": "Listado de directorio",
    "List all libraries": "Listar todas las bibliotecas",
    "Add an extension store": "Añadir una tienda de extensiones",
    "Add book poster": "Añadir póster de libro",
    "Add collection poster": "Añadir póster de colección",
    "Add readlist poster": "Añadir póster de lista de lectura",
    "Add series poster": "Añadir póster de serie",
    "Add series, a whole library or every series missing metadata to the queue": "Añadir una serie, una biblioteca completa o todas las series sin metadatos a la cola",
    "Aggregate reading devices from session history": "Agregar dispositivos de lectura a partir del historial de sesiones",
    "Aggregated home payload for clients": "Carga útil de inicio agregada para clientes",
    "Analyze a library": "Analizar una biblioteca",
    "Analyze book": "Analizar libro",
    "Analyze series": "Analizar serie",
    "Analyze transient book": "Analizar libro transitorio",
    "Apply selected fields of a match and keep a rollback snapshot": "Aplicar los campos seleccionados de una coincidencia y conservar una instantánea de reversión",
    "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "Aplicar los campos elegidos de una entrada de cola, devuelve 204 cuando la entrada sale de la cola",
    "Ask the running batch to stop after the current series": "Pedir al lote en ejecución que se detenga después de la serie actual",
    "Available versions and download sources": "Versiones disponibles y fuentes de descarga",
    "Book counts grouped by computed first letter": "Recuento de libros agrupado por inicial calculada",
    "Book cover resized on demand": "Portada del libro redimensionada bajo demanda",
    "Book ids whose computed first letter matches": "ID de libros cuya inicial calculada coincide",
    "Bring back a book previously removed from the library": "Recuperar un libro eliminado previamente de la biblioteca",
    "Bring back a series previously removed from the library": "Recuperar una serie eliminada previamente de la biblioteca",
    "Browse a source by popular or latest, or search inside it": "Explorar una fuente por popularidad o novedad, o buscar dentro de ella",
    "Browse installable extensions": "Explorar extensiones instalables",
    "Calibre availability, version and conversion mode": "Disponibilidad, versión y modo de conversión de Calibre",
    "Change logger levels at runtime, use INHERIT to restore the default": "Cambiar los niveles de registro en tiempo de ejecución, use INHERIT para restaurar el valor predeterminado",
    "Clear backoff and circuit state of a source": "Borrar el retroceso y el estado de circuito de una fuente",
    "Clear queue entries by scope: queued, review, failed or all": "Borrar entradas de la cola por ámbito: en espera, revisión, fallidas o todas",
    "Clear task queue": "Vaciar la cola de tareas",
    "Clear the pinned executable and fall back to the managed installation": "Borrar el ejecutable fijado y volver a la instalación gestionada",
    "Client capability negotiation": "Negociación de capacidades del cliente",
    "Compute the field level difference of a queue entry": "Calcular la diferencia a nivel de campo de una entrada de cola",
    "Convert a single book and queue re-analysis": "Convertir un solo libro y encolar el reanálisis",
    "Convert pending books of a library": "Convertir los libros pendientes de una biblioteca",
    "Count books still waiting for conversion in a library": "Contar los libros que aún esperan conversión en una biblioteca",
    "Counters for the scrape queue and the inbox": "Contadores de la cola de scraping y de la bandeja de entrada",
    "Create API key": "Crear clave de API",
    "Create a library": "Crear una biblioteca",
    "Create a scrape only API token with fixed scopes": "Crear un token de API exclusivo para scraping con ámbitos fijos",
    "Create an annotation with a locator and a text fallback": "Crear una anotación con un localizador y un texto de respaldo",
    "Create collection": "Crear colección",
    "Create readlist": "Crear lista de lectura",
    "Create user": "Crear usuario",
    "Current install progress": "Progreso de instalación actual",
    "Current jar location and whether it can be replaced in place": "Ubicación actual del jar y si se puede reemplazar en el sitio",
    "Delete API key": "Eliminar clave de API",
    "Delete a library": "Eliminar una biblioteca",
    "Delete a single task by id": "Eliminar una sola tarea por ID",
    "Delete all duplicate pages by hash": "Eliminar todas las páginas duplicadas por hash",
    "Delete an API key of a user": "Eliminar una clave de API de un usuario",
    "Delete book file": "Eliminar archivo de libro",
    "Delete book poster": "Eliminar póster del libro",
    "Delete collection": "Eliminar colección",
    "Delete collection poster": "Eliminar póster de la colección",
    "Delete downloaded cache of an online series, chapters remain readable through the source": "Eliminar la caché descargada de una serie en línea, los capítulos siguen siendo legibles a través de la fuente",
    "Delete every finished task record": "Eliminar todos los registros de tareas finalizadas",
    "Delete every package downloaded from the update feed": "Eliminar todos los paquetes descargados del feed de actualizaciones",
    "Delete every queued task; running ones are kept": "Eliminar todas las tareas en cola; las que están en ejecución se conservan",
    "Delete global settings": "Eliminar la configuración global",
    "Delete my annotation": "Eliminar mi anotación",
    "Delete readlist": "Eliminar lista de lectura",
    "Delete readlist poster": "Eliminar póster de la lista de lectura",
    "Delete series files": "Eliminar archivos de la serie",
    "Delete series poster": "Eliminar póster de la serie",
    "Delete specific duplicate page": "Eliminar una página duplicada concreta",
    "Delete user": "Eliminar usuario",
    "Delete user settings": "Eliminar la configuración de usuario",
    "Discard the staged package": "Descartar el paquete preparado",
    "Download CSS file": "Descargar archivo CSS",
    "Download a raw log file": "Descargar un archivo de registro sin procesar",
    "Download a release package, verify it and stage it for install": "Descargar un paquete de versión, verificarlo y prepararlo para instalar",
    "Download an online chapter as a local CBZ and re-analyze it": "Descargar un capítulo en línea como CBZ local y reanalizarlo",
    "Download and install the latest Calibre portable build": "Descargar e instalar la última versión portátil de Calibre",
    "Download and install the latest Suwayomi release": "Descargar e instalar la última versión de Suwayomi",
    "Download book file": "Descargar archivo de libro",
    "Download font file": "Descargar archivo de fuente",
    "Download from the official source and install in the background": "Descargar desde la fuente oficial e instalar en segundo plano",
    "Download readlist": "Descargar lista de lectura",
    "Download series": "Descargar serie",
    "Echo build and runtime information": "Devolver información de compilación y de ejecución",
    "Empty the trash of one or all libraries": "Vaciar la papelera de una o de todas las bibliotecas",
    "Empty trash for a library": "Vaciar la papelera de una biblioteca",
    "Enable or disable a runtime": "Activar o desactivar un entorno de ejecución",
    "Enable or disable a user": "Activar o desactivar un usuario",
    "Environment and dependency check for an external runtime": "Comprobación de entorno y dependencias de un entorno de ejecución externo",
    "Equivalent shell steps for a manual install": "Pasos de shell equivalentes para una instalación manual",
    "Expire one or all sessions of a user": "Caducar una o todas las sesiones de un usuario",
    "Export a page range as zip": "Exportar un rango de páginas como zip",
    "Export audit entries as CSV": "Exportar las entradas de auditoría como CSV",
    "Export the library inventory as JSON": "Exportar el inventario de la biblioteca como JSON",
    "Get Epub resource": "Obtener recurso Epub",
    "Get Lantai reading capabilities": "Obtener las capacidades de lectura de Lantai",
    "Get book details": "Obtener detalles del libro",
    "Get book page image": "Obtener imagen de página del libro",
    "Get book page thumbnail": "Obtener miniatura de página del libro",
    "Get book poster image": "Obtener imagen del póster del libro",
    "Get book progression": "Obtener el progreso del libro",
    "Get book's WebPub manifest": "Obtener el manifiesto WebPub del libro",
    "Get book's WebPub manifest (DiViNa)": "Obtener el manifiesto WebPub del libro (DiViNa)",
    "Get book's WebPub manifest (Epub)": "Obtener el manifiesto WebPub del libro (Epub)",
    "Get book's WebPub manifest (PDF)": "Obtener el manifiesto WebPub del libro (PDF)",
    "Get book's poster image": "Obtener la imagen del póster del libro",
    "Get collection details": "Obtener detalles de la colección",
    "Get collection poster image": "Obtener imagen del póster de la colección",
    "Get collection's poster image": "Obtener la imagen del póster de la colección",
    "Get details for a single library": "Obtener los detalles de una sola biblioteca",
    "Get known duplicate image thumbnail": "Obtener la miniatura de una imagen duplicada conocida",
    "Get next book in readlist": "Obtener el libro siguiente en la lista de lectura",
    "Get next book in series": "Obtener el libro siguiente en la serie",
    "Get previous book in readlist": "Obtener el libro anterior en la lista de lectura",
    "Get previous book in series": "Obtener el libro anterior en la serie",
    "Get raw book page": "Obtener una página sin procesar del libro",
    "Get reading statistics for the current user": "Obtener las estadísticas de lectura del usuario actual",
    "Get readlist details": "Obtener detalles de la lista de lectura",
    "Get readlist poster image": "Obtener imagen del póster de la lista de lectura",
    "Get readlist read progress (Mihon)": "Obtener el progreso de lectura de la lista de lectura (Mihon)",
    "Get readlist's poster image": "Obtener la imagen del póster de la lista de lectura",
    "Get series details": "Obtener detalles de la serie",
    "Get series poster image": "Obtener imagen del póster de la serie",
    "Get series read progress (Mihon)": "Obtener el progreso de lectura de la serie (Mihon)",
    "Get series' poster image": "Obtener la imagen del póster de la serie",
    "Get the archive status of the current user's reading statistics": "Obtener el estado de archivo de las estadísticas de lectura del usuario actual",
    "Get the reading time of one book for the current user": "Obtener el tiempo de lectura de un libro para el usuario actual",
    "Get transient book page": "Obtener página de libro transitorio",
    "Get unknown duplicate image thumbnail": "Obtener la miniatura de una imagen duplicada desconocida",
    "Import a Suwayomi jar manually": "Importar un jar de Suwayomi manualmente",
    "Import a locally downloaded Calibre portable installer": "Importar un instalador portátil de Calibre descargado localmente",
    "Import books": "Importar libros",
    "Include or exclude a source from search and entry points": "Incluir o excluir una fuente de la búsqueda y de los puntos de entrada",
    "Install an extension from an uploaded package": "Instalar una extensión desde un paquete subido",
    "Install an extension from the catalog": "Instalar una extensión desde el catálogo",
    "Latest Calibre portable release": "Última versión portátil de Calibre",
    "Latest Suwayomi release": "Última versión de Suwayomi",
    "List active reading sessions for the current user": "Listar las sesiones de lectura activas del usuario actual",
    "List age ratings": "Listar las clasificaciones por edad",
    "List and search users": "Listar y buscar usuarios",
    "List applied scrape records of the series behind a queue entry": "Listar los registros de scraping aplicados de la serie detrás de una entrada de cola",
    "List assignable roles": "Listar los roles asignables",
    "List authors": "Listar autores",
    "List authors names": "Listar nombres de autores",
    "List authors roles": "Listar roles de autores",
    "List authors' names": "Listar los nombres de los autores",
    "List authors' roles": "Listar los roles de los autores",
    "List available log files": "Listar los archivos de registro disponibles",
    "List book pages": "Listar las páginas del libro",
    "List book posters": "Listar los pósteres del libro",
    "List book tags": "Listar las etiquetas del libro",
    "List book's positions": "Listar las posiciones del libro",
    "List book's readlists": "Listar las listas de lectura del libro",
    "List books": "Listar libros",
    "List books on deck": "Listar los libros en el estante",
    "List books removed from library but still present on disk": "Listar los libros eliminados de la biblioteca pero aún presentes en el disco",
    "List collection's posters": "Listar los pósteres de la colección",
    "List collection's series": "Listar las series de la colección",
    "List collections": "Listar colecciones",
    "List duplicate books": "Listar libros duplicados",
    "List duplicate matches": "Listar coincidencias duplicadas",
    "List extension stores": "Listar tiendas de extensiones",
    "List finished tasks, paged": "Listar las tareas finalizadas, paginado",
    "List font families": "Listar familias de fuentes",
    "List genres": "Listar géneros",
    "List historical events": "Listar eventos históricos",
    "List installed extensions": "Listar las extensiones instaladas",
    "List known duplicates": "Listar los duplicados conocidos",
    "List languages": "Listar idiomas",
    "List latest books": "Listar los libros más recientes",
    "List latest series": "Listar las series más recientes",
    "List loggers and their levels": "Listar los registradores y sus niveles",
    "List match candidates with their current and proposed values": "Listar los candidatos de coincidencia con sus valores actuales y propuestos",
    "List match jobs of this session": "Listar los trabajos de coincidencia de esta sesión",
    "List materialized chapters of a subscription with download state": "Listar los capítulos materializados de una suscripción con su estado de descarga",
    "List my annotations of a book": "Listar mis anotaciones de un libro",
    "List new series": "Listar las series nuevas",
    "List notable entities": "Listar entidades notables",
    "List notable entities kinds": "Listar los tipos de entidades notables",
    "List notable entities names": "Listar los nombres de entidades notables",
    "List online metadata sources and their daily usage": "Listar las fuentes de metadatos en línea y su uso diario",
    "List publishers": "Listar editoriales",
    "List queued and running tasks, paged": "Listar las tareas en cola y en ejecución, paginado",
    "List reading sessions": "Listar las sesiones de lectura",
    "List readlist's books": "Listar los libros de la lista de lectura",
    "List readlist's posters": "Listar los pósteres de la lista de lectura",
    "List readlists": "Listar listas de lectura",
    "List registered OAuth2 providers": "Listar los proveedores OAuth2 registrados",
    "List releases": "Listar las versiones",
    "List series": "Listar series",
    "List series groups": "Listar grupos de series",
    "List series posters": "Listar pósteres de series",
    "List series release dates": "Listar las fechas de publicación de las series",
    "List series release years": "Listar los años de publicación de las series",
    "List series removed from library but still present on disk": "Listar las series eliminadas de la biblioteca pero aún presentes en el disco",
    "List series tags": "Listar las etiquetas de las series",
    "List series' books": "Listar los libros de la serie",
    "List series' collections": "Listar las colecciones de la serie",
    "List sharing labels": "Listar las etiquetas de uso compartido",
    "List sources with their search/entry toggle state": "Listar las fuentes con su estado de activación para búsqueda y entrada",
    "List subscribed online mangas": "Listar los mangas en línea suscritos",
    "List tags": "Listar etiquetas",
    "List the API keys of a user": "Listar las claves de API de un usuario",
    "List the actions present in the audit trail": "Listar las acciones presentes en la pista de auditoría",
    "List the active sessions of a user": "Listar las sesiones activas de un usuario",
    "List the books of the series behind a queue entry with their current metadata": "Listar los libros de la serie detrás de una entrada de cola con sus metadatos actuales",
    "List the runtime filters exposed by a source": "Listar los filtros de ejecución expuestos por una fuente",
    "List the scrape only API tokens": "Listar los tokens de API exclusivos para scraping",
    "List the sources provided by an extension": "Listar las fuentes proporcionadas por una extensión",
    "List trash entries of one or all libraries": "Listar las entradas de la papelera de una o de todas las bibliotecas",
    "List unknown duplicates": "Listar los duplicados desconocidos",
    "List updated series": "Listar las series actualizadas",
    "List users": "Listar usuarios",
    "Mark a match as skipped": "Marcar una coincidencia como omitida",
    "Mark announcements as read": "Marcar los anuncios como leídos",
    "Mark book as unread": "Marcar el libro como no leído",
    "Mark book poster as selected": "Marcar el póster del libro como seleccionado",
    "Mark book progression": "Registrar el progreso del libro",
    "Mark book's read progress": "Registrar el progreso de lectura del libro",
    "Mark collection poster as selected": "Marcar el póster de la colección como seleccionado",
    "Mark duplicate page as known": "Marcar una página duplicada como conocida",
    "Mark readlist poster as selected": "Marcar el póster de la lista de lectura como seleccionado",
    "Mark series as read": "Marcar la serie como leída",
    "Mark series as unread": "Marcar la serie como no leída",
    "Mark series poster as selected": "Marcar el póster de la serie como seleccionado",
    "Match ComicRack list": "Emparejar una lista de ComicRack",
    "Match a single series against enabled sources": "Emparejar una sola serie con las fuentes habilitadas",
    "Packages already downloaded into the config directory": "Paquetes ya descargados en el directorio de configuración",
    "Per-library series/book counts and total size": "Recuento de series/libros y tamaño total por biblioteca",
    "Permanently delete a trash entry and its files": "Eliminar permanentemente una entrada de la papelera y sus archivos",
    "Pin an existing ebook-convert executable": "Fijar un ejecutable ebook-convert existente",
    "Poll a match job": "Consultar un trabajo de coincidencia",
    "Probe an ebook-convert executable without saving it": "Sondear un ejecutable ebook-convert sin guardarlo",
    "Probe every external runtime managed by Lantai": "Sondear todos los entornos de ejecución externos gestionados por Lantai",
    "Probe one external runtime": "Sondear un entorno de ejecución externo",
    "Progress of the package download currently running": "Progreso de la descarga de paquete en curso",
    "Progress of the package validation currently running": "Progreso de la validación de paquete en curso",
    "Proxy the remote cover of a search result": "Servir de proxy para la portada remota de un resultado de búsqueda",
    "Purge reading sessions beyond the retention window": "Purgar las sesiones de lectura más allá de la ventana de retención",
    "Re-verify the most recent downloaded package": "Verificar de nuevo el paquete descargado más reciente",
    "Read Suwayomi settings": "Leer la configuración de Suwayomi",
    "Read bootstrap payload": "Leer la carga útil de arranque",
    "Read chapter bundle": "Leer el paquete de capítulos",
    "Read chapter outline page": "Leer la página de esquema del capítulo",
    "Read chapter window": "Leer la ventana de capítulo",
    "Read image resource": "Leer recurso de imagen",
    "Read one page of the global scrape queue": "Leer una página de la cola global de scraping",
    "Read one page of the scrape queue for an external agent": "Leer una página de la cola de scraping para un agente externo",
    "Read one queue entry with the hints an external agent needs": "Leer una entrada de cola con las pistas que necesita un agente externo",
    "Read page thumbnail": "Leer la miniatura de la página",
    "Read scrape settings": "Leer la configuración de scraping",
    "Read structured log entries, cursor based": "Leer las entradas de registro estructuradas, basadas en cursor",
    "Read the configurable preferences of a source": "Leer las preferencias configurables de una fuente",
    "Read the remote detail of a single manga": "Leer el detalle remoto de un solo manga",
    "Read the update feed and report the latest release plus any downloaded package": "Leer el feed de actualizaciones e informar de la última versión y de cualquier paquete descargado",
    "Read trash settings": "Leer la configuración de la papelera",
    "Rebuild the daily reading statistics of the current user": "Reconstruir las estadísticas diarias de lectura del usuario actual",
    "Recompute first letters for every series": "Recalcular las iniciales de todas las series",
    "Refresh book metadata": "Refrescar los metadatos del libro",
    "Refresh installed extension metadata": "Refrescar los metadatos de las extensiones instaladas",
    "Refresh metadata for a library": "Refrescar los metadatos de una biblioteca",
    "Refresh series metadata": "Refrescar los metadatos de la serie",
    "Regenerate books posters": "Regenerar los pósteres de los libros",
    "Remove a book from the library, keeping its file untouched": "Quitar un libro de la biblioteca sin tocar su archivo",
    "Remove a series from the library, keeping its files untouched": "Quitar una serie de la biblioteca sin tocar sus archivos",
    "Remove a single queue entry": "Quitar una sola entrada de la cola",
    "Replace the roles of a user": "Reemplazar los roles de un usuario",
    "Replace the running jar with the staged one and restart": "Reemplazar el jar en ejecución por el preparado y reiniciar",
    "Report a reading heartbeat": "Informar de un latido de lectura",
    "Reset a queue entry back to queued": "Restablecer una entrada de cola a en espera",
    "Reset the password of a user and expire its sessions": "Restablecer la contraseña de un usuario y caducar sus sesiones",
    "Restore a trash entry back to its library": "Restaurar una entrada de la papelera a su biblioteca",
    "Restore logger levels to their default": "Restaurar los niveles de registro predeterminados",
    "Restore the last backup jar and restart": "Restaurar el último jar de copia de seguridad y reiniciar",
    "Resume task processing": "Reanudar el procesamiento de tareas",
    "Retrieve API keys": "Recuperar las claves de API",
    "Retrieve a single audit entry": "Recuperar una sola entrada de auditoría",
    "Retrieve a user with its activity summary": "Recuperar un usuario con su resumen de actividad",
    "Retrieve announcements": "Recuperar los anuncios",
    "Retrieve authentication activity": "Recuperar la actividad de autenticación",
    "Retrieve authentication activity for the current user": "Recuperar la actividad de autenticación del usuario actual",
    "Retrieve current user": "Recuperar el usuario actual",
    "Retrieve global client settings": "Recuperar la configuración global del cliente",
    "Retrieve latest authentication activity for a user": "Recuperar la última actividad de autenticación de un usuario",
    "Retrieve server settings": "Recuperar la configuración del servidor",
    "Retrieve user client settings": "Recuperar la configuración de cliente del usuario",
    "Revoke a scrape only API token": "Revocar un token de API exclusivo para scraping",
    "Rotate the managed Suwayomi credentials": "Rotar las credenciales gestionadas de Suwayomi",
    "Run the automatic install for the given runtimes": "Ejecutar la instalación automática de los entornos de ejecución indicados",
    "Runtime and storage status": "Estado del entorno de ejecución y del almacenamiento",
    "Save global settings": "Guardar la configuración global",
    "Save user settings": "Guardar la configuración de usuario",
    "Scan a library": "Escanear una biblioteca",
    "Scan folder for transient books": "Escanear la carpeta en busca de libros transitorios",
    "Search audit entries": "Buscar en las entradas de auditoría",
    "Search inside a book": "Buscar dentro de un libro",
    "Search mangas across enabled sources": "Buscar mangas en las fuentes habilitadas",
    "Search metadata candidates from one or all enabled sources": "Buscar candidatos de metadatos en una o en todas las fuentes habilitadas",
    "Series ids whose computed first letter matches": "ID de series cuya inicial calculada coincide",
    "Set cookie": "Establecer cookie",
    "Set the conversion mode: never, on-demand or eager": "Establecer el modo de conversión: nunca, bajo demanda o inmediato",
    "Set the trash quota of a library, zero means inheriting the global default": "Establecer la cuota de papelera de una biblioteca, cero significa heredar el valor global",
    "Snapshot of the automatic install performed at startup": "Instantánea de la instalación automática realizada al arrancar",
    "Snapshot the main and tasks SQLite databases": "Instantánea de las bases de datos SQLite principal y de tareas",
    "Start a background auto-match job for a library": "Iniciar un trabajo de coincidencia automática en segundo plano para una biblioteca",
    "Start an external runtime": "Iniciar un entorno de ejecución externo",
    "Start or resume scraping the queue": "Iniciar o reanudar el scraping de la cola",
    "Start, stop or restart Suwayomi": "Iniciar, detener o reiniciar Suwayomi",
    "Stop an external runtime": "Detener un entorno de ejecución externo",
    "Stop taking new tasks; running tasks finish": "Dejar de aceptar tareas nuevas; las que están en ejecución terminan",
    "Stop the current reading session": "Detener la sesión de lectura actual",
    "Stream new log entries": "Transmitir las nuevas entradas de registro",
    "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "Enviar un resultado de scraping para una entrada de cola, o pasar un ID de serie para sobrescribir un resultado ya aplicado",
    "Subscribe to a remote manga and materialize it as a series": "Suscribirse a un manga remoto y materializarlo como serie",
    "Suwayomi runtime status": "Estado del entorno de ejecución de Suwayomi",
    "Sync offline reading sessions": "Sincronizar las sesiones de lectura sin conexión",
    "Synchronize chapters of a subscribed series": "Sincronizar los capítulos de una serie suscrita",
    "Undo an applied match using its snapshot": "Deshacer una coincidencia aplicada usando su instantánea",
    "Uninstall a runtime, data is removed only when purge is requested": "Desinstalar un entorno de ejecución, los datos solo se eliminan cuando se solicita la purga",
    "Uninstall an extension": "Desinstalar una extensión",
    "Unsubscribe a series and remove its placeholder files": "Cancelar la suscripción de una serie y eliminar sus archivos de marcador de posición",
    "Update Suwayomi settings": "Actualizar la configuración de Suwayomi",
    "Update a library": "Actualizar una biblioteca",
    "Update an installed extension": "Actualizar una extensión instalada",
    "Update book metadata": "Actualizar los metadatos del libro",
    "Update book metadata in bulk": "Actualizar los metadatos de los libros en lote",
    "Update collection": "Actualizar colección",
    "Update current user's password": "Actualizar la contraseña del usuario actual",
    "Update note, color or marker of my annotation": "Actualizar la nota, el color o el marcador de mi anotación",
    "Update one preference of a source and return the refreshed list": "Actualizar una preferencia de una fuente y devolver la lista actualizada",
    "Update readlist": "Actualizar lista de lectura",
    "Update readlist read progress (Mihon)": "Actualizar el progreso de lectura de la lista de lectura (Mihon)",
    "Update scrape settings, trusting the results requires an explicit confirmation": "Actualizar la configuración de scraping, confiar en los resultados requiere una confirmación explícita",
    "Update series metadata": "Actualizar los metadatos de la serie",
    "Update series read progress (Mihon)": "Actualizar el progreso de lectura de la serie (Mihon)",
    "Update server settings": "Actualizar la configuración del servidor",
    "Update the username and email of a user": "Actualizar el nombre de usuario y el correo electrónico de un usuario",
    "Update trash settings": "Actualizar la configuración de la papelera",
    "Update user": "Actualizar usuario",
    "Update user's password": "Actualizar la contraseña de un usuario",
    "Upload a package and install it in the background": "Subir un paquete e instalarlo en segundo plano",
    "Upload an upgrade package and validate it without touching the running jar": "Subir un paquete de actualización y validarlo sin tocar el jar en ejecución",
      "Add a scraping source": "Añadir una fuente de scraping",
      "Apply bulk series metadata from a text payload": "Aplicar metadatos de series en lote desde un texto",
      "Authenticate a KOReader user": "Autenticar a un usuario de KOReader",
      "Browse a library": "Explorar una biblioteca",
      "Check Kobo connectivity": "Comprobar la conectividad de Kobo",
      "Count entries awaiting bulk metadata": "Contar las entradas pendientes de metadatos",
      "Create an API token": "Crear un token de API",
      "Delete a scraping source": "Eliminar una fuente de scraping",
      "Delete an API token": "Eliminar un token de API",
      "Download a book file for Kobo": "Descargar un archivo de libro para Kobo",
      "Download the metadata package template": "Descargar la plantilla del paquete de metadatos",
      "Enable or disable a scraping source": "Activar o desactivar una fuente de scraping",
      "Exchange a Kobo device token": "Intercambiar un token de dispositivo Kobo",
      "Export series metadata as a text file": "Exportar los metadatos de series como archivo de texto",
      "Get Kobo metadata for a book": "Obtener los metadatos de Kobo de un libro",
      "Get Kobo reading state for a book": "Obtener el estado de lectura de Kobo de un libro",
      "Get a book cover for Kobo": "Obtener una portada de libro para Kobo",
      "Get a book page image": "Obtener la imagen de una página de libro",
      "Get a book page thumbnail": "Obtener la miniatura de una página de libro",
      "Get a book thumbnail": "Obtener la miniatura de un libro",
      "Get one collection": "Obtener una colección",
      "Get one library": "Obtener una biblioteca",
      "Get one reading list": "Obtener una lista de lectura",
      "Get one series with its books": "Obtener una serie con sus libros",
      "Get reading progress for a document": "Obtener el progreso de lectura de un documento",
      "Get scraping source details": "Obtener los detalles de una fuente de scraping",
      "Get the OPDS authentication document": "Obtener el documento de autenticación de OPDS",
      "Get the OPDS search description": "Obtener la descripción de búsqueda de OPDS",
      "Get the OPDS v1 catalog": "Obtener el catálogo OPDS v1",
      "Get the OPDS v2 catalog": "Obtener el catálogo OPDS v2",
      "Get the WebPub manifest for a DIVINA book": "Obtener el manifiesto WebPub de un libro DIVINA",
      "Get the WebPub manifest for a PDF": "Obtener el manifiesto WebPub de un PDF",
      "Get the WebPub manifest for a book": "Obtener el manifiesto WebPub de un libro",
      "Get the WebPub manifest for an EPUB": "Obtener el manifiesto WebPub de un EPUB",
      "Import a series metadata package": "Importar un paquete de metadatos de series",
      "Initialize a Kobo device": "Inicializar un dispositivo Kobo",
      "List API tokens": "Listar los tokens de API",
      "List all series": "Listar todas las series",
      "List available API token scopes": "Listar los ámbitos de token de API disponibles",
      "List books currently being read": "Listar los libros en lectura",
      "List books to keep reading": "Listar los libros para seguir leyendo",
      "List collections in a library": "Listar las colecciones de una biblioteca",
      "List entries awaiting bulk metadata": "Listar las entradas pendientes de metadatos",
      "List libraries": "Listar las bibliotecas",
      "List reading lists": "Listar las listas de lectura",
      "List reading lists in a library": "Listar las listas de lectura de una biblioteca",
      "List recently added books": "Listar los libros añadidos recientemente",
      "List recently added series": "Listar las series añadidas recientemente",
      "Proxy unmatched Kobo requests": "Redirigir las solicitudes de Kobo no reconocidas",
      "Register a KOReader user (disabled)": "Registrar un usuario de KOReader (desactivado)",
      "Search the catalog": "Buscar en el catálogo",
      "Submit bulk metadata for application": "Enviar metadatos en lote para su aplicación",
      "Sync the Kobo library": "Sincronizar la biblioteca de Kobo",
      "Test a scraping source connection": "Probar la conexión de una fuente de scraping",
      "Update Kobo reading state for a book": "Actualizar el estado de lectura de Kobo de un libro",
      "Update a scraping source": "Actualizar una fuente de scraping",
      "Update an API token": "Actualizar un token de API",
      "Update reading progress for a document": "Actualizar el progreso de lectura de un documento",
    "Serve the web interface": "Servir la interfaz web",
    "Stream server-sent events": "Transmitir eventos del servidor",
  };
  B["it"] = {
    "Delete all sync points": "Elimina tutti i punti di sincronizzazione",
    "Directory listing": "Elenco della directory",
    "List all libraries": "Elenca tutte le librerie",
    "Add an extension store": "Aggiungi uno store di estensioni",
    "Add book poster": "Aggiungi poster del libro",
    "Add collection poster": "Aggiungi poster della raccolta",
    "Add readlist poster": "Aggiungi poster della lista di lettura",
    "Add series poster": "Aggiungi poster della serie",
    "Add series, a whole library or every series missing metadata to the queue": "Aggiungi una serie, un'intera libreria o tutte le serie senza metadati alla coda",
    "Aggregate reading devices from session history": "Aggrega i dispositivi di lettura dalla cronologia delle sessioni",
    "Aggregated home payload for clients": "Payload aggregato della home per i client",
    "Analyze a library": "Analizza una libreria",
    "Analyze book": "Analizza libro",
    "Analyze series": "Analizza serie",
    "Analyze transient book": "Analizza libro temporaneo",
    "Apply selected fields of a match and keep a rollback snapshot": "Applica i campi selezionati di una corrispondenza e conserva un'istantanea di rollback",
    "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "Applica i campi scelti di una voce della coda, restituisce 204 quando la voce esce dalla coda",
    "Ask the running batch to stop after the current series": "Chiedi al batch in esecuzione di fermarsi dopo la serie corrente",
    "Available versions and download sources": "Versioni disponibili e origini di download",
    "Book counts grouped by computed first letter": "Conteggio dei libri raggruppato per iniziale calcolata",
    "Book cover resized on demand": "Copertina del libro ridimensionata su richiesta",
    "Book ids whose computed first letter matches": "ID dei libri la cui iniziale calcolata corrisponde",
    "Bring back a book previously removed from the library": "Ripristina un libro precedentemente rimosso dalla libreria",
    "Bring back a series previously removed from the library": "Ripristina una serie precedentemente rimossa dalla libreria",
    "Browse a source by popular or latest, or search inside it": "Sfoglia una fonte per popolarità o novità, oppure cerca al suo interno",
    "Browse installable extensions": "Sfoglia le estensioni installabili",
    "Calibre availability, version and conversion mode": "Disponibilità, versione e modalità di conversione di Calibre",
    "Change logger levels at runtime, use INHERIT to restore the default": "Cambia i livelli dei logger a runtime, usa INHERIT per ripristinare il valore predefinito",
    "Clear backoff and circuit state of a source": "Azzera il backoff e lo stato del circuito di una fonte",
    "Clear queue entries by scope: queued, review, failed or all": "Svuota le voci della coda per ambito: in attesa, revisione, non riuscite o tutte",
    "Clear task queue": "Svuota la coda delle attività",
    "Clear the pinned executable and fall back to the managed installation": "Rimuovi l'eseguibile bloccato e torna all'installazione gestita",
    "Client capability negotiation": "Negoziazione delle capacità del client",
    "Compute the field level difference of a queue entry": "Calcola la differenza a livello di campo di una voce della coda",
    "Convert a single book and queue re-analysis": "Converti un singolo libro e accoda la ri-analisi",
    "Convert pending books of a library": "Converti i libri in sospeso di una libreria",
    "Count books still waiting for conversion in a library": "Conta i libri ancora in attesa di conversione in una libreria",
    "Counters for the scrape queue and the inbox": "Contatori per la coda di scraping e la casella di posta",
    "Create API key": "Crea chiave API",
    "Create a library": "Crea una libreria",
    "Create a scrape only API token with fixed scopes": "Crea un token API solo scraping con ambiti fissi",
    "Create an annotation with a locator and a text fallback": "Crea un'annotazione con un localizzatore e un testo di riserva",
    "Create collection": "Crea raccolta",
    "Create readlist": "Crea lista di lettura",
    "Create user": "Crea utente",
    "Current install progress": "Avanzamento dell'installazione corrente",
    "Current jar location and whether it can be replaced in place": "Posizione attuale del jar e possibilità di sostituirlo sul posto",
    "Delete API key": "Elimina chiave API",
    "Delete a library": "Elimina una libreria",
    "Delete a single task by id": "Elimina una singola attività per ID",
    "Delete all duplicate pages by hash": "Elimina tutte le pagine duplicate per hash",
    "Delete an API key of a user": "Elimina una chiave API di un utente",
    "Delete book file": "Elimina file del libro",
    "Delete book poster": "Elimina poster del libro",
    "Delete collection": "Elimina raccolta",
    "Delete collection poster": "Elimina poster della raccolta",
    "Delete downloaded cache of an online series, chapters remain readable through the source": "Elimina la cache scaricata di una serie online, i capitoli restano leggibili tramite la fonte",
    "Delete every finished task record": "Elimina tutti i record delle attività completate",
    "Delete every package downloaded from the update feed": "Elimina tutti i pacchetti scaricati dal feed di aggiornamento",
    "Delete every queued task; running ones are kept": "Elimina tutte le attività in coda; quelle in esecuzione vengono mantenute",
    "Delete global settings": "Elimina le impostazioni globali",
    "Delete my annotation": "Elimina la mia annotazione",
    "Delete readlist": "Elimina lista di lettura",
    "Delete readlist poster": "Elimina poster della lista di lettura",
    "Delete series files": "Elimina i file della serie",
    "Delete series poster": "Elimina poster della serie",
    "Delete specific duplicate page": "Elimina una pagina duplicata specifica",
    "Delete user": "Elimina utente",
    "Delete user settings": "Elimina le impostazioni utente",
    "Discard the staged package": "Scarta il pacchetto preparato",
    "Download CSS file": "Scarica file CSS",
    "Download a raw log file": "Scarica un file di log grezzo",
    "Download a release package, verify it and stage it for install": "Scarica un pacchetto di rilascio, verificalo e preparalo per l'installazione",
    "Download an online chapter as a local CBZ and re-analyze it": "Scarica un capitolo online come CBZ locale e ri-analizzalo",
    "Download and install the latest Calibre portable build": "Scarica e installa l'ultima build portatile di Calibre",
    "Download and install the latest Suwayomi release": "Scarica e installa l'ultima release di Suwayomi",
    "Download book file": "Scarica file del libro",
    "Download font file": "Scarica file del font",
    "Download from the official source and install in the background": "Scarica dalla fonte ufficiale e installa in background",
    "Download readlist": "Scarica lista di lettura",
    "Download series": "Scarica serie",
    "Echo build and runtime information": "Restituisci le informazioni di build e runtime",
    "Empty the trash of one or all libraries": "Svuota il cestino di una o di tutte le librerie",
    "Empty trash for a library": "Svuota il cestino di una libreria",
    "Enable or disable a runtime": "Abilita o disabilita un runtime",
    "Enable or disable a user": "Abilita o disabilita un utente",
    "Environment and dependency check for an external runtime": "Verifica di ambiente e dipendenze per un runtime esterno",
    "Equivalent shell steps for a manual install": "Passaggi shell equivalenti per un'installazione manuale",
    "Expire one or all sessions of a user": "Fai scadere una o tutte le sessioni di un utente",
    "Export a page range as zip": "Esporta un intervallo di pagine come zip",
    "Export audit entries as CSV": "Esporta le voci di audit come CSV",
    "Export the library inventory as JSON": "Esporta l'inventario della libreria come JSON",
    "Get Epub resource": "Ottieni risorsa Epub",
    "Get Lantai reading capabilities": "Ottieni le capacità di lettura di Lantai",
    "Get book details": "Ottieni i dettagli del libro",
    "Get book page image": "Ottieni l'immagine di una pagina del libro",
    "Get book page thumbnail": "Ottieni la miniatura di una pagina del libro",
    "Get book poster image": "Ottieni l'immagine del poster del libro",
    "Get book progression": "Ottieni l'avanzamento del libro",
    "Get book's WebPub manifest": "Ottieni il manifest WebPub del libro",
    "Get book's WebPub manifest (DiViNa)": "Ottieni il manifest WebPub del libro (DiViNa)",
    "Get book's WebPub manifest (Epub)": "Ottieni il manifest WebPub del libro (Epub)",
    "Get book's WebPub manifest (PDF)": "Ottieni il manifest WebPub del libro (PDF)",
    "Get book's poster image": "Ottieni l'immagine del poster del libro",
    "Get collection details": "Ottieni i dettagli della raccolta",
    "Get collection poster image": "Ottieni l'immagine del poster della raccolta",
    "Get collection's poster image": "Ottieni l'immagine del poster della raccolta",
    "Get details for a single library": "Ottieni i dettagli di una singola libreria",
    "Get known duplicate image thumbnail": "Ottieni la miniatura di un'immagine duplicata nota",
    "Get next book in readlist": "Ottieni il libro successivo nella lista di lettura",
    "Get next book in series": "Ottieni il libro successivo nella serie",
    "Get previous book in readlist": "Ottieni il libro precedente nella lista di lettura",
    "Get previous book in series": "Ottieni il libro precedente nella serie",
    "Get raw book page": "Ottieni una pagina grezza del libro",
    "Get reading statistics for the current user": "Ottieni le statistiche di lettura dell'utente corrente",
    "Get readlist details": "Ottieni i dettagli della lista di lettura",
    "Get readlist poster image": "Ottieni l'immagine del poster della lista di lettura",
    "Get readlist read progress (Mihon)": "Ottieni l'avanzamento di lettura della lista di lettura (Mihon)",
    "Get readlist's poster image": "Ottieni l'immagine del poster della lista di lettura",
    "Get series details": "Ottieni i dettagli della serie",
    "Get series poster image": "Ottieni l'immagine del poster della serie",
    "Get series read progress (Mihon)": "Ottieni l'avanzamento di lettura della serie (Mihon)",
    "Get series' poster image": "Ottieni l'immagine del poster della serie",
    "Get the archive status of the current user's reading statistics": "Ottieni lo stato di archiviazione delle statistiche di lettura dell'utente corrente",
    "Get the reading time of one book for the current user": "Ottieni il tempo di lettura di un libro per l'utente corrente",
    "Get transient book page": "Ottieni una pagina di un libro temporaneo",
    "Get unknown duplicate image thumbnail": "Ottieni la miniatura di un'immagine duplicata sconosciuta",
    "Import a Suwayomi jar manually": "Importa manualmente un jar di Suwayomi",
    "Import a locally downloaded Calibre portable installer": "Importa un installer portatile di Calibre scaricato localmente",
    "Import books": "Importa libri",
    "Include or exclude a source from search and entry points": "Includi o escludi una fonte dalla ricerca e dai punti di ingresso",
    "Install an extension from an uploaded package": "Installa un'estensione da un pacchetto caricato",
    "Install an extension from the catalog": "Installa un'estensione dal catalogo",
    "Latest Calibre portable release": "Ultima release portatile di Calibre",
    "Latest Suwayomi release": "Ultima release di Suwayomi",
    "List active reading sessions for the current user": "Elenca le sessioni di lettura attive dell'utente corrente",
    "List age ratings": "Elenca le classificazioni per età",
    "List and search users": "Elenca e cerca utenti",
    "List applied scrape records of the series behind a queue entry": "Elenca i record di scraping applicati della serie dietro una voce della coda",
    "List assignable roles": "Elenca i ruoli assegnabili",
    "List authors": "Elenca gli autori",
    "List authors names": "Elenca i nomi degli autori",
    "List authors roles": "Elenca i ruoli degli autori",
    "List authors' names": "Elenca i nomi degli autori",
    "List authors' roles": "Elenca i ruoli degli autori",
    "List available log files": "Elenca i file di log disponibili",
    "List book pages": "Elenca le pagine del libro",
    "List book posters": "Elenca i poster del libro",
    "List book tags": "Elenca i tag del libro",
    "List book's positions": "Elenca le posizioni del libro",
    "List book's readlists": "Elenca le liste di lettura del libro",
    "List books": "Elenca i libri",
    "List books on deck": "Elenca i libri sul deck",
    "List books removed from library but still present on disk": "Elenca i libri rimossi dalla libreria ma ancora presenti sul disco",
    "List collection's posters": "Elenca i poster della raccolta",
    "List collection's series": "Elenca le serie della raccolta",
    "List collections": "Elenca le raccolte",
    "List duplicate books": "Elenca i libri duplicati",
    "List duplicate matches": "Elenca le corrispondenze duplicate",
    "List extension stores": "Elenca gli store di estensioni",
    "List finished tasks, paged": "Elenca le attività completate, paginato",
    "List font families": "Elenca le famiglie di font",
    "List genres": "Elenca i generi",
    "List historical events": "Elenca gli eventi storici",
    "List installed extensions": "Elenca le estensioni installate",
    "List known duplicates": "Elenca i duplicati noti",
    "List languages": "Elenca le lingue",
    "List latest books": "Elenca i libri più recenti",
    "List latest series": "Elenca le serie più recenti",
    "List loggers and their levels": "Elenca i logger e i loro livelli",
    "List match candidates with their current and proposed values": "Elenca i candidati di corrispondenza con i valori attuali e proposti",
    "List match jobs of this session": "Elenca i lavori di corrispondenza di questa sessione",
    "List materialized chapters of a subscription with download state": "Elenca i capitoli materializzati di un abbonamento con lo stato di download",
    "List my annotations of a book": "Elenca le mie annotazioni di un libro",
    "List new series": "Elenca le nuove serie",
    "List notable entities": "Elenca le entità notevoli",
    "List notable entities kinds": "Elenca i tipi di entità notevoli",
    "List notable entities names": "Elenca i nomi delle entità notevoli",
    "List online metadata sources and their daily usage": "Elenca le fonti di metadati online e il loro utilizzo giornaliero",
    "List publishers": "Elenca gli editori",
    "List queued and running tasks, paged": "Elenca le attività in coda e in esecuzione, paginato",
    "List reading sessions": "Elenca le sessioni di lettura",
    "List readlist's books": "Elenca i libri della lista di lettura",
    "List readlist's posters": "Elenca i poster della lista di lettura",
    "List readlists": "Elenca le liste di lettura",
    "List registered OAuth2 providers": "Elenca i provider OAuth2 registrati",
    "List releases": "Elenca le release",
    "List series": "Elenca le serie",
    "List series groups": "Elenca i gruppi di serie",
    "List series posters": "Elenca i poster delle serie",
    "List series release dates": "Elenca le date di uscita delle serie",
    "List series release years": "Elenca gli anni di uscita delle serie",
    "List series removed from library but still present on disk": "Elenca le serie rimosse dalla libreria ma ancora presenti sul disco",
    "List series tags": "Elenca i tag delle serie",
    "List series' books": "Elenca i libri della serie",
    "List series' collections": "Elenca le raccolte della serie",
    "List sharing labels": "Elenca le etichette di condivisione",
    "List sources with their search/entry toggle state": "Elenca le fonti con il loro stato di attivazione per ricerca e ingresso",
    "List subscribed online mangas": "Elenca i manga online sottoscritti",
    "List tags": "Elenca i tag",
    "List the API keys of a user": "Elenca le chiavi API di un utente",
    "List the actions present in the audit trail": "Elenca le azioni presenti nella traccia di audit",
    "List the active sessions of a user": "Elenca le sessioni attive di un utente",
    "List the books of the series behind a queue entry with their current metadata": "Elenca i libri della serie dietro una voce della coda con i loro metadati attuali",
    "List the runtime filters exposed by a source": "Elenca i filtri runtime esposti da una fonte",
    "List the scrape only API tokens": "Elenca i token API solo scraping",
    "List the sources provided by an extension": "Elenca le fonti fornite da un'estensione",
    "List trash entries of one or all libraries": "Elenca le voci del cestino di una o di tutte le librerie",
    "List unknown duplicates": "Elenca i duplicati sconosciuti",
    "List updated series": "Elenca le serie aggiornate",
    "List users": "Elenca gli utenti",
    "Mark a match as skipped": "Contrassegna una corrispondenza come saltata",
    "Mark announcements as read": "Contrassegna gli annunci come letti",
    "Mark book as unread": "Contrassegna il libro come non letto",
    "Mark book poster as selected": "Contrassegna il poster del libro come selezionato",
    "Mark book progression": "Registra l'avanzamento del libro",
    "Mark book's read progress": "Registra l'avanzamento di lettura del libro",
    "Mark collection poster as selected": "Contrassegna il poster della raccolta come selezionato",
    "Mark duplicate page as known": "Contrassegna una pagina duplicata come nota",
    "Mark readlist poster as selected": "Contrassegna il poster della lista di lettura come selezionato",
    "Mark series as read": "Contrassegna la serie come letta",
    "Mark series as unread": "Contrassegna la serie come non letta",
    "Mark series poster as selected": "Contrassegna il poster della serie come selezionato",
    "Match ComicRack list": "Abbina una lista ComicRack",
    "Match a single series against enabled sources": "Abbina una singola serie alle fonti abilitate",
    "Packages already downloaded into the config directory": "Pacchetti già scaricati nella directory di configurazione",
    "Per-library series/book counts and total size": "Conteggio serie/libri e dimensione totale per libreria",
    "Permanently delete a trash entry and its files": "Elimina definitivamente una voce del cestino e i suoi file",
    "Pin an existing ebook-convert executable": "Fissa un eseguibile ebook-convert esistente",
    "Poll a match job": "Interroga un lavoro di corrispondenza",
    "Probe an ebook-convert executable without saving it": "Sonda un eseguibile ebook-convert senza salvarlo",
    "Probe every external runtime managed by Lantai": "Sonda tutti i runtime esterni gestiti da Lantai",
    "Probe one external runtime": "Sonda un runtime esterno",
    "Progress of the package download currently running": "Avanzamento del download del pacchetto in corso",
    "Progress of the package validation currently running": "Avanzamento della validazione del pacchetto in corso",
    "Proxy the remote cover of a search result": "Fai da proxy per la copertina remota di un risultato di ricerca",
    "Purge reading sessions beyond the retention window": "Elimina le sessioni di lettura oltre la finestra di conservazione",
    "Re-verify the most recent downloaded package": "Verifica di nuovo il pacchetto scaricato più di recente",
    "Read Suwayomi settings": "Leggi le impostazioni di Suwayomi",
    "Read bootstrap payload": "Leggi il payload di bootstrap",
    "Read chapter bundle": "Leggi il bundle dei capitoli",
    "Read chapter outline page": "Leggi la pagina di outline del capitolo",
    "Read chapter window": "Leggi la finestra del capitolo",
    "Read image resource": "Leggi la risorsa immagine",
    "Read one page of the global scrape queue": "Leggi una pagina della coda di scraping globale",
    "Read one page of the scrape queue for an external agent": "Leggi una pagina della coda di scraping per un agente esterno",
    "Read one queue entry with the hints an external agent needs": "Leggi una voce della coda con i suggerimenti necessari a un agente esterno",
    "Read page thumbnail": "Leggi la miniatura della pagina",
    "Read scrape settings": "Leggi le impostazioni di scraping",
    "Read structured log entries, cursor based": "Leggi le voci di log strutturate, basate su cursore",
    "Read the configurable preferences of a source": "Leggi le preferenze configurabili di una fonte",
    "Read the remote detail of a single manga": "Leggi il dettaglio remoto di un singolo manga",
    "Read the update feed and report the latest release plus any downloaded package": "Leggi il feed di aggiornamento e riporta l'ultima release e ogni pacchetto scaricato",
    "Read trash settings": "Leggi le impostazioni del cestino",
    "Rebuild the daily reading statistics of the current user": "Ricostruisci le statistiche di lettura giornaliere dell'utente corrente",
    "Recompute first letters for every series": "Ricalcola le iniziali di ogni serie",
    "Refresh book metadata": "Ricarica i metadati del libro",
    "Refresh installed extension metadata": "Ricarica i metadati delle estensioni installate",
    "Refresh metadata for a library": "Ricarica i metadati di una libreria",
    "Refresh series metadata": "Ricarica i metadati della serie",
    "Regenerate books posters": "Rigenera i poster dei libri",
    "Remove a book from the library, keeping its file untouched": "Rimuovi un libro dalla libreria senza toccare il suo file",
    "Remove a series from the library, keeping its files untouched": "Rimuovi una serie dalla libreria senza toccare i suoi file",
    "Remove a single queue entry": "Rimuovi una singola voce della coda",
    "Replace the roles of a user": "Sostituisci i ruoli di un utente",
    "Replace the running jar with the staged one and restart": "Sostituisci il jar in esecuzione con quello preparato e riavvia",
    "Report a reading heartbeat": "Segnala un heartbeat di lettura",
    "Reset a queue entry back to queued": "Riporta una voce della coda in attesa",
    "Reset the password of a user and expire its sessions": "Reimposta la password di un utente e fai scadere le sue sessioni",
    "Restore a trash entry back to its library": "Ripristina una voce del cestino nella sua libreria",
    "Restore logger levels to their default": "Ripristina i livelli dei logger predefiniti",
    "Restore the last backup jar and restart": "Ripristina l'ultimo jar di backup e riavvia",
    "Resume task processing": "Riprendi l'elaborazione delle attività",
    "Retrieve API keys": "Recupera le chiavi API",
    "Retrieve a single audit entry": "Recupera una singola voce di audit",
    "Retrieve a user with its activity summary": "Recupera un utente con il riepilogo delle attività",
    "Retrieve announcements": "Recupera gli annunci",
    "Retrieve authentication activity": "Recupera l'attività di autenticazione",
    "Retrieve authentication activity for the current user": "Recupera l'attività di autenticazione dell'utente corrente",
    "Retrieve current user": "Recupera l'utente corrente",
    "Retrieve global client settings": "Recupera le impostazioni globali del client",
    "Retrieve latest authentication activity for a user": "Recupera l'ultima attività di autenticazione di un utente",
    "Retrieve server settings": "Recupera le impostazioni del server",
    "Retrieve user client settings": "Recupera le impostazioni client dell'utente",
    "Revoke a scrape only API token": "Revoca un token API solo scraping",
    "Rotate the managed Suwayomi credentials": "Ruota le credenziali gestite di Suwayomi",
    "Run the automatic install for the given runtimes": "Esegui l'installazione automatica per i runtime indicati",
    "Runtime and storage status": "Stato del runtime e dello storage",
    "Save global settings": "Salva le impostazioni globali",
    "Save user settings": "Salva le impostazioni utente",
    "Scan a library": "Scansiona una libreria",
    "Scan folder for transient books": "Scansiona la cartella per libri temporanei",
    "Search audit entries": "Cerca nelle voci di audit",
    "Search inside a book": "Cerca dentro un libro",
    "Search mangas across enabled sources": "Cerca manga tra le fonti abilitate",
    "Search metadata candidates from one or all enabled sources": "Cerca candidati di metadati da una o da tutte le fonti abilitate",
    "Series ids whose computed first letter matches": "ID delle serie la cui iniziale calcolata corrisponde",
    "Set cookie": "Imposta il cookie",
    "Set the conversion mode: never, on-demand or eager": "Imposta la modalità di conversione: mai, su richiesta o immediata",
    "Set the trash quota of a library, zero means inheriting the global default": "Imposta la quota del cestino di una libreria, zero significa ereditare il valore globale",
    "Snapshot of the automatic install performed at startup": "Istantanea dell'installazione automatica eseguita all'avvio",
    "Snapshot the main and tasks SQLite databases": "Istantanea dei database SQLite principale e delle attività",
    "Start a background auto-match job for a library": "Avvia un lavoro di corrispondenza automatica in background per una libreria",
    "Start an external runtime": "Avvia un runtime esterno",
    "Start or resume scraping the queue": "Avvia o riprendi lo scraping della coda",
    "Start, stop or restart Suwayomi": "Avvia, arresta o riavvia Suwayomi",
    "Stop an external runtime": "Arresta un runtime esterno",
    "Stop taking new tasks; running tasks finish": "Smetti di accettare nuove attività; quelle in esecuzione terminano",
    "Stop the current reading session": "Interrompi la sessione di lettura corrente",
    "Stream new log entries": "Trasmetti in streaming le nuove voci di log",
    "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "Invia un risultato di scraping per una voce della coda, oppure passa un ID serie per sovrascrivere un risultato già applicato",
    "Subscribe to a remote manga and materialize it as a series": "Sottoscrivi un manga remoto e materializzalo come serie",
    "Suwayomi runtime status": "Stato del runtime di Suwayomi",
    "Sync offline reading sessions": "Sincronizza le sessioni di lettura offline",
    "Synchronize chapters of a subscribed series": "Sincronizza i capitoli di una serie sottoscritta",
    "Undo an applied match using its snapshot": "Annulla una corrispondenza applicata usando la sua istantanea",
    "Uninstall a runtime, data is removed only when purge is requested": "Disinstalla un runtime, i dati vengono rimossi solo quando viene richiesta la pulizia",
    "Uninstall an extension": "Disinstalla un'estensione",
    "Unsubscribe a series and remove its placeholder files": "Annulla l'abbonamento a una serie e rimuovi i suoi file segnaposto",
    "Update Suwayomi settings": "Aggiorna le impostazioni di Suwayomi",
    "Update a library": "Aggiorna una libreria",
    "Update an installed extension": "Aggiorna un'estensione installata",
    "Update book metadata": "Aggiorna i metadati del libro",
    "Update book metadata in bulk": "Aggiorna i metadati dei libri in blocco",
    "Update collection": "Aggiorna la raccolta",
    "Update current user's password": "Aggiorna la password dell'utente corrente",
    "Update note, color or marker of my annotation": "Aggiorna nota, colore o marcatore della mia annotazione",
    "Update one preference of a source and return the refreshed list": "Aggiorna una preferenza di una fonte e restituisci l'elenco aggiornato",
    "Update readlist": "Aggiorna la lista di lettura",
    "Update readlist read progress (Mihon)": "Aggiorna l'avanzamento di lettura della lista di lettura (Mihon)",
    "Update scrape settings, trusting the results requires an explicit confirmation": "Aggiorna le impostazioni di scraping, fidarsi dei risultati richiede una conferma esplicita",
    "Update series metadata": "Aggiorna i metadati della serie",
    "Update series read progress (Mihon)": "Aggiorna l'avanzamento di lettura della serie (Mihon)",
    "Update server settings": "Aggiorna le impostazioni del server",
    "Update the username and email of a user": "Aggiorna il nome utente e l'email di un utente",
    "Update trash settings": "Aggiorna le impostazioni del cestino",
    "Update user": "Aggiorna l'utente",
    "Update user's password": "Aggiorna la password di un utente",
    "Upload a package and install it in the background": "Carica un pacchetto e installalo in background",
    "Upload an upgrade package and validate it without touching the running jar": "Carica un pacchetto di aggiornamento e convalidalo senza toccare il jar in esecuzione",
      "Add a scraping source": "Aggiungere un'origine di scraping",
      "Apply bulk series metadata from a text payload": "Applicare metadati di serie in blocco da un testo",
      "Authenticate a KOReader user": "Autenticare un utente KOReader",
      "Browse a library": "Sfogliare una libreria",
      "Check Kobo connectivity": "Verificare la connettività Kobo",
      "Count entries awaiting bulk metadata": "Contare le voci in attesa di metadati",
      "Create an API token": "Creare un token API",
      "Delete a scraping source": "Eliminare un'origine di scraping",
      "Delete an API token": "Eliminare un token API",
      "Download a book file for Kobo": "Scaricare un file di libro per Kobo",
      "Download the metadata package template": "Scaricare il modello del pacchetto di metadati",
      "Enable or disable a scraping source": "Abilitare o disabilitare un'origine di scraping",
      "Exchange a Kobo device token": "Scambiare un token di dispositivo Kobo",
      "Export series metadata as a text file": "Esportare i metadati delle serie come file di testo",
      "Get Kobo metadata for a book": "Ottenere i metadati Kobo di un libro",
      "Get Kobo reading state for a book": "Ottenere lo stato di lettura Kobo di un libro",
      "Get a book cover for Kobo": "Ottenere una copertina per Kobo",
      "Get a book page image": "Ottenere l'immagine di una pagina",
      "Get a book page thumbnail": "Ottenere la miniatura di una pagina",
      "Get a book thumbnail": "Ottenere la miniatura di un libro",
      "Get one collection": "Ottenere una raccolta",
      "Get one library": "Ottenere una libreria",
      "Get one reading list": "Ottenere una lista di lettura",
      "Get one series with its books": "Ottenere una serie con i suoi libri",
      "Get reading progress for a document": "Ottenere l'avanzamento di lettura di un documento",
      "Get scraping source details": "Ottenere i dettagli di un'origine di scraping",
      "Get the OPDS authentication document": "Ottenere il documento di autenticazione OPDS",
      "Get the OPDS search description": "Ottenere la descrizione di ricerca OPDS",
      "Get the OPDS v1 catalog": "Ottenere il catalogo OPDS v1",
      "Get the OPDS v2 catalog": "Ottenere il catalogo OPDS v2",
      "Get the WebPub manifest for a DIVINA book": "Ottenere il manifest WebPub di un libro DIVINA",
      "Get the WebPub manifest for a PDF": "Ottenere il manifest WebPub di un PDF",
      "Get the WebPub manifest for a book": "Ottenere il manifest WebPub di un libro",
      "Get the WebPub manifest for an EPUB": "Ottenere il manifest WebPub di un EPUB",
      "Import a series metadata package": "Importare un pacchetto di metadati di serie",
      "Initialize a Kobo device": "Inizializzare un dispositivo Kobo",
      "List API tokens": "Elencare i token API",
      "List all series": "Elencare tutte le serie",
      "List available API token scopes": "Elencare gli ambiti dei token API disponibili",
      "List books currently being read": "Elencare i libri in lettura",
      "List books to keep reading": "Elencare i libri da continuare",
      "List collections in a library": "Elencare le raccolte di una libreria",
      "List entries awaiting bulk metadata": "Elencare le voci in attesa di metadati",
      "List libraries": "Elencare le librerie",
      "List reading lists": "Elencare le liste di lettura",
      "List reading lists in a library": "Elencare le liste di lettura di una libreria",
      "List recently added books": "Elencare i libri aggiunti di recente",
      "List recently added series": "Elencare le serie aggiunte di recente",
      "Proxy unmatched Kobo requests": "Inoltrare le richieste Kobo non riconosciute",
      "Register a KOReader user (disabled)": "Registrare un utente KOReader (disattivato)",
      "Search the catalog": "Cercare nel catalogo",
      "Submit bulk metadata for application": "Inviare metadati in blocco per l'applicazione",
      "Sync the Kobo library": "Sincronizzare la libreria Kobo",
      "Test a scraping source connection": "Testare la connessione di un'origine di scraping",
      "Update Kobo reading state for a book": "Aggiornare lo stato di lettura Kobo di un libro",
      "Update a scraping source": "Aggiornare un'origine di scraping",
      "Update an API token": "Aggiornare un token API",
      "Update reading progress for a document": "Aggiornare l'avanzamento di lettura di un documento",
    "Serve the web interface": "Servire l'interfaccia web",
    "Stream server-sent events": "Trasmettere eventi dal server",
  };
  B["pt-BR"] = {
    "Delete all sync points": "Excluir todos os pontos de sincronização",
    "Directory listing": "Listagem de diretório",
    "List all libraries": "Listar todas as bibliotecas",
    "Add an extension store": "Adicionar uma loja de extensões",
    "Add book poster": "Adicionar pôster do livro",
    "Add collection poster": "Adicionar pôster da coleção",
    "Add readlist poster": "Adicionar pôster da lista de leitura",
    "Add series poster": "Adicionar pôster da série",
    "Add series, a whole library or every series missing metadata to the queue": "Adicionar uma série, uma biblioteca inteira ou todas as séries sem metadados à fila",
    "Aggregate reading devices from session history": "Agregar dispositivos de leitura a partir do histórico de sessões",
    "Aggregated home payload for clients": "Carga inicial agregada para clientes",
    "Analyze a library": "Analisar uma biblioteca",
    "Analyze book": "Analisar livro",
    "Analyze series": "Analisar série",
    "Analyze transient book": "Analisar livro transitório",
    "Apply selected fields of a match and keep a rollback snapshot": "Aplicar os campos selecionados de uma correspondência e manter um instantâneo de reversão",
    "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "Aplicar os campos escolhidos de uma entrada da fila, retorna 204 quando a entrada sai da fila",
    "Ask the running batch to stop after the current series": "Pedir ao lote em execução para parar após a série atual",
    "Available versions and download sources": "Versões disponíveis e fontes de download",
    "Book counts grouped by computed first letter": "Contagem de livros agrupada pela inicial calculada",
    "Book cover resized on demand": "Capa do livro redimensionada sob demanda",
    "Book ids whose computed first letter matches": "IDs de livros cuja inicial calculada corresponde",
    "Bring back a book previously removed from the library": "Restaurar um livro removido anteriormente da biblioteca",
    "Bring back a series previously removed from the library": "Restaurar uma série removida anteriormente da biblioteca",
    "Browse a source by popular or latest, or search inside it": "Navegar por uma fonte por popularidade ou novidade, ou pesquisar dentro dela",
    "Browse installable extensions": "Navegar pelas extensões instaláveis",
    "Calibre availability, version and conversion mode": "Disponibilidade, versão e modo de conversão do Calibre",
    "Change logger levels at runtime, use INHERIT to restore the default": "Alterar os níveis de log em tempo de execução, use INHERIT para restaurar o padrão",
    "Clear backoff and circuit state of a source": "Limpar o backoff e o estado de circuito de uma fonte",
    "Clear queue entries by scope: queued, review, failed or all": "Limpar entradas da fila por escopo: em espera, revisão, falhas ou todas",
    "Clear task queue": "Limpar a fila de tarefas",
    "Clear the pinned executable and fall back to the managed installation": "Limpar o executável fixado e voltar à instalação gerenciada",
    "Client capability negotiation": "Negociação de capacidades do cliente",
    "Compute the field level difference of a queue entry": "Calcular a diferença em nível de campo de uma entrada da fila",
    "Convert a single book and queue re-analysis": "Converter um único livro e enfileirar a reanálise",
    "Convert pending books of a library": "Converter os livros pendentes de uma biblioteca",
    "Count books still waiting for conversion in a library": "Contar os livros ainda aguardando conversão em uma biblioteca",
    "Counters for the scrape queue and the inbox": "Contadores da fila de scraping e da caixa de entrada",
    "Create API key": "Criar chave de API",
    "Create a library": "Criar uma biblioteca",
    "Create a scrape only API token with fixed scopes": "Criar um token de API somente scraping com escopos fixos",
    "Create an annotation with a locator and a text fallback": "Criar uma anotação com um localizador e um texto de fallback",
    "Create collection": "Criar coleção",
    "Create readlist": "Criar lista de leitura",
    "Create user": "Criar usuário",
    "Current install progress": "Progresso da instalação atual",
    "Current jar location and whether it can be replaced in place": "Local atual do jar e se ele pode ser substituído no local",
    "Delete API key": "Excluir chave de API",
    "Delete a library": "Excluir uma biblioteca",
    "Delete a single task by id": "Excluir uma única tarefa por ID",
    "Delete all duplicate pages by hash": "Excluir todas as páginas duplicadas por hash",
    "Delete an API key of a user": "Excluir uma chave de API de um usuário",
    "Delete book file": "Excluir arquivo do livro",
    "Delete book poster": "Excluir pôster do livro",
    "Delete collection": "Excluir coleção",
    "Delete collection poster": "Excluir pôster da coleção",
    "Delete downloaded cache of an online series, chapters remain readable through the source": "Excluir o cache baixado de uma série online, os capítulos permanecem legíveis pela fonte",
    "Delete every finished task record": "Excluir todos os registros de tarefas concluídas",
    "Delete every package downloaded from the update feed": "Excluir todos os pacotes baixados do feed de atualização",
    "Delete every queued task; running ones are kept": "Excluir todas as tarefas enfileiradas; as em execução são mantidas",
    "Delete global settings": "Excluir as configurações globais",
    "Delete my annotation": "Excluir minha anotação",
    "Delete readlist": "Excluir lista de leitura",
    "Delete readlist poster": "Excluir pôster da lista de leitura",
    "Delete series files": "Excluir arquivos da série",
    "Delete series poster": "Excluir pôster da série",
    "Delete specific duplicate page": "Excluir uma página duplicada específica",
    "Delete user": "Excluir usuário",
    "Delete user settings": "Excluir as configurações do usuário",
    "Discard the staged package": "Descartar o pacote preparado",
    "Download CSS file": "Baixar arquivo CSS",
    "Download a raw log file": "Baixar um arquivo de log bruto",
    "Download a release package, verify it and stage it for install": "Baixar um pacote de versão, verificá-lo e prepará-lo para instalação",
    "Download an online chapter as a local CBZ and re-analyze it": "Baixar um capítulo online como CBZ local e reanalisá-lo",
    "Download and install the latest Calibre portable build": "Baixar e instalar a versão portátil mais recente do Calibre",
    "Download and install the latest Suwayomi release": "Baixar e instalar a versão mais recente do Suwayomi",
    "Download book file": "Baixar arquivo do livro",
    "Download font file": "Baixar arquivo de fonte",
    "Download from the official source and install in the background": "Baixar da fonte oficial e instalar em segundo plano",
    "Download readlist": "Baixar lista de leitura",
    "Download series": "Baixar série",
    "Echo build and runtime information": "Retornar informações de build e tempo de execução",
    "Empty the trash of one or all libraries": "Esvaziar a lixeira de uma ou de todas as bibliotecas",
    "Empty trash for a library": "Esvaziar a lixeira de uma biblioteca",
    "Enable or disable a runtime": "Ativar ou desativar um runtime",
    "Enable or disable a user": "Ativar ou desativar um usuário",
    "Environment and dependency check for an external runtime": "Verificação de ambiente e dependências de um runtime externo",
    "Equivalent shell steps for a manual install": "Etapas de shell equivalentes para uma instalação manual",
    "Expire one or all sessions of a user": "Expirar uma ou todas as sessões de um usuário",
    "Export a page range as zip": "Exportar um intervalo de páginas como zip",
    "Export audit entries as CSV": "Exportar as entradas de auditoria como CSV",
    "Export the library inventory as JSON": "Exportar o inventário da biblioteca como JSON",
    "Get Epub resource": "Obter recurso Epub",
    "Get Lantai reading capabilities": "Obter as capacidades de leitura do Lantai",
    "Get book details": "Obter detalhes do livro",
    "Get book page image": "Obter imagem de página do livro",
    "Get book page thumbnail": "Obter miniatura de página do livro",
    "Get book poster image": "Obter imagem do pôster do livro",
    "Get book progression": "Obter o progresso do livro",
    "Get book's WebPub manifest": "Obter o manifesto WebPub do livro",
    "Get book's WebPub manifest (DiViNa)": "Obter o manifesto WebPub do livro (DiViNa)",
    "Get book's WebPub manifest (Epub)": "Obter o manifesto WebPub do livro (Epub)",
    "Get book's WebPub manifest (PDF)": "Obter o manifesto WebPub do livro (PDF)",
    "Get book's poster image": "Obter a imagem do pôster do livro",
    "Get collection details": "Obter detalhes da coleção",
    "Get collection poster image": "Obter imagem do pôster da coleção",
    "Get collection's poster image": "Obter a imagem do pôster da coleção",
    "Get details for a single library": "Obter os detalhes de uma única biblioteca",
    "Get known duplicate image thumbnail": "Obter a miniatura de uma imagem duplicada conhecida",
    "Get next book in readlist": "Obter o próximo livro na lista de leitura",
    "Get next book in series": "Obter o próximo livro na série",
    "Get previous book in readlist": "Obter o livro anterior na lista de leitura",
    "Get previous book in series": "Obter o livro anterior na série",
    "Get raw book page": "Obter uma página bruta do livro",
    "Get reading statistics for the current user": "Obter as estatísticas de leitura do usuário atual",
    "Get readlist details": "Obter detalhes da lista de leitura",
    "Get readlist poster image": "Obter imagem do pôster da lista de leitura",
    "Get readlist read progress (Mihon)": "Obter o progresso de leitura da lista de leitura (Mihon)",
    "Get readlist's poster image": "Obter a imagem do pôster da lista de leitura",
    "Get series details": "Obter detalhes da série",
    "Get series poster image": "Obter imagem do pôster da série",
    "Get series read progress (Mihon)": "Obter o progresso de leitura da série (Mihon)",
    "Get series' poster image": "Obter a imagem do pôster da série",
    "Get the archive status of the current user's reading statistics": "Obter o status de arquivamento das estatísticas de leitura do usuário atual",
    "Get the reading time of one book for the current user": "Obter o tempo de leitura de um livro para o usuário atual",
    "Get transient book page": "Obter página de livro transitório",
    "Get unknown duplicate image thumbnail": "Obter a miniatura de uma imagem duplicada desconhecida",
    "Import a Suwayomi jar manually": "Importar um jar do Suwayomi manualmente",
    "Import a locally downloaded Calibre portable installer": "Importar um instalador portátil do Calibre baixado localmente",
    "Import books": "Importar livros",
    "Include or exclude a source from search and entry points": "Incluir ou excluir uma fonte da pesquisa e dos pontos de entrada",
    "Install an extension from an uploaded package": "Instalar uma extensão a partir de um pacote enviado",
    "Install an extension from the catalog": "Instalar uma extensão a partir do catálogo",
    "Latest Calibre portable release": "Versão portátil mais recente do Calibre",
    "Latest Suwayomi release": "Versão mais recente do Suwayomi",
    "List active reading sessions for the current user": "Listar as sessões de leitura ativas do usuário atual",
    "List age ratings": "Listar as classificações etárias",
    "List and search users": "Listar e pesquisar usuários",
    "List applied scrape records of the series behind a queue entry": "Listar os registros de scraping aplicados da série por trás de uma entrada da fila",
    "List assignable roles": "Listar as funções atribuíveis",
    "List authors": "Listar autores",
    "List authors names": "Listar nomes de autores",
    "List authors roles": "Listar funções de autores",
    "List authors' names": "Listar os nomes dos autores",
    "List authors' roles": "Listar as funções dos autores",
    "List available log files": "Listar os arquivos de log disponíveis",
    "List book pages": "Listar as páginas do livro",
    "List book posters": "Listar os pôsteres do livro",
    "List book tags": "Listar as tags do livro",
    "List book's positions": "Listar as posições do livro",
    "List book's readlists": "Listar as listas de leitura do livro",
    "List books": "Listar livros",
    "List books on deck": "Listar os livros no deck",
    "List books removed from library but still present on disk": "Listar os livros removidos da biblioteca mas ainda presentes no disco",
    "List collection's posters": "Listar os pôsteres da coleção",
    "List collection's series": "Listar as séries da coleção",
    "List collections": "Listar coleções",
    "List duplicate books": "Listar livros duplicados",
    "List duplicate matches": "Listar correspondências duplicadas",
    "List extension stores": "Listar lojas de extensões",
    "List finished tasks, paged": "Listar as tarefas concluídas, paginado",
    "List font families": "Listar famílias de fontes",
    "List genres": "Listar gêneros",
    "List historical events": "Listar eventos históricos",
    "List installed extensions": "Listar as extensões instaladas",
    "List known duplicates": "Listar os duplicados conhecidos",
    "List languages": "Listar idiomas",
    "List latest books": "Listar os livros mais recentes",
    "List latest series": "Listar as séries mais recentes",
    "List loggers and their levels": "Listar os loggers e seus níveis",
    "List match candidates with their current and proposed values": "Listar os candidatos de correspondência com seus valores atuais e propostos",
    "List match jobs of this session": "Listar os trabalhos de correspondência desta sessão",
    "List materialized chapters of a subscription with download state": "Listar os capítulos materializados de uma assinatura com o estado de download",
    "List my annotations of a book": "Listar minhas anotações de um livro",
    "List new series": "Listar as séries novas",
    "List notable entities": "Listar entidades notáveis",
    "List notable entities kinds": "Listar os tipos de entidades notáveis",
    "List notable entities names": "Listar os nomes de entidades notáveis",
    "List online metadata sources and their daily usage": "Listar as fontes de metadados online e seu uso diário",
    "List publishers": "Listar editoras",
    "List queued and running tasks, paged": "Listar as tarefas em espera e em execução, paginado",
    "List reading sessions": "Listar as sessões de leitura",
    "List readlist's books": "Listar os livros da lista de leitura",
    "List readlist's posters": "Listar os pôsteres da lista de leitura",
    "List readlists": "Listar listas de leitura",
    "List registered OAuth2 providers": "Listar os provedores OAuth2 registrados",
    "List releases": "Listar as versões",
    "List series": "Listar séries",
    "List series groups": "Listar grupos de séries",
    "List series posters": "Listar pôsteres de séries",
    "List series release dates": "Listar as datas de lançamento das séries",
    "List series release years": "Listar os anos de lançamento das séries",
    "List series removed from library but still present on disk": "Listar as séries removidas da biblioteca mas ainda presentes no disco",
    "List series tags": "Listar as tags das séries",
    "List series' books": "Listar os livros da série",
    "List series' collections": "Listar as coleções da série",
    "List sharing labels": "Listar os rótulos de compartilhamento",
    "List sources with their search/entry toggle state": "Listar as fontes com seu estado de ativação para pesquisa e entrada",
    "List subscribed online mangas": "Listar os mangás online assinados",
    "List tags": "Listar tags",
    "List the API keys of a user": "Listar as chaves de API de um usuário",
    "List the actions present in the audit trail": "Listar as ações presentes na trilha de auditoria",
    "List the active sessions of a user": "Listar as sessões ativas de um usuário",
    "List the books of the series behind a queue entry with their current metadata": "Listar os livros da série por trás de uma entrada da fila com seus metadados atuais",
    "List the runtime filters exposed by a source": "Listar os filtros de tempo de execução expostos por uma fonte",
    "List the scrape only API tokens": "Listar os tokens de API somente scraping",
    "List the sources provided by an extension": "Listar as fontes fornecidas por uma extensão",
    "List trash entries of one or all libraries": "Listar as entradas da lixeira de uma ou de todas as bibliotecas",
    "List unknown duplicates": "Listar os duplicados desconhecidos",
    "List updated series": "Listar as séries atualizadas",
    "List users": "Listar usuários",
    "Mark a match as skipped": "Marcar uma correspondência como ignorada",
    "Mark announcements as read": "Marcar os anúncios como lidos",
    "Mark book as unread": "Marcar o livro como não lido",
    "Mark book poster as selected": "Marcar o pôster do livro como selecionado",
    "Mark book progression": "Registrar o progresso do livro",
    "Mark book's read progress": "Registrar o progresso de leitura do livro",
    "Mark collection poster as selected": "Marcar o pôster da coleção como selecionado",
    "Mark duplicate page as known": "Marcar uma página duplicada como conhecida",
    "Mark readlist poster as selected": "Marcar o pôster da lista de leitura como selecionado",
    "Mark series as read": "Marcar a série como lida",
    "Mark series as unread": "Marcar a série como não lida",
    "Mark series poster as selected": "Marcar o pôster da série como selecionado",
    "Match ComicRack list": "Corresponder uma lista do ComicRack",
    "Match a single series against enabled sources": "Corresponder uma única série às fontes habilitadas",
    "Packages already downloaded into the config directory": "Pacotes já baixados no diretório de configuração",
    "Per-library series/book counts and total size": "Contagem de séries/livros e tamanho total por biblioteca",
    "Permanently delete a trash entry and its files": "Excluir permanentemente uma entrada da lixeira e seus arquivos",
    "Pin an existing ebook-convert executable": "Fixar um executável ebook-convert existente",
    "Poll a match job": "Consultar um trabalho de correspondência",
    "Probe an ebook-convert executable without saving it": "Sondar um executável ebook-convert sem salvá-lo",
    "Probe every external runtime managed by Lantai": "Sondar todos os runtimes externos gerenciados pelo Lantai",
    "Probe one external runtime": "Sondar um runtime externo",
    "Progress of the package download currently running": "Progresso do download de pacote em execução",
    "Progress of the package validation currently running": "Progresso da validação de pacote em execução",
    "Proxy the remote cover of a search result": "Servir como proxy para a capa remota de um resultado de pesquisa",
    "Purge reading sessions beyond the retention window": "Purgar as sessões de leitura além da janela de retenção",
    "Re-verify the most recent downloaded package": "Verificar novamente o pacote baixado mais recente",
    "Read Suwayomi settings": "Ler as configurações do Suwayomi",
    "Read bootstrap payload": "Ler o payload de bootstrap",
    "Read chapter bundle": "Ler o pacote de capítulos",
    "Read chapter outline page": "Ler a página de estrutura do capítulo",
    "Read chapter window": "Ler a janela de capítulo",
    "Read image resource": "Ler recurso de imagem",
    "Read one page of the global scrape queue": "Ler uma página da fila global de scraping",
    "Read one page of the scrape queue for an external agent": "Ler uma página da fila de scraping para um agente externo",
    "Read one queue entry with the hints an external agent needs": "Ler uma entrada da fila com as dicas que um agente externo precisa",
    "Read page thumbnail": "Ler a miniatura da página",
    "Read scrape settings": "Ler as configurações de scraping",
    "Read structured log entries, cursor based": "Ler as entradas de log estruturadas, baseadas em cursor",
    "Read the configurable preferences of a source": "Ler as preferências configuráveis de uma fonte",
    "Read the remote detail of a single manga": "Ler o detalhe remoto de um único mangá",
    "Read the update feed and report the latest release plus any downloaded package": "Ler o feed de atualização e relatar a versão mais recente e qualquer pacote baixado",
    "Read trash settings": "Ler as configurações da lixeira",
    "Rebuild the daily reading statistics of the current user": "Reconstruir as estatísticas diárias de leitura do usuário atual",
    "Recompute first letters for every series": "Recalcular as iniciais de todas as séries",
    "Refresh book metadata": "Recarregar os metadados do livro",
    "Refresh installed extension metadata": "Recarregar os metadados das extensões instaladas",
    "Refresh metadata for a library": "Recarregar os metadados de uma biblioteca",
    "Refresh series metadata": "Recarregar os metadados da série",
    "Regenerate books posters": "Regenerar os pôsteres dos livros",
    "Remove a book from the library, keeping its file untouched": "Remover um livro da biblioteca sem tocar em seu arquivo",
    "Remove a series from the library, keeping its files untouched": "Remover uma série da biblioteca sem tocar em seus arquivos",
    "Remove a single queue entry": "Remover uma única entrada da fila",
    "Replace the roles of a user": "Substituir as funções de um usuário",
    "Replace the running jar with the staged one and restart": "Substituir o jar em execução pelo preparado e reiniciar",
    "Report a reading heartbeat": "Reportar um heartbeat de leitura",
    "Reset a queue entry back to queued": "Redefinir uma entrada da fila para em espera",
    "Reset the password of a user and expire its sessions": "Redefinir a senha de um usuário e expirar suas sessões",
    "Restore a trash entry back to its library": "Restaurar uma entrada da lixeira para sua biblioteca",
    "Restore logger levels to their default": "Restaurar os níveis de log para o padrão",
    "Restore the last backup jar and restart": "Restaurar o último jar de backup e reiniciar",
    "Resume task processing": "Retomar o processamento de tarefas",
    "Retrieve API keys": "Recuperar as chaves de API",
    "Retrieve a single audit entry": "Recuperar uma única entrada de auditoria",
    "Retrieve a user with its activity summary": "Recuperar um usuário com seu resumo de atividade",
    "Retrieve announcements": "Recuperar os anúncios",
    "Retrieve authentication activity": "Recuperar a atividade de autenticação",
    "Retrieve authentication activity for the current user": "Recuperar a atividade de autenticação do usuário atual",
    "Retrieve current user": "Recuperar o usuário atual",
    "Retrieve global client settings": "Recuperar as configurações globais do cliente",
    "Retrieve latest authentication activity for a user": "Recuperar a atividade de autenticação mais recente de um usuário",
    "Retrieve server settings": "Recuperar as configurações do servidor",
    "Retrieve user client settings": "Recuperar as configurações de cliente do usuário",
    "Revoke a scrape only API token": "Revogar um token de API somente scraping",
    "Rotate the managed Suwayomi credentials": "Rotacionar as credenciais gerenciadas do Suwayomi",
    "Run the automatic install for the given runtimes": "Executar a instalação automática para os runtimes indicados",
    "Runtime and storage status": "Status do runtime e do armazenamento",
    "Save global settings": "Salvar as configurações globais",
    "Save user settings": "Salvar as configurações do usuário",
    "Scan a library": "Escanear uma biblioteca",
    "Scan folder for transient books": "Escanear a pasta em busca de livros transitórios",
    "Search audit entries": "Pesquisar nas entradas de auditoria",
    "Search inside a book": "Pesquisar dentro de um livro",
    "Search mangas across enabled sources": "Pesquisar mangás em todas as fontes habilitadas",
    "Search metadata candidates from one or all enabled sources": "Pesquisar candidatos de metadados em uma ou em todas as fontes habilitadas",
    "Series ids whose computed first letter matches": "IDs de séries cuja inicial calculada corresponde",
    "Set cookie": "Definir cookie",
    "Set the conversion mode: never, on-demand or eager": "Definir o modo de conversão: nunca, sob demanda ou imediato",
    "Set the trash quota of a library, zero means inheriting the global default": "Definir a cota da lixeira de uma biblioteca, zero significa herdar o padrão global",
    "Snapshot of the automatic install performed at startup": "Instantâneo da instalação automática realizada na inicialização",
    "Snapshot the main and tasks SQLite databases": "Instantâneo dos bancos de dados SQLite principal e de tarefas",
    "Start a background auto-match job for a library": "Iniciar um trabalho de correspondência automática em segundo plano para uma biblioteca",
    "Start an external runtime": "Iniciar um runtime externo",
    "Start or resume scraping the queue": "Iniciar ou retomar o scraping da fila",
    "Start, stop or restart Suwayomi": "Iniciar, parar ou reiniciar o Suwayomi",
    "Stop an external runtime": "Parar um runtime externo",
    "Stop taking new tasks; running tasks finish": "Parar de aceitar novas tarefas; as em execução são concluídas",
    "Stop the current reading session": "Encerrar a sessão de leitura atual",
    "Stream new log entries": "Transmitir as novas entradas de log",
    "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "Enviar um resultado de scraping para uma entrada da fila, ou passar um ID de série para sobrescrever um resultado já aplicado",
    "Subscribe to a remote manga and materialize it as a series": "Assinar um mangá remoto e materializá-lo como série",
    "Suwayomi runtime status": "Status do runtime do Suwayomi",
    "Sync offline reading sessions": "Sincronizar as sessões de leitura offline",
    "Synchronize chapters of a subscribed series": "Sincronizar os capítulos de uma série assinada",
    "Undo an applied match using its snapshot": "Desfazer uma correspondência aplicada usando seu instantâneo",
    "Uninstall a runtime, data is removed only when purge is requested": "Desinstalar um runtime, os dados só são removidos quando a limpeza é solicitada",
    "Uninstall an extension": "Desinstalar uma extensão",
    "Unsubscribe a series and remove its placeholder files": "Cancelar a assinatura de uma série e remover seus arquivos de placeholder",
    "Update Suwayomi settings": "Atualizar as configurações do Suwayomi",
    "Update a library": "Atualizar uma biblioteca",
    "Update an installed extension": "Atualizar uma extensão instalada",
    "Update book metadata": "Atualizar os metadados do livro",
    "Update book metadata in bulk": "Atualizar os metadados dos livros em massa",
    "Update collection": "Atualizar coleção",
    "Update current user's password": "Atualizar a senha do usuário atual",
    "Update note, color or marker of my annotation": "Atualizar nota, cor ou marcador da minha anotação",
    "Update one preference of a source and return the refreshed list": "Atualizar uma preferência de uma fonte e retornar a lista atualizada",
    "Update readlist": "Atualizar lista de leitura",
    "Update readlist read progress (Mihon)": "Atualizar o progresso de leitura da lista de leitura (Mihon)",
    "Update scrape settings, trusting the results requires an explicit confirmation": "Atualizar as configurações de scraping, confiar nos resultados exige uma confirmação explícita",
    "Update series metadata": "Atualizar os metadados da série",
    "Update series read progress (Mihon)": "Atualizar o progresso de leitura da série (Mihon)",
    "Update server settings": "Atualizar as configurações do servidor",
    "Update the username and email of a user": "Atualizar o nome de usuário e o e-mail de um usuário",
    "Update trash settings": "Atualizar as configurações da lixeira",
    "Update user": "Atualizar usuário",
    "Update user's password": "Atualizar a senha de um usuário",
    "Upload a package and install it in the background": "Enviar um pacote e instalá-lo em segundo plano",
    "Upload an upgrade package and validate it without touching the running jar": "Enviar um pacote de atualização e validá-lo sem tocar no jar em execução",
      "Add a scraping source": "Adicionar uma fonte de scraping",
      "Apply bulk series metadata from a text payload": "Aplicar metadados de séries em lote a partir de um texto",
      "Authenticate a KOReader user": "Autenticar um usuário do KOReader",
      "Browse a library": "Navegar por uma biblioteca",
      "Check Kobo connectivity": "Verificar a conectividade do Kobo",
      "Count entries awaiting bulk metadata": "Contar entradas aguardando metadados",
      "Create an API token": "Criar um token de API",
      "Delete a scraping source": "Excluir uma fonte de scraping",
      "Delete an API token": "Excluir um token de API",
      "Download a book file for Kobo": "Baixar um arquivo de livro para o Kobo",
      "Download the metadata package template": "Baixar o modelo do pacote de metadados",
      "Enable or disable a scraping source": "Ativar ou desativar uma fonte de scraping",
      "Exchange a Kobo device token": "Trocar um token de dispositivo Kobo",
      "Export series metadata as a text file": "Exportar metadados de séries como arquivo de texto",
      "Get Kobo metadata for a book": "Obter os metadados do Kobo de um livro",
      "Get Kobo reading state for a book": "Obter o estado de leitura do Kobo de um livro",
      "Get a book cover for Kobo": "Obter uma capa de livro para o Kobo",
      "Get a book page image": "Obter a imagem de uma página do livro",
      "Get a book page thumbnail": "Obter a miniatura de uma página do livro",
      "Get a book thumbnail": "Obter a miniatura de um livro",
      "Get one collection": "Obter uma coleção",
      "Get one library": "Obter uma biblioteca",
      "Get one reading list": "Obter uma lista de leitura",
      "Get one series with its books": "Obter uma série com seus livros",
      "Get reading progress for a document": "Obter o progresso de leitura de um documento",
      "Get scraping source details": "Obter os detalhes de uma fonte de scraping",
      "Get the OPDS authentication document": "Obter o documento de autenticação do OPDS",
      "Get the OPDS search description": "Obter a descrição de pesquisa do OPDS",
      "Get the OPDS v1 catalog": "Obter o catálogo OPDS v1",
      "Get the OPDS v2 catalog": "Obter o catálogo OPDS v2",
      "Get the WebPub manifest for a DIVINA book": "Obter o manifesto WebPub de um livro DIVINA",
      "Get the WebPub manifest for a PDF": "Obter o manifesto WebPub de um PDF",
      "Get the WebPub manifest for a book": "Obter o manifesto WebPub de um livro",
      "Get the WebPub manifest for an EPUB": "Obter o manifesto WebPub de um EPUB",
      "Import a series metadata package": "Importar um pacote de metadados de séries",
      "Initialize a Kobo device": "Inicializar um dispositivo Kobo",
      "List API tokens": "Listar os tokens de API",
      "List all series": "Listar todas as séries",
      "List available API token scopes": "Listar os escopos de token de API disponíveis",
      "List books currently being read": "Listar os livros em leitura",
      "List books to keep reading": "Listar os livros para continuar lendo",
      "List collections in a library": "Listar as coleções de uma biblioteca",
      "List entries awaiting bulk metadata": "Listar as entradas aguardando metadados",
      "List libraries": "Listar as bibliotecas",
      "List reading lists": "Listar as listas de leitura",
      "List reading lists in a library": "Listar as listas de leitura de uma biblioteca",
      "List recently added books": "Listar os livros adicionados recentemente",
      "List recently added series": "Listar as séries adicionadas recentemente",
      "Proxy unmatched Kobo requests": "Encaminhar solicitações Kobo não reconhecidas",
      "Register a KOReader user (disabled)": "Registrar um usuário do KOReader (desativado)",
      "Search the catalog": "Pesquisar no catálogo",
      "Submit bulk metadata for application": "Enviar metadados em lote para aplicação",
      "Sync the Kobo library": "Sincronizar a biblioteca do Kobo",
      "Test a scraping source connection": "Testar a conexão de uma fonte de scraping",
      "Update Kobo reading state for a book": "Atualizar o estado de leitura do Kobo de um livro",
      "Update a scraping source": "Atualizar uma fonte de scraping",
      "Update an API token": "Atualizar um token de API",
      "Update reading progress for a document": "Atualizar o progresso de leitura de um documento",
    "Serve the web interface": "Servir a interface web",
    "Stream server-sent events": "Transmitir eventos do servidor",
  };
  B["ru"] = {
    "Delete all sync points": "Удалить все точки синхронизации",
    "Directory listing": "Список каталога",
    "List all libraries": "Список всех библиотек",
    "Add an extension store": "Добавить магазин расширений",
    "Add book poster": "Добавить постер книги",
    "Add collection poster": "Добавить постер коллекции",
    "Add readlist poster": "Добавить постер списка чтения",
    "Add series poster": "Добавить постер серии",
    "Add series, a whole library or every series missing metadata to the queue": "Добавить серию, всю библиотеку или все серии без метаданных в очередь",
    "Aggregate reading devices from session history": "Собрать устройства чтения из истории сеансов",
    "Aggregated home payload for clients": "Агрегированные данные главного экрана для клиентов",
    "Analyze a library": "Проанализировать библиотеку",
    "Analyze book": "Проанализировать книгу",
    "Analyze series": "Проанализировать серию",
    "Analyze transient book": "Проанализировать временную книгу",
    "Apply selected fields of a match and keep a rollback snapshot": "Применить выбранные поля совпадения и сохранить снимок для отката",
    "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "Применить выбранные поля записи очереди, возвращает 204, когда запись покидает очередь",
    "Ask the running batch to stop after the current series": "Попросить выполняемый пакет остановиться после текущей серии",
    "Available versions and download sources": "Доступные версии и источники загрузки",
    "Book counts grouped by computed first letter": "Количество книг в разрезе вычисленной первой буквы",
    "Book cover resized on demand": "Обложка книги, масштабируемая по запросу",
    "Book ids whose computed first letter matches": "ID книг, у которых совпадает вычисленная первая буква",
    "Bring back a book previously removed from the library": "Вернуть книгу, ранее удалённую из библиотеки",
    "Bring back a series previously removed from the library": "Вернуть серию, ранее удалённую из библиотеки",
    "Browse a source by popular or latest, or search inside it": "Просмотр источника по популярности или новизне либо поиск внутри него",
    "Browse installable extensions": "Просмотр устанавливаемых расширений",
    "Calibre availability, version and conversion mode": "Доступность Calibre, версия и режим конвертации",
    "Change logger levels at runtime, use INHERIT to restore the default": "Изменить уровни журналирования на лету, INHERIT восстанавливает значение по умолчанию",
    "Clear backoff and circuit state of a source": "Сбросить состояние задержки и предохранителя источника",
    "Clear queue entries by scope: queued, review, failed or all": "Очистить записи очереди по области: в ожидании, проверка, сбой или все",
    "Clear task queue": "Очистить очередь задач",
    "Clear the pinned executable and fall back to the managed installation": "Сбросить закреплённый исполняемый файл и вернуться к управляемой установке",
    "Client capability negotiation": "Согласование возможностей клиента",
    "Compute the field level difference of a queue entry": "Вычислить разницу на уровне полей записи очереди",
    "Convert a single book and queue re-analysis": "Конвертировать одну книгу и поставить повторный анализ в очередь",
    "Convert pending books of a library": "Конвертировать ожидающие книги библиотеки",
    "Count books still waiting for conversion in a library": "Подсчитать книги, ожидающие конвертации в библиотеке",
    "Counters for the scrape queue and the inbox": "Счётчики очереди скрапинга и входящих",
    "Create API key": "Создать ключ API",
    "Create a library": "Создать библиотеку",
    "Create a scrape only API token with fixed scopes": "Создать токен API только для скрапинга с фиксированными областями",
    "Create an annotation with a locator and a text fallback": "Создать аннотацию с локатором и текстовым резервом",
    "Create collection": "Создать коллекцию",
    "Create readlist": "Создать список чтения",
    "Create user": "Создать пользователя",
    "Current install progress": "Текущий прогресс установки",
    "Current jar location and whether it can be replaced in place": "Текущее расположение jar и возможность замены на месте",
    "Delete API key": "Удалить ключ API",
    "Delete a library": "Удалить библиотеку",
    "Delete a single task by id": "Удалить одну задачу по ID",
    "Delete all duplicate pages by hash": "Удалить все дублирующиеся страницы по хешу",
    "Delete an API key of a user": "Удалить ключ API пользователя",
    "Delete book file": "Удалить файл книги",
    "Delete book poster": "Удалить постер книги",
    "Delete collection": "Удалить коллекцию",
    "Delete collection poster": "Удалить постер коллекции",
    "Delete downloaded cache of an online series, chapters remain readable through the source": "Удалить загруженный кеш онлайн-серии, главы остаются доступными через источник",
    "Delete every finished task record": "Удалить все записи завершённых задач",
    "Delete every package downloaded from the update feed": "Удалить все пакеты, загруженные из канала обновлений",
    "Delete every queued task; running ones are kept": "Удалить все задачи в очереди; выполняющиеся сохраняются",
    "Delete global settings": "Удалить глобальные настройки",
    "Delete my annotation": "Удалить свою аннотацию",
    "Delete readlist": "Удалить список чтения",
    "Delete readlist poster": "Удалить постер списка чтения",
    "Delete series files": "Удалить файлы серии",
    "Delete series poster": "Удалить постер серии",
    "Delete specific duplicate page": "Удалить определённую дублирующуюся страницу",
    "Delete user": "Удалить пользователя",
    "Delete user settings": "Удалить настройки пользователя",
    "Discard the staged package": "Отменить подготовленный пакет",
    "Download CSS file": "Скачать файл CSS",
    "Download a raw log file": "Скачать необработанный файл журнала",
    "Download a release package, verify it and stage it for install": "Скачать пакет релиза, проверить его и подготовить к установке",
    "Download an online chapter as a local CBZ and re-analyze it": "Скачать онлайн-главу как локальный CBZ и повторно её проанализировать",
    "Download and install the latest Calibre portable build": "Скачать и установить последнюю портативную сборку Calibre",
    "Download and install the latest Suwayomi release": "Скачать и установить последнюю версию Suwayomi",
    "Download book file": "Скачать файл книги",
    "Download font file": "Скачать файл шрифта",
    "Download from the official source and install in the background": "Скачать из официального источника и установить в фоне",
    "Download readlist": "Скачать список чтения",
    "Download series": "Скачать серию",
    "Echo build and runtime information": "Вернуть сведения о сборке и среде выполнения",
    "Empty the trash of one or all libraries": "Очистить корзину одной или всех библиотек",
    "Empty trash for a library": "Очистить корзину библиотеки",
    "Enable or disable a runtime": "Включить или отключить среду выполнения",
    "Enable or disable a user": "Включить или отключить пользователя",
    "Environment and dependency check for an external runtime": "Проверка окружения и зависимостей для внешней среды выполнения",
    "Equivalent shell steps for a manual install": "Эквивалентные шаги оболочки для ручной установки",
    "Expire one or all sessions of a user": "Истечь один или все сеансы пользователя",
    "Export a page range as zip": "Экспортировать диапазон страниц в zip",
    "Export audit entries as CSV": "Экспортировать записи аудита в CSV",
    "Export the library inventory as JSON": "Экспортировать инвентарь библиотеки в JSON",
    "Get Epub resource": "Получить ресурс Epub",
    "Get Lantai reading capabilities": "Получить возможности чтения Lantai",
    "Get book details": "Получить сведения о книге",
    "Get book page image": "Получить изображение страницы книги",
    "Get book page thumbnail": "Получить миниатюру страницы книги",
    "Get book poster image": "Получить изображение постера книги",
    "Get book progression": "Получить прогресс книги",
    "Get book's WebPub manifest": "Получить манифест WebPub книги",
    "Get book's WebPub manifest (DiViNa)": "Получить манифест WebPub книги (DiViNa)",
    "Get book's WebPub manifest (Epub)": "Получить манифест WebPub книги (Epub)",
    "Get book's WebPub manifest (PDF)": "Получить манифест WebPub книги (PDF)",
    "Get book's poster image": "Получить изображение постера книги",
    "Get collection details": "Получить сведения о коллекции",
    "Get collection poster image": "Получить изображение постера коллекции",
    "Get collection's poster image": "Получить изображение постера коллекции",
    "Get details for a single library": "Получить сведения об одной библиотеке",
    "Get known duplicate image thumbnail": "Получить миниатюру известного дубликата изображения",
    "Get next book in readlist": "Получить следующую книгу в списке чтения",
    "Get next book in series": "Получить следующую книгу в серии",
    "Get previous book in readlist": "Получить предыдущую книгу в списке чтения",
    "Get previous book in series": "Получить предыдущую книгу в серии",
    "Get raw book page": "Получить необработанную страницу книги",
    "Get reading statistics for the current user": "Получить статистику чтения текущего пользователя",
    "Get readlist details": "Получить сведения о списке чтения",
    "Get readlist poster image": "Получить изображение постера списка чтения",
    "Get readlist read progress (Mihon)": "Получить прогресс чтения списка чтения (Mihon)",
    "Get readlist's poster image": "Получить изображение постера списка чтения",
    "Get series details": "Получить сведения о серии",
    "Get series poster image": "Получить изображение постера серии",
    "Get series read progress (Mihon)": "Получить прогресс чтения серии (Mihon)",
    "Get series' poster image": "Получить изображение постера серии",
    "Get the archive status of the current user's reading statistics": "Получить состояние архивации статистики чтения текущего пользователя",
    "Get the reading time of one book for the current user": "Получить время чтения одной книги для текущего пользователя",
    "Get transient book page": "Получить страницу временной книги",
    "Get unknown duplicate image thumbnail": "Получить миниатюру неизвестного дубликата изображения",
    "Import a Suwayomi jar manually": "Импортировать jar Suwayomi вручную",
    "Import a locally downloaded Calibre portable installer": "Импортировать локально загруженный портативный установщик Calibre",
    "Import books": "Импортировать книги",
    "Include or exclude a source from search and entry points": "Включить или исключить источник из поиска и точек входа",
    "Install an extension from an uploaded package": "Установить расширение из загруженного пакета",
    "Install an extension from the catalog": "Установить расширение из каталога",
    "Latest Calibre portable release": "Последний портативный релиз Calibre",
    "Latest Suwayomi release": "Последний релиз Suwayomi",
    "List active reading sessions for the current user": "Список активных сеансов чтения текущего пользователя",
    "List age ratings": "Список возрастных рейтингов",
    "List and search users": "Список и поиск пользователей",
    "List applied scrape records of the series behind a queue entry": "Список применённых записей скрапинга серии, стоящей за записью очереди",
    "List assignable roles": "Список назначаемых ролей",
    "List authors": "Список авторов",
    "List authors names": "Список имён авторов",
    "List authors roles": "Список ролей авторов",
    "List authors' names": "Список имён авторов",
    "List authors' roles": "Список ролей авторов",
    "List available log files": "Список доступных файлов журнала",
    "List book pages": "Список страниц книги",
    "List book posters": "Список постеров книги",
    "List book tags": "Список тегов книги",
    "List book's positions": "Список позиций книги",
    "List book's readlists": "Список списков чтения книги",
    "List books": "Список книг",
    "List books on deck": "Список книг на полке",
    "List books removed from library but still present on disk": "Список книг, удалённых из библиотеки, но всё ещё присутствующих на диске",
    "List collection's posters": "Список постеров коллекции",
    "List collection's series": "Список серий коллекции",
    "List collections": "Список коллекций",
    "List duplicate books": "Список дублирующихся книг",
    "List duplicate matches": "Список дублирующихся совпадений",
    "List extension stores": "Список магазинов расширений",
    "List finished tasks, paged": "Список завершённых задач с постраничной выдачей",
    "List font families": "Список семейств шрифтов",
    "List genres": "Список жанров",
    "List historical events": "Список исторических событий",
    "List installed extensions": "Список установленных расширений",
    "List known duplicates": "Список известных дубликатов",
    "List languages": "Список языков",
    "List latest books": "Список последних книг",
    "List latest series": "Список последних серий",
    "List loggers and their levels": "Список журналируемых категорий и их уровней",
    "List match candidates with their current and proposed values": "Список кандидатов совпадения с их текущими и предлагаемыми значениями",
    "List match jobs of this session": "Список заданий сопоставления этого сеанса",
    "List materialized chapters of a subscription with download state": "Список материализованных глав подписки с состоянием загрузки",
    "List my annotations of a book": "Список моих аннотаций к книге",
    "List new series": "Список новых серий",
    "List notable entities": "Список примечательных сущностей",
    "List notable entities kinds": "Список видов примечательных сущностей",
    "List notable entities names": "Список имён примечательных сущностей",
    "List online metadata sources and their daily usage": "Список онлайн-источников метаданных и их ежедневного использования",
    "List publishers": "Список издательств",
    "List queued and running tasks, paged": "Список задач в ожидании и выполняющихся с постраничной выдачей",
    "List reading sessions": "Список сеансов чтения",
    "List readlist's books": "Список книг списка чтения",
    "List readlist's posters": "Список постеров списка чтения",
    "List readlists": "Список списков чтения",
    "List registered OAuth2 providers": "Список зарегистрированных провайдеров OAuth2",
    "List releases": "Список релизов",
    "List series": "Список серий",
    "List series groups": "Список групп серий",
    "List series posters": "Список постеров серий",
    "List series release dates": "Список дат выхода серий",
    "List series release years": "Список годов выхода серий",
    "List series removed from library but still present on disk": "Список серий, удалённых из библиотеки, но всё ещё присутствующих на диске",
    "List series tags": "Список тегов серий",
    "List series' books": "Список книг серии",
    "List series' collections": "Список коллекций серии",
    "List sharing labels": "Список меток общего доступа",
    "List sources with their search/entry toggle state": "Список источников с состоянием переключателя поиска и входа",
    "List subscribed online mangas": "Список подписанных онлайн-манг",
    "List tags": "Список тегов",
    "List the API keys of a user": "Список ключей API пользователя",
    "List the actions present in the audit trail": "Список действий, присутствующих в журнале аудита",
    "List the active sessions of a user": "Список активных сеансов пользователя",
    "List the books of the series behind a queue entry with their current metadata": "Список книг серии, стоящей за записью очереди, с их текущими метаданными",
    "List the runtime filters exposed by a source": "Список фильтров среды выполнения, предоставляемых источником",
    "List the scrape only API tokens": "Список токенов API только для скрапинга",
    "List the sources provided by an extension": "Список источников, предоставляемых расширением",
    "List trash entries of one or all libraries": "Список записей корзины одной или всех библиотек",
    "List unknown duplicates": "Список неизвестных дубликатов",
    "List updated series": "Список обновлённых серий",
    "List users": "Список пользователей",
    "Mark a match as skipped": "Пометить совпадение как пропущенное",
    "Mark announcements as read": "Пометить объявления как прочитанные",
    "Mark book as unread": "Пометить книгу как непрочитанную",
    "Mark book poster as selected": "Пометить постер книги как выбранный",
    "Mark book progression": "Записать прогресс книги",
    "Mark book's read progress": "Записать прогресс чтения книги",
    "Mark collection poster as selected": "Пометить постер коллекции как выбранный",
    "Mark duplicate page as known": "Пометить дублирующуюся страницу как известную",
    "Mark readlist poster as selected": "Пометить постер списка чтения как выбранный",
    "Mark series as read": "Пометить серию как прочитанную",
    "Mark series as unread": "Пометить серию как непрочитанную",
    "Mark series poster as selected": "Пометить постер серии как выбранный",
    "Match ComicRack list": "Сопоставить список ComicRack",
    "Match a single series against enabled sources": "Сопоставить одну серию с включёнными источниками",
    "Packages already downloaded into the config directory": "Пакеты, уже загруженные в каталог конфигурации",
    "Per-library series/book counts and total size": "Количество серий/книг и общий размер по библиотекам",
    "Permanently delete a trash entry and its files": "Окончательно удалить запись корзины и её файлы",
    "Pin an existing ebook-convert executable": "Закрепить существующий исполняемый файл ebook-convert",
    "Poll a match job": "Опросить задание сопоставления",
    "Probe an ebook-convert executable without saving it": "Проверить исполняемый файл ebook-convert без сохранения",
    "Probe every external runtime managed by Lantai": "Проверить все внешние среды выполнения, управляемые Lantai",
    "Probe one external runtime": "Проверить одну внешнюю среду выполнения",
    "Progress of the package download currently running": "Прогресс текущей загрузки пакета",
    "Progress of the package validation currently running": "Прогресс текущей проверки пакета",
    "Proxy the remote cover of a search result": "Проксировать удалённую обложку результата поиска",
    "Purge reading sessions beyond the retention window": "Удалить сеансы чтения за пределами окна хранения",
    "Re-verify the most recent downloaded package": "Повторно проверить последний загруженный пакет",
    "Read Suwayomi settings": "Прочитать настройки Suwayomi",
    "Read bootstrap payload": "Прочитать данные начальной загрузки",
    "Read chapter bundle": "Прочитать набор глав",
    "Read chapter outline page": "Прочитать страницу структуры главы",
    "Read chapter window": "Прочитать окно главы",
    "Read image resource": "Прочитать ресурс изображения",
    "Read one page of the global scrape queue": "Прочитать одну страницу глобальной очереди скрапинга",
    "Read one page of the scrape queue for an external agent": "Прочитать одну страницу очереди скрапинга для внешнего агента",
    "Read one queue entry with the hints an external agent needs": "Прочитать одну запись очереди с подсказками, нужными внешнему агенту",
    "Read page thumbnail": "Прочитать миниатюру страницы",
    "Read scrape settings": "Прочитать настройки скрапинга",
    "Read structured log entries, cursor based": "Прочитать структурированные записи журнала по курсору",
    "Read the configurable preferences of a source": "Прочитать настраиваемые предпочтения источника",
    "Read the remote detail of a single manga": "Прочитать удалённые сведения об одной манге",
    "Read the update feed and report the latest release plus any downloaded package": "Прочитать канал обновлений и сообщить последний релиз и любой загруженный пакет",
    "Read trash settings": "Прочитать настройки корзины",
    "Rebuild the daily reading statistics of the current user": "Перестроить ежедневную статистику чтения текущего пользователя",
    "Recompute first letters for every series": "Пересчитать первые буквы для всех серий",
    "Refresh book metadata": "Перечитать метаданные книги",
    "Refresh installed extension metadata": "Перечитать метаданные установленных расширений",
    "Refresh metadata for a library": "Перечитать метаданные библиотеки",
    "Refresh series metadata": "Перечитать метаданные серии",
    "Regenerate books posters": "Перегенерировать постеры книг",
    "Remove a book from the library, keeping its file untouched": "Удалить книгу из библиотеки, не трогая её файл",
    "Remove a series from the library, keeping its files untouched": "Удалить серию из библиотеки, не трогая её файлы",
    "Remove a single queue entry": "Удалить одну запись очереди",
    "Replace the roles of a user": "Заменить роли пользователя",
    "Replace the running jar with the staged one and restart": "Заменить работающий jar подготовленным и перезапустить",
    "Report a reading heartbeat": "Отправить сигнал активности чтения",
    "Reset a queue entry back to queued": "Вернуть запись очереди в состояние ожидания",
    "Reset the password of a user and expire its sessions": "Сбросить пароль пользователя и истечь его сеансы",
    "Restore a trash entry back to its library": "Восстановить запись корзины в её библиотеку",
    "Restore logger levels to their default": "Восстановить уровни журналирования по умолчанию",
    "Restore the last backup jar and restart": "Восстановить последний резервный jar и перезапустить",
    "Resume task processing": "Возобновить обработку задач",
    "Retrieve API keys": "Получить ключи API",
    "Retrieve a single audit entry": "Получить одну запись аудита",
    "Retrieve a user with its activity summary": "Получить пользователя со сводкой активности",
    "Retrieve announcements": "Получить объявления",
    "Retrieve authentication activity": "Получить активность аутентификации",
    "Retrieve authentication activity for the current user": "Получить активность аутентификации текущего пользователя",
    "Retrieve current user": "Получить текущего пользователя",
    "Retrieve global client settings": "Получить глобальные настройки клиента",
    "Retrieve latest authentication activity for a user": "Получить последнюю активность аутентификации пользователя",
    "Retrieve server settings": "Получить настройки сервера",
    "Retrieve user client settings": "Получить клиентские настройки пользователя",
    "Revoke a scrape only API token": "Отозвать токен API только для скрапинга",
    "Rotate the managed Suwayomi credentials": "Обновить управляемые учётные данные Suwayomi",
    "Run the automatic install for the given runtimes": "Запустить автоматическую установку для указанных сред выполнения",
    "Runtime and storage status": "Состояние среды выполнения и хранилища",
    "Save global settings": "Сохранить глобальные настройки",
    "Save user settings": "Сохранить настройки пользователя",
    "Scan a library": "Просканировать библиотеку",
    "Scan folder for transient books": "Просканировать папку на наличие временных книг",
    "Search audit entries": "Поиск по записям аудита",
    "Search inside a book": "Поиск внутри книги",
    "Search mangas across enabled sources": "Поиск манг по включённым источникам",
    "Search metadata candidates from one or all enabled sources": "Поиск кандидатов метаданных из одного или всех включённых источников",
    "Series ids whose computed first letter matches": "ID серий, у которых совпадает вычисленная первая буква",
    "Set cookie": "Установить cookie",
    "Set the conversion mode: never, on-demand or eager": "Установить режим конвертации: никогда, по требованию или сразу",
    "Set the trash quota of a library, zero means inheriting the global default": "Установить квоту корзины библиотеки, ноль означает наследование глобального значения",
    "Snapshot of the automatic install performed at startup": "Снимок автоматической установки, выполненной при запуске",
    "Snapshot the main and tasks SQLite databases": "Снимок основной базы SQLite и базы задач",
    "Start a background auto-match job for a library": "Запустить фоновое задание автоматического сопоставления для библиотеки",
    "Start an external runtime": "Запустить внешнюю среду выполнения",
    "Start or resume scraping the queue": "Запустить или возобновить скрапинг очереди",
    "Start, stop or restart Suwayomi": "Запустить, остановить или перезапустить Suwayomi",
    "Stop an external runtime": "Остановить внешнюю среду выполнения",
    "Stop taking new tasks; running tasks finish": "Прекратить приём новых задач; выполняющиеся завершатся",
    "Stop the current reading session": "Остановить текущий сеанс чтения",
    "Stream new log entries": "Потоковая передача новых записей журнала",
    "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "Отправить результат скрапинга для записи очереди или передать ID серии, чтобы перезаписать уже применённый результат",
    "Subscribe to a remote manga and materialize it as a series": "Подписаться на удалённую мангу и материализовать её как серию",
    "Suwayomi runtime status": "Состояние среды выполнения Suwayomi",
    "Sync offline reading sessions": "Синхронизировать офлайн-сеансы чтения",
    "Synchronize chapters of a subscribed series": "Синхронизировать главы подписанной серии",
    "Undo an applied match using its snapshot": "Отменить применённое сопоставление с помощью его снимка",
    "Uninstall a runtime, data is removed only when purge is requested": "Удалить среду выполнения, данные удаляются только при запросе очистки",
    "Uninstall an extension": "Удалить расширение",
    "Unsubscribe a series and remove its placeholder files": "Отписать серию и удалить её файлы-заполнители",
    "Update Suwayomi settings": "Обновить настройки Suwayomi",
    "Update a library": "Обновить библиотеку",
    "Update an installed extension": "Обновить установленное расширение",
    "Update book metadata": "Обновить метаданные книги",
    "Update book metadata in bulk": "Массово обновить метаданные книг",
    "Update collection": "Обновить коллекцию",
    "Update current user's password": "Обновить пароль текущего пользователя",
    "Update note, color or marker of my annotation": "Обновить заметку, цвет или маркер моей аннотации",
    "Update one preference of a source and return the refreshed list": "Обновить одно предпочтение источника и вернуть обновлённый список",
    "Update readlist": "Обновить список чтения",
    "Update readlist read progress (Mihon)": "Обновить прогресс чтения списка чтения (Mihon)",
    "Update scrape settings, trusting the results requires an explicit confirmation": "Обновить настройки скрапинга, доверие результатам требует явного подтверждения",
    "Update series metadata": "Обновить метаданные серии",
    "Update series read progress (Mihon)": "Обновить прогресс чтения серии (Mihon)",
    "Update server settings": "Обновить настройки сервера",
    "Update the username and email of a user": "Обновить имя пользователя и адрес электронной почты",
    "Update trash settings": "Обновить настройки корзины",
    "Update user": "Обновить пользователя",
    "Update user's password": "Обновить пароль пользователя",
    "Upload a package and install it in the background": "Загрузить пакет и установить его в фоне",
    "Upload an upgrade package and validate it without touching the running jar": "Загрузить пакет обновления и проверить его, не трогая работающий jar",
      "Add a scraping source": "Добавить источник скрапинга",
      "Apply bulk series metadata from a text payload": "Применить метаданные серий пакетно из текста",
      "Authenticate a KOReader user": "Аутентифицировать пользователя KOReader",
      "Browse a library": "Просмотреть библиотеку",
      "Check Kobo connectivity": "Проверить подключение Kobo",
      "Count entries awaiting bulk metadata": "Подсчитать записи, ожидающие метаданных",
      "Create an API token": "Создать токен API",
      "Delete a scraping source": "Удалить источник скрапинга",
      "Delete an API token": "Удалить токен API",
      "Download a book file for Kobo": "Скачать файл книги для Kobo",
      "Download the metadata package template": "Скачать шаблон пакета метаданных",
      "Enable or disable a scraping source": "Включить или отключить источник скрапинга",
      "Exchange a Kobo device token": "Обменять токен устройства Kobo",
      "Export series metadata as a text file": "Экспортировать метаданные серий в текстовый файл",
      "Get Kobo metadata for a book": "Получить метаданные Kobo для книги",
      "Get Kobo reading state for a book": "Получить состояние чтения Kobo для книги",
      "Get a book cover for Kobo": "Получить обложку книги для Kobo",
      "Get a book page image": "Получить изображение страницы книги",
      "Get a book page thumbnail": "Получить миниатюру страницы книги",
      "Get a book thumbnail": "Получить миниатюру книги",
      "Get one collection": "Получить коллекцию",
      "Get one library": "Получить библиотеку",
      "Get one reading list": "Получить список чтения",
      "Get one series with its books": "Получить серию с её книгами",
      "Get reading progress for a document": "Получить прогресс чтения документа",
      "Get scraping source details": "Получить сведения об источнике скрапинга",
      "Get the OPDS authentication document": "Получить документ аутентификации OPDS",
      "Get the OPDS search description": "Получить описание поиска OPDS",
      "Get the OPDS v1 catalog": "Получить каталог OPDS v1",
      "Get the OPDS v2 catalog": "Получить каталог OPDS v2",
      "Get the WebPub manifest for a DIVINA book": "Получить манифест WebPub для книги DIVINA",
      "Get the WebPub manifest for a PDF": "Получить манифест WebPub для PDF",
      "Get the WebPub manifest for a book": "Получить манифест WebPub для книги",
      "Get the WebPub manifest for an EPUB": "Получить манифест WebPub для EPUB",
      "Import a series metadata package": "Импортировать пакет метаданных серии",
      "Initialize a Kobo device": "Инициализировать устройство Kobo",
      "List API tokens": "Список токенов API",
      "List all series": "Список всех серий",
      "List available API token scopes": "Список доступных областей действия токена API",
      "List books currently being read": "Список книг, которые читаются сейчас",
      "List books to keep reading": "Список книг для продолжения чтения",
      "List collections in a library": "Список коллекций в библиотеке",
      "List entries awaiting bulk metadata": "Список записей, ожидающих метаданных",
      "List libraries": "Список библиотек",
      "List reading lists": "Список списков чтения",
      "List reading lists in a library": "Список списков чтения в библиотеке",
      "List recently added books": "Список недавно добавленных книг",
      "List recently added series": "Список недавно добавленных серий",
      "Proxy unmatched Kobo requests": "Проксировать нераспознанные запросы Kobo",
      "Register a KOReader user (disabled)": "Зарегистрировать пользователя KOReader (отключено)",
      "Search the catalog": "Поиск по каталогу",
      "Submit bulk metadata for application": "Отправить пакетные метаданные для применения",
      "Sync the Kobo library": "Синхронизировать библиотеку Kobo",
      "Test a scraping source connection": "Проверить подключение источника скрапинга",
      "Update Kobo reading state for a book": "Обновить состояние чтения Kobo для книги",
      "Update a scraping source": "Обновить источник скрапинга",
      "Update an API token": "Обновить токен API",
      "Update reading progress for a document": "Обновить прогресс чтения документа",
    "Serve the web interface": "Отдать веб-интерфейс",
    "Stream server-sent events": "Передавать события сервера",
  };
  B["vi"] = {
    "Delete all sync points": "Xóa tất cả điểm đồng bộ",
    "Directory listing": "Liệt kê thư mục",
    "List all libraries": "Liệt kê tất cả thư viện",
    "Add an extension store": "Thêm cửa hàng tiện ích",
    "Add book poster": "Thêm áp phích sách",
    "Add collection poster": "Thêm áp phích bộ sưu tập",
    "Add readlist poster": "Thêm áp phích danh sách đọc",
    "Add series poster": "Thêm áp phích bộ truyện",
    "Add series, a whole library or every series missing metadata to the queue": "Thêm bộ truyện, toàn bộ thư viện hoặc mọi bộ truyện thiếu siêu dữ liệu vào hàng đợi",
    "Aggregate reading devices from session history": "Tổng hợp thiết bị đọc từ lịch sử phiên",
    "Aggregated home payload for clients": "Dữ liệu trang chủ tổng hợp cho ứng dụng khách",
    "Analyze a library": "Phân tích thư viện",
    "Analyze book": "Phân tích sách",
    "Analyze series": "Phân tích bộ truyện",
    "Analyze transient book": "Phân tích sách tạm thời",
    "Apply selected fields of a match and keep a rollback snapshot": "Áp dụng các trường đã chọn của một kết quả khớp và giữ ảnh chụp để hoàn tác",
    "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "Áp dụng các trường đã chọn của một mục trong hàng đợi, trả về 204 khi mục rời khỏi hàng đợi",
    "Ask the running batch to stop after the current series": "Yêu cầu lô đang chạy dừng sau bộ truyện hiện tại",
    "Available versions and download sources": "Các phiên bản khả dụng và nguồn tải xuống",
    "Book counts grouped by computed first letter": "Số lượng sách theo chữ cái đầu được tính",
    "Book cover resized on demand": "Ảnh bìa sách được thu nhỏ theo yêu cầu",
    "Book ids whose computed first letter matches": "ID sách có chữ cái đầu được tính trùng khớp",
    "Bring back a book previously removed from the library": "Khôi phục sách đã bị gỡ khỏi thư viện trước đó",
    "Bring back a series previously removed from the library": "Khôi phục bộ truyện đã bị gỡ khỏi thư viện trước đó",
    "Browse a source by popular or latest, or search inside it": "Duyệt nguồn theo phổ biến hoặc mới nhất, hoặc tìm kiếm bên trong nguồn",
    "Browse installable extensions": "Duyệt các tiện ích có thể cài đặt",
    "Calibre availability, version and conversion mode": "Tình trạng, phiên bản và chế độ chuyển đổi của Calibre",
    "Change logger levels at runtime, use INHERIT to restore the default": "Thay đổi mức nhật ký khi đang chạy, dùng INHERIT để khôi phục mặc định",
    "Clear backoff and circuit state of a source": "Xóa trạng thái backoff và cầu dao của một nguồn",
    "Clear queue entries by scope: queued, review, failed or all": "Xóa mục hàng đợi theo phạm vi: đang chờ, cần xem xét, thất bại hoặc tất cả",
    "Clear task queue": "Xóa hàng đợi tác vụ",
    "Clear the pinned executable and fall back to the managed installation": "Bỏ ghim tệp thực thi và quay lại bản cài đặt được quản lý",
    "Client capability negotiation": "Thương lượng năng lực ứng dụng khách",
    "Compute the field level difference of a queue entry": "Tính chênh lệch ở cấp trường của một mục hàng đợi",
    "Convert a single book and queue re-analysis": "Chuyển đổi một sách và đưa vào hàng đợi phân tích lại",
    "Convert pending books of a library": "Chuyển đổi các sách đang chờ của một thư viện",
    "Count books still waiting for conversion in a library": "Đếm số sách còn chờ chuyển đổi trong thư viện",
    "Counters for the scrape queue and the inbox": "Bộ đếm cho hàng đợi cào dữ liệu và hộp thư đến",
    "Create API key": "Tạo khóa API",
    "Create a library": "Tạo thư viện",
    "Create a scrape only API token with fixed scopes": "Tạo mã thông báo API chỉ cho cào dữ liệu với phạm vi cố định",
    "Create an annotation with a locator and a text fallback": "Tạo chú thích với bộ định vị và văn bản dự phòng",
    "Create collection": "Tạo bộ sưu tập",
    "Create readlist": "Tạo danh sách đọc",
    "Create user": "Tạo người dùng",
    "Current install progress": "Tiến độ cài đặt hiện tại",
    "Current jar location and whether it can be replaced in place": "Vị trí jar hiện tại và khả năng thay thế tại chỗ",
    "Delete API key": "Xóa khóa API",
    "Delete a library": "Xóa thư viện",
    "Delete a single task by id": "Xóa một tác vụ theo ID",
    "Delete all duplicate pages by hash": "Xóa tất cả trang trùng lặp theo hash",
    "Delete an API key of a user": "Xóa khóa API của một người dùng",
    "Delete book file": "Xóa tệp sách",
    "Delete book poster": "Xóa áp phích sách",
    "Delete collection": "Xóa bộ sưu tập",
    "Delete collection poster": "Xóa áp phích bộ sưu tập",
    "Delete downloaded cache of an online series, chapters remain readable through the source": "Xóa bộ đệm đã tải của một bộ truyện trực tuyến, chương vẫn đọc được qua nguồn",
    "Delete every finished task record": "Xóa mọi bản ghi tác vụ đã hoàn thành",
    "Delete every package downloaded from the update feed": "Xóa mọi gói đã tải từ nguồn cập nhật",
    "Delete every queued task; running ones are kept": "Xóa mọi tác vụ đang chờ; tác vụ đang chạy được giữ lại",
    "Delete global settings": "Xóa cài đặt toàn cục",
    "Delete my annotation": "Xóa chú thích của tôi",
    "Delete readlist": "Xóa danh sách đọc",
    "Delete readlist poster": "Xóa áp phích danh sách đọc",
    "Delete series files": "Xóa tệp của bộ truyện",
    "Delete series poster": "Xóa áp phích bộ truyện",
    "Delete specific duplicate page": "Xóa một trang trùng lặp cụ thể",
    "Delete user": "Xóa người dùng",
    "Delete user settings": "Xóa cài đặt người dùng",
    "Discard the staged package": "Hủy gói đã chuẩn bị",
    "Download CSS file": "Tải tệp CSS",
    "Download a raw log file": "Tải tệp nhật ký thô",
    "Download a release package, verify it and stage it for install": "Tải gói phát hành, xác minh và chuẩn bị cài đặt",
    "Download an online chapter as a local CBZ and re-analyze it": "Tải một chương trực tuyến thành CBZ cục bộ và phân tích lại",
    "Download and install the latest Calibre portable build": "Tải và cài đặt bản Calibre portable mới nhất",
    "Download and install the latest Suwayomi release": "Tải và cài đặt bản phát hành Suwayomi mới nhất",
    "Download book file": "Tải tệp sách",
    "Download font file": "Tải tệp phông chữ",
    "Download from the official source and install in the background": "Tải từ nguồn chính thức và cài đặt trong nền",
    "Download readlist": "Tải danh sách đọc",
    "Download series": "Tải bộ truyện",
    "Echo build and runtime information": "Trả về thông tin bản dựng và môi trường chạy",
    "Empty the trash of one or all libraries": "Dọn thùng rác của một hoặc tất cả thư viện",
    "Empty trash for a library": "Dọn thùng rác của một thư viện",
    "Enable or disable a runtime": "Bật hoặc tắt môi trường chạy",
    "Enable or disable a user": "Bật hoặc tắt người dùng",
    "Environment and dependency check for an external runtime": "Kiểm tra môi trường và phụ thuộc cho môi trường chạy bên ngoài",
    "Equivalent shell steps for a manual install": "Các bước shell tương đương để cài đặt thủ công",
    "Expire one or all sessions of a user": "Hết hạn một hoặc tất cả phiên của người dùng",
    "Export a page range as zip": "Xuất một khoảng trang dưới dạng zip",
    "Export audit entries as CSV": "Xuất các mục kiểm toán dưới dạng CSV",
    "Export the library inventory as JSON": "Xuất kiểm kê thư viện dưới dạng JSON",
    "Get Epub resource": "Lấy tài nguyên Epub",
    "Get Lantai reading capabilities": "Lấy năng lực đọc của Lantai",
    "Get book details": "Lấy chi tiết sách",
    "Get book page image": "Lấy hình ảnh trang sách",
    "Get book page thumbnail": "Lấy ảnh thu nhỏ trang sách",
    "Get book poster image": "Lấy hình ảnh áp phích sách",
    "Get book progression": "Lấy tiến độ sách",
    "Get book's WebPub manifest": "Lấy manifest WebPub của sách",
    "Get book's WebPub manifest (DiViNa)": "Lấy manifest WebPub của sách (DiViNa)",
    "Get book's WebPub manifest (Epub)": "Lấy manifest WebPub của sách (Epub)",
    "Get book's WebPub manifest (PDF)": "Lấy manifest WebPub của sách (PDF)",
    "Get book's poster image": "Lấy hình ảnh áp phích của sách",
    "Get collection details": "Lấy chi tiết bộ sưu tập",
    "Get collection poster image": "Lấy hình ảnh áp phích bộ sưu tập",
    "Get collection's poster image": "Lấy hình ảnh áp phích của bộ sưu tập",
    "Get details for a single library": "Lấy chi tiết của một thư viện",
    "Get known duplicate image thumbnail": "Lấy ảnh thu nhỏ của hình ảnh trùng lặp đã biết",
    "Get next book in readlist": "Lấy sách tiếp theo trong danh sách đọc",
    "Get next book in series": "Lấy sách tiếp theo trong bộ truyện",
    "Get previous book in readlist": "Lấy sách trước trong danh sách đọc",
    "Get previous book in series": "Lấy sách trước trong bộ truyện",
    "Get raw book page": "Lấy trang sách thô",
    "Get reading statistics for the current user": "Lấy thống kê đọc của người dùng hiện tại",
    "Get readlist details": "Lấy chi tiết danh sách đọc",
    "Get readlist poster image": "Lấy hình ảnh áp phích danh sách đọc",
    "Get readlist read progress (Mihon)": "Lấy tiến độ đọc của danh sách đọc (Mihon)",
    "Get readlist's poster image": "Lấy hình ảnh áp phích của danh sách đọc",
    "Get series details": "Lấy chi tiết bộ truyện",
    "Get series poster image": "Lấy hình ảnh áp phích bộ truyện",
    "Get series read progress (Mihon)": "Lấy tiến độ đọc của bộ truyện (Mihon)",
    "Get series' poster image": "Lấy hình ảnh áp phích của bộ truyện",
    "Get the archive status of the current user's reading statistics": "Lấy trạng thái lưu trữ thống kê đọc của người dùng hiện tại",
    "Get the reading time of one book for the current user": "Lấy thời gian đọc của một sách cho người dùng hiện tại",
    "Get transient book page": "Lấy trang sách tạm thời",
    "Get unknown duplicate image thumbnail": "Lấy ảnh thu nhỏ của hình ảnh trùng lặp chưa biết",
    "Import a Suwayomi jar manually": "Nhập jar Suwayomi thủ công",
    "Import a locally downloaded Calibre portable installer": "Nhập bộ cài Calibre portable đã tải về cục bộ",
    "Import books": "Nhập sách",
    "Include or exclude a source from search and entry points": "Bao gồm hoặc loại trừ một nguồn khỏi tìm kiếm và điểm đầu vào",
    "Install an extension from an uploaded package": "Cài đặt tiện ích từ gói đã tải lên",
    "Install an extension from the catalog": "Cài đặt tiện ích từ danh mục",
    "Latest Calibre portable release": "Bản phát hành Calibre portable mới nhất",
    "Latest Suwayomi release": "Bản phát hành Suwayomi mới nhất",
    "List active reading sessions for the current user": "Liệt kê các phiên đọc đang hoạt động của người dùng hiện tại",
    "List age ratings": "Liệt kê phân loại độ tuổi",
    "List and search users": "Liệt kê và tìm kiếm người dùng",
    "List applied scrape records of the series behind a queue entry": "Liệt kê các bản ghi cào dữ liệu đã áp dụng của bộ truyện đứng sau một mục hàng đợi",
    "List assignable roles": "Liệt kê các vai trò có thể gán",
    "List authors": "Liệt kê tác giả",
    "List authors names": "Liệt kê tên tác giả",
    "List authors roles": "Liệt kê vai trò tác giả",
    "List authors' names": "Liệt kê tên của các tác giả",
    "List authors' roles": "Liệt kê vai trò của các tác giả",
    "List available log files": "Liệt kê các tệp nhật ký khả dụng",
    "List book pages": "Liệt kê các trang sách",
    "List book posters": "Liệt kê áp phích sách",
    "List book tags": "Liệt kê thẻ của sách",
    "List book's positions": "Liệt kê vị trí của sách",
    "List book's readlists": "Liệt kê danh sách đọc của sách",
    "List books": "Liệt kê sách",
    "List books on deck": "Liệt kê sách trên deck",
    "List books removed from library but still present on disk": "Liệt kê sách đã gỡ khỏi thư viện nhưng vẫn còn trên đĩa",
    "List collection's posters": "Liệt kê áp phích của bộ sưu tập",
    "List collection's series": "Liệt kê bộ truyện của bộ sưu tập",
    "List collections": "Liệt kê bộ sưu tập",
    "List duplicate books": "Liệt kê sách trùng lặp",
    "List duplicate matches": "Liệt kê kết quả khớp trùng lặp",
    "List extension stores": "Liệt kê cửa hàng tiện ích",
    "List finished tasks, paged": "Liệt kê tác vụ đã hoàn thành, phân trang",
    "List font families": "Liệt kê họ phông chữ",
    "List genres": "Liệt kê thể loại",
    "List historical events": "Liệt kê sự kiện lịch sử",
    "List installed extensions": "Liệt kê tiện ích đã cài đặt",
    "List known duplicates": "Liệt kê các bản trùng lặp đã biết",
    "List languages": "Liệt kê ngôn ngữ",
    "List latest books": "Liệt kê sách mới nhất",
    "List latest series": "Liệt kê bộ truyện mới nhất",
    "List loggers and their levels": "Liệt kê logger và mức của chúng",
    "List match candidates with their current and proposed values": "Liệt kê ứng viên khớp với giá trị hiện tại và đề xuất",
    "List match jobs of this session": "Liệt kê các công việc khớp của phiên này",
    "List materialized chapters of a subscription with download state": "Liệt kê các chương đã cụ thể hóa của một đăng ký kèm trạng thái tải",
    "List my annotations of a book": "Liệt kê chú thích của tôi cho một sách",
    "List new series": "Liệt kê bộ truyện mới",
    "List notable entities": "Liệt kê thực thể đáng chú ý",
    "List notable entities kinds": "Liệt kê loại thực thể đáng chú ý",
    "List notable entities names": "Liệt kê tên thực thể đáng chú ý",
    "List online metadata sources and their daily usage": "Liệt kê nguồn siêu dữ liệu trực tuyến và mức sử dụng hàng ngày",
    "List publishers": "Liệt kê nhà xuất bản",
    "List queued and running tasks, paged": "Liệt kê tác vụ đang chờ và đang chạy, phân trang",
    "List reading sessions": "Liệt kê phiên đọc",
    "List readlist's books": "Liệt kê sách của danh sách đọc",
    "List readlist's posters": "Liệt kê áp phích của danh sách đọc",
    "List readlists": "Liệt kê danh sách đọc",
    "List registered OAuth2 providers": "Liệt kê nhà cung cấp OAuth2 đã đăng ký",
    "List releases": "Liệt kê bản phát hành",
    "List series": "Liệt kê bộ truyện",
    "List series groups": "Liệt kê nhóm bộ truyện",
    "List series posters": "Liệt kê áp phích bộ truyện",
    "List series release dates": "Liệt kê ngày phát hành của bộ truyện",
    "List series release years": "Liệt kê năm phát hành của bộ truyện",
    "List series removed from library but still present on disk": "Liệt kê bộ truyện đã gỡ khỏi thư viện nhưng vẫn còn trên đĩa",
    "List series tags": "Liệt kê thẻ của bộ truyện",
    "List series' books": "Liệt kê sách của bộ truyện",
    "List series' collections": "Liệt kê bộ sưu tập của bộ truyện",
    "List sharing labels": "Liệt kê nhãn chia sẻ",
    "List sources with their search/entry toggle state": "Liệt kê nguồn kèm trạng thái bật tắt tìm kiếm/đầu vào",
    "List subscribed online mangas": "Liệt kê manga trực tuyến đã đăng ký",
    "List tags": "Liệt kê thẻ",
    "List the API keys of a user": "Liệt kê khóa API của một người dùng",
    "List the actions present in the audit trail": "Liệt kê các hành động có trong nhật ký kiểm toán",
    "List the active sessions of a user": "Liệt kê phiên đang hoạt động của một người dùng",
    "List the books of the series behind a queue entry with their current metadata": "Liệt kê sách của bộ truyện đứng sau một mục hàng đợi kèm siêu dữ liệu hiện tại",
    "List the runtime filters exposed by a source": "Liệt kê bộ lọc môi trường chạy do một nguồn cung cấp",
    "List the scrape only API tokens": "Liệt kê mã thông báo API chỉ cho cào dữ liệu",
    "List the sources provided by an extension": "Liệt kê nguồn do một tiện ích cung cấp",
    "List trash entries of one or all libraries": "Liệt kê mục thùng rác của một hoặc tất cả thư viện",
    "List unknown duplicates": "Liệt kê các bản trùng lặp chưa biết",
    "List updated series": "Liệt kê bộ truyện đã cập nhật",
    "List users": "Liệt kê người dùng",
    "Mark a match as skipped": "Đánh dấu một kết quả khớp là bỏ qua",
    "Mark announcements as read": "Đánh dấu thông báo là đã đọc",
    "Mark book as unread": "Đánh dấu sách là chưa đọc",
    "Mark book poster as selected": "Đánh dấu áp phích sách là đã chọn",
    "Mark book progression": "Ghi nhận tiến độ sách",
    "Mark book's read progress": "Ghi nhận tiến độ đọc của sách",
    "Mark collection poster as selected": "Đánh dấu áp phích bộ sưu tập là đã chọn",
    "Mark duplicate page as known": "Đánh dấu trang trùng lặp là đã biết",
    "Mark readlist poster as selected": "Đánh dấu áp phích danh sách đọc là đã chọn",
    "Mark series as read": "Đánh dấu bộ truyện là đã đọc",
    "Mark series as unread": "Đánh dấu bộ truyện là chưa đọc",
    "Mark series poster as selected": "Đánh dấu áp phích bộ truyện là đã chọn",
    "Match ComicRack list": "Khớp danh sách ComicRack",
    "Match a single series against enabled sources": "Khớp một bộ truyện với các nguồn đã bật",
    "Packages already downloaded into the config directory": "Các gói đã tải vào thư mục cấu hình",
    "Per-library series/book counts and total size": "Số lượng bộ truyện/sách và tổng dung lượng theo thư viện",
    "Permanently delete a trash entry and its files": "Xóa vĩnh viễn một mục thùng rác và các tệp của nó",
    "Pin an existing ebook-convert executable": "Ghim tệp thực thi ebook-convert hiện có",
    "Poll a match job": "Truy vấn một công việc khớp",
    "Probe an ebook-convert executable without saving it": "Kiểm tra tệp thực thi ebook-convert mà không lưu",
    "Probe every external runtime managed by Lantai": "Kiểm tra mọi môi trường chạy bên ngoài do Lantai quản lý",
    "Probe one external runtime": "Kiểm tra một môi trường chạy bên ngoài",
    "Progress of the package download currently running": "Tiến độ tải gói đang chạy",
    "Progress of the package validation currently running": "Tiến độ xác minh gói đang chạy",
    "Proxy the remote cover of a search result": "Ủy quyền ảnh bìa từ xa của một kết quả tìm kiếm",
    "Purge reading sessions beyond the retention window": "Xóa các phiên đọc vượt quá cửa sổ lưu trữ",
    "Re-verify the most recent downloaded package": "Xác minh lại gói đã tải gần nhất",
    "Read Suwayomi settings": "Đọc cài đặt Suwayomi",
    "Read bootstrap payload": "Đọc dữ liệu khởi động",
    "Read chapter bundle": "Đọc gói chương",
    "Read chapter outline page": "Đọc trang phác thảo chương",
    "Read chapter window": "Đọc cửa sổ chương",
    "Read image resource": "Đọc tài nguyên hình ảnh",
    "Read one page of the global scrape queue": "Đọc một trang của hàng đợi cào dữ liệu toàn cục",
    "Read one page of the scrape queue for an external agent": "Đọc một trang của hàng đợi cào dữ liệu cho tác nhân bên ngoài",
    "Read one queue entry with the hints an external agent needs": "Đọc một mục hàng đợi kèm gợi ý mà tác nhân bên ngoài cần",
    "Read page thumbnail": "Đọc ảnh thu nhỏ trang",
    "Read scrape settings": "Đọc cài đặt cào dữ liệu",
    "Read structured log entries, cursor based": "Đọc các mục nhật ký có cấu trúc, dựa trên con trỏ",
    "Read the configurable preferences of a source": "Đọc các tùy chọn có thể cấu hình của một nguồn",
    "Read the remote detail of a single manga": "Đọc chi tiết từ xa của một manga",
    "Read the update feed and report the latest release plus any downloaded package": "Đọc nguồn cập nhật và báo cáo bản phát hành mới nhất cùng mọi gói đã tải",
    "Read trash settings": "Đọc cài đặt thùng rác",
    "Rebuild the daily reading statistics of the current user": "Xây dựng lại thống kê đọc hàng ngày của người dùng hiện tại",
    "Recompute first letters for every series": "Tính lại chữ cái đầu cho mọi bộ truyện",
    "Refresh book metadata": "Làm mới siêu dữ liệu sách",
    "Refresh installed extension metadata": "Làm mới siêu dữ liệu của tiện ích đã cài đặt",
    "Refresh metadata for a library": "Làm mới siêu dữ liệu của một thư viện",
    "Refresh series metadata": "Làm mới siêu dữ liệu bộ truyện",
    "Regenerate books posters": "Tạo lại áp phích sách",
    "Remove a book from the library, keeping its file untouched": "Gỡ sách khỏi thư viện, giữ nguyên tệp",
    "Remove a series from the library, keeping its files untouched": "Gỡ bộ truyện khỏi thư viện, giữ nguyên các tệp",
    "Remove a single queue entry": "Gỡ một mục hàng đợi",
    "Replace the roles of a user": "Thay thế vai trò của một người dùng",
    "Replace the running jar with the staged one and restart": "Thay jar đang chạy bằng bản đã chuẩn bị và khởi động lại",
    "Report a reading heartbeat": "Báo nhịp đọc",
    "Reset a queue entry back to queued": "Đặt lại một mục hàng đợi về trạng thái chờ",
    "Reset the password of a user and expire its sessions": "Đặt lại mật khẩu của một người dùng và hết hạn các phiên",
    "Restore a trash entry back to its library": "Khôi phục một mục thùng rác về thư viện của nó",
    "Restore logger levels to their default": "Khôi phục mức nhật ký về mặc định",
    "Restore the last backup jar and restart": "Khôi phục jar sao lưu gần nhất và khởi động lại",
    "Resume task processing": "Tiếp tục xử lý tác vụ",
    "Retrieve API keys": "Lấy khóa API",
    "Retrieve a single audit entry": "Lấy một mục kiểm toán",
    "Retrieve a user with its activity summary": "Lấy một người dùng kèm tóm tắt hoạt động",
    "Retrieve announcements": "Lấy thông báo",
    "Retrieve authentication activity": "Lấy hoạt động xác thực",
    "Retrieve authentication activity for the current user": "Lấy hoạt động xác thực của người dùng hiện tại",
    "Retrieve current user": "Lấy người dùng hiện tại",
    "Retrieve global client settings": "Lấy cài đặt ứng dụng khách toàn cục",
    "Retrieve latest authentication activity for a user": "Lấy hoạt động xác thực gần nhất của một người dùng",
    "Retrieve server settings": "Lấy cài đặt máy chủ",
    "Retrieve user client settings": "Lấy cài đặt ứng dụng khách của người dùng",
    "Revoke a scrape only API token": "Thu hồi mã thông báo API chỉ cho cào dữ liệu",
    "Rotate the managed Suwayomi credentials": "Xoay vòng thông tin đăng nhập Suwayomi được quản lý",
    "Run the automatic install for the given runtimes": "Chạy cài đặt tự động cho các môi trường chạy được chỉ định",
    "Runtime and storage status": "Trạng thái môi trường chạy và lưu trữ",
    "Save global settings": "Lưu cài đặt toàn cục",
    "Save user settings": "Lưu cài đặt người dùng",
    "Scan a library": "Quét thư viện",
    "Scan folder for transient books": "Quét thư mục tìm sách tạm thời",
    "Search audit entries": "Tìm kiếm trong các mục kiểm toán",
    "Search inside a book": "Tìm kiếm bên trong một sách",
    "Search mangas across enabled sources": "Tìm kiếm manga trên các nguồn đã bật",
    "Search metadata candidates from one or all enabled sources": "Tìm kiếm ứng viên siêu dữ liệu từ một hoặc tất cả nguồn đã bật",
    "Series ids whose computed first letter matches": "ID bộ truyện có chữ cái đầu được tính trùng khớp",
    "Set cookie": "Đặt cookie",
    "Set the conversion mode: never, on-demand or eager": "Đặt chế độ chuyển đổi: không bao giờ, theo yêu cầu hoặc ngay lập tức",
    "Set the trash quota of a library, zero means inheriting the global default": "Đặt hạn mức thùng rác của một thư viện, số 0 nghĩa là kế thừa mặc định toàn cục",
    "Snapshot of the automatic install performed at startup": "Ảnh chụp cài đặt tự động được thực hiện khi khởi động",
    "Snapshot the main and tasks SQLite databases": "Ảnh chụp cơ sở dữ liệu SQLite chính và tác vụ",
    "Start a background auto-match job for a library": "Bắt đầu công việc khớp tự động trong nền cho một thư viện",
    "Start an external runtime": "Khởi động một môi trường chạy bên ngoài",
    "Start or resume scraping the queue": "Bắt đầu hoặc tiếp tục cào dữ liệu hàng đợi",
    "Start, stop or restart Suwayomi": "Khởi động, dừng hoặc khởi động lại Suwayomi",
    "Stop an external runtime": "Dừng một môi trường chạy bên ngoài",
    "Stop taking new tasks; running tasks finish": "Ngừng nhận tác vụ mới; tác vụ đang chạy sẽ hoàn tất",
    "Stop the current reading session": "Dừng phiên đọc hiện tại",
    "Stream new log entries": "Truyền phát các mục nhật ký mới",
    "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "Gửi kết quả cào dữ liệu cho một mục hàng đợi, hoặc truyền ID bộ truyện để ghi đè kết quả đã áp dụng",
    "Subscribe to a remote manga and materialize it as a series": "Đăng ký một manga từ xa và cụ thể hóa thành bộ truyện",
    "Suwayomi runtime status": "Trạng thái môi trường chạy Suwayomi",
    "Sync offline reading sessions": "Đồng bộ phiên đọc ngoại tuyến",
    "Synchronize chapters of a subscribed series": "Đồng bộ chương của một bộ truyện đã đăng ký",
    "Undo an applied match using its snapshot": "Hoàn tác một kết quả khớp đã áp dụng bằng ảnh chụp của nó",
    "Uninstall a runtime, data is removed only when purge is requested": "Gỡ cài đặt môi trường chạy, dữ liệu chỉ bị xóa khi yêu cầu thanh lọc",
    "Uninstall an extension": "Gỡ cài đặt tiện ích",
    "Unsubscribe a series and remove its placeholder files": "Hủy đăng ký một bộ truyện và xóa các tệp giữ chỗ",
    "Update Suwayomi settings": "Cập nhật cài đặt Suwayomi",
    "Update a library": "Cập nhật thư viện",
    "Update an installed extension": "Cập nhật tiện ích đã cài đặt",
    "Update book metadata": "Cập nhật siêu dữ liệu sách",
    "Update book metadata in bulk": "Cập nhật siêu dữ liệu sách hàng loạt",
    "Update collection": "Cập nhật bộ sưu tập",
    "Update current user's password": "Cập nhật mật khẩu của người dùng hiện tại",
    "Update note, color or marker of my annotation": "Cập nhật ghi chú, màu hoặc dấu của chú thích của tôi",
    "Update one preference of a source and return the refreshed list": "Cập nhật một tùy chọn của nguồn và trả về danh sách đã làm mới",
    "Update readlist": "Cập nhật danh sách đọc",
    "Update readlist read progress (Mihon)": "Cập nhật tiến độ đọc của danh sách đọc (Mihon)",
    "Update scrape settings, trusting the results requires an explicit confirmation": "Cập nhật cài đặt cào dữ liệu, tin tưởng kết quả cần xác nhận rõ ràng",
    "Update series metadata": "Cập nhật siêu dữ liệu bộ truyện",
    "Update series read progress (Mihon)": "Cập nhật tiến độ đọc của bộ truyện (Mihon)",
    "Update server settings": "Cập nhật cài đặt máy chủ",
    "Update the username and email of a user": "Cập nhật tên người dùng và email của một người dùng",
    "Update trash settings": "Cập nhật cài đặt thùng rác",
    "Update user": "Cập nhật người dùng",
    "Update user's password": "Cập nhật mật khẩu của một người dùng",
    "Upload a package and install it in the background": "Tải lên một gói và cài đặt trong nền",
    "Upload an upgrade package and validate it without touching the running jar": "Tải lên gói nâng cấp và xác minh mà không động đến jar đang chạy",
      "Add a scraping source": "Thêm một nguồn cào dữ liệu",
      "Apply bulk series metadata from a text payload": "Áp dụng hàng loạt siêu dữ liệu bộ truyện từ văn bản",
      "Authenticate a KOReader user": "Xác thực người dùng KOReader",
      "Browse a library": "Duyệt thư viện",
      "Check Kobo connectivity": "Kiểm tra kết nối Kobo",
      "Count entries awaiting bulk metadata": "Đếm các mục đang chờ siêu dữ liệu",
      "Create an API token": "Tạo mã thông báo API",
      "Delete a scraping source": "Xóa một nguồn cào dữ liệu",
      "Delete an API token": "Xóa mã thông báo API",
      "Download a book file for Kobo": "Tải xuống tệp sách cho Kobo",
      "Download the metadata package template": "Tải xuống mẫu gói siêu dữ liệu",
      "Enable or disable a scraping source": "Bật hoặc tắt một nguồn cào dữ liệu",
      "Exchange a Kobo device token": "Trao đổi mã thông báo thiết bị Kobo",
      "Export series metadata as a text file": "Xuất siêu dữ liệu bộ truyện ra tệp văn bản",
      "Get Kobo metadata for a book": "Lấy siêu dữ liệu Kobo của một cuốn sách",
      "Get Kobo reading state for a book": "Lấy trạng thái đọc Kobo của một cuốn sách",
      "Get a book cover for Kobo": "Lấy bìa sách cho Kobo",
      "Get a book page image": "Lấy hình ảnh trang sách",
      "Get a book page thumbnail": "Lấy hình thu nhỏ trang sách",
      "Get a book thumbnail": "Lấy hình thu nhỏ của sách",
      "Get one collection": "Lấy một bộ sưu tập",
      "Get one library": "Lấy một thư viện",
      "Get one reading list": "Lấy một danh sách đọc",
      "Get one series with its books": "Lấy một bộ truyện cùng các sách của nó",
      "Get reading progress for a document": "Lấy tiến độ đọc của một tài liệu",
      "Get scraping source details": "Lấy chi tiết nguồn cào dữ liệu",
      "Get the OPDS authentication document": "Lấy tài liệu xác thực OPDS",
      "Get the OPDS search description": "Lấy mô tả tìm kiếm OPDS",
      "Get the OPDS v1 catalog": "Lấy danh mục OPDS v1",
      "Get the OPDS v2 catalog": "Lấy danh mục OPDS v2",
      "Get the WebPub manifest for a DIVINA book": "Lấy manifest WebPub cho sách DIVINA",
      "Get the WebPub manifest for a PDF": "Lấy manifest WebPub cho PDF",
      "Get the WebPub manifest for a book": "Lấy manifest WebPub cho sách",
      "Get the WebPub manifest for an EPUB": "Lấy manifest WebPub cho EPUB",
      "Import a series metadata package": "Nhập gói siêu dữ liệu bộ truyện",
      "Initialize a Kobo device": "Khởi tạo thiết bị Kobo",
      "List API tokens": "Liệt kê mã thông báo API",
      "List all series": "Liệt kê tất cả bộ truyện",
      "List available API token scopes": "Liệt kê phạm vi mã thông báo API khả dụng",
      "List books currently being read": "Liệt kê sách đang đọc",
      "List books to keep reading": "Liệt kê sách để đọc tiếp",
      "List collections in a library": "Liệt kê bộ sưu tập trong thư viện",
      "List entries awaiting bulk metadata": "Liệt kê các mục đang chờ siêu dữ liệu",
      "List libraries": "Liệt kê thư viện",
      "List reading lists": "Liệt kê danh sách đọc",
      "List reading lists in a library": "Liệt kê danh sách đọc trong thư viện",
      "List recently added books": "Liệt kê sách mới thêm",
      "List recently added series": "Liệt kê bộ truyện mới thêm",
      "Proxy unmatched Kobo requests": "Chuyển tiếp các yêu cầu Kobo không khớp",
      "Register a KOReader user (disabled)": "Đăng ký người dùng KOReader (đã tắt)",
      "Search the catalog": "Tìm kiếm danh mục",
      "Submit bulk metadata for application": "Gửi siêu dữ liệu hàng loạt để áp dụng",
      "Sync the Kobo library": "Đồng bộ thư viện Kobo",
      "Test a scraping source connection": "Kiểm tra kết nối nguồn cào dữ liệu",
      "Update Kobo reading state for a book": "Cập nhật trạng thái đọc Kobo của một cuốn sách",
      "Update a scraping source": "Cập nhật nguồn cào dữ liệu",
      "Update an API token": "Cập nhật mã thông báo API",
      "Update reading progress for a document": "Cập nhật tiến độ đọc của một tài liệu",
    "Serve the web interface": "Phục vụ giao diện web",
    "Stream server-sent events": "Truyền sự kiện từ máy chủ",
  };
  B["ar"] = {
    "Delete all sync points": "حذف كل نقاط المزامنة",
    "Directory listing": "قائمة الدليل",
    "List all libraries": "عرض كل المكتبات",
    "Add an extension store": "إضافة متجر إضافات",
    "Add book poster": "إضافة ملصق كتاب",
    "Add collection poster": "إضافة ملصق مجموعة",
    "Add readlist poster": "إضافة ملصق قائمة قراءة",
    "Add series poster": "إضافة ملصق سلسلة",
    "Add series, a whole library or every series missing metadata to the queue": "إضافة سلسلة أو مكتبة كاملة أو كل السلاسل التي تنقصها البيانات الوصفية إلى قائمة الانتظار",
    "Aggregate reading devices from session history": "تجميع أجهزة القراءة من سجل الجلسات",
    "Aggregated home payload for clients": "بيانات الصفحة الرئيسية المجمّعة للعملاء",
    "Analyze a library": "تحليل مكتبة",
    "Analyze book": "تحليل كتاب",
    "Analyze series": "تحليل سلسلة",
    "Analyze transient book": "تحليل كتاب مؤقت",
    "Apply selected fields of a match and keep a rollback snapshot": "تطبيق الحقول المحددة لمطابقة مع الاحتفاظ بلقطة للتراجع",
    "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue": "تطبيق الحقول المختارة لعنصر في قائمة الانتظار، يُرجع 204 عندما يغادر العنصر القائمة",
    "Ask the running batch to stop after the current series": "مطالبة الدفعة قيد التشغيل بالتوقف بعد السلسلة الحالية",
    "Available versions and download sources": "الإصدارات المتاحة ومصادر التنزيل",
    "Book counts grouped by computed first letter": "عدد الكتب مجمّعًا حسب الحرف الأول المحسوب",
    "Book cover resized on demand": "غلاف الكتاب يُغيَّر حجمه عند الطلب",
    "Book ids whose computed first letter matches": "معرّفات الكتب التي يطابق حرفها الأول المحسوب",
    "Bring back a book previously removed from the library": "استعادة كتاب سبق إزالته من المكتبة",
    "Bring back a series previously removed from the library": "استعادة سلسلة سبق إزالتها من المكتبة",
    "Browse a source by popular or latest, or search inside it": "تصفح مصدر حسب الشهرة أو الأحدث، أو البحث داخله",
    "Browse installable extensions": "تصفح الإضافات القابلة للتثبيت",
    "Calibre availability, version and conversion mode": "توفر Calibre وإصداره ووضع التحويل",
    "Change logger levels at runtime, use INHERIT to restore the default": "تغيير مستويات المسجّل أثناء التشغيل، استخدم INHERIT لاستعادة الافتراضي",
    "Clear backoff and circuit state of a source": "مسح حالة التأخير والقاطع لمصدر",
    "Clear queue entries by scope: queued, review, failed or all": "مسح عناصر قائمة الانتظار حسب النطاق: في الانتظار، مراجعة، فاشلة أو الكل",
    "Clear task queue": "مسح قائمة انتظار المهام",
    "Clear the pinned executable and fall back to the managed installation": "إزالة الملف التنفيذي المثبّت والعودة إلى التثبيت المُدار",
    "Client capability negotiation": "تفاوض قدرات العميل",
    "Compute the field level difference of a queue entry": "حساب الفرق على مستوى الحقول لعنصر في قائمة الانتظار",
    "Convert a single book and queue re-analysis": "تحويل كتاب واحد وإضافة إعادة التحليل إلى قائمة الانتظار",
    "Convert pending books of a library": "تحويل الكتب المعلّقة في مكتبة",
    "Count books still waiting for conversion in a library": "عدّ الكتب التي ما زالت تنتظر التحويل في مكتبة",
    "Counters for the scrape queue and the inbox": "عدّادات قائمة انتظار الاستخراج وصندوق الوارد",
    "Create API key": "إنشاء مفتاح API",
    "Create a library": "إنشاء مكتبة",
    "Create a scrape only API token with fixed scopes": "إنشاء رمز API للاستخراج فقط بنطاقات ثابتة",
    "Create an annotation with a locator and a text fallback": "إنشاء ملاحظة بمحدد موقع ونص احتياطي",
    "Create collection": "إنشاء مجموعة",
    "Create readlist": "إنشاء قائمة قراءة",
    "Create user": "إنشاء مستخدم",
    "Current install progress": "تقدم التثبيت الحالي",
    "Current jar location and whether it can be replaced in place": "موقع jar الحالي وما إذا كان يمكن استبداله في مكانه",
    "Delete API key": "حذف مفتاح API",
    "Delete a library": "حذف مكتبة",
    "Delete a single task by id": "حذف مهمة واحدة بالمعرّف",
    "Delete all duplicate pages by hash": "حذف كل الصفحات المكررة حسب التجزئة",
    "Delete an API key of a user": "حذف مفتاح API لمستخدم",
    "Delete book file": "حذف ملف الكتاب",
    "Delete book poster": "حذف ملصق الكتاب",
    "Delete collection": "حذف مجموعة",
    "Delete collection poster": "حذف ملصق المجموعة",
    "Delete downloaded cache of an online series, chapters remain readable through the source": "حذف ذاكرة التخزين المؤقت المنزّلة لسلسلة عبر الإنترنت، تبقى الفصول قابلة للقراءة عبر المصدر",
    "Delete every finished task record": "حذف كل سجلات المهام المكتملة",
    "Delete every package downloaded from the update feed": "حذف كل الحزم المنزّلة من موجز التحديثات",
    "Delete every queued task; running ones are kept": "حذف كل المهام في الانتظار، تُحفظ المهام قيد التشغيل",
    "Delete global settings": "حذف الإعدادات العامة",
    "Delete my annotation": "حذف ملاحظتي",
    "Delete readlist": "حذف قائمة قراءة",
    "Delete readlist poster": "حذف ملصق قائمة القراءة",
    "Delete series files": "حذف ملفات السلسلة",
    "Delete series poster": "حذف ملصق السلسلة",
    "Delete specific duplicate page": "حذف صفحة مكررة محددة",
    "Delete user": "حذف مستخدم",
    "Delete user settings": "حذف إعدادات المستخدم",
    "Discard the staged package": "التخلص من الحزمة المجهّزة",
    "Download CSS file": "تنزيل ملف CSS",
    "Download a raw log file": "تنزيل ملف سجل خام",
    "Download a release package, verify it and stage it for install": "تنزيل حزمة إصدار والتحقق منها وتجهيزها للتثبيت",
    "Download an online chapter as a local CBZ and re-analyze it": "تنزيل فصل عبر الإنترنت كملف CBZ محلي وإعادة تحليله",
    "Download and install the latest Calibre portable build": "تنزيل وتثبيت أحدث إصدار محمول من Calibre",
    "Download and install the latest Suwayomi release": "تنزيل وتثبيت أحدث إصدار من Suwayomi",
    "Download book file": "تنزيل ملف الكتاب",
    "Download font file": "تنزيل ملف الخط",
    "Download from the official source and install in the background": "التنزيل من المصدر الرسمي والتثبيت في الخلفية",
    "Download readlist": "تنزيل قائمة القراءة",
    "Download series": "تنزيل السلسلة",
    "Echo build and runtime information": "إرجاع معلومات البناء وبيئة التشغيل",
    "Empty the trash of one or all libraries": "إفراغ سلة المحذوفات لمكتبة واحدة أو لكل المكتبات",
    "Empty trash for a library": "إفراغ سلة المحذوفات لمكتبة",
    "Enable or disable a runtime": "تمكين أو تعطيل بيئة تشغيل",
    "Enable or disable a user": "تمكين أو تعطيل مستخدم",
    "Environment and dependency check for an external runtime": "فحص البيئة والتبعيات لبيئة تشغيل خارجية",
    "Equivalent shell steps for a manual install": "خطوات shell المكافئة للتثبيت اليدوي",
    "Expire one or all sessions of a user": "إنهاء جلسة واحدة أو كل جلسات مستخدم",
    "Export a page range as zip": "تصدير نطاق صفحات كملف zip",
    "Export audit entries as CSV": "تصدير مدخلات التدقيق كملف CSV",
    "Export the library inventory as JSON": "تصدير جرد المكتبة كملف JSON",
    "Get Epub resource": "الحصول على مورد Epub",
    "Get Lantai reading capabilities": "الحصول على قدرات القراءة في Lantai",
    "Get book details": "الحصول على تفاصيل الكتاب",
    "Get book page image": "الحصول على صورة صفحة الكتاب",
    "Get book page thumbnail": "الحصول على صورة مصغرة لصفحة الكتاب",
    "Get book poster image": "الحصول على صورة ملصق الكتاب",
    "Get book progression": "الحصول على تقدم الكتاب",
    "Get book's WebPub manifest": "الحصول على بيان WebPub للكتاب",
    "Get book's WebPub manifest (DiViNa)": "الحصول على بيان WebPub للكتاب (DiViNa)",
    "Get book's WebPub manifest (Epub)": "الحصول على بيان WebPub للكتاب (Epub)",
    "Get book's WebPub manifest (PDF)": "الحصول على بيان WebPub للكتاب (PDF)",
    "Get book's poster image": "الحصول على صورة ملصق الكتاب",
    "Get collection details": "الحصول على تفاصيل المجموعة",
    "Get collection poster image": "الحصول على صورة ملصق المجموعة",
    "Get collection's poster image": "الحصول على صورة ملصق المجموعة",
    "Get details for a single library": "الحصول على تفاصيل مكتبة واحدة",
    "Get known duplicate image thumbnail": "الحصول على صورة مصغرة لصورة مكررة معروفة",
    "Get next book in readlist": "الحصول على الكتاب التالي في قائمة القراءة",
    "Get next book in series": "الحصول على الكتاب التالي في السلسلة",
    "Get previous book in readlist": "الحصول على الكتاب السابق في قائمة القراءة",
    "Get previous book in series": "الحصول على الكتاب السابق في السلسلة",
    "Get raw book page": "الحصول على صفحة كتاب خام",
    "Get reading statistics for the current user": "الحصول على إحصاءات قراءة المستخدم الحالي",
    "Get readlist details": "الحصول على تفاصيل قائمة القراءة",
    "Get readlist poster image": "الحصول على صورة ملصق قائمة القراءة",
    "Get readlist read progress (Mihon)": "الحصول على تقدم قراءة قائمة القراءة (Mihon)",
    "Get readlist's poster image": "الحصول على صورة ملصق قائمة القراءة",
    "Get series details": "الحصول على تفاصيل السلسلة",
    "Get series poster image": "الحصول على صورة ملصق السلسلة",
    "Get series read progress (Mihon)": "الحصول على تقدم قراءة السلسلة (Mihon)",
    "Get series' poster image": "الحصول على صورة ملصق السلسلة",
    "Get the archive status of the current user's reading statistics": "الحصول على حالة أرشفة إحصاءات قراءة المستخدم الحالي",
    "Get the reading time of one book for the current user": "الحصول على وقت قراءة كتاب واحد للمستخدم الحالي",
    "Get transient book page": "الحصول على صفحة كتاب مؤقت",
    "Get unknown duplicate image thumbnail": "الحصول على صورة مصغرة لصورة مكررة غير معروفة",
    "Import a Suwayomi jar manually": "استيراد jar الخاص بـ Suwayomi يدويًا",
    "Import a locally downloaded Calibre portable installer": "استيراد مثبّت Calibre المحمول المنزّل محليًا",
    "Import books": "استيراد كتب",
    "Include or exclude a source from search and entry points": "تضمين مصدر أو استبعاده من البحث ونقاط الدخول",
    "Install an extension from an uploaded package": "تثبيت إضافة من حزمة مرفوعة",
    "Install an extension from the catalog": "تثبيت إضافة من الكتالوج",
    "Latest Calibre portable release": "أحدث إصدار محمول من Calibre",
    "Latest Suwayomi release": "أحدث إصدار من Suwayomi",
    "List active reading sessions for the current user": "عرض جلسات القراءة النشطة للمستخدم الحالي",
    "List age ratings": "عرض تصنيفات الأعمار",
    "List and search users": "عرض المستخدمين والبحث فيهم",
    "List applied scrape records of the series behind a queue entry": "عرض سجلات الاستخراج المطبّقة للسلسلة خلف عنصر في قائمة الانتظار",
    "List assignable roles": "عرض الأدوار القابلة للتخصيص",
    "List authors": "عرض المؤلفين",
    "List authors names": "عرض أسماء المؤلفين",
    "List authors roles": "عرض أدوار المؤلفين",
    "List authors' names": "عرض أسماء المؤلفين",
    "List authors' roles": "عرض أدوار المؤلفين",
    "List available log files": "عرض ملفات السجل المتاحة",
    "List book pages": "عرض صفحات الكتاب",
    "List book posters": "عرض ملصقات الكتاب",
    "List book tags": "عرض وسوم الكتاب",
    "List book's positions": "عرض مواضع الكتاب",
    "List book's readlists": "عرض قوائم قراءة الكتاب",
    "List books": "عرض الكتب",
    "List books on deck": "عرض الكتب في الواجهة",
    "List books removed from library but still present on disk": "عرض الكتب المُزالة من المكتبة لكنها ما زالت موجودة على القرص",
    "List collection's posters": "عرض ملصقات المجموعة",
    "List collection's series": "عرض سلاسل المجموعة",
    "List collections": "عرض المجموعات",
    "List duplicate books": "عرض الكتب المكررة",
    "List duplicate matches": "عرض المطابقات المكررة",
    "List extension stores": "عرض متاجر الإضافات",
    "List finished tasks, paged": "عرض المهام المكتملة، بترقيم الصفحات",
    "List font families": "عرض عائلات الخطوط",
    "List genres": "عرض الأنواع",
    "List historical events": "عرض الأحداث التاريخية",
    "List installed extensions": "عرض الإضافات المثبّتة",
    "List known duplicates": "عرض التكرارات المعروفة",
    "List languages": "عرض اللغات",
    "List latest books": "عرض أحدث الكتب",
    "List latest series": "عرض أحدث السلاسل",
    "List loggers and their levels": "عرض المسجّلات ومستوياتها",
    "List match candidates with their current and proposed values": "عرض مرشحي المطابقة مع قيمهم الحالية والمقترحة",
    "List match jobs of this session": "عرض مهام المطابقة لهذه الجلسة",
    "List materialized chapters of a subscription with download state": "عرض الفصول المجسّدة لاشتراك مع حالة التنزيل",
    "List my annotations of a book": "عرض ملاحظاتي لكتاب",
    "List new series": "عرض السلاسل الجديدة",
    "List notable entities": "عرض الكيانات البارزة",
    "List notable entities kinds": "عرض أنواع الكيانات البارزة",
    "List notable entities names": "عرض أسماء الكيانات البارزة",
    "List online metadata sources and their daily usage": "عرض مصادر البيانات الوصفية عبر الإنترنت واستخدامها اليومي",
    "List publishers": "عرض دور النشر",
    "List queued and running tasks, paged": "عرض المهام في الانتظار وقيد التشغيل، بترقيم الصفحات",
    "List reading sessions": "عرض جلسات القراءة",
    "List readlist's books": "عرض كتب قائمة القراءة",
    "List readlist's posters": "عرض ملصقات قائمة القراءة",
    "List readlists": "عرض قوائم القراءة",
    "List registered OAuth2 providers": "عرض موفري OAuth2 المسجّلين",
    "List releases": "عرض الإصدارات",
    "List series": "عرض السلاسل",
    "List series groups": "عرض مجموعات السلاسل",
    "List series posters": "عرض ملصقات السلاسل",
    "List series release dates": "عرض تواريخ إصدار السلاسل",
    "List series release years": "عرض سنوات إصدار السلاسل",
    "List series removed from library but still present on disk": "عرض السلاسل المُزالة من المكتبة لكنها ما زالت موجودة على القرص",
    "List series tags": "عرض وسوم السلاسل",
    "List series' books": "عرض كتب السلسلة",
    "List series' collections": "عرض مجموعات السلسلة",
    "List sharing labels": "عرض تسميات المشاركة",
    "List sources with their search/entry toggle state": "عرض المصادر مع حالة تبديل البحث والدخول",
    "List subscribed online mangas": "عرض المانغا عبر الإنترنت المشترك بها",
    "List tags": "عرض الوسوم",
    "List the API keys of a user": "عرض مفاتيح API لمستخدم",
    "List the actions present in the audit trail": "عرض الإجراءات الموجودة في مسار التدقيق",
    "List the active sessions of a user": "عرض الجلسات النشطة لمستخدم",
    "List the books of the series behind a queue entry with their current metadata": "عرض كتب السلسلة خلف عنصر في قائمة الانتظار مع بياناتها الوصفية الحالية",
    "List the runtime filters exposed by a source": "عرض مرشحات التشغيل التي يوفرها مصدر",
    "List the scrape only API tokens": "عرض رموز API المخصصة للاستخراج فقط",
    "List the sources provided by an extension": "عرض المصادر التي توفرها إضافة",
    "List trash entries of one or all libraries": "عرض مدخلات سلة المحذوفات لمكتبة واحدة أو لكل المكتبات",
    "List unknown duplicates": "عرض التكرارات غير المعروفة",
    "List updated series": "عرض السلاسل المحدّثة",
    "List users": "عرض المستخدمين",
    "Mark a match as skipped": "تعليم مطابقة كمتخطاة",
    "Mark announcements as read": "تعليم الإعلانات كمقروءة",
    "Mark book as unread": "تعليم الكتاب كغير مقروء",
    "Mark book poster as selected": "تعليم ملصق الكتاب كمحدد",
    "Mark book progression": "تسجيل تقدم الكتاب",
    "Mark book's read progress": "تسجيل تقدم قراءة الكتاب",
    "Mark collection poster as selected": "تعليم ملصق المجموعة كمحدد",
    "Mark duplicate page as known": "تعليم صفحة مكررة كمعروفة",
    "Mark readlist poster as selected": "تعليم ملصق قائمة القراءة كمحدد",
    "Mark series as read": "تعليم السلسلة كمقروءة",
    "Mark series as unread": "تعليم السلسلة كغير مقروءة",
    "Mark series poster as selected": "تعليم ملصق السلسلة كمحدد",
    "Match ComicRack list": "مطابقة قائمة ComicRack",
    "Match a single series against enabled sources": "مطابقة سلسلة واحدة مع المصادر الممكّنة",
    "Packages already downloaded into the config directory": "الحزم المنزّلة بالفعل إلى دليل التكوين",
    "Per-library series/book counts and total size": "عدد السلاسل/الكتب والحجم الإجمالي لكل مكتبة",
    "Permanently delete a trash entry and its files": "حذف مدخل سلة المحذوفات وملفاته نهائيًا",
    "Pin an existing ebook-convert executable": "تثبيت ملف ebook-convert موجود",
    "Poll a match job": "استعلام مهمة مطابقة",
    "Probe an ebook-convert executable without saving it": "فحص ملف ebook-convert دون حفظه",
    "Probe every external runtime managed by Lantai": "فحص كل بيئات التشغيل الخارجية التي يديرها Lantai",
    "Probe one external runtime": "فحص بيئة تشغيل خارجية واحدة",
    "Progress of the package download currently running": "تقدم تنزيل الحزمة الجاري",
    "Progress of the package validation currently running": "تقدم التحقق من الحزمة الجاري",
    "Proxy the remote cover of a search result": "تمرير غلاف بعيد لنتيجة بحث عبر وسيط",
    "Purge reading sessions beyond the retention window": "إزالة جلسات القراءة خارج نافذة الاحتفاظ",
    "Re-verify the most recent downloaded package": "إعادة التحقق من أحدث حزمة منزّلة",
    "Read Suwayomi settings": "قراءة إعدادات Suwayomi",
    "Read bootstrap payload": "قراءة بيانات التمهيد",
    "Read chapter bundle": "قراءة حزمة الفصول",
    "Read chapter outline page": "قراءة صفحة مخطط الفصل",
    "Read chapter window": "قراءة نافذة الفصل",
    "Read image resource": "قراءة مورد صورة",
    "Read one page of the global scrape queue": "قراءة صفحة واحدة من قائمة انتظار الاستخراج العامة",
    "Read one page of the scrape queue for an external agent": "قراءة صفحة واحدة من قائمة انتظار الاستخراج لوكيل خارجي",
    "Read one queue entry with the hints an external agent needs": "قراءة عنصر واحد من قائمة الانتظار مع التلميحات التي يحتاجها وكيل خارجي",
    "Read page thumbnail": "قراءة صورة الصفحة المصغرة",
    "Read scrape settings": "قراءة إعدادات الاستخراج",
    "Read structured log entries, cursor based": "قراءة مدخلات السجل المنظمة، بالاعتماد على المؤشر",
    "Read the configurable preferences of a source": "قراءة التفضيلات القابلة للتكوين لمصدر",
    "Read the remote detail of a single manga": "قراءة التفاصيل البعيدة لمانغا واحدة",
    "Read the update feed and report the latest release plus any downloaded package": "قراءة موجز التحديثات والإبلاغ عن أحدث إصدار وأي حزمة منزّلة",
    "Read trash settings": "قراءة إعدادات سلة المحذوفات",
    "Rebuild the daily reading statistics of the current user": "إعادة بناء إحصاءات القراءة اليومية للمستخدم الحالي",
    "Recompute first letters for every series": "إعادة حساب الأحرف الأولى لكل سلسلة",
    "Refresh book metadata": "إعادة تحميل البيانات الوصفية للكتاب",
    "Refresh installed extension metadata": "إعادة تحميل البيانات الوصفية للإضافات المثبّتة",
    "Refresh metadata for a library": "إعادة تحميل البيانات الوصفية لمكتبة",
    "Refresh series metadata": "إعادة تحميل البيانات الوصفية للسلسلة",
    "Regenerate books posters": "إعادة إنشاء ملصقات الكتب",
    "Remove a book from the library, keeping its file untouched": "إزالة كتاب من المكتبة مع ترك ملفه دون تغيير",
    "Remove a series from the library, keeping its files untouched": "إزالة سلسلة من المكتبة مع ترك ملفاتها دون تغيير",
    "Remove a single queue entry": "إزالة عنصر واحد من قائمة الانتظار",
    "Replace the roles of a user": "استبدال أدوار مستخدم",
    "Replace the running jar with the staged one and restart": "استبدال jar قيد التشغيل بالمجهّز وإعادة التشغيل",
    "Report a reading heartbeat": "الإبلاغ عن نبضة قراءة",
    "Reset a queue entry back to queued": "إعادة تعيين عنصر في قائمة الانتظار إلى الانتظار",
    "Reset the password of a user and expire its sessions": "إعادة تعيين كلمة مرور مستخدم وإنهاء جلساته",
    "Restore a trash entry back to its library": "استعادة مدخل سلة المحذوفات إلى مكتبته",
    "Restore logger levels to their default": "استعادة مستويات المسجّل إلى افتراضياتها",
    "Restore the last backup jar and restart": "استعادة آخر jar احتياطي وإعادة التشغيل",
    "Resume task processing": "استئناف معالجة المهام",
    "Retrieve API keys": "استرداد مفاتيح API",
    "Retrieve a single audit entry": "استرداد مدخل تدقيق واحد",
    "Retrieve a user with its activity summary": "استرداد مستخدم مع ملخص نشاطه",
    "Retrieve announcements": "استرداد الإعلانات",
    "Retrieve authentication activity": "استرداد نشاط المصادقة",
    "Retrieve authentication activity for the current user": "استرداد نشاط المصادقة للمستخدم الحالي",
    "Retrieve current user": "استرداد المستخدم الحالي",
    "Retrieve global client settings": "استرداد إعدادات العميل العامة",
    "Retrieve latest authentication activity for a user": "استرداد أحدث نشاط مصادقة لمستخدم",
    "Retrieve server settings": "استرداد إعدادات الخادم",
    "Retrieve user client settings": "استرداد إعدادات عميل المستخدم",
    "Revoke a scrape only API token": "إلغاء رمز API المخصص للاستخراج فقط",
    "Rotate the managed Suwayomi credentials": "تدوير بيانات اعتماد Suwayomi المُدارة",
    "Run the automatic install for the given runtimes": "تشغيل التثبيت التلقائي لبيئات التشغيل المحددة",
    "Runtime and storage status": "حالة بيئة التشغيل والتخزين",
    "Save global settings": "حفظ الإعدادات العامة",
    "Save user settings": "حفظ إعدادات المستخدم",
    "Scan a library": "فحص مكتبة",
    "Scan folder for transient books": "فحص المجلد بحثًا عن كتب مؤقتة",
    "Search audit entries": "البحث في مدخلات التدقيق",
    "Search inside a book": "البحث داخل كتاب",
    "Search mangas across enabled sources": "البحث عن المانغا عبر المصادر الممكّنة",
    "Search metadata candidates from one or all enabled sources": "البحث عن مرشحي البيانات الوصفية من مصدر واحد أو كل المصادر الممكّنة",
    "Series ids whose computed first letter matches": "معرّفات السلاسل التي يطابق حرفها الأول المحسوب",
    "Set cookie": "تعيين ملف تعريف الارتباط",
    "Set the conversion mode: never, on-demand or eager": "تعيين وضع التحويل: أبدًا، عند الطلب أو فوريًا",
    "Set the trash quota of a library, zero means inheriting the global default": "تعيين حصة سلة المحذوفات لمكتبة، الصفر يعني وراثة الافتراضي العام",
    "Snapshot of the automatic install performed at startup": "لقطة للتثبيت التلقائي الذي تم عند بدء التشغيل",
    "Snapshot the main and tasks SQLite databases": "لقطة لقواعد بيانات SQLite الرئيسية والمهام",
    "Start a background auto-match job for a library": "بدء مهمة مطابقة تلقائية في الخلفية لمكتبة",
    "Start an external runtime": "بدء بيئة تشغيل خارجية",
    "Start or resume scraping the queue": "بدء أو استئناف استخراج قائمة الانتظار",
    "Start, stop or restart Suwayomi": "بدء Suwayomi أو إيقافه أو إعادة تشغيله",
    "Stop an external runtime": "إيقاف بيئة تشغيل خارجية",
    "Stop taking new tasks; running tasks finish": "التوقف عن قبول مهام جديدة، المهام قيد التشغيل تُكمل",
    "Stop the current reading session": "إيقاف جلسة القراءة الحالية",
    "Stream new log entries": "بث مدخلات سجل جديدة",
    "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result": "إرسال نتيجة استخراج لعنصر في قائمة الانتظار، أو تمرير معرّف سلسلة لاستبدال نتيجة مطبّقة بالفعل",
    "Subscribe to a remote manga and materialize it as a series": "الاشتراك في مانغا بعيدة وتجسيدها كسلسلة",
    "Suwayomi runtime status": "حالة بيئة تشغيل Suwayomi",
    "Sync offline reading sessions": "مزامنة جلسات القراءة دون اتصال",
    "Synchronize chapters of a subscribed series": "مزامنة فصول سلسلة مشترك بها",
    "Undo an applied match using its snapshot": "التراجع عن مطابقة مطبّقة باستخدام لقطتها",
    "Uninstall a runtime, data is removed only when purge is requested": "إلغاء تثبيت بيئة تشغيل، تُحذف البيانات فقط عند طلب التنظيف",
    "Uninstall an extension": "إلغاء تثبيت إضافة",
    "Unsubscribe a series and remove its placeholder files": "إلغاء الاشتراك في سلسلة وإزالة ملفاتها النائبة",
    "Update Suwayomi settings": "تحديث إعدادات Suwayomi",
    "Update a library": "تحديث مكتبة",
    "Update an installed extension": "تحديث إضافة مثبّتة",
    "Update book metadata": "تحديث البيانات الوصفية للكتاب",
    "Update book metadata in bulk": "تحديث البيانات الوصفية للكتب بشكل جماعي",
    "Update collection": "تحديث المجموعة",
    "Update current user's password": "تحديث كلمة مرور المستخدم الحالي",
    "Update note, color or marker of my annotation": "تحديث ملاحظة أو لون أو علامة ملاحظتي",
    "Update one preference of a source and return the refreshed list": "تحديث تفضيل واحد لمصدر وإرجاع القائمة المحدّثة",
    "Update readlist": "تحديث قائمة القراءة",
    "Update readlist read progress (Mihon)": "تحديث تقدم قراءة قائمة القراءة (Mihon)",
    "Update scrape settings, trusting the results requires an explicit confirmation": "تحديث إعدادات الاستخراج، الثقة في النتائج تتطلب تأكيدًا صريحًا",
    "Update series metadata": "تحديث البيانات الوصفية للسلسلة",
    "Update series read progress (Mihon)": "تحديث تقدم قراءة السلسلة (Mihon)",
    "Update server settings": "تحديث إعدادات الخادم",
    "Update the username and email of a user": "تحديث اسم المستخدم والبريد الإلكتروني لمستخدم",
    "Update trash settings": "تحديث إعدادات سلة المحذوفات",
    "Update user": "تحديث مستخدم",
    "Update user's password": "تحديث كلمة مرور مستخدم",
    "Upload a package and install it in the background": "رفع حزمة وتثبيتها في الخلفية",
    "Upload an upgrade package and validate it without touching the running jar": "رفع حزمة ترقية والتحقق منها دون المساس بـ jar قيد التشغيل",
      "Add a scraping source": "إضافة مصدر استخراج",
      "Apply bulk series metadata from a text payload": "تطبيق بيانات السلسلة الوصفية دفعة واحدة من نص",
      "Authenticate a KOReader user": "مصادقة مستخدم KOReader",
      "Browse a library": "تصفح مكتبة",
      "Check Kobo connectivity": "التحقق من اتصال Kobo",
      "Count entries awaiting bulk metadata": "عد العناصر التي تنتظر البيانات الوصفية",
      "Create an API token": "إنشاء رمز API",
      "Delete a scraping source": "حذف مصدر استخراج",
      "Delete an API token": "حذف رمز API",
      "Download a book file for Kobo": "تنزيل ملف كتاب لـ Kobo",
      "Download the metadata package template": "تنزيل قالب حزمة البيانات الوصفية",
      "Enable or disable a scraping source": "تمكين أو تعطيل مصدر استخراج",
      "Exchange a Kobo device token": "تبادل رمز جهاز Kobo",
      "Export series metadata as a text file": "تصدير بيانات السلسلة الوصفية إلى ملف نصي",
      "Get Kobo metadata for a book": "الحصول على بيانات Kobo الوصفية لكتاب",
      "Get Kobo reading state for a book": "الحصول على حالة قراءة Kobo لكتاب",
      "Get a book cover for Kobo": "الحصول على غلاف كتاب لـ Kobo",
      "Get a book page image": "الحصول على صورة صفحة كتاب",
      "Get a book page thumbnail": "الحصول على صورة مصغرة لصفحة كتاب",
      "Get a book thumbnail": "الحصول على صورة مصغرة لكتاب",
      "Get one collection": "الحصول على مجموعة",
      "Get one library": "الحصول على مكتبة",
      "Get one reading list": "الحصول على قائمة قراءة",
      "Get one series with its books": "الحصول على سلسلة مع كتبها",
      "Get reading progress for a document": "الحصول على تقدم القراءة لمستند",
      "Get scraping source details": "الحصول على تفاصيل مصدر الاستخراج",
      "Get the OPDS authentication document": "الحصول على مستند مصادقة OPDS",
      "Get the OPDS search description": "الحصول على وصف بحث OPDS",
      "Get the OPDS v1 catalog": "الحصول على كتالوج OPDS v1",
      "Get the OPDS v2 catalog": "الحصول على كتالوج OPDS v2",
      "Get the WebPub manifest for a DIVINA book": "الحصول على بيان WebPub لكتاب DIVINA",
      "Get the WebPub manifest for a PDF": "الحصول على بيان WebPub لملف PDF",
      "Get the WebPub manifest for a book": "الحصول على بيان WebPub لكتاب",
      "Get the WebPub manifest for an EPUB": "الحصول على بيان WebPub لملف EPUB",
      "Import a series metadata package": "استيراد حزمة بيانات وصفية للسلسلة",
      "Initialize a Kobo device": "تهيئة جهاز Kobo",
      "List API tokens": "سرد رموز API",
      "List all series": "سرد جميع السلاسل",
      "List available API token scopes": "سرد نطاقات رموز API المتاحة",
      "List books currently being read": "سرد الكتب التي تُقرأ حاليًا",
      "List books to keep reading": "سرد الكتب لمواصلة القراءة",
      "List collections in a library": "سرد المجموعات في مكتبة",
      "List entries awaiting bulk metadata": "سرد العناصر التي تنتظر البيانات الوصفية",
      "List libraries": "سرد المكتبات",
      "List reading lists": "سرد قوائم القراءة",
      "List reading lists in a library": "سرد قوائم القراءة في مكتبة",
      "List recently added books": "سرد الكتب المضافة حديثًا",
      "List recently added series": "سرد السلاسل المضافة حديثًا",
      "Proxy unmatched Kobo requests": "تمرير طلبات Kobo غير المطابقة",
      "Register a KOReader user (disabled)": "تسجيل مستخدم KOReader (معطّل)",
      "Search the catalog": "البحث في الكتالوج",
      "Submit bulk metadata for application": "إرسال البيانات الوصفية دفعة واحدة للتطبيق",
      "Sync the Kobo library": "مزامنة مكتبة Kobo",
      "Test a scraping source connection": "اختبار اتصال مصدر الاستخراج",
      "Update Kobo reading state for a book": "تحديث حالة قراءة Kobo لكتاب",
      "Update a scraping source": "تحديث مصدر استخراج",
      "Update an API token": "تحديث رمز API",
      "Update reading progress for a document": "تحديث تقدم القراءة لمستند",
    "Serve the web interface": "تقديم واجهة الويب",
    "Stream server-sent events": "بث أحداث الخادم",
  };
})();

(function () {
  const GROUP_I18N = {
    'zh-Hans': {
      Libraries: '书库', Series: '系列', Books: '书籍',
      Collections: '合集', Readlists: '阅读清单', Referential: '参考数据',
      Users: '用户与权限', Server: '服务端', Integrations: '集成',
      lantai: '兰台自有', other: '其他'
    },
    'zh-Hant': {
      Libraries: '書庫', Series: '系列', Books: '書籍',
      Collections: '合集', Readlists: '閱讀清單', Referential: '參考資料',
      Users: '使用者與權限', Server: '伺服端', Integrations: '整合',
      lantai: '蘭台自有', other: '其他'
    },
    en: {
      Libraries: 'Libraries', Series: 'Series', Books: 'Books',
      Collections: 'Collections', Readlists: 'Read lists', Referential: 'Reference data',
      Users: 'Users and roles', Server: 'Server', Integrations: 'Integrations',
      lantai: 'Lantai', other: 'Other'
    },
    ja: {
      Libraries: 'ライブラリ', Series: 'シリーズ', Books: '書籍',
      Collections: 'コレクション', Readlists: '読書リスト', Referential: '参照データ',
      Users: 'ユーザーとロール', Server: 'サーバー', Integrations: '連携',
      lantai: '蘭台独自', other: 'その他'
    },
    ko: {
      Libraries: '서재', Series: '시리즈', Books: '도서',
      Collections: '모음집', Readlists: '읽기 목록', Referential: '참조 데이터',
      Users: '사용자와 역할', Server: '서버', Integrations: '연동',
      lantai: '란타이 전용', other: '기타'
    }
  };

  function grpName(g) {
    const l = L.lang();
    const t = GROUP_I18N[l] || GROUP_I18N.en;
    return t[g] || g;
  }

  function sumText(s) {
    if (!s) return '';
    const d = (window.LANTAI_API_I18N || {})[L.lang()];
    return (d && d[s]) || s;
  }

  function state() {
    if (!S.api) S.api = { doc: null, err: '', busy: false, group: 'all', q: '', open: {}, detail: {} };
    if (!S.api.detail) S.api.detail = {};
    if (!S.api.open) S.api.open = {};
    return S.api;
  }

  function load(attempt) {
    const P = state();
    const n = attempt || 1;
    if (n === 1 && P.busy) return;
    P.busy = true;
    P.err = '';
    fetch('v3/api-docs', { credentials: 'same-origin', cache: 'no-store' })
      .then(r => {
        if (r.status === 401 || r.status === 403) {
          const err = new Error('admin');
          err.fatal = true;
          return Promise.reject(err);
        }
        return r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status));
      })
      .then(doc => { P.doc = doc; P.busy = false; if (S.route === '/protocol') render(); })
      .catch(e => {
        if (n < 2 && !e.fatal) { setTimeout(() => load(n + 1), 800); return; }
        P.err = L.t('api.loadFail', { msg: e.fatal ? L.t('api.needAdmin') : e.message });
        P.busy = false;
        if (S.route === '/protocol') render();
      });
  }

  function tagMap(doc) {
    const groups = (doc && (doc['x-tagGroups'] || (doc.info && doc.info['x-tagGroups']))) || [];
    const map = {}, order = [];
    groups.forEach(g => {
      order.push(g.name);
      (g.tags || []).forEach(t => { map[t] = g.name; });
    });
    return { map: map, order: order.concat(['lantai', 'other']) };  }

  function versionOf(p) {
    if (p.indexOf('/api/lantai/') === 0) return 'lantai';
    if (p.indexOf('/api/v2/') === 0) return 'v2';
    if (p.indexOf('/api/v1/') === 0) return 'v1';
    return 'other';
  }

  function ops(doc) {
    const out = [];
    if (!doc || !doc.paths) return out;
    const tm = tagMap(doc).map;
    Object.keys(doc.paths).forEach(p => {
      const item = doc.paths[p] || {};
      ['get', 'post', 'put', 'patch', 'delete'].forEach(m => {
        const op = item[m];
        if (!op) return;
        const tag = (op.tags && op.tags[0]) || '(untagged)';
        const group = tm[tag] || (/^lantai/i.test(tag) ? 'lantai' : 'other');
        out.push({
          key: m + ' ' + p, method: m.toUpperCase(), path: p,
          summary: op.summary || '', tag: tag, group: group, ver: versionOf(p), raw: op
        });
      });
    });
    return out;
  }

  function opsCached(doc) {
    const P = state();
    if (!doc) return [];
    if (P.opsDoc !== doc) { P.opsDoc = doc; P.opsCache = ops(doc); }
    return P.opsCache;
  }

  function resolveRef(doc, schema) {
    if (!schema) return null;
    const r = schema['$ref'];
    if (r && r.indexOf('#/components/schemas/') === 0) {
      const name = r.slice(21);
      const box = doc && doc.components && doc.components.schemas;
      if (box && box[name]) return box[name];
    }
    return schema;
  }

  function schemaType(s) {
    if (!s) return '—';
    if (s.type === 'array') return 'array<' + schemaType(s.items) + '>';
    if (s.type) return s.type;
    if (s['$ref']) return String(s['$ref']).split('/').pop();
    if (s.properties) return 'object';
    if (s.oneOf || s.anyOf) return 'oneOf';
    return '—';
  }

  function paramRows(raw, doc) {
    const ps = (raw && raw.parameters) || [];
    return ps.map(p => [
      p.name + (p.in ? ' (' + p.in + ')' : ''),
      schemaType(p.schema),
      p.required ? '✓' : '',
      p.description || ''
    ]);
  }

  function bodyRows(raw, doc) {
    const rb = raw && raw.requestBody;
    if (!rb) return [];
    const content = rb.content || {};
    const key = content['application/json'] ? 'application/json' : Object.keys(content)[0];
    if (!key) return [];
    let s = resolveRef(doc, content[key].schema);
    if (s && s.type === 'array') s = resolveRef(doc, s.items);
    if (!s || !s.properties) return [];
    const req = s.required || [];
    return Object.keys(s.properties).map(k => {
      const p = resolveRef(doc, s.properties[k]);
      return [k, schemaType(s.properties[k]), req.indexOf(k) >= 0 ? '✓' : '', (p && p.description) || ''];
    });
  }

  function respRows(raw) {
    const rs = (raw && raw.responses) || {};
    return Object.keys(rs).sort().map(c => [c, (rs[c] && rs[c].description) || '']);
  }

  function miniTable(head, rows) {
    return '<div class="tbl-wrap" style="margin-top:6px"><table class="tbl api-dt"><thead><tr>' +
      head.map(h => '<th>' + esc(h) + '</th>').join('') +
      '</tr></thead><tbody>' +
      rows.map(r => '<tr>' + r.map((c, i) => '<td class="mini' + (i === 0 ? ' mono' : '') + '">' + esc(c || '—') + '</td>').join('') + '</tr>').join('') +
      '</tbody></table></div>';
  }

  function detailHtml(o, doc) {
    const raw = o.raw || {};
    const pr = paramRows(raw, doc), br = bodyRows(raw, doc), rr = respRows(raw);
    const parts = [];
    parts.push('<div class="api-dt-h">' + esc(L.t('api.detailParams')) + '</div>');
    parts.push(pr.length ? miniTable([L.t('api.detailName'), L.t('api.detailType'), L.t('api.detailRequired'), L.t('api.detailDesc')], pr)
      : '<div class="mini" style="color:var(--tx-4)">' + esc(L.t('api.detailNone')) + '</div>');
    if (br.length) {
      parts.push('<div class="api-dt-h">' + esc(L.t('api.detailBody')) + '</div>');
      parts.push(miniTable([L.t('api.detailName'), L.t('api.detailType'), L.t('api.detailRequired'), L.t('api.detailDesc')], br));
    }
    if (rr.length) {
      parts.push('<div class="api-dt-h">' + esc(L.t('api.detailResp')) + '</div>');
      parts.push(miniTable(['HTTP', L.t('api.detailDesc')], rr));
    }
    return '<div class="api-dt-box">' + parts.join('') + '</div>';
  }

  function authBlock() {
    return '<div class="note">' + esc(L.t('api.authLan', { host: CFG.host })) + '</div>' +
    '<div class="tbl-wrap" style="margin-top:12px"><table class="tbl"><thead><tr><th style="width:130px">' + esc(L.t('api.colMethod')) + '</th><th>' + esc(L.t('api.authHint')) + '</th></tr></thead><tbody>' +
    '<tr><td>' + esc(L.t('api.authBasic')) + '</td><td class="mini mono">' + esc(L.t('api.authBasicUse')) + '</td></tr>' +
    '<tr><td>' + esc(L.t('api.authApiKey')) + '</td><td class="mini mono">' + esc(L.t('api.authApiKeyUse')) + '</td></tr>' +
    '<tr><td>' + esc(L.t('api.authAgent')) + '</td><td class="mini mono">' + esc(L.t('api.authAgentUse')) + '</td></tr>' +
    '<tr><td>' + esc(L.t('api.authSession')) + '</td><td class="mini">' + esc(L.t('api.authSessionUse')) + '</td></tr>' +
    '</tbody></table></div>' +
    '<pre class="mono" style="font-size:11.5px;line-height:1.8;background:var(--bg-2);padding:14px;border-radius:8px;margin-top:12px;overflow:auto">' +
    '# LAN\ncurl -u email:password ' + esc(CFG.host) + '/api/v2/users/me\n\n' +
    '# API key\ncurl -H "X-API-Key: KEY" ' + esc(CFG.host) + '/api/lantai/v1/system\n\n' +
    '# Agent token\ncurl -H "Authorization: Bearer $(cat agent-token.txt)" \\\n  "' + esc(CFG.host) + '/api/lantai/v1/bulk/metadata/pending?limit=20"</pre>';
  }

  function clientRows() {
    const l = L.lang();
    const use = (l === 'zh-Hans' || l === 'zh-Hant') ? window.ScrapeHelpDoc.clients.zh : window.ScrapeHelpDoc.clients.en;
    return use;
  }

  function render(v) {
    v = v || document.getElementById('view');
    if (!v) return;
    if (!isAdmin()) {
      v.innerHTML = '<div class="ph"><div><h1>' + esc(L.t('api.title')) + '</h1><div class="sub">' + esc(L.t('api.sub', { n: 0, host: CFG.host })) + '</div></div></div>' +
        '<div class="card"><div class="empty">' + ico('key') + '<h4>' + esc(L.t('api.needAdmin')) + '</h4>' +
        '<p>' + esc(L.t('api.needAdminHint')) + '</p></div></div>';
      return;
    }
    const P = state();
    if (!P.doc && !P.busy && !P.err) load();
    const doc = P.doc;
    const all = opsCached(doc);
    const tagOrder = tagMap(doc).order;
    const q = (P.q || '').toLowerCase();
    const filtered = all.filter(o =>
      (P.group === 'all' || o.group === P.group) &&
      (!q || o.path.toLowerCase().indexOf(q) >= 0 || sumText(o.summary).toLowerCase().indexOf(q) >= 0 || (o.summary || '').toLowerCase().indexOf(q) >= 0 || o.tag.toLowerCase().indexOf(q) >= 0)
    );
    const countBy = g => all.filter(o => o.group === g).length;
    const verName = { lantai: L.t('api.verLantai'), v1: L.t('api.verV1'), v2: L.t('api.verV2'), other: L.t('api.verOther') };

    const chips = '<div class="filters" style="margin-bottom:20px">' +
      '<span class="lbl" style="margin:0">' + esc(L.t('api.filterGroup')) + '</span><div class="chips">' +
      '<span class="chip' + (P.group === 'all' ? ' on' : '') + '" data-apigroup="all">' + esc(L.t('api.all')) + ' ' + all.length + '</span>' +
      tagOrder.filter(g => countBy(g) > 0).map(g => '<span class="chip' + (P.group === g ? ' on' : '') + '" data-apigroup="' + esc(g) + '">' + esc(grpName(g)) + ' ' + countBy(g) + '</span>').join('') +
      '</div>' +
      '<span class="mini" style="margin-left:auto">' + esc(L.t('api.count', { n: filtered.length })) + '</span>' +
      '<input class="inp" style="width:230px;height:31px" placeholder="' + esc(L.t('api.search')) + '" value="' + esc(P.q) + '" data-apiq>' +
      '</div>';

    const body = tagOrder.map(g => {
      const list = filtered.filter(o => o.group === g);
      if (!list.length) return '';
      const open = P.open[g] === true;
      return '<div class="card" style="margin-bottom:14px"><div class="card-h" data-apigrp="' + esc(g) + '" style="cursor:pointer">' +
        '<h3>' + ico('file') + ' ' + esc(grpName(g)) + '</h3>' +
        '<div class="r"><span class="badge">' + list.length + '</span><span class="mini">' + esc(open ? L.t('api.collapse') : L.t('api.expand')) + '</span></div></div>' +
        (open ? '<div class="tbl-wrap"><table class="tbl"><thead><tr><th style="width:78px">' + esc(L.t('api.colMethod')) + '</th><th>' + esc(L.t('api.colPath')) + '</th><th>' + esc(L.t('api.colSummary')) + '</th><th style="width:62px">' + esc(L.t('api.colVer')) + '</th><th style="width:130px">' + esc(L.t('api.colTag')) + '</th></tr></thead><tbody>' +
          list.map(o => {
            const shown = !!P.detail[o.key];
            return '<tr class="api-op' + (shown ? ' on' : '') + '" data-apiop="' + esc(o.key) + '">' +
              '<td class="mono" style="font-size:11px;' + (o.method === 'DELETE' ? 'color:var(--cinnabar)' : 'color:var(--acc-2)') + '">' + o.method + '</td>' +
              '<td class="mono" style="font-size:11.5px;word-break:break-all">' + esc(o.path) + '</td>' +
              '<td class="mini">' + esc(sumText(o.summary) || '—') + '</td>' +
              '<td><span class="badge">' + esc(verName[o.ver]) + '</span></td>' +
              '<td class="mini" style="color:var(--tx-3)">' + esc(o.tag) + '</td></tr>' +
              (shown ? '<tr class="api-op-detail"><td colspan="5">' + detailHtml(o, doc) + '</td></tr>' : '');
          }).join('') +
          '</tbody></table></div>' : '') + '</div>';
    }).join('');

    v.innerHTML =
      '<div class="ph"><div><h1>' + esc(L.t('api.title')) + '</h1><div class="sub">' + esc(L.t('api.sub', { n: all.length, host: CFG.host })) + '</div></div>' +
      '<div class="acts"><button class="btn" data-act="copyapi">' + ico('key') + esc(L.t('api.copyBase')) + '</button>' +
      '<button class="btn" data-apijson>' + ico('file') + esc(L.t('api.openapi')) + '</button>' +
      '<button class="btn pri" data-go="/users">' + ico('user') + esc(L.t('api.users')) + '</button></div></div>' +
      (P.err ? '<div class="note red" style="margin-bottom:16px;display:flex;align-items:center;gap:12px">' + esc(P.err) +
        '<button class="btn sm" data-apiretry style="margin-left:auto;flex:none">' + esc(L.t('api.retry')) + '</button></div>' : '') +
      (P.busy || !doc ? (P.err ? '' : '<div class="card"><div class="empty" style="padding:48px 0"><div class="spinner" style="margin:0 auto 12px"></div><div class="mini">' + esc(L.t('api.loading')) + '</div></div></div>')
        : '<div class="card" style="margin-bottom:18px"><div class="card-h" data-apiauth style="cursor:pointer"><h3>' + ico('key') + ' ' + esc(L.t('api.authTitle')) + '</h3>' +
          '<div class="r"><span class="mini">' + esc(L.t('api.authHint')) + '</span><span class="mini" id="apiAuthTog">' + esc(L.t('api.expand')) + '</span></div></div>' +
          '<div id="apiAuth" style="display:none">' + authBlock() + '</div></div>' +
          chips + (body || '<div class="card"><div class="empty">' + esc(L.t('api.noResult')) + '</div></div>') +
          '<div class="grid g-2" style="margin-top:20px">' +
          '<div class="card"><div class="card-h"><h3>' + ico('cpu') + ' ' + esc(L.t('api.clients')) + '</h3></div>' +
          '<div class="tbl-wrap"><table class="tbl"><thead><tr><th>' + esc(L.t('api.clientsClient')) + '</th><th>' + esc(L.t('api.clientsFits')) + '</th><th>' + esc(L.t('api.clientsNot')) + '</th></tr></thead><tbody>' +
          clientRows().map(r => '<tr><td>' + esc(r[0]) + '</td><td class="mini">' + esc(r[1]) + '</td><td class="mini" style="color:var(--cinnabar)">' + esc(r[2]) + '</td></tr>').join('') +
          '</tbody></table></div></div>' +
          '<div class="card"><div class="card-h"><h3>' + ico('ext') + ' ' + esc(L.t('api.access')) + '</h3></div>' +
          '<div class="mono mini" style="line-height:1.9;padding:2px 0">' + esc(L.t('api.accessAddr')) + ' ' + esc(CFG.host) + '<br>' +
          esc(L.t('api.accessAccount')) + ' —<br>' + esc(L.t('api.accessLan')) + '</div>' +
          '<div class="rowflex" style="margin-top:12px;gap:8px"><button class="btn sm" data-act="copyapi">' + ico('key') + esc(CFG.host) + '</button>' +
          '<span class="mini">' + esc(L.t('api.accessSame')) + '</span></div>' +
          '<div class="hr"></div><div class="mini mono" style="color:var(--tx-3)">OPDS v1.2 / v2 · OPDS-PSE · WebPub Manifest</div></div></div>');

    wireApi(v);
    wire(v);
  }

  function wireApi(v) {
    const P = state();
    const scope = s => v.querySelectorAll(s);
    const at = fn => {
      const box = document.getElementById('view');
      const top = box ? box.scrollTop : 0;
      fn();
      const el = document.getElementById('view');
      if (el) el.scrollTop = top;
    };
    scope('[data-apigroup]').forEach(el => el.onclick = () => at(() => { P.group = el.dataset.apigroup; render(); }));
    scope('[data-apigrp]').forEach(el => el.onclick = () => at(() => { const k = el.dataset.apigrp; P.open[k] = P.open[k] !== true; render(); }));
    scope('[data-apiop]').forEach(el => el.onclick = () => at(() => { const k = el.dataset.apiop; P.detail[k] = !P.detail[k]; render(); }));
    const auth = v.querySelector('[data-apiauth]');
    if (auth) auth.onclick = () => {
      const box = v.querySelector('#apiAuth'), tog = v.querySelector('#apiAuthTog');
      const open = box.style.display === 'none';
      box.style.display = open ? '' : 'none';
      if (tog) tog.textContent = open ? L.t('api.collapse') : L.t('api.expand');
    };
    const js = v.querySelector('[data-apijson]');
    if (js) js.onclick = () => window.open('v3/api-docs', '_blank');
    const rt = v.querySelector('[data-apiretry]');
    if (rt) rt.onclick = () => { P.doc = null; P.err = ''; P.busy = false; load(); render(); };
    const qi = v.querySelector('[data-apiq]');
    if (qi) qi.oninput = () => {
      P.q = qi.value;
      clearTimeout(qi._t);
      qi._t = setTimeout(() => {
        at(() => render());
        const f = v.querySelector('[data-apiq]');
        if (f) { f.focus(); f.setSelectionRange(f.value.length, f.value.length); }
      }, 260);
    };
  }

  window.LantaiApiDoc = { render: render };
})();
