(function () {
  'use strict';

  var L = window.LantaiI18n || { t: function (k) { return k; }, lang: function () { return 'zh-Hans'; }, langs: function () { return []; } };
  var base = (window.resourceBaseUrl || '/').replace(/\/?$/, '/');
  var live = { libs: false, series: false, books: false, users: false, stats: false, tasks: false, devices: false, system: false, runtimes: false };
  var TASK_CN = {
    ScanLibrary: '扫描书库',
    FindBooksToConvert: '查找待转换书籍',
    FindBooksWithMissingPageHash: '查找缺失页哈希',
    FindDuplicatePagesToDelete: '查找重复页',
    EmptyTrash: '清空回收站',
    AnalyzeBook: '分析书籍',
    GenerateBookThumbnail: '生成封面',
    FindBookThumbnailsToRegenerate: '检查待重建封面',
    RefreshBookMetadata: '刷新书籍元数据',
    RefreshSeriesMetadata: '刷新系列元数据',
    AggregateSeriesMetadata: '聚合系列元数据',
    RefreshBookLocalArtwork: '刷新书籍本地图片',
    RefreshSeriesLocalArtwork: '刷新系列本地图片',
    HashBook: '计算书籍哈希',
    HashBookPages: '计算页哈希',
    HashBookKoreader: '计算 Kobo 哈希',
    RemoveHashedPages: '清理页哈希',
    RebuildIndex: '重建索引',
    UpgradeIndex: '升级索引',
    ImportBook: '导入书籍',
    ConvertBook: '格式转换',
    RepairExtension: '修复扩展名',
    DeleteBook: '删除书籍',
    DeleteSeries: '删除系列'
  };
  function taskCat(type) {
    if (/^(Refresh|Aggregate|Generate|FindBookThumbnails)/.test(type)) return 'meta';
    if (/^(Import|Convert)/.test(type)) return 'io';
    return 'scan';
  }
  window.taskLabel = function (type) { return TASK_CN[type] || type; };
  var loading = {};

  function pad(n) { return String(n).padStart(2, '0'); }
  function fmtDate(v) {
    if (!v) return '';
    var d = new Date(v);
    if (isNaN(d.getTime())) return '';
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }
  function extOf(name) {
    var m = /\.([a-z0-9]+)\s*$/i.exec(name || '');
    return m ? m[1].toLowerCase() : '';
  }
  function basic(email, password) {
    var raw = unescape(encodeURIComponent(email + ':' + password));
    return 'Basic ' + btoa(raw);
  }

  function request(path, opts) {
    opts = opts || {};
    var init = { credentials: 'same-origin', headers: Object.assign({}, opts.headers || {}) };
    if (opts.method) init.method = opts.method;
    if (opts.signal) init.signal = opts.signal;
    if (opts.body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(opts.body);
    }
    return fetch(base + path.replace(/^\//, ''), init).then(function (res) {
      if (res.status === 401) {
        var e401 = new Error('unauthorized');
        e401.status = 401;
        if (runtime.me || runtime.sse) {
          runtime.me = null;
          if (runtime.sse) { runtime.sse.close(); runtime.sse = null; }
          if (typeof closeDrawer === 'function') closeDrawer();
          showBoot('登录状态已失效，请重新登录。', false);
        }
        throw e401;
      }
      if (!res.ok) {
        return res.text().catch(function () { return ''; }).then(function (text) {
          var err = new Error(text || ('HTTP ' + res.status));
          err.status = res.status;
          throw err;
        });
      }
      if (res.status === 204) return null;
      return res.text().then(function (text) {
        if (!text) return null;
        var ct = res.headers.get('content-type') || '';
        if (ct.indexOf('json') < 0) return text;
        try { return JSON.parse(text); } catch (e) { return text; }
      });
    });
  }

  function unwrap(res) {
    if (Array.isArray(res)) return res;
    return (res && res.content) || [];
  }

  function requestEtag(path, etag) {
    var init = { credentials: 'same-origin', headers: {} };
    if (etag) init.headers['If-None-Match'] = etag;
    return fetch(base + path.replace(/^\//, ''), init).then(function (res) {
      if (res.status === 304) return { notModified: true, etag: etag };
      if (res.status === 401) {
        var e401 = new Error('unauthorized');
        e401.status = 401;
        throw e401;
      }
      if (!res.ok) {
        var err = new Error('HTTP ' + res.status);
        err.status = res.status;
        throw err;
      }
      return res.json().then(function (data) {
        return { data: data, etag: res.headers.get('ETag') || '' };
      });
    });
  }

  function notDeleted(body) {
    var b = body || {}, cond = b.condition, extra = { deleted: { operator: 'isFalse' } };
    var out = {};
    for (var k in b) out[k] = b[k];
    if (!cond) out.condition = extra;
    else if (Array.isArray(cond.allOf)) out.condition = { allOf: cond.allOf.concat([extra]) };
    else out.condition = { allOf: [cond, extra] };
    return out;
  }

  function searchSeries(body) {
    return request('api/v1/series/list?unpaged=true&sort=lastModified,desc', { method: 'POST', body: notDeleted(body) }).then(unwrap);
  }

  function letterIds(letter) {
    return request('api/lantai/v1/series/letter-ids?letter=' + encodeURIComponent(letter)).then(unwrap);
  }

  function bookLetterIds(letter) {
    return request('api/lantai/v1/books/letter-ids?letter=' + encodeURIComponent(letter)).then(unwrap);
  }

  function bookLetterGroups() {
    return request('api/lantai/v1/books/letter-groups').then(function (r) { return Array.isArray(r) ? r : []; });
  }

  function searchBooks(body) {
    return request('api/v1/books/list?unpaged=true&sort=lastModified,desc', { method: 'POST', body: notDeleted(body) }).then(unwrap);
  }

  function seriesGroups(body) {
    return request('api/v1/series/list/alphabetical-groups', { method: 'POST', body: body || {} }).then(function (r) { return Array.isArray(r) ? r : []; });
  }

  function refData(kind) {
    return request('api/v2/' + kind + '?unpaged=true').then(function (r) { return unwrap(r); });
  }

  function mapLibrary(l) {
    return {
      id: l.id,
      name: l.name,
      type: l.type === 'ONLINE' ? 'ONLINE' : 'LOCAL',
      mediaType: '',
      root: l.root || '',
      scan: { cbx: l.scanCbx ? 1 : 0, pdf: l.scanPdf ? 1 : 0, epub: l.scanEpub ? 1 : 0, txt: l.scanTxt ? 1 : 0 },
      interval: l.scanInterval || 'DISABLED',
      hashFiles: !!l.hashFiles,
      hashPages: !!l.hashPages,
      hashKoreader: !!l.hashKoreader,
      analyzeDimensions: !!l.analyzeDimensions,
      forceModified: !!l.scanForceModifiedTime,
      onStartup: !!l.scanOnStartup,
      repairExt: !!l.repairExtensions,
      convertCbz: !!l.convertToCbz,
      oneshots: l.oneshotsDirectory || '',
      exclusions: Array.isArray(l.scanDirectoryExclusions) ? l.scanDirectoryExclusions.join('\n') : '',
      unavailable: !!l.unavailable,
      updated: ''
    };
  }

  function mapSeries(s) {
    var md = s.metadata || {};
    var bm = s.booksMetadata || {};
    var owner = LIBS.find(function (l) { return l.id === s.libraryId; });
    var online = !!(owner && owner.type === 'ONLINE');
    var status = md.status === 'ENDED' ? 'COMPLETED' : md.status === 'ABANDONED' ? 'HIATUS' : (md.status || 'ONGOING');
    var releaseDate = bm.releaseDate || '';
    return {
      id: s.id,
      lib: s.libraryId,
      t: md.title || s.name,
      author: (bm.authors || []).map(function (a) { return a.name; }).join('、'),
      status: status,
      oneShot: !!s.oneshot,
      vol: '',
      genres: md.genres || [],
      tags: Array.from(md.tags || []),
      lang: md.language || '',
      dirs: s.booksCount || 0,
      last: fmtDate(s.lastModified),
      note: md.summary || bm.summary || '',
      online: online,
      publisher: md.publisher || '',
      source: '',
      published: releaseDate,
      year: releaseDate ? parseInt(String(releaseDate).slice(0, 4), 10) || 0 : 0,
      chapters: s.booksCount || 0,
      downloaded: 0
    };
  }

  function mapBook(b) {
    var media = b.media || {};
    var md = b.metadata || {};
    var ext = extOf(b.name);
    var profile = media.mediaProfile || ({ pdf: 'PDF', epub: 'EPUB', cbz: 'DIVINA', cbr: 'DIVINA', txt: 'TXT' })[ext] || '';
    if (!profile && media.mediaType) {
      var mt = String(media.mediaType).toLowerCase();
      if (mt.indexOf('epub') >= 0) profile = 'EPUB';
      else if (mt.indexOf('pdf') >= 0) profile = 'PDF';
      else if (mt.indexOf('zip') >= 0 || mt.indexOf('rar') >= 0) profile = 'DIVINA';
      else if (mt.indexOf('text') >= 0) profile = 'TXT';
    }
    var fmt = ext || (profile === 'DIVINA' ? 'cbz' : profile === 'EPUB' ? 'epub' : profile === 'PDF' ? 'pdf' : profile === 'TXT' ? 'txt' : '');
    return {
      id: b.id,
      s: b.seriesId,
      num: b.number || 0,
      name: b.name,
      mdTitle: md.title || '',
      mdNum: md.number || '',
      mdIsbn: md.isbn || '',
      mdAuthors: (md.authors || []).map(function (a) { return a.name; }).join('、'),
      fmt: fmt,
      profile: profile,
      divina: !!media.epubDivinaCompatible,
      pages: media.pagesCount || 0,
      size: b.sizeBytes || 0,
      pub: md.releaseDate || '',
      added: b.createdDate || '',
      last: fmtDate(b.lastModified || b.fileLastModified || b.createdDate),
      status: media.status || 'READY',
      mediaType: media.mediaType || '',
      w: 0,
      h: 0,
      note: '',
      _rp: b.readProgress || null
    };
  }

  function applyReadProgress(rawBooks) {
    rawBooks.forEach(function (b) {
      if (!b.readProgress) return;
      var rp = b.readProgress;
      PROGRESS[b.id] = {
        page: rp.page || 0,
        pages: (b.media && b.media.pagesCount) || 0,
        completed: !!rp.completed,
        at: new Date(rp.readDate || rp.lastModified || rp.created || Date.now()).getTime(),
        device: rp.deviceName || 'Web'
      };
    });
  }

  function mergeSeries(list) {
    list.forEach(function (s) {
      var mapped = mapSeries(s);
      var idx = SERIES.findIndex(function (x) { return x.id === s.id; });
      if (idx >= 0) SERIES[idx] = mapped; else SERIES.push(mapped);
    });
  }

  function mergeBooks(list) {
    list.forEach(function (b) {
      var mapped = mapBook(b);
      var idx = BOOKS.findIndex(function (x) { return x.id === b.id; });
      if (idx >= 0) BOOKS[idx] = mapped; else BOOKS.push(mapped);
    });
    applyReadProgress(list);
  }

  function fetchAll(path, size) {
    var out = [], page = 0;
    function step() {
      var sep = path.indexOf('?') >= 0 ? '&' : '?';
      return request(path + sep + 'size=' + size + '&page=' + page).then(function (p) {
        var content = (p && p.content) || [];
        out = out.concat(content);
        var total = (p && typeof p.totalElements === 'number') ? p.totalElements : out.length;
        page++;
        if (!content.length || out.length >= total) return out;
        return step();
      });
    }
    return step();
  }

  function hydrateShell() {
    return Promise.all([
      request('api/v1/libraries').catch(function () { return null; }),
      request('api/v2/users').catch(function () { return null; }),
      request('api/lantai/v1/reading/stats').catch(function () { return null; }),
      request('api/lantai/v1/tasks').catch(function () { return null; }),
      request('api/lantai/v1/devices').catch(function () { return null; }),
      request('api/lantai/v1/system').catch(function () { return null; }),
      request('api/lantai/v1/runtimes').catch(function () { return null; })
    ]).then(function (out) {
      var libs = out[0], users = out[1], stats = out[2], tasks = out[3], devices = out[4], system = out[5], runtimes = out[6];

      LIBS.length = 0; USERS.length = 0;
      if (typeof TASKS !== 'undefined') TASKS.length = 0;
      if (typeof DEVICES !== 'undefined') DEVICES.length = 0;
      if (typeof SESSIONS !== 'undefined') SESSIONS.length = 0;
      if (typeof ANNOTATIONS !== 'undefined') ANNOTATIONS.length = 0;
      if (typeof PROGRESS !== 'undefined') Object.keys(PROGRESS).forEach(function (k) { delete PROGRESS[k]; });
      if (Array.isArray(libs)) {
        libs.forEach(function (l) { LIBS.push(mapLibrary(l)); });
        live.libs = true;
      }
      if (Array.isArray(users)) {
        users.forEach(function (u) {
          var uname = u.username || (u.email ? String(u.email).split('@')[0] : '');
          USERS.push({
            id: u.id,
            username: uname,
            name: uname,
            email: u.email || '',
            roles: Array.from(u.roles || []),
            me: !!(runtime.me && runtime.me.id && runtime.me.id === u.id),
            libs: u.sharedAllLibraries ? '全部库' : ((u.sharedLibrariesIds || []).length + ' 个库'),
            last: ''
          });
        });
        live.users = true;
      }
      if (stats && Array.isArray(stats.days)) {
        SESSIONS.length = 0;
        stats.days.forEach(function (d) {
          if (!d.durationMs && !d.pagesRead) return;
          var day = new Date(d.date + 'T12:00:00');
          SESSIONS.push({ start: day.getTime(), dur: d.durationMs || 0, pages: d.pagesRead || 0, n: d.sessionCount || 0, dev: 'server', devName: '服务端汇总', srv: true });
        });
        S.sessionExtra = 0;
        runtime.statsSummary = {
          from: stats.from, to: stats.to,
          totalDurationMs: stats.totalDurationMs || 0,
          totalPagesRead: stats.totalPagesRead || 0,
          sessionCount: stats.sessionCount || 0,
          bookCount: stats.bookCount || 0,
          books: Array.isArray(stats.books) ? stats.books : []
        };
        live.stats = true;
      }
      if (tasks && Array.isArray(tasks.tasks) && typeof TASKS !== 'undefined') {
        TASKS.length = 0;
        tasks.tasks.forEach(function (t) {
          TASKS.push({
            id: t.id,
            name: TASK_CN[t.type] || t.type,
            state: t.owner ? 'run' : 'queued',
            at: String(t.createdDate || '').replace('T', ' ').slice(5, 16),
            detail: (t.owner ? '执行 ' + t.owner : '等待调度') + ' · 优先级 ' + t.priority,
            cat: taskCat(t.type),
            type: t.type, priority: t.priority, owner: t.owner
          });
        });
        runtime.taskSummary = { total: tasks.total, running: tasks.running, queued: tasks.queued, byType: tasks.byType || {} };
        live.tasks = true;
      }
      if (devices && Array.isArray(devices.devices)) {
        runtime.devices = devices.devices;
        live.devices = true;
      }
      if (system && system.counts) {
        runtime.system = system;
        live.system = true;
      }
      if (Array.isArray(runtimes)) {
        var map = {};
        runtimes.forEach(function (r) { if (r && r.kind) map[r.kind] = r; });
        runtime.runtimes = map;
        live.runtimes = true;
      }
      if (typeof CFG !== 'undefined') {
        CFG.host = location.origin;
        CFG.port = Number(location.port) || (location.protocol === 'https:' ? 443 : 80);
        if (runtime.system && runtime.system.version) {
          CFG.version = runtime.system.version;
          CFG.build = 'lantai-' + runtime.system.version + '.jar';
        }
      }
    });
  }

  var CATALOG_PAGE = (function () {
    try {
      var n = Number(localStorage.getItem('lantai.pageSize'));
      return n > 0 ? n : 100;
    } catch (e) { return 100; }
  })();

  function catalogState() {
    if (!runtime.catalog) {
      runtime.catalog = {
        series: { page: 0, total: 0, done: false },
        books: { page: 0, total: 0, done: false }
      };
    }
    return runtime.catalog;
  }

  function catalogPage(kind) {
    var st = catalogState()[kind];
    if (st.done) return Promise.resolve(false);
    var path = (kind === 'series' ? 'api/v1/series?sort=lastModified,desc' : 'api/v1/books?sort=lastModified,desc')
      + '&deleted=false&size=' + CATALOG_PAGE + '&page=' + st.page;
    return request(path).then(function (p) {
      var content = (p && p.content) || [];
      var first = st.page === 0;
      st.page++;
      st.total = (p && typeof p.totalElements === 'number') ? p.totalElements : st.total + content.length;
      if (kind === 'series') {
        if (first && !runtime.catalogLoaded) SERIES.length = 0;
        mergeSeries(content);
        live.series = true;
        st.done = !content.length || SERIES.length >= st.total;
      } else {
        if (first && !runtime.catalogLoaded) BOOKS.length = 0;
        mergeBooks(content);
        live.books = true;
        st.done = !content.length || BOOKS.length >= st.total;
      }
      return !st.done;
    });
  }

  function hydrateCatalog(reuse, force) {
    if (!force && reuse && runtime.catalogLoaded) return Promise.resolve(true);
    if (!force && runtime.catalogLoading) return runtime.catalogLoading;
    if (force) {
      runtime.catalogLoaded = false;
      runtime.catalogLoading = null;
    }
    runtime.catalog = null;
    runtime._more = null;
    runtime.catalogLoading = Promise.all([
      catalogPage('series').catch(function () { return false; }),
      catalogPage('books').catch(function () { return false; })
    ]).then(function () {
      runtime.catalogLoaded = true;
      runtime.catalogLoading = null;
      if (typeof window.__lantaiOnCatalog === 'function') window.__lantaiOnCatalog();
      return true;
    }, function () {
      runtime.catalogLoading = null;
      return false;
    });
    return runtime.catalogLoading;
  }

  function catalogMore(kind) {
    if (!runtime._more) runtime._more = {};
    if (runtime._more[kind]) return runtime._more[kind];
    var p = catalogPage(kind).then(function (hasMore) {
      runtime._more[kind] = null;
      return hasMore;
    }, function () {
      runtime._more[kind] = null;
      return false;
    });
    runtime._more[kind] = p;
    return p;
  }

  function catalogFull(kind) {
    if (catalogState()[kind].done) return Promise.resolve(true);
    return catalogMore(kind).then(function (hasMore) {
      return hasMore ? catalogFull(kind) : true;
    });
  }

  function catalogTotal() {
    var st = catalogState();
    return {
      series: st.series.total || SERIES.length,
      books: st.books.total || BOOKS.length,
      seriesDone: st.series.done,
      booksDone: st.books.done
    };
  }

  function hydrate(force) {
    return hydrateShell().then(function () { return hydrateCatalog(false, force); }).then(paintRuntimeDot);
  }

  function fetchSeriesBooks(id) {
    return request('api/v1/series/' + id + '/books?size=500').then(function (page) {
      var content = (page && page.content) || [];
      var ids = {};
      content.forEach(function (b) { ids[b.id] = true; });
      for (var i = BOOKS.length - 1; i >= 0; i--) {
        if (BOOKS[i].s === id && !ids[BOOKS[i].id]) BOOKS.splice(i, 1);
      }
      mergeBooks(content);
    });
  }

  function collectionSeries(id) {
    return request('api/v1/collections/' + id + '/series?unpaged=true').then(function (page) {
      var list = (page && page.content) || [];
      mergeSeries(list);
      return list.map(function (s) { return s.id; });
    });
  }

  function readlistBooks(id) {
    return request('api/v1/readlists/' + id + '/books?unpaged=true').then(function (page) {
      var list = (page && page.content) || [];
      mergeBooks(list);
      return list.map(function (b) { return b.id; });
    });
  }

  function fetchSeries(id) {
    return request('api/v1/series/' + id).then(function (s) { mergeSeries([s]); });
  }

  function ensureSeries(id) {
    if (!SERIES.some(function (s) { return s.id === id; })) {
      var p = fetchSeries(id);
      return p.then(function () { return fetchSeriesBooks(id); });
    }
    return fetchSeriesBooks(id);
  }

  function ensureSeriesOnce(id) {
    S._seriesLoaded = S._seriesLoaded || {};
    if (S._seriesLoaded[id]) return null;
    var p = ensureSeries(id).then(function () { S._seriesLoaded[id] = true; }).catch(function () { S._seriesLoaded[id] = true; });
    return { pending: true, promise: p };
  }

  function ensureBook(id) {
    if (BOOKS.some(function (b) { return b.id === id; })) return null;
    if (!loading[id]) {
      loading[id] = request('api/v1/books/' + id).then(function (b) {
        var mapped = mapBook(b);
        var idx = BOOKS.findIndex(function (x) { return x.id === id; });
        if (idx >= 0) BOOKS[idx] = mapped; else BOOKS.push(mapped);
        applyReadProgress([b]);
      }).catch(function () {}).then(function () { delete loading[id]; });
    }
    return { pending: true, promise: loading[id] };
  }

  function skeleton() {
    var view = document.querySelector('#view');
    if (!view) return;
    view.innerHTML = '<div class="empty" style="padding-top:120px"><div class="spinner" style="margin:0 auto 14px"></div><h4>正在载入…</h4></div>';
  }

  var REMEMBER_KEY = 'lantai.remember';

  function readRemember() {
    try {
      var raw = localStorage.getItem(REMEMBER_KEY);
      if (!raw) return null;
      var v = JSON.parse(raw);
      return v && v.u ? v : null;
    } catch (e) { return null; }
  }

  function saveRemember(login, password) {
    var box = document.querySelector('#bRemember');
    try {
      if (box && box.checked) localStorage.setItem(REMEMBER_KEY, JSON.stringify({ u: login, p: password }));
      else localStorage.removeItem(REMEMBER_KEY);
    } catch (e) { }
  }

  function showBoot(msg, isError) {
    var boot = document.querySelector('#boot');
    var app = document.querySelector('#app');
    if (app) app.style.display = 'none';
    if (boot) boot.style.display = '';
    if (typeof bootHtml === 'function') bootHtml();
    if (msg) bootError(msg, isError !== false);
  }

  function bootError(msg, isError) {
    var body = document.querySelector('#bootBody');
    if (!body) return;
    var olds = body.querySelectorAll('.boot-err');
    for (var i = 0; i < olds.length; i++) body.removeChild(olds[i]);
    var el = document.createElement('div');
    el.className = 'mini boot-err';
    el.style.cssText = 'margin-top:10px;line-height:1.6;color:' + (isError === false ? 'var(--tx-3)' : 'var(--bad)');
    el.textContent = msg;
    body.appendChild(el);
  }

  function enter() {
    return hydrateShell().then(function () {
      showApp();
      hydrateCatalog();
      bindWake();
      watchRuntime();
    }).catch(function (err) {
      showApp();
      if (typeof toast === 'function') toast('数据载入失败，当前显示示例数据', 'err');
    });
  }

  function paintRuntimeDot() {
    if (typeof paintRuntime === 'function') paintRuntime();
  }

  function pullRuntimes() {
    return request('api/lantai/v1/runtimes')
      .then(function (list) {
        if (!Array.isArray(list)) return;
        var map = {};
        list.forEach(function (r) { if (r && r.kind) map[r.kind] = r; });
        runtime.runtimes = map;
        live.runtimes = true;
      })
      .catch(function () { })
      .then(paintRuntimeDot);
  }

  function swyRunning() {
    var st = (runtime.runtimes || {}).suwayomi;
    return !!(st && st.running);
  }

  var watchTimer = null;
  var WATCH_DELAYS = [3000, 8000, 15000, 25000, 40000];

  function watchRuntime() {
    if (watchTimer) { clearTimeout(watchTimer); watchTimer = null; }
    if (swyRunning()) return;
    var i = 0;
    var step = function () {
      watchTimer = null;
      if (swyRunning()) return;
      pullRuntimes().then(function () {
        if (swyRunning() || i + 1 >= WATCH_DELAYS.length) return;
        i++;
        watchTimer = setTimeout(step, WATCH_DELAYS[i]);
      });
    };
    watchTimer = setTimeout(step, WATCH_DELAYS[0]);
  }

  var wakeBound = false;

  function bindWake() {
    if (wakeBound || !document.addEventListener) return;
    wakeBound = true;
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) return;
      pullRuntimes().then(function () { if (!swyRunning()) watchRuntime(); });
    });
  }

  function showApp() {
    var boot = document.querySelector('#boot');
    var app = document.querySelector('#app');
    if (boot) boot.style.display = 'none';
    if (app) app.style.display = '';
    render();
    paintRuntime();
    paintBell();
    connectSse();
  }

  function connectSse() {
    if (runtime.sse || typeof EventSource === 'undefined') return;
    var es;
    try {
      es = new EventSource(base + 'sse/v1/events', { withCredentials: true });
    } catch (e) {
      return;
    }
    runtime.sse = es;
    var timer = null, hits = 0, lastHit = 0, burst = false, queueSeen = false;
    var refresh = function () {
      var now = Date.now();
      if (now - lastHit < 2000) hits++; else hits = Math.max(0, hits - 1);
      lastHit = now;
      if (hits >= 20) burst = true;
      clearTimeout(timer);
      timer = setTimeout(function () {
        if (Date.now() - lastHit < 4000) { refresh(); return; }
        var path = parseHash().path;
        var view0 = document.querySelector('#view');
        var top = view0 ? view0.scrollTop : 0;
        hits = 0; burst = false;
        window.__lantaiKeepScroll = true;
        hydrate().then(function () {
          if (!/^\/read\//.test(path)) render();
          var v = document.querySelector('#view');
          if (v) v.scrollTop = top;
        }).catch(function () {});
      }, burst ? 6000 : 2500);
    };
    ['BookAdded', 'BookChanged', 'BookDeleted', 'BookImported',
      'SeriesAdded', 'SeriesChanged', 'SeriesDeleted',
      'LibraryAdded', 'LibraryChanged', 'LibraryDeleted',
      'CollectionAdded', 'CollectionChanged', 'CollectionDeleted',
      'ReadListAdded', 'ReadListChanged', 'ReadListDeleted',
      'ReadProgressChanged', 'ReadProgressDeleted', 'ReadProgressSeriesChanged', 'ReadProgressSeriesDeleted',
      'ThumbnailBookAdded', 'ThumbnailBookDeleted', 'ThumbnailSeriesAdded', 'ThumbnailSeriesDeleted',
      'ThumbnailSeriesCollectionAdded', 'ThumbnailSeriesCollectionDeleted',
      'ThumbnailReadListAdded', 'ThumbnailReadListDeleted'].forEach(function (name) {
      es.addEventListener(name, refresh);
    });
    es.addEventListener('TaskQueueStatus', function (e) {
      var n = 0;
      try { n = (JSON.parse(e.data || '{}').count) || 0; } catch (err) { n = 0; }
      if (n > 0) { queueSeen = true; return; }
      if (queueSeen) { queueSeen = false; refresh(); }
    });
    es.addEventListener('SessionExpired', function () { location.reload(); });
    es.onerror = function () {
      if (es.readyState === EventSource.CLOSED) {
        es.close();
        runtime.sse = null;
        setTimeout(connectSse, 15000);
      }
    };
  }

  function wrapRender() {
    if (window.__lantaiRenderWrapped) return;
    window.__lantaiRenderWrapped = true;
    var origRender = window.render;
    window.render = function () {
      var path = parseHash().path;
      var sm = /^\/series\/([^/?]+)/.exec(path);
      var bm = /^\/book\/([^/?]+)/.exec(path);
      var pending = null;
      if (sm) pending = ensureSeriesOnce(sm[1]);
      if (bm) pending = pending || ensureBook(bm[1]);
      var view = document.querySelector('#view');
      if (pending) {
        if (view.getAttribute('data-pending') !== path) {
          view.setAttribute('data-pending', path);
          skeleton();
        }
        pending.promise.then(function () {
          view.removeAttribute('data-pending');
          origRender();
        });
        return;
      }
      origRender();
    };
  }

  var runtime = {
    base: base,
    me: null,
    sse: null,
    live: live,
    request: request,
    requestEtag: requestEtag,
    searchSeries: searchSeries,
    letterIds: letterIds,
    bookLetterIds: bookLetterIds,
    bookLetterGroups: bookLetterGroups,
    searchBooks: searchBooks,
    seriesGroups: seriesGroups,
    refData: refData,
    remember: readRemember,
    hydrate: hydrate,
    hydrateShell: hydrateShell,
    hydrateCatalog: hydrateCatalog,
    catalogReady: function () { return !!runtime.catalogLoaded; },
    catalogMore: catalogMore,
    catalogFull: catalogFull,
    catalogTotal: catalogTotal,
    collectionSeries: collectionSeries,
    readlistBooks: readlistBooks,
    start: function () {
      wrapRender();
      var walk = fetch(base + 'api/v2/users/me', { credentials: 'same-origin' });
      return walk.then(function (res) {
        if (res.status === 401) {
          showBoot(L.t('boot.needLogin'), false);
          return;
        }
        if (!res.ok) {
          showBoot(L.t('boot.serverDown', { code: res.status }), true);
          return;
        }
        return res.json().then(function (me) {
          runtime.me = me;
          return enter();
        });
      }).catch(function (err) {
        showBoot(L.t('boot.cantConnect'), true);
      });
    },
    login: function () {
      var userEl = document.querySelector('#bIn1');
      var pwdEl = document.querySelector('#bIn2');
      var btn = document.querySelector('#bGo');
      var login = (userEl.value || '').trim();
      var password = pwdEl.value || '';
      if (!login || !password) return bootError(L.t('boot.fillLogin'));
      if (btn.disabled) return;
      btn.disabled = true;
      var old = btn.textContent;
      btn.innerHTML = '<span class="spinner" style="margin-right:8px"></span>' + L.t('boot.checking');
      fetch(base + 'api/v2/users/me?remember-me=true', {
        credentials: 'same-origin',
        headers: { Authorization: basic(login, password) }
      }).then(function (res) {
        if (res.status === 401) throw new Error(L.t('boot.badCred'));
        if (!res.ok) throw new Error(L.t('boot.loginFail'));
        return res.json();
      }).then(function (me) {
        runtime.me = me;
        saveRemember(login, password);
        return enter();
      }).catch(function (err) {
        btn.disabled = false;
        btn.textContent = old;
        bootError(err.message || String(err));
      });
    },
    logout: function () {
      return fetch(base + 'api/logout', { method: 'POST', credentials: 'same-origin' }).catch(function () {}).then(function () {
        runtime.me = null;
        if (runtime.sse) { runtime.sse.close(); runtime.sse = null; }
        if (typeof closeDrawer === 'function') closeDrawer();
        showBoot(L.t('boot.loggedOut'), false);
      });
    },
    showBoot: showBoot,
    bootError: bootError
  };

  window.LantaiRuntime = runtime;
})();
