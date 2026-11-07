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
