const SC_META_FIELDS = [
  ['title', '标题', '文本', '系列标题', '可写'],
  ['summary', '简介', '文本', '可多行，换行用 \\n', '可写'],
  ['publisher', '出版社', '文本', '', '可写'],
  ['language', '语言', '文本', 'BCP-47，如 zh-CN；zh 与 zh-CN 视为同一语言', '可写'],
  ['genres', '分类', '数组或分隔文本', '奇幻、冒险', '可写'],
  ['authors', '作者', '数组或分隔文本', '九井谅子', '可写'],
  ['releaseDate', '出版日期', 'YYYY-MM-DD 或 YYYY', '写给本次范围内各册', '可写'],
  ['isbn', 'ISBN', '文本', '', '可写'],
  ['tags', '标签', '数组或分隔文本', '已完结、全彩', '可写'],
  ['coverUrl', '封面地址', 'URL', '只接地址，不接文件', '可写'],
  ['ageRating', '年龄分级', '数字', '当前版本不写入', '忽略'],
];

const SC_CSV_HEAD = ['id', 'seriesId', 'seriesTitle', 'status', 'title', 'summary', 'publisher', 'language', 'genres', 'authors', 'releaseDate', 'isbn', 'tags', 'coverUrl'];

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
    title: '', summary: '', publisher: '', language: '',
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
      m.title, m.summary, m.publisher, m.language,
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
  const filled = metadata.title || metadata.summary || metadata.publisher || metadata.language ||
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
        title: obj.title, summary: obj.summary, publisher: obj.publisher, language: obj.language,
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
  return '<div class="note">刮削是给系列补元数据：标题、简介、出版社、语言、分类、作者、出版日期、ISBN、标签、封面。兰台的原则是<b>内嵌信息优先、只补缺失、冲突不自动覆盖</b>。</div>' +
    '<h4 style="margin:16px 0 8px">名单从哪来</h4>' +
    '<div class="tbl-wrap"><table class="tbl"><thead><tr><th style="width:130px">来源</th><th>说明</th></tr></thead><tbody>' +
    '<tr><td>单本 / 多选</td><td class="mini">书库页勾选系列后点「加入刮削名单」；切到「按书籍」勾选具体册时只登记选中的册，同系列其它册不受影响</td></tr>' +
    '<tr><td>整库</td><td class="mini">刮削设置或书库页对某个库整体发起</td></tr>' +
    '<tr><td>缺元数据</td><td class="mini">自动挑出信息不全的系列，上限 200 条</td></tr>' +
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
    '</ol></div>';
}

function scHelpFormat() {
  return '<div class="note">两种格式内容等价，任选一种。JSON 字段是嵌套的 <span class="mono">metadata</span> 对象，CSV 是平铺的一行。</div>' +
    '<h4 style="margin:16px 0 8px">JSON 结构</h4>' +
    '<pre class="mono" style="font-size:11.5px;line-height:1.75;background:var(--bg-2);padding:14px;border-radius:8px;overflow:auto">' +
    '{\n  "version": 1,\n  "count": 2,\n  "items": [\n    {\n      "id": "0K3M8Q1T7XGE5",\n      "seriesId": "0K3M8Q1T7XGE5",\n      "seriesTitle": "迷宫饭",\n      "status": "QUEUED",\n      "metadata": {\n        "title": "迷宫饭",\n        "summary": "地下迷宫里的美食冒险。",\n        "publisher": "青文",\n        "language": "zh-CN",\n        "genres": ["奇幻", "冒险"],\n        "authors": ["九井谅子"],\n        "releaseDate": "2015-01-01",\n        "isbn": "9789863562305",\n        "tags": ["已完结"],\n        "coverUrl": ""\n      }\n    }\n  ]\n}' +
    '</pre>' +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">直接传单个对象、或直接传数组也可以，解析时会自动兼容。</div>' +
    '<h4 style="margin:16px 0 8px">CSV 列</h4>' +
    '<pre class="mono" style="font-size:11.5px;line-height:1.75;background:var(--bg-2);padding:14px;border-radius:8px;overflow:auto">' +
    SC_CSV_HEAD.join(',') + '\n' +
    '0K3M8Q1T7XGE5,0K3M8Q1T7XGE5,迷宫饭,QUEUED,迷宫饭,地下迷宫里的美食冒险。,青文,zh-CN,奇幻、冒险,九井谅子,2015-01-01,9789863562305,已完结,' +
    '</pre>' +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">第一行必须是表头。数组字段用顿号、逗号或竖线分隔都可以。含逗号的单元格要用英文双引号包起来。文件存 UTF-8。</div>' +
    '<h4 style="margin:16px 0 8px">字段说明</h4>' +
    scFieldTable() +
    '<h4 style="margin:16px 0 8px">别名</h4>' +
    '<div class="note">填写时下面这些写法都能识别：<span class="mono">description</span> → 简介，<span class="mono">categories</span> → 分类，<span class="mono">author</span> → 作者，<span class="mono">publishDate</span> → 出版日期，<span class="mono">cover</span> → 封面地址，<span class="mono">queueId</span> → id。</div>' +
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
    ['GET', 'scrape/agent/queue/{id}', '取单条详情：现有值、内嵌值、册清单、上次回填', 'scrape:read'],
    ['POST', 'scrape/agent/queue/{id}/result', '提交结果；id 给条目 id 走比对，给 seriesId 直接覆盖', 'scrape:submit'],
    ['GET', 'scrape/agent/stats', '名单条目数与待处理回填数', 'scrape:read'],
  ];
  const box = s => '<pre class="mono" style="font-size:11.5px;line-height:1.75;background:var(--bg-2);padding:14px;border-radius:8px;overflow:auto">' + s + '</pre>';
  return '<div class="note">基地址 <span class="mono">http://127.0.0.1:25600/api/lantai/v1/</span>。对外只开放下面四个接口，令牌在「刮削设置 → 刮削令牌」生成，权限写死为 <span class="mono">scrape:read</span> 与 <span class="mono">scrape:submit</span>，读不到书库其它接口。</div>' +
    '<div class="note" style="margin-top:8px">外部程序用请求头传令牌，二选一：<span class="mono">X-API-Token: lt_xxx</span> 或 <span class="mono">Authorization: Bearer lt_xxx</span>。浏览器登录态的 <span class="mono">LANTAI-SESSION</span> Cookie 也能通，但脚本不要依赖它。</div>' +
    '<div class="tbl-wrap" style="margin-top:12px"><table class="tbl"><thead><tr><th style="width:64px">方法</th><th style="width:290px">路径</th><th>说明</th><th style="width:110px">所需权限</th></tr></thead><tbody>' +
    rows.map(r => '<tr><td><span class="badge">' + r[0] + '</span></td><td class="mono mini">' + r[1] + '</td><td class="mini">' + r[2] + '</td><td class="mini mono">' + r[3] + '</td></tr>').join('') +
    '</tbody></table></div>' +
    '<h4 style="margin:16px 0 8px">拉名单</h4>' +
    box('curl -H "X-API-Token: lt_xxxxxxxx" \\\n  "http://127.0.0.1:25600/api/lantai/v1/scrape/agent/queue?offset=0&amp;limit=50"') +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)"><span class="mono">offset</span> 默认 0，<span class="mono">limit</span> 默认 20、最大 200。返回的 <span class="mono">total</span> 与 <span class="mono">counts</span>（queued / running / review / failed）都是<b>名单条目数</b>——一个系列一行，不是册数；<span class="mono">items</span> 每条含 <span class="mono">id</span>、<span class="mono">seriesId</span>、<span class="mono">seriesTitle</span>、<span class="mono">status</span>、<span class="mono">attempt</span>、<span class="mono">createdDate</span>、<span class="mono">lastError</span>，以及 <span class="mono">bookIds</span>——空数组表示整系列，非空表示只刮这几册，长度即册数。</div>' +
    '<h4 style="margin:16px 0 8px">取单条详情</h4>' +
    box('curl -H "X-API-Token: lt_xxxxxxxx" \\\n  "http://127.0.0.1:25600/api/lantai/v1/scrape/agent/queue/0K3M8Q1T7XGE5"') +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">返回现有值（<span class="mono">summary</span> / <span class="mono">publisher</span> / <span class="mono">authors</span> / <span class="mono">genres</span> / <span class="mono">hasCover</span>）、内嵌值（<span class="mono">embeddedAuthors</span> / <span class="mono">embeddedPublisher</span>）、<span class="mono">books</span> 册清单（每册含 <span class="mono">bookId</span>、<span class="mono">fileName</span>、<span class="mono">title</span>、<span class="mono">number</span>、<span class="mono">authors</span>、<span class="mono">releaseDate</span>、<span class="mono">isbn</span>、<span class="mono">tags</span>、<span class="mono">summary</span>、<span class="mono">publisher</span>、<span class="mono">genres</span>、<span class="mono">language</span> 与三个锁定标记 <span class="mono">titleLock</span> / <span class="mono">numberLock</span> / <span class="mono">authorsLock</span>），以及 <span class="mono">external</span>——上次回填的原始 payload，没有则为 null。</div>' +
    '<h4 style="margin:16px 0 8px">回传结果</h4>' +
    box('curl -X POST -H "X-API-Token: lt_xxxxxxxx" -H "Content-Type: application/json" \\\n  -d \'{"metadata":{"title":"迷宫饭","authors":["九井谅子"],"genres":["奇幻","冒险"]}}\' \\\n  "http://127.0.0.1:25600/api/lantai/v1/scrape/agent/queue/0K3M8Q1T7XGE5/result"') +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">payload 可以套一层 <span class="mono">metadata</span>，也可以把字段平铺在顶层。数组字段用 JSON 数组，或用顿号 / 逗号 / 竖线分隔的字符串。别名照样识别：<span class="mono">description</span> → 简介、<span class="mono">categories</span> → 分类、<span class="mono">author</span> → 作者、<span class="mono">publishDate</span> → 出版日期、<span class="mono">cover</span> → 封面地址。</div>' +
    '<div class="mini" style="margin-top:6px;color:var(--tx-3)">返回 <span class="mono">status</span>：<span class="mono">DONE</span> 已应用并移出名单，<span class="mono">REVIEW</span> 进入待核对；<span class="mono">appliedFields</span> 是真正写入的字段，<span class="mono">message</span> 说明跳过或失败原因。空值不会被当成「清空」，只表示该字段保持一致。</div>' +
    '<h4 style="margin:16px 0 8px">按册写</h4>' +
    box('{\n  "metadata": { "publisher": "青文" },\n  "books": [\n    { "bookId": "0K3M8Q1T7XGE5", "fields": ["title", "authors", "isbn"], "title": "迷宫饭 1", "authors": ["九井谅子"], "isbn": "9789863562305" }\n  ]\n}') +
    '<div class="mini" style="margin-top:8px;color:var(--tx-3)">带 <span class="mono">books</span> 时只写列出的册；<span class="mono">fields</span> 省略则默认写 <span class="mono">title</span>、<span class="mono">number</span>、<span class="mono">authors</span>、<span class="mono">releaseDate</span>、<span class="mono">isbn</span>、<span class="mono">tags</span>、<span class="mono">summary</span>、<span class="mono">publisher</span>、<span class="mono">genres</span>、<span class="mono">language</span>。队列条目本身带册范围时，范围外的 <span class="mono">bookId</span> 会被忽略；已锁定的字段跳过。</div>' +
    '<h4 style="margin:16px 0 8px">查统计</h4>' +
    box('curl -H "X-API-Token: lt_xxxxxxxx" "http://127.0.0.1:25600/api/lantai/v1/scrape/agent/stats"\n\n{"total":2,"queued":1,"review":1,"failed":0,"inboxReceived":1}') +
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
