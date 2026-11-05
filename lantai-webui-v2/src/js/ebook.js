(function () {
  'use strict';

  var THEMES = [
    ['paper', '纸白', '#fbf9f3', '#1f1e1a', '#ffffff', '#f3efe4', '#8a6d2f'],
    ['sepia', '牛皮纸', '#f0e5cf', '#4a3f2b', '#f8f0de', '#e6d8bd', '#8a5a26'],
    ['green', '护眼', '#dde8d6', '#2b362a', '#e8f0e2', '#cfdcc6', '#4c6b45'],
    ['ink', '墨黑', '#0c0d0f', '#9fa6ad', '#15171a', '#1b1e22', '#7a8fa6'],
    ['night', '夜间', '#14171c', '#b4c0cc', '#1b1f25', '#20252c', '#6f8aa6']
  ];
  var FONTS = [
    ['serif', '宋体', '"Songti SC","SimSun",Georgia,"Times New Roman",serif'],
    ['sans', '黑体', '"PingFang SC","Microsoft YaHei",system-ui,sans-serif'],
    ['kai', '楷体', '"Kaiti SC",KaiTi,STKaiti,"Songti SC",serif'],
    ['book', '原书', 'inherit']
  ];
  var COLORS = ['#C9A24B', '#B0402C', '#46523F', '#2F5D8A', '#7B4F6B', '#E3C377'];
  var MARK_CN = { highlight: '高亮', underline: '下划线', bookmark: '书签', comment: '笔记' };

  var PRE = {
    theme: 'paper', size: 18, lh: 1.85, para: 0.9, font: 'serif', measure: 38, indent: true,
    pubcss: true, wake: false, imm: false, ttsRate: 1, ttsVoice: '', sink: 0,
    layout: 'scroll', dir: 'LTR'
  };
  try { Object.assign(PRE, JSON.parse(localStorage.getItem('lantai-ebook') || '{}')); } catch (e) { }
  if (!localStorage.getItem('lantai-ebook.pub1')) {
    PRE.pubcss = true;
    try { localStorage.setItem('lantai-ebook.pub1', '1'); } catch (e) { }
  }

  var EB = {
    open: false, bookId: '', book: null, total: 0, ch: 1, seen: 1,
    chs: new Map(), secs: new Map(), pending: {}, park: new Map(),
    lo: 0, hi: 0, outline: null, outlineLoading: false,
    anns: [], links: [], inlines: [],
    panel: null, shadow: null, docEl: null, view: null,
    shadowSeeded: false, progTimer: null, hbTimer: null, session: null, beats: 0,
    restore: -1, restoreT: null, idle: null, raf: 0, bootAt: 0,
    sel: null, tts: null, ttsIdx: 0, ttsOn: false, wakeLock: null,
    toastT: null, lastCh: 0, ready: false, warmF: false, warmB: false,
    page: 0, pages: 1, annTab: 'all', annQ: '', annQT: 0,
    findQ: '', findMark: '', fontFams: null
  };

  function rt() { return window.LantaiRuntime; }
  function req(p, o) {
    var opt = o || {};
    if (PRE.imm) opt = Object.assign({}, opt, { headers: Object.assign({ 'X-Lantai-Incognito': '1' }, opt.headers || {}) });
    return rt().request(p, opt);
  }
  function $(id) { return document.getElementById(id); }
  function el(tag, cls, html) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (html != null) n.innerHTML = html;
    return n;
  }
  function ico(name, size) {
    return '<svg width="' + (size || 16) + '" height="' + (size || 16) + '"><use href="#i-' + name + '"/></svg>';
  }
  function errText(e) {
    if (!e) return '请求失败';
    if (e.status === 403) return '没有这个书库的阅读权限';
    if (e.status === 404) return '书籍或章节不存在';
    return e.message ? String(e.message).slice(0, 120) : 'HTTP ' + (e.status || 'err');
  }

  function toast(text, kind) {
    var t = $('ebToast'); if (!t) return;
    t.textContent = text;
    t.className = 'eb-toast on' + (kind === 'err' ? ' err' : '');
    clearTimeout(EB.toastT);
    EB.toastT = setTimeout(function () { t.className = 'eb-toast'; }, 2600);
  }
  function tip(title, body) {
    var t = $('ebTip'); if (!t) return;
    if (!title) { t.classList.remove('on'); return; }
    t.innerHTML = '<b>' + esc(title) + '</b>' + (body || '');
    t.classList.add('on');
  }

  function savePrefs() {
    try { localStorage.setItem('lantai-ebook', JSON.stringify(PRE)); } catch (e) { }
  }
  function immKey() { return 'lantai.imm.e.' + EB.bookId; }
  function immResume(bookId) {
    try { return JSON.parse(sessionStorage.getItem('lantai.imm.e.' + bookId) || 'null'); } catch (e) { return null; }
  }

  function themeOf(key) {
    for (var i = 0; i < THEMES.length; i++) if (THEMES[i][0] === key) return THEMES[i];
    return THEMES[0];
  }

  function applyTheme() {
    var r = $('eb'); if (!r) return;
    var t = themeOf(PRE.theme);
    r.style.setProperty('--eb-bg', t[2]);
    r.style.setProperty('--eb-tx', t[3]);
    r.style.setProperty('--eb-card', t[4]);
    r.style.setProperty('--eb-sunk', t[5]);
    r.style.setProperty('--eb-acc', t[6]);
    r.style.setProperty('--eb-dim', t[0] === 'ink' || t[0] === 'night' ? '#8b939b' : '#6f6a5e');
    r.style.setProperty('--eb-line', t[0] === 'ink' || t[0] === 'night' ? 'rgba(255,255,255,.12)' : 'rgba(0,0,0,.1)');
    r.style.setProperty('--eb-shadow', t[0] === 'ink' || t[0] === 'night' ? '0 16px 48px rgba(0,0,0,.55)' : '0 16px 48px rgba(48,40,24,.16)');
    r.classList.toggle('night', t[0] === 'ink' || t[0] === 'night');
    if (EB.shadow) applyBase();
  }

  function applyType() {
    var r = $('eb'); if (!r) return;
    r.style.setProperty('--eb-fs', PRE.size + 'px');
    r.style.setProperty('--eb-lh', PRE.lh);
    r.style.setProperty('--eb-para', PRE.para + 'em');
    r.style.setProperty('--eb-ti', PRE.indent ? '2em' : '0');
    r.style.setProperty('--eb-measure', PRE.measure + 'rem');
    r.style.setProperty('--eb-fam', fontFamily());
    if (PRE.font.indexOf('srv:') === 0) fontFaceOf(PRE.font.slice(4));
    if (EB.shadow) applyBase();
  }

  function fontFamily() {
    for (var i = 0; i < FONTS.length; i++) if (FONTS[i][0] === PRE.font) return FONTS[i][2];
    return FONTS[0][2];
  }

  var fontFaceState = { family: '', css: {} };

  function loadFontFamilies() {
    if (EB.fontFams !== null) return;
    EB.fontFams = [];
    req('api/v1/fonts/families').then(function (list) {
      var names = (list || []).filter(function (x) { return typeof x === 'string' && x; });
      EB.fontFams = names;
      names.forEach(function (name) {
        var key = 'srv:' + name;
        if (!FONTS.some(function (f) { return f[0] === key; })) FONTS.push([key, name, "'" + name + "',serif"]);
      });
      if (EB.panel === 'set') paintSet('set');
    }).catch(function () { });
  }

  function fontFaceOf(name) {
    if (!EB.shadow || !name) return;
    var style = EB.shadow.getElementById('ebFont');
    if (!style) {
      style = document.createElement('style');
      style.id = 'ebFont';
      var anchor = EB.shadow.getElementById('ebPub') || EB.shadow.getElementById('ebDoc');
      if (anchor && anchor.parentNode) anchor.parentNode.insertBefore(style, anchor);
      else EB.shadow.appendChild(style);
    }
    if (fontFaceState.family === name) return;
    if (fontFaceState.css[name]) {
      style.textContent = fontFaceState.css[name];
      fontFaceState.family = name;
      return;
    }
    var base = (window.resourceBaseUrl || '/').replace(/\/?$/, '/') + 'api/v1/fonts/resource/' + encodeURIComponent(name) + '/';
    fetch(base + 'css', { credentials: 'same-origin' })
      .then(function (res) { return res.ok ? res.text() : ''; })
      .then(function (css) {
        var fixed = String(css || '').replace(/url\((['"]?)([^'")]+)\1\)/g, function (whole, quote, url) {
          if (/^(data:|https?:|\/\/|\/)/.test(url)) return whole;
          return "url('" + base + url + "')";
        });
        fontFaceState.css[name] = fixed;
        if (PRE.font !== 'srv:' + name) return;
        fontFaceState.family = name;
        style.textContent = fixed;
      })
      .catch(function () { });
  }

  function isPaged() { return PRE.layout !== 'scroll'; }

  function colsOf(w) { return PRE.layout === 'double' && w >= 860 ? 2 : 1; }

  function viewHeight() {
    var v = EB.view;
    var h = (v && (v.clientHeight || v.offsetHeight)) || 0;
    return Math.max(280, Math.round(h || window.innerHeight * 0.8));
  }

  function layoutMetrics() {
    var v = EB.view;
    var cw = (v && (v.clientWidth || v.offsetWidth)) || 600;
    var gut = Math.round(Math.min(72, Math.max(16, cw * 0.045)));
    var cols = colsOf(cw);
    var gap = gut * 2;
    var pw = Math.max(180, Math.floor((cw - gut * 2 - (cols - 1) * gap) / cols));
    return { gut: gut, gap: gap, pw: pw, cols: cols, step: cw };
  }

  function measure() {
    var v = EB.view;
    if (!v || !isPaged()) { EB.pages = 1; EB.page = 0; return; }
    var m = layoutMetrics();
    EB.pages = Math.max(1, Math.ceil((v.scrollWidth - 2) / Math.max(1, m.step)));
    EB.page = clamp(Math.ceil((v.scrollLeft - 1) / Math.max(1, m.step)), 0, EB.pages - 1);
  }

  function goPage(n) {
    var v = EB.view; if (!v || !isPaged()) return;
    measure();
    EB.page = clamp(n, 0, EB.pages - 1);
    var max = Math.max(0, v.scrollWidth - v.clientWidth);
    v.scrollLeft = Math.min(EB.page * layoutMetrics().step, max);
  }

  function turnPage(d) {
    if (!isPaged()) return false;
    measure();
    var next = EB.page + (PRE.dir === 'RTL' ? -d : d);
    if (next < 0) {
      if (EB.ch > 1) { gotoChapter(EB.ch - 1, 1); return true; }
      return false;
    }
    if (next > EB.pages - 1) {
      if (EB.ch < EB.total) { gotoChapter(EB.ch + 1, 0); return true; }
      return false;
    }
    goPage(next);
    queueProgress();
    paintProgressBar();
    return true;
  }

  function applyLayout() {
    var r = $('eb'); if (!r) return;
    var m = layoutMetrics();
    r.style.setProperty('--eb-gut', m.gut + 'px');
    r.style.setProperty('--eb-gap', m.gap + 'px');
    r.style.setProperty('--eb-pagew', m.pw + 'px');
    r.style.setProperty('--eb-vh', viewHeight() + 'px');
    r.classList.toggle('paged', isPaged());
    r.classList.toggle('rtl', PRE.dir === 'RTL');
    if (EB.docEl) EB.docEl.classList.toggle('paged', isPaged());
    if (isPaged()) {
      measure();
      goPage(EB.page);
    } else if (EB.view) {
      EB.view.scrollLeft = 0;
      EB.pages = 1; EB.page = 0;
    }
    paintProgressBar();
  }

  function switchLayout(v) {
    if (PRE.layout === v) return;
    PRE.layout = v;
    savePrefs();
    applyLayout();
    paintBot();
    if (!EB.ready) { paintSet('set'); return; }
    tip('正在重排', v === 'scroll' ? '连续滚动' : v === 'double' ? '双栏分页' : '单栏分页');
    reset(EB.ch, 0).then(function () {
      tip('');
      applyLayout();
      if (isPaged()) requestAnimationFrame(function () { measure(); scrollToSec(EB.secs.get(EB.ch), 0); paintProgressBar(); });
      paintBot();
      paintSet('set');
    }).catch(function () { tip(''); paintSet('set'); });
  }

  var BASE_CSS = [
    ':host{display:block}',
    ':where(.doc){color:var(--eb-tx)}',
    '.doc.doc{box-sizing:border-box;max-width:var(--eb-measure);margin:0 auto;padding:0 26px;text-align:justify;' +
    'font-size:var(--eb-fs);line-height:var(--eb-lh);' +
    'word-break:break-word;overflow-wrap:break-word;-webkit-hyphens:auto;hyphens:auto}',
    '.doc .sec{position:relative}',
    '.doc .sec+.sec{margin-top:2.6em}',
    '.doc .sec.artpage{min-height:var(--eb-vh,100vh);background-position:center;background-repeat:no-repeat;' +
    'background-size:contain!important;break-inside:avoid}',
    '.doc.doc.paged .sec.artpage{min-height:100%;break-before:column;break-inside:avoid}',
    '.doc.doc.paged .sec{break-before:column}',
    '.doc.doc.paged .sec:first-child{break-before:auto}',
    '.doc.doc.paged{max-width:none;width:calc(100% - var(--eb-gut,24px) * 2);margin:0 auto;padding:0;height:100%;' +
    'column-width:var(--eb-pagew,600px);column-gap:var(--eb-gap,48px);column-fill:auto}',
    '.doc.doc.paged img,.doc.doc.paged figure,.doc.doc.paged pre,.doc.doc.paged table{break-inside:avoid}',
    '.doc .eb-head{display:flex;align-items:baseline;gap:10px;margin:0 0 1.1em;padding-top:1.1em;' +
    'border-top:1px solid var(--eb-line);color:var(--eb-dim);font-size:.66em;letter-spacing:.05em;user-select:none;text-indent:0}',
    '.doc .sec:first-child .eb-head{border-top:0;padding-top:0}',
    '.doc .eb-head .n{font-family:var(--f-mono);font-size:1.02em;opacity:.75;flex:none}',
    '.doc .eb-head .t{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '.doc .eb-head .w{flex:none;opacity:.6;font-family:var(--f-mono);font-size:.94em}',
    '.doc .skel{min-height:320px;border-radius:8px;background:linear-gradient(110deg,var(--eb-sunk) 8%,var(--eb-card) 22%,var(--eb-sunk) 36%);' +
    'background-size:220% 100%;animation:ebshine 1.4s linear infinite}',
    '@keyframes ebshine{0%{background-position:120% 0}100%{background-position:-60% 0}}',
    '.doc.doc p{line-height:var(--eb-lh)}',
    ':where(.doc) p{margin:0 0 var(--eb-para);text-indent:var(--eb-ti)}',
    ':where(.doc) .ct>p:first-child,:where(.doc) h1+p,:where(.doc) h2+p,:where(.doc) h3+p,:where(.doc) .eb-head+p{text-indent:0}',
    '.doc .ct.txt p{line-break:strict;text-justify:inter-ideograph;word-break:normal}',
    '.doc .ct.txt>p:first-child{text-indent:var(--eb-ti)}',
    ':where(.doc) h1,:where(.doc) h2,:where(.doc) h3,:where(.doc) h4,:where(.doc) h5,:where(.doc) h6{' +
    'line-height:1.45;margin:1.2em 0 .7em;text-align:left;text-indent:0;font-weight:600}',
    ':where(.doc) h1{font-size:1.5em}:where(.doc) h2{font-size:1.32em}:where(.doc) h3{font-size:1.16em}',
    '.doc.doc img,.doc.doc svg,.doc.doc video{max-width:100%!important;height:auto!important;display:block;margin:.9em auto}',
    ':where(.doc) figure{margin:1em 0}',
    ':where(.doc) blockquote{margin:.35em 0;padding-left:.9em;border-left:2px solid var(--eb-line)}',
    ':where(.doc) blockquote p{text-indent:0}',
    ':where(.doc) pre,:where(.doc) code{white-space:pre-wrap;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.92em}',
    ':where(.doc) table{max-width:100%;border-collapse:collapse}',
    ':where(.doc) td,:where(.doc) th{padding:2px 6px;border:1px solid currentColor}',
    ':where(.doc) hr{border:0;height:1px;background:currentColor;opacity:.22;margin:1.6em 0}',
    ':where(.doc) a{color:inherit;text-decoration:underline;text-underline-offset:2px;cursor:pointer}',
    ':where(.doc) sup,:where(.doc) sub{font-size:.72em;line-height:0}',
    ':where(.doc) ul,:where(.doc) ol{padding-left:1.4em;margin:0 0 var(--eb-para)}',
    '.doc mark[data-ann]{cursor:pointer;border-radius:2px;color:inherit}',
    '.doc [data-ch]{cursor:pointer}',
    '.doc .eb-ill{cursor:zoom-in}',
    '::highlight(eb-tts){background-color:rgba(201,162,75,.30);color:inherit}',
    '::highlight(eb-find){background-color:rgba(90,150,235,.32);color:inherit}',
    ':where(.doc) .tts-cur{background-color:rgba(201,162,75,.18);border-radius:3px}'
  ].join('\n');

  var PUB_LAYOUT_DROP = /^(?:-(?:webkit|moz|ms|o)-)?(position|top|right|bottom|left|inset[\w-]*|margin[\w-]*|padding[\w-]*|width|height|min-[\w-]+|max-[\w-]+|inline-size|block-size|display|float|clear|overflow[\w-]*|column[\w-]*|break-[\w-]+|page-break[\w-]*|z-index|transform[\w-]*|flex[\w-]*|grid[\w-]*|gap|box-sizing|contain|content-visibility|writing-mode|text-orientation|background-attachment)\s*:/i;
  var PUB_ROOT_DROP = /^background[\w-]*\s*:/i;
  var PUB_KEEP_BODY = /^(color|font-style|font-weight)\s*:/i;
  var PUB_CACHE = {};
  var pubSeq = 0;
  var pubKey = '';

  function famCss() {
    return (PRE.font === 'book' ? ':where(.doc)' : '.doc.doc') + '{font-family:var(--eb-fam)}';
  }

  function ensureShadow() {
    var v = $('ebView');
    if (!v) return null;
    if (!v.shadowRoot) {
      EB.shadow = v.attachShadow({ mode: 'open' });
      EB.shadow.innerHTML = '<style id="ebBase"></style><style id="ebPub"></style><article class="doc" id="ebDoc"></article>';
    }
    EB.shadow = v.shadowRoot;
    EB.view = v;
    EB.docEl = EB.shadow.getElementById('ebDoc');
    applyBase();
    applyLayout();
    return EB.shadow;
  }

  function applyBase() {
    if (!EB.shadow) return;
    var st = EB.shadow.getElementById('ebBase');
    if (st) st.textContent = famCss() + '\n' + BASE_CSS;
  }

  function dropDecls(body, root) {
    var safe = body.replace(/(data:[^"')]+)/gi, function (m) { return m.replace(/;/g, '\u0002'); });
    var kept = safe.split(';').filter(function (d) {
      var t = d.trim();
      if (!t || PUB_LAYOUT_DROP.test(t)) return false;
      if (root && PUB_ROOT_DROP.test(t)) return false;
      return true;
    }).map(function (d) {
      return /background/i.test(d) ? d.replace(/\bfixed\b/gi, 'scroll') : d;
    });
    return kept.join(';').replace(/\u0002/g, ';');
  }

  function pubRewrite(css) {
    return String(css || '').replace(/([^{}]+)\{([^{}]*)\}/g, function (m, sel, body) {
      if (sel.indexOf('@') >= 0) return m;
      var root = /(^|[\s,>+~()])html(?=$|[\s,>+~{.#:\[])/i.test(sel);
      var s = sel
        .replace(/(^|[\s,>+~()])html(?=$|[\s,>+~{.#:\[])/gi, '$1:host')
        .replace(/(^|[\s,>+~()])body(?=$|[\s,>+~{.#:\[])/gi, '$1:where(.doc .sec)');
      return s + '{' + dropDecls(body, root) + '}';
    });
  }

  function pubUrls(css, base) {
    return String(css || '').replace(/url\(\s*(['"]?)([^'")]+)\1\s*\)/gi, function (m, q, u) {
      if (/^(data:|https?:|\/\/|#)/i.test(u)) return m;
      try { return 'url("' + new URL(u, base).href + '")'; } catch (e) { return m; }
    });
  }

  var PUB_IMPORT = /@import\s+(?:url\(\s*(['"]?)([^'")]+)\1\s*\)|(['"])([^'"]+)\3)\s*[^;]*;/gi;

  function fetchPub(href, depth) {
    var abs;
    try { abs = new URL(href, location.href).href; } catch (e) { abs = href; }
    if (PUB_CACHE[abs]) return PUB_CACHE[abs];
    PUB_CACHE[abs] = fetch(abs, { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.text() : ''; })
      .then(function (text) {
        if (!text) return '';
        var jobs = [];
        var body = text.replace(PUB_IMPORT, function (m, q1, u1, q2, u2) {
          var raw = u1 || u2 || '';
          if (!raw || /^(data:|https?:|\/\/)/i.test(raw) || (depth || 0) >= 3) return '';
          var next;
          try { next = new URL(raw, abs).href; } catch (e) { return ''; }
          jobs.push(fetchPub(next, (depth || 0) + 1));
          return '\u0001' + (jobs.length - 1) + '\u0001';
        });
        return Promise.all(jobs).then(function (list) {
          var merged = body.replace(/\u0001(\d+)\u0001/g, function (m, i) { return list[+i] || ''; });
          return pubRewrite(pubUrls(merged, abs));
        });
      })
      .catch(function () { return ''; });
    return PUB_CACHE[abs];
  }

  function clearPub(sh) {
    [].slice.call(sh.querySelectorAll('style[data-eb],link[data-eb]')).forEach(function (n) { n.remove(); });
  }

  function syncPub() {
    if (!EB.shadow) return;
    var sh = EB.shadow;
    var seq = ++pubSeq;
    if (!PRE.pubcss) { pubKey = ''; clearPub(sh); return; }
    var inl = EB.inlines.map(pubRewrite);
    var links = EB.links.slice();
    Promise.all(links.map(function (h) { return fetchPub(h, 0); })).then(function (list) {
      if (seq !== pubSeq || !EB.shadow || !PRE.pubcss) return;
      var css = list.filter(Boolean).concat(inl).filter(Boolean);
      var key = css.join('\u0000');
      if (key === pubKey) return;
      pubKey = key;
      clearPub(sh);
      var anchor = sh.getElementById('ebPub') || sh.getElementById('ebDoc');
      if (!anchor || !anchor.parentNode) return;
      css.forEach(function (t) {
        var s = document.createElement('style');
        s.textContent = t;
        s.setAttribute('data-eb', '1');
        anchor.parentNode.insertBefore(s, anchor);
      });
    });
  }

  function mount() {
    var r = $('eb');
    if (r) return r;
    r = el('div', 'eb');
    r.id = 'eb';
    r.innerHTML =
      '<div class="eb-bg"></div>' +
      '<div class="eb-view" id="ebView"></div>' +
      '<div class="eb-top" id="ebTop"></div>' +
      '<div class="eb-bot" id="ebBot"></div>' +
      '<div class="eb-panel l" id="ebToc"></div>' +
      '<div class="eb-panel r" id="ebSet"></div>' +
      '<div class="eb-sel" id="ebSel"></div>' +
      '<div class="eb-tts" id="ebTts"></div>' +
      '<div class="eb-lb" id="ebLb"><img id="ebLbImg" alt=""></div>' +
      '<div class="eb-ask" id="ebAsk"></div>' +
      '<div class="eb-toast" id="ebToast"></div>' +
      '<div class="eb-tip" id="ebTip"></div>';
    document.body.appendChild(r);
    r.addEventListener('mousemove', bumpIdle);
    r.addEventListener('pointerdown', bumpIdle, { passive: true });
    $('ebLb').addEventListener('click', function () { $('ebLb').classList.remove('on'); });
    $('ebView').addEventListener('scroll', onScroll, { passive: true });
    document.addEventListener('selectionchange', function () {
      if (!EB.open) return;
      clearTimeout(EB.selT);
      EB.selT = setTimeout(onSelect, 160);
    });
    document.addEventListener('keydown', onKey);
    var rz = null;
    window.addEventListener('resize', function () {
      if (!EB.open) return;
      clearTimeout(rz);
      rz = setTimeout(applyLayout, 160);
    });
    if (window.speechSynthesis) {
      window.speechSynthesis.onvoiceschanged = function () {
        if (EB.panel === 'set') fillVoices();
      };
    }
    return r;
  }

  function bumpIdle() {
    var r = $('eb'); if (!r) return;
    r.classList.remove('idle');
    clearTimeout(EB.idle);
    EB.idle = setTimeout(function () {
      if (!EB.open) return;
      if (EB.panel || EB.sel) return;
      r.classList.add('idle');
    }, 3200);
  }

  function paintTop() {
    var t = $('ebTop'); if (!t) return;
    var b = EB.book || {};
    var cur = EB.chs.get(EB.ch) || {};
    t.innerHTML =
      '<button class="eb-btn" data-a="exit" title="返回（Esc）">' + ico('left') + '</button>' +
      '<div class="eb-title"><b>' + esc(b.title || '') + (PRE.imm ? '<i class="eb-imm">隐身</i>' : '') + '</b><small id="ebCrumb">' +
      esc(cur.title || '第 ' + EB.ch + ' 章') + ' · ' + EB.ch + ' / ' + num(EB.total) + '</small></div>' +
      '<button class="eb-btn" data-a="toc" title="目录（T）">' + ico('toc') + '</button>' +
      '<button class="eb-btn" data-a="find" title="全书搜索（Ctrl+F）">' + ico('search') + '</button>' +
      '<button class="eb-btn" data-a="bm" title="书签（B）">' + ico('bookmark') + '</button>' +
      '<button class="eb-btn" data-a="mark" title="标注（A）">' + ico('marker') + '</button>' +
      '<button class="eb-btn" data-a="tts" title="朗读（D）">' + ico('volume') + '</button>' +
      '<button class="eb-btn" data-a="set" title="设置（S）">' + ico('settings') + '</button>' +
      '<button class="eb-btn hide-s" data-a="full" title="全屏（F）">' + ico('expand') + '</button>';
    [].slice.call(t.querySelectorAll('[data-a]')).forEach(function (btn) {
      btn.onclick = function () {
        var a = btn.dataset.a;
        if (a === 'exit') close();
        else if (a === 'toc') openPanel('toc');
        else if (a === 'find') openPanel('find');
        else if (a === 'bm') { EB.annTab = 'bookmark'; openPanel('ann'); }
        else if (a === 'mark') { EB.annTab = 'mark'; openPanel('ann'); }
        else if (a === 'set') openPanel('set');
        else if (a === 'tts') ttsToggle();
        else if (a === 'full') fullscreen();
      };
    });
    markTop();
  }

  function markTop() {
    var t = $('ebTop'); if (!t) return;
    [].slice.call(t.querySelectorAll('[data-a]')).forEach(function (b) {
      var a = b.dataset.a;
      var tab = EB.annTab || 'all';
      b.classList.toggle('on', (a === 'toc' && EB.panel === 'toc') ||
        (a === 'find' && EB.panel === 'find') ||
        (a === 'bm' && EB.panel === 'ann' && tab === 'bookmark') ||
        (a === 'mark' && EB.panel === 'ann' && tab !== 'bookmark') ||
        (a === 'set' && EB.panel === 'set') || (a === 'tts' && EB.ttsOn));
    });
  }

  function paintBot() {
    var b = $('ebBot'); if (!b) return;
    b.innerHTML =
      '<span class="mono hide-s" id="ebPos">' + EB.ch + ' / ' + num(EB.total) + '</span>' +
      '<div class="eb-track" id="ebTrack"><div class="rail"></div><div class="fill" id="ebFill"></div><div class="knob" id="ebKnob"></div></div>' +
      '<span class="mono" id="ebPct">0%</span>' +
      '<button class="eb-btn hide-s" data-a="prev" title="上一章">' + ico('left') + '</button>' +
      '<button class="eb-btn hide-s" data-a="next" title="下一章">' + ico('right') + '</button>';
    var track = $('ebTrack');
    track.addEventListener('click', function (ev) {
      var r = track.getBoundingClientRect();
      var f = clamp((ev.clientX - r.left) / Math.max(1, r.width), 0, 1);
      seek(f);
    });
    [].slice.call(b.querySelectorAll('[data-a]')).forEach(function (btn) {
      btn.onclick = function () {
        var d = btn.dataset.a === 'prev' ? -1 : 1;
        if (isPaged()) { turnPage(d); return; }
        d < 0 ? prevChapter() : nextChapter();
      };
    });
    paintProgressBar();
  }

  function paintProgressBar() {
    var fill = $('ebFill'), knob = $('ebKnob'), pct = $('ebPct'), pos = $('ebPos');
    if (!fill) return;
    var f = totalProgress();
    fill.style.width = (f * 100).toFixed(2) + '%';
    if (knob) knob.style.left = (f * 100).toFixed(2) + '%';
    if (pct) pct.textContent = (f * 100).toFixed(2) + '%';
    if (pos) {
      if (isPaged()) {
        measure();
        var txt = EB.ch + ' / ' + num(EB.total);
        var cur = EB.secs.get(EB.ch);
        if (cur) {
          var vr2 = EB.view.getBoundingClientRect();
          var sr2 = cur.getBoundingClientRect();
          var st2 = Math.max(1, layoutMetrics().step);
          var cols = Math.max(1, Math.ceil((sr2.width - 2) / st2));
          var startPage = Math.round((EB.view.scrollLeft + sr2.left - vr2.left) / st2);
          txt += ' · ' + clamp(EB.page - startPage + 1, 1, cols) + ' / ' + cols + ' 页';
        }
        pos.textContent = txt;
      } else {
        pos.textContent = EB.ch + ' / ' + num(EB.total);
      }
    }
  }

  function totalProgress() {
    if (!EB.total) return 0;
    return clamp((EB.ch - 1 + chapterProgress()) / EB.total, 0, 1);
  }

  function chapterProgress() {
    var sec = EB.secs.get(EB.ch);
    if (!sec || !EB.view) return 0;
    if (isPaged()) {
      var pv = EB.view.getBoundingClientRect();
      var ps = sec.getBoundingClientRect();
      var span2 = ps.width - EB.view.clientWidth;
      if (span2 <= 8) return 0;
      return clamp((pv.left - ps.left) / span2, 0, 1);
    }
    var vr = EB.view.getBoundingClientRect();
    var sr = sec.getBoundingClientRect();
    var top = EB.view.scrollTop + (sr.top - vr.top);
    var span = sr.height - EB.view.clientHeight;
    if (span <= 8) return 0;
    return clamp((EB.view.scrollTop - top) / span, 0, 1);
  }

  function parseHtml(html) {
    var doc = new DOMParser().parseFromString(html || '', 'text/html');
    var b = doc.body;
    var body = b ? b.innerHTML : '';
    var links = [].slice.call(doc.querySelectorAll('link[rel~="stylesheet"]')).map(function (l) { return l.getAttribute('href'); }).filter(Boolean);
    var styles = [].slice.call(doc.querySelectorAll('style')).map(function (s) { return s.textContent || ''; }).filter(Boolean);
    return {
      body: body, links: links, styles: styles,
      cls: b ? (b.getAttribute('class') || '') : '',
      sty: b ? (b.getAttribute('style') || '') : ''
    };
  }

  function applyBody(sec, parsed) {
    if (!sec || !parsed) return;
    (parsed.cls || '').split(/\s+/).forEach(function (c) {
      if (c && c !== 'sec' && c !== 'doc') sec.classList.add(c);
    });
    (parsed.sty || '').split(';').forEach(function (decl) {
      var i = decl.indexOf(':');
      if (i < 1) return;
      var k = decl.slice(0, i).trim();
      var v = decl.slice(i + 1).trim();
      if (!k || !v || !PUB_KEEP_BODY.test(k + ':')) return;
      sec.style.setProperty(k, v);
    });
  }

  function pushUniq(list, items) {
    items.forEach(function (x) { if (x && list.indexOf(x) < 0) list.push(x); });
  }

  function artPage(sec, ct) {
    if (!ct) return false;
    if (ct.querySelector('img,video,audio,canvas,iframe,object,embed')) return false;
    if (!sec || sec.classList.length < 2) return false;
    return (ct.textContent || '').replace(/[\s\u00a0\u3000]+/g, '').length < 24;
  }

  function buildSec(dto, inline) {
    var n = dto.chapter;
    EB.chs.set(n, { n: n, title: dto.title || '', chars: dto.chars || 0, href: dto.href || '' });
    var sec = el('section', 'sec');
    sec.dataset.ch = String(n);
    var head = el('div', 'eb-head');
    head.innerHTML = '<span class="n">' + n + '</span><span class="t">' + esc(dto.title || '') + '</span>' +
      '<span class="w">' + (dto.chars ? num(dto.chars) + ' 字' : '') + '</span>';
    var ct = el('div', EB.book && EB.book.profile === 'TXT' ? 'ct txt' : 'ct');
    var parsed = parseHtml(dto.html);
    ct.innerHTML = parsed.body || '';
    applyBody(sec, parsed);
    pushUniq(EB.links, parsed.links);
    pushUniq(EB.inlines, parsed.styles);
    sec.appendChild(head);
    sec.appendChild(ct);
    tidy(ct, n);
    if (artPage(sec, ct)) sec.classList.add('artpage');
    EB.secs.set(n, sec);
    EB.lo = EB.lo ? Math.min(EB.lo, n) : n;
    EB.hi = Math.max(EB.hi, n);
    return sec;
  }

  function tidy(root, n) {
    [].slice.call(root.querySelectorAll('svg')).forEach(function (svg) {
      var pics = [].slice.call(svg.querySelectorAll('image'));
      if (!pics.length) return;
      var box = el('div', 'eb-svgimg');
      pics.forEach(function (node) {
        var src = node.getAttribute('xlink:href') || node.getAttribute('href') || node.getAttribute('src');
        if (!src) return;
        var img = el('img', 'eb-ill');
        img.src = src;
        img.alt = '';
        box.appendChild(img);
      });
      if (box.childNodes.length) svg.parentNode.replaceChild(box, svg);
      else svg.remove();
    });
    [].slice.call(root.querySelectorAll('image')).forEach(function (node) {
      var src = node.getAttribute('xlink:href') || node.getAttribute('href') || node.getAttribute('src');
      if (!src) return node.remove();
      var img = el('img', 'eb-ill');
      img.src = src;
      img.alt = '';
      node.parentNode.replaceChild(img, node);
    });
    [].slice.call(root.querySelectorAll('img')).forEach(function (img) {
      img.classList.add('eb-ill');
      img.loading = 'lazy';
      var iw = parseInt(img.getAttribute('width'), 10);
      var ih = parseInt(img.getAttribute('height'), 10);
      img.style.setProperty('max-width', '100%', 'important');
      img.style.setProperty('height', 'auto', 'important');
      if (iw > 0 && ih > 0) img.style.setProperty('aspect-ratio', iw + ' / ' + ih);
      img.removeAttribute('width');
      img.removeAttribute('height');
      img.addEventListener('click', function () { zoomImg(img.src); });
    });
    [].slice.call(root.querySelectorAll('a')).forEach(function (a) {
      var ch = a.getAttribute('data-ch');
      if (ch) {
        a.addEventListener('click', function (ev) {
          ev.preventDefault();
          gotoChapter(+ch, 0);
        });
        return;
      }
      var href = a.getAttribute('href') || '';
      if (href.indexOf('#') === 0 && href.length > 1) {
        a.addEventListener('click', function (ev) {
          ev.preventDefault();
          jumpAnchor(href.slice(1));
        });
      } else if (/^(https?:)?\/\//i.test(href)) {
        a.setAttribute('target', '_blank');
        a.setAttribute('rel', 'noopener noreferrer');
      } else {
        a.addEventListener('click', function (ev) { ev.preventDefault(); });
      }
    });
    paintMarks(root, n);
  }

  function jumpAnchor(id) {
    if (!EB.shadow) return;
    var target = EB.shadow.getElementById(id);
    if (!target) return;
    var vr = EB.view.getBoundingClientRect();
    var tr = target.getBoundingClientRect();
    EB.view.scrollTop += tr.top - vr.top - EB.view.clientHeight * 0.3;
    target.style.transition = 'background .4s';
    target.style.background = 'rgba(201,162,75,.3)';
    setTimeout(function () { target.style.background = ''; }, 900);
  }

  function zoomImg(src) {
    if (!src) return;
    $('ebLbImg').src = src;
    $('ebLb').classList.add('on');
  }

  function load(n, where) {
    if (n < 1 || n > EB.total) return Promise.resolve(null);
    if (EB.secs.has(n)) return Promise.resolve(EB.secs.get(n));
    if (EB.park.has(n)) {
      var parked = EB.park.get(n);
      EB.park.delete(n);
      EB.secs.set(n, parked);
      EB.lo = EB.lo ? Math.min(EB.lo, n) : n;
      EB.hi = Math.max(EB.hi, n);
      insertSec(parked);
      return Promise.resolve(parked);
    }
    var key = 'k' + n;
    if (EB.pending[key]) return EB.pending[key];
    var p = req('api/lantai/v1/read/' + EB.bookId + '/chapter/' + n + '?images=url&w=0&fmt=orig')
      .then(function (dto) {
        delete EB.pending[key];
        if (!EB.open) return null;
        var sec = buildSec(dto, false);
        insertSec(sec);
        return sec;
      })
      .catch(function (e) {
        delete EB.pending[key];
        throw e;
      });
    EB.pending[key] = p;
    return p;
  }

  function insertSec(sec) {
    if (!EB.docEl) return;
    var n = +sec.dataset.ch;
    var kids = [].slice.call(EB.docEl.children);
    var anchor = null;
    for (var i = 0; i < kids.length; i++) {
      if ((+kids[i].dataset.ch || 0) > n) { anchor = kids[i]; break; }
    }
    if (!anchor) {
      EB.docEl.appendChild(sec);
      syncPub();
      return;
    }
    var vr = EB.view.getBoundingClientRect();
    var visible = anchor.getBoundingClientRect().top < vr.bottom;
    var beforeH = EB.view.scrollHeight;
    var beforeW = EB.view.scrollWidth;
    EB.docEl.insertBefore(sec, anchor);
    if (isPaged()) EB.view.scrollLeft += EB.view.scrollWidth - beforeW;
    else if (visible) EB.view.scrollTop += EB.view.scrollHeight - beforeH;
    syncPub();
  }

  var KEEP_BEHIND = 4, KEEP_AHEAD = 6, PARK_MAX = 48;

  function trimWindow() {
    if (!EB.open || !EB.docEl || !EB.view) return;
    var paged = isPaged();
    var wLo = EB.ch - (paged ? 1 : KEEP_BEHIND);
    var wHi = EB.ch + (paged ? 1 : KEEP_AHEAD);
    var kids = [].slice.call(EB.docEl.children);
    var hasLeft = false, hasRight = false, hit = false;
    for (var i = 0; i < kids.length; i++) {
      var c = +kids[i].dataset.ch || 0;
      if (!c || (c >= wLo && c <= wHi)) continue;
      hit = true;
      if (c < wLo) hasLeft = true; else hasRight = true;
    }
    if (!hit) { trimPark(); return; }
    var span = paged ? EB.view.scrollWidth : EB.view.scrollHeight;
    for (var j = 0; j < kids.length; j++) {
      var sec = kids[j];
      var n = +sec.dataset.ch || 0;
      if (!n || (n >= wLo && n <= wHi)) continue;
      EB.docEl.removeChild(sec);
      EB.secs.delete(n);
      EB.park.set(n, sec);
    }
    var drop = span - (paged ? EB.view.scrollWidth : EB.view.scrollHeight);
    if (drop > 0 && hasLeft && !hasRight) {
      if (paged) EB.view.scrollLeft = Math.max(0, EB.view.scrollLeft - drop);
      else EB.view.scrollTop = Math.max(0, EB.view.scrollTop - drop);
    }
    trimPark();
  }

  function trimPark() {
    if (EB.park.size <= PARK_MAX) return;
    var keys = [].slice.call(EB.park.keys()).sort(function (a, b) { return Math.abs(b - EB.ch) - Math.abs(a - EB.ch); });
    for (var i = 0; i < keys.length && EB.park.size > PARK_MAX; i++) EB.park.delete(keys[i]);
  }

  function ensureWindow() {
    if (!EB.open || !isPaged()) return;
    load(EB.ch + 1, 'after').catch(function () { });
    load(EB.ch - 1, 'before').catch(function () { });
  }

  function reset(n, prog) {
    if (!EB.docEl) return Promise.resolve();
    EB.secs.clear();
    EB.park.clear();
    EB.docEl.innerHTML = '';
    EB.lo = 0;
    EB.hi = 0;
    EB.links = [];
    EB.inlines = [];
    EB.ch = n;
    return load(n, 'after').then(function () {
      paintTop();
      paintBot();
      return Promise.all([
        load(n - 1, 'before').catch(function () { }),
        load(n + 1, 'after').catch(function () { })
      ]).then(function () {
        if (!EB.open) return null;
        if (isPaged()) {
          requestAnimationFrame(function () {
            measure();
            scrollToSec(EB.secs.get(n), prog || 0);
            paintProgressBar();
          });
        }
        return null;
      });
    });
  }

  function onScroll() {
    if (!EB.open) return;
    bumpIdle();
    if (EB.raf) return;
    EB.raf = requestAnimationFrame(function () {
      EB.raf = 0;
      if (isPaged()) measure();
      var n = chapterAt();
      if (n && n !== EB.ch) {
        EB.ch = n;
        EB.seen = Math.max(EB.seen, n);
        var crumb = $('ebCrumb');
        var cur = EB.chs.get(n) || {};
        if (crumb) crumb.textContent = (cur.title || '第 ' + n + ' 章') + ' · ' + n + ' / ' + num(EB.total);
        markToc();
        queueProgress();
        trimWindow();
        ensureWindow();
      }
      paintProgressBar();
      extend();
    });
  }

  function chapterAt() {
    if (!EB.docEl || !EB.view) return 0;
    var secs = EB.docEl.children;
    if (!secs.length) return EB.ch;
    var vr = EB.view.getBoundingClientRect();
    if (isPaged()) {
      var x = vr.left + 2;
      for (var i = 0; i < secs.length; i++) {
        var pr = secs[i].getBoundingClientRect();
        if (pr.right > x) return +secs[i].dataset.ch || EB.ch;
      }
      return +secs[secs.length - 1].dataset.ch || EB.ch;
    }
    var line = vr.top + 96;
    var lo = 0, hi = secs.length - 1, res = +secs[secs.length - 1].dataset.ch || EB.ch;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      var r = secs[mid].getBoundingClientRect();
      if (r.bottom > line) { res = +secs[mid].dataset.ch || EB.ch; hi = mid - 1; } else lo = mid + 1;
    }
    return res;
  }

  function extend() {
    if (!EB.view) return;
    if (isPaged()) return;
    var v = EB.view;
    if (!EB.warmF && v.scrollTop + v.clientHeight > v.scrollHeight - 1400 && EB.hi < EB.total) {
      EB.warmF = true;
      load(EB.hi + 1, 'after')
        .then(function () { if (EB.open && EB.hi < EB.total) return load(EB.hi + 1, 'after'); })
        .catch(function () { })
        .then(function () { EB.warmF = false; });
    }
    if (!EB.warmB && v.scrollTop < 1400 && EB.lo > 1) {
      EB.warmB = true;
      load(EB.lo - 1, 'before')
        .then(function () { if (EB.open && EB.lo > 1) return load(EB.lo - 1, 'before'); })
        .catch(function () { })
        .then(function () { EB.warmB = false; });
    }
  }

  function scrollToSec(sec, prog) {
    if (!sec || !EB.view) return;
    if (isPaged()) {
      measure();
      var bv = EB.view.getBoundingClientRect();
      var bs = sec.getBoundingClientRect();
      var st = Math.max(1, layoutMetrics().step);
      var startPage = Math.max(0, Math.round((EB.view.scrollLeft + bs.left - bv.left) / st));
      var secCols = Math.max(1, Math.ceil((bs.width - 2) / st));
      var rel = clamp(prog || 0, 0, 1);
      goPage(rel >= 0.999 ? startPage + secCols - 1 : startPage + Math.round(rel * (secCols - 1)));
      return;
    }
    var vr = EB.view.getBoundingClientRect();
    var sr = sec.getBoundingClientRect();
    var pad = parseFloat(getComputedStyle(EB.view).paddingTop) || 0;
    var top = EB.view.scrollTop + (sr.top - vr.top) - pad;
    var span = Math.max(0, sr.height - EB.view.clientHeight);
    EB.view.scrollTop = Math.max(0, top + span * clamp(prog || 0, 0, 1));
  }

  function gotoChapter(n, prog) {
    n = Math.round(clamp(n, 1, EB.total));
    var sec = EB.secs.get(n);
    if (sec) {
      scrollToSec(sec, prog || 0);
      EB.ch = n;
      paintProgressBar();
      queueProgress();
      setTimeout(function () {
        if (!EB.open) return;
        var s = EB.secs.get(n);
        if (s) scrollToSec(s, prog || 0);
      }, 240);
      return;
    }
    tip('正在载入', '第 ' + n + ' 章 · 共 ' + num(EB.total) + ' 章');
    reset(n, prog || 0).then(function () {
      tip('');
      scrollToSec(EB.secs.get(n), prog || 0);
      EB.ch = n;
      paintProgressBar();
      queueProgress();
      setTimeout(function () {
        if (!EB.open) return;
        var s = EB.secs.get(n);
        if (s) scrollToSec(s, prog || 0);
      }, 240);
    }).catch(function (e) {
      tip('');
      toast('载入失败：' + errText(e), 'err');
    });
  }

  function prevChapter() { if (EB.ch > 1) gotoChapter(EB.ch - 1, 0); }
  function nextChapter() { if (EB.ch < EB.total) gotoChapter(EB.ch + 1, 0); }

  function seek(f) {
    var target = clamp(Math.ceil(f * EB.total), 1, EB.total);
    gotoChapter(target, 0);
  }

  function queueProgress() {
    clearTimeout(EB.progTimer);
    EB.progTimer = setTimeout(sendProgress, 900);
  }

  function sendProgress() {
    if (!EB.open) return;
    var ref = EB.chs.get(EB.ch) || {};
    var p = chapterProgress();
    var tp = totalProgress();
    if (PRE.imm) {
      try { sessionStorage.setItem(immKey(), JSON.stringify({ ch: EB.ch, p: p })); } catch (e) { }
      return;
    }
    if (typeof PROGRESS !== 'undefined') {
      PROGRESS[EB.bookId] = {
        page: EB.ch, pages: EB.total, completed: tp >= 0.999, device: 'webui', at: Date.now(),
        locator: { href: ref.href || ('#page=' + EB.ch), position: EB.ch, totalProgression: tp }
      };
    }
    req('api/v1/books/' + EB.bookId + '/progression', {
      method: 'PUT',
      body: {
        modified: new Date().toISOString(),
        device: { id: 'webui', name: '网页端' },
        locator: {
          href: ref.href || ('#page=' + EB.ch),
          type: (EB.book && EB.book.profile === 'TXT') ? 'text/plain' : 'application/xhtml+xml',
          locations: { position: EB.ch, progression: p, totalProgression: tp }
        }
      }
    }).catch(function () { });
  }

  function startBeat() {
    clearInterval(EB.hbTimer);
    EB.hbTimer = setInterval(function () {
      if (!EB.open || PRE.imm || !EB.session) return;
      EB.beats++;
      EB.session.dur += 30000;
      EB.session.pages = Math.max(EB.session.pages, EB.seen);
      if (typeof pushSession === 'function') pushSession(EB.session);
      req('api/lantai/v1/reading/heartbeat', {
        method: 'POST',
        body: { bookId: EB.bookId, page: EB.ch, deviceId: 'webui', deviceName: '网页端', client: 'lantai-webui' }
      }).catch(function () { });
    }, 30000);
  }

  function loadOutline() {
    if (EB.outline || EB.outlineLoading) return Promise.resolve(EB.outline);
    EB.outlineLoading = true;
    return req('api/lantai/v1/read/' + EB.bookId + '/chapters?outline=true')
      .then(function (dto) {
        EB.outline = (dto && dto.outline) || [];
        EB.outlineLoading = false;
        if (!EB.outline.length) EB.outline = null;
        return EB.outline;
      })
      .catch(function () { EB.outlineLoading = false; return null; });
  }

  function openPanel(which) {
    var r = $('eb');
    if (EB.panel === which) {
      EB.panel = null;
      $('ebToc').classList.remove('on');
      $('ebSet').classList.remove('on');
      r.classList.remove('toc-open', 'set-open');
      markTop();
      return;
    }
    EB.panel = which;
    if (which === 'toc') {
      $('ebSet').classList.remove('on');
      paintToc();
      $('ebToc').classList.add('on');
      r.classList.add('toc-open');
      r.classList.remove('set-open');
    } else {
      $('ebToc').classList.remove('on');
      r.classList.remove('toc-open');
      r.classList.add('set-open');
      if (which === 'find') paintFind(); else paintSet(which);
      $('ebSet').classList.add('on');
    }
    markTop();
  }

  function closePanels() {
    EB.panel = null;
    $('ebToc').classList.remove('on');
    $('ebSet').classList.remove('on');
    var r = $('eb'); if (r) r.classList.remove('toc-open', 'set-open');
    markTop();
  }

  function paintFind() {
    var P = $('ebSet');
    P.innerHTML =
      '<div class="eb-panel-h"><b>全书搜索</b><span class="mono" id="ebFindN"></span>' +
      '<button class="eb-btn" data-a="close">' + ico('close') + '</button></div>' +
      '<div class="eb-search">' + ico('search') + '<input id="ebFindQ" placeholder="搜索正文，回车开始" autocomplete="off" value="' + esc(EB.findQ || '') + '"></div>' +
      '<div class="eb-list" id="ebFindList"><div class="eb-empty">输入关键词后回车</div></div>';
    P.querySelector('[data-a=close]').onclick = function () { closePanels(); };
    var input = $('ebFindQ');
    input.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      runFind(this.value.trim());
    });
    if (EB.findQ) runFind(EB.findQ);
    setTimeout(function () { try { input.focus(); } catch (e) { } }, 40);
  }

  function markText(text, q) {
    var s = esc(text);
    var k = esc(q);
    if (!k) return s;
    var i = s.toLowerCase().indexOf(k.toLowerCase());
    if (i < 0) return s;
    return s.slice(0, i) + '<mark>' + s.slice(i, i + k.length) + '</mark>' + s.slice(i + k.length);
  }

  function runFind(q) {
    EB.findQ = q;
    var box = $('ebFindList'), n = $('ebFindN');
    if (!box) return;
    if (!q) {
      box.innerHTML = '<div class="eb-empty">输入关键词后回车</div>';
      if (n) n.textContent = '';
      return;
    }
    box.innerHTML = '<div class="eb-empty">正在搜索…</div>';
    if (n) n.textContent = '';
    var slow = setTimeout(function () {
      if (EB.panel === 'find' && box && !box.querySelector('.eb-find-i')) {
        box.innerHTML = '<div class="eb-empty">首次搜索要建立正文索引，请稍候…</div>';
      }
    }, 1500);
    req('api/lantai/v1/read/' + EB.bookId + '/search?limit=60&q=' + encodeURIComponent(q))
      .then(function (d) {
        clearTimeout(slow);
        if (EB.panel !== 'find') return;
        var hits = (d && d.hits) || [];
        if (n) n.textContent = d && d.total ? num(d.total) + ' 处 · ' + num(d.matchedChapters) + ' 章' : '无结果';
        if (!hits.length) {
          box.innerHTML = '<div class="eb-empty">没有找到「' + esc(q) + '」</div>';
          return;
        }
        box.innerHTML = hits.map(function (h, i) {
          return '<div class="eb-find-i" data-hit="' + i + '">' +
            '<div class="h"><span class="mono">' + h.chapter + '</span>' + esc(h.title) + '</div>' +
            '<div class="s">' + markText(h.snippet, q) + '</div></div>';
        }).join('');
        [].slice.call(box.querySelectorAll('[data-hit]')).forEach(function (el) {
          el.onclick = function () { gotoHit(hits[+el.dataset.hit], q); };
        });
      })
      .catch(function (e) {
        clearTimeout(slow);
        box.innerHTML = '<div class="eb-empty">搜索失败：' + esc(errText(e)) + '</div>';
      });
  }

  function gotoHit(hit, q) {
    closePanels();
    if (hit.chapter === EB.ch) {
      findInView(q);
      return;
    }
    gotoChapter(hit.chapter, 0);
    setTimeout(function () { findInView(q); }, 800);
  }

  function findInView(q) {
    var sec = EB.secs.get(EB.ch);
    if (!sec || !q) return;
    var ct = sec.querySelector('.ct') || sec;
    var low = q.toLowerCase();
    var ranges = [];
    var w = document.createTreeWalker(ct, NodeFilter.SHOW_TEXT, null);
    var node;
    while ((node = w.nextNode())) {
      var value = node.nodeValue || '';
      var vl = value.toLowerCase();
      var at = 0;
      while (at < vl.length) {
        var i = vl.indexOf(low, at);
        if (i < 0) break;
        ranges.push({ node: node, s: i, e: i + q.length });
        at = i + q.length;
      }
    }
    if (!ranges.length) return;
    if (window.CSS && CSS.highlights && typeof window.Highlight === 'function') {
      var hl = new window.Highlight();
      var first = null;
      ranges.forEach(function (r) {
        var rg = document.createRange();
        try {
          rg.setStart(r.node, r.s);
          rg.setEnd(r.node, r.e);
        } catch (e) { return; }
        if (!first) first = rg;
        hl.add(rg);
      });
      CSS.highlights.set('eb-find', hl);
      if (first) {
        var holder = first.startContainer.parentElement || ct;
        ttsReveal(holder);
      }
    }
  }

  function paintToc() {
    var P = $('ebToc');
    P.innerHTML =
      '<div class="eb-panel-h"><b>目录</b><span class="mono" id="ebTocN">' + num(EB.total) + ' 章</span>' +
      '<button class="eb-btn" data-a="close">' + ico('close') + '</button></div>' +
      '<div class="eb-search">' + ico('search') + '<input id="ebTocQ" placeholder="搜索章节" autocomplete="off"></div>' +
      '<div class="eb-list" id="ebTocList"><div class="eb-empty">正在读取目录…</div></div>';
    P.querySelector('[data-a=close]').onclick = function () { closePanels(); };
    $('ebTocQ').addEventListener('input', function () { renderToc(this.value.trim()); });
    loadOutline().then(function (list) {
      if (EB.panel !== 'toc') return;
      renderToc('');
      if (list && list.length) $('ebTocN').textContent = num(list.length) + ' 节';
    });
  }

  function renderToc(q) {
    var box = $('ebTocList'); if (!box) return;
    var list = EB.outline;
    var items = list && list.length ? list : [].slice.call(EB.chs.keys()).sort(function (a, b) { return a - b; }).map(function (n) {
      var c = EB.chs.get(n);
      return { n: n, title: c.title, depth: 0, chars: c.chars };
    });
    if (q) {
      var low = q.toLowerCase();
      items = items.filter(function (x) { return String(x.title || '').toLowerCase().indexOf(low) >= 0; });
    }
    if (!items.length) { box.innerHTML = '<div class="eb-empty">没有匹配的章节</div>'; return; }
    var hasKids = items.some(function (x) { return (x.depth || 0) > 0; });
    box.innerHTML = items.map(function (x) {
      var vol = hasKids && !q && (x.depth || 0) === 0;
      return '<div class="eb-toc-i lv' + Math.min(2, x.depth || 0) + (vol ? ' vol' : '') + (x.n === EB.ch ? ' on' : '') + '" data-n="' + x.n + '">' +
        '<span class="lv">' + (vol ? '卷' : x.n) + '</span><span class="nm">' + esc(x.title || '第 ' + x.n + ' 章') + '</span>' +
        (x.chars ? '<span class="pg">' + (x.chars > 10000 ? (x.chars / 10000).toFixed(1) + '万' : num(x.chars)) + '</span>' : '') +
        '</div>';
    }).join('');
    [].slice.call(box.querySelectorAll('[data-n]')).forEach(function (n) {
      n.onclick = function () {
        gotoChapter(+n.dataset.n, 0);
        closePanels();
      };
    });
    var cur = box.querySelector('.on');
    if (cur) cur.scrollIntoView({ block: 'center' });
  }

  function markToc() {
    if (EB.panel !== 'toc') return;
    var box = $('ebTocList'); if (!box) return;
    [].slice.call(box.querySelectorAll('[data-n]')).forEach(function (n) {
      n.classList.toggle('on', +n.dataset.n === EB.ch);
    });
  }

  function paintSet(which) {
    var P = $('ebSet');
    if (which === 'ann') { paintAnns(); return; }
    loadFontFamilies();
    P.innerHTML =
      '<div class="eb-panel-h"><b>阅读设置</b><button class="eb-btn" data-a="close">' + ico('close') + '</button></div>' +
      '<div class="eb-list">' +
      '<div class="eb-group">底色</div>' +
      '<div class="eb-row"><label>主题</label><div class="grow"><div class="eb-swatches">' +
      THEMES.map(function (t) {
        return '<div class="eb-sw-dot' + (PRE.theme === t[0] ? ' on' : '') + '" data-th="' + t[0] + '" title="' + t[1] + '">' +
          '<span style="background:' + t[2] + ';border:1px solid ' + t[3] + '22"></span></div>';
      }).join('') + '</div></div></div>' +
      '<div class="eb-group">排版</div>' +
      '<div class="eb-row"><label>字号</label><div class="grow" style="flex-wrap:nowrap">' +
      '<button class="eb-chip sq" data-sz="-1">A-</button>' +
      '<span class="mono" style="flex:1;text-align:center;font-size:12px">' + PRE.size + '</span>' +
      '<button class="eb-chip sq" data-sz="1">A+</button></div></div>' +
      '<div class="eb-row"><label>行距</label><div class="grow">' +
      [[1.55, '紧'], [1.85, '中'], [2.15, '松']].map(function (x) {
        return '<button class="eb-chip' + (Math.abs(PRE.lh - x[0]) < .01 ? ' on' : '') + '" data-lh="' + x[0] + '">' + x[1] + '</button>';
      }).join('') + '</div></div>' +
      '<div class="eb-row"><label>段距</label><div class="grow">' +
      [[0.4, '窄'], [0.9, '中'], [1.5, '宽']].map(function (x) {
        return '<button class="eb-chip' + (Math.abs(PRE.para - x[0]) < .01 ? ' on' : '') + '" data-pa="' + x[0] + '">' + x[1] + '</button>';
      }).join('') + '</div></div>' +
      '<div class="eb-row"><label>字体</label><div class="grow">' +
      FONTS.map(function (f) {
        return '<button class="eb-chip' + (PRE.font === f[0] ? ' on' : '') + '" data-ft="' + f[0] + '" style="font-family:' + f[2] + '">' + f[1] + '</button>';
      }).join('') + '</div></div>' +
      '<div class="eb-row"><label>缩进</label><div class="grow"><button class="eb-sw' + (PRE.indent ? ' on' : '') + '" data-sw="indent"></button></div></div>' +
      '<div class="eb-row"><label>版心</label><div class="eb-slider"><input type="range" id="ebMeasure" min="26" max="64" step="2" value="' + PRE.measure + '"><span class="v" id="ebMeasureV">' + PRE.measure + '</span></div></div>' +
      '<div class="eb-row"><label>原书样式</label><div class="grow"><button class="eb-sw' + (PRE.pubcss ? ' on' : '') + '" data-sw="pubcss"></button></div></div>' +
      '<div class="eb-note">关闭原书样式使用兰台排版，字号与行距调整才会生效；打开可还原出版社版式。</div>' +
      '<div class="eb-group">翻页</div>' +
      '<div class="eb-row"><label>方式</label><div class="grow">' +
      [['scroll', '连续滚动'], ['paged', '单栏分页'], ['double', '双栏分页']].map(function (x) {
        return '<button class="eb-chip' + (PRE.layout === x[0] ? ' on' : '') + '" data-lt="' + x[0] + '">' + x[1] + '</button>';
      }).join('') + '</div></div>' +
      '<div class="eb-row' + (PRE.layout === 'scroll' ? ' dim' : '') + '"><label>方向</label><div class="grow">' +
      [['LTR', '从左往右'], ['RTL', '从右往左']].map(function (x) {
        return '<button class="eb-chip' + (PRE.dir === x[0] ? ' on' : '') + '" data-dr="' + x[0] + '"' +
          (PRE.layout === 'scroll' ? ' disabled' : '') + '>' + x[1] + '</button>';
      }).join('') + '</div></div>' +
      '<div class="eb-note">分页模式下方向决定翻页键与左右点击区；连续滚动时方向不生效。</div>' +
      '<div class="eb-group">朗读</div>' +
      '<div class="eb-row"><label>语速</label><div class="grow">' +
      [[0.8, '慢'], [1, '常'], [1.2, '快'], [1.5, '极快']].map(function (x) {
        return '<button class="eb-chip' + (Math.abs(PRE.ttsRate - x[0]) < .01 ? ' on' : '') + '" data-tr="' + x[0] + '">' + x[1] + '</button>';
      }).join('') + '</div></div>' +
      '<div class="eb-row"><label>音色</label><div class="grow"><select id="ebVoice" style="width:100%;height:28px;border-radius:7px;border:1px solid var(--eb-line);background:var(--eb-sunk);color:var(--eb-tx);font-size:11.5px;padding:0 6px"></select></div></div>' +
      '<div class="eb-group">阅读</div>' +
      '<div class="eb-row"><label>屏幕常亮</label><div class="grow"><button class="eb-sw' + (PRE.wake ? ' on' : '') + '" data-sw="wake"></button></div></div>' +
      '<div class="eb-row"><label>隐身阅读</label><div class="grow"><button class="eb-sw' + (PRE.imm ? ' on' : '') + '" data-sw="imm"></button></div></div>' +
      '<div class="eb-note">隐身阅读不写入进度、不上报时长，本次阅读不会出现在统计里。</div>' +
      '</div>';
    P.querySelector('[data-a=close]').onclick = function () { closePanels(); };
    [].slice.call(P.querySelectorAll('[data-th]')).forEach(function (d) {
      d.onclick = function () { PRE.theme = d.dataset.th; savePrefs(); applyTheme(); paintSet('set'); };
    });
    [].slice.call(P.querySelectorAll('[data-lt]')).forEach(function (b) {
      b.onclick = function () { switchLayout(b.dataset.lt); };
    });
    [].slice.call(P.querySelectorAll('[data-dr]')).forEach(function (b) {
      b.onclick = function () {
        if (PRE.layout === 'scroll') return toast('连续滚动下方向不生效，先切到分页');
        PRE.dir = b.dataset.dr; savePrefs(); applyLayout(); paintSet('set');
      };
    });
    [].slice.call(P.querySelectorAll('[data-sz]')).forEach(function (b) {
      b.onclick = function () {
        PRE.size = clamp(PRE.size + (+b.dataset.sz), 13, 32);
        savePrefs(); applyType(); paintSet('set');
      };
    });
    [].slice.call(P.querySelectorAll('[data-lh]')).forEach(function (b) {
      b.onclick = function () { PRE.lh = +b.dataset.lh; savePrefs(); applyType(); paintSet('set'); };
    });
    [].slice.call(P.querySelectorAll('[data-pa]')).forEach(function (b) {
      b.onclick = function () { PRE.para = +b.dataset.pa; savePrefs(); applyType(); paintSet('set'); };
    });
    [].slice.call(P.querySelectorAll('[data-ft]')).forEach(function (b) {
      b.onclick = function () { PRE.font = b.dataset.ft; savePrefs(); applyType(); paintSet('set'); };
    });
    [].slice.call(P.querySelectorAll('[data-tr]')).forEach(function (b) {
      b.onclick = function () {
        PRE.ttsRate = +b.dataset.tr; savePrefs(); paintSet('set');
        if (EB.ttsOn) ttsRestart();
      };
    });
    [].slice.call(P.querySelectorAll('[data-sw]')).forEach(function (b) {
      b.onclick = function () {
        var k = b.dataset.sw;
        PRE[k] = !PRE[k];
        savePrefs();
        b.classList.toggle('on', PRE[k]);
        if (k === 'pubcss') syncPub();
        if (k === 'wake') PRE.wake ? wakeOn() : wakeOff();
        if (k === 'imm') {
          try {
            if (PRE.imm) sessionStorage.setItem(immKey(), JSON.stringify({ ch: EB.ch, p: chapterProgress() }));
            else sessionStorage.removeItem(immKey());
          } catch (e) { }
          paintTop();
          toast(PRE.imm ? '隐身阅读已开启，本次不记录进度' : '已恢复记录进度');
        }
      };
    });
    var range = $('ebMeasure');
    range.addEventListener('input', function () {
      PRE.measure = +range.value;
      $('ebMeasureV').textContent = PRE.measure;
      applyType();
    });
    range.addEventListener('change', savePrefs);
    fillVoices();
  }

  function fillVoices() {
    var sel = $('ebVoice'); if (!sel) return;
    var list = ttsVoices();
    sel.innerHTML = '<option value="">系统默认</option>' + list.map(function (v) {
      return '<option value="' + esc(v.name) + '"' + (PRE.ttsVoice === v.name ? ' selected' : '') + '>' + esc(v.name + ' · ' + v.lang) + '</option>';
    }).join('');
    sel.onchange = function () { PRE.ttsVoice = sel.value; savePrefs(); };
  }

  function ttsVoices() {
    if (!window.speechSynthesis) return [];
    var all = window.speechSynthesis.getVoices() || [];
    var zh = all.filter(function (v) { return /^zh|^cmn|Chinese|中文|普通话/i.test(v.lang + ' ' + v.name); });
    return zh.length ? zh : all;
  }

  function annFiltered() {
    var tab = EB.annTab || 'all';
    var q = String(EB.annQ || '').trim().toLowerCase();
    return EB.anns.filter(function (a) {
      if (tab === 'bookmark' && a.kind !== 'bookmark') return false;
      if (tab === 'mark' && a.kind === 'bookmark') return false;
      if (!q) return true;
      var title = (EB.chs.get(a.ch) || {}).title || '';
      return (String(a.mark || '') + ' ' + String(a.note || '') + ' ' + title).toLowerCase().indexOf(q) >= 0;
    }).sort(function (a, b) { return a.ch - b.ch || (a.at || 0) - (b.at || 0); });
  }

  function paintAnns() {
    var P = $('ebSet');
    var bm = 0;
    EB.anns.forEach(function (a) { if (a.kind === 'bookmark') bm++; });
    var list = annFiltered();
    var tab = EB.annTab || 'all';
    P.innerHTML =
      '<div class="eb-panel-h"><b>书签与标注</b><span class="mono">' + bm + ' 书签 · ' + (EB.anns.length - bm) + ' 标注</span>' +
      '<button class="eb-btn" data-a="close">' + ico('close') + '</button></div>' +
      '<div class="eb-seg">' +
      [['all', '全部'], ['bookmark', '书签'], ['mark', '标注']].map(function (x) {
        return '<button class="eb-chip' + (tab === x[0] ? ' on' : '') + '" data-atab="' + x[0] + '">' + x[1] + '</button>';
      }).join('') + '</div>' +
      '<div class="eb-search">' + ico('search') + '<input id="ebAnnQ" placeholder="搜索书签、标注或笔记" autocomplete="off" value="' + esc(EB.annQ || '') + '"></div>' +
      '<div style="padding:0 12px 8px"><button class="eb-chip" data-bmadd="1" style="width:100%">' + ico('bookmark', 13) + ' 给第 ' + EB.ch + ' 章加书签</button></div>' +
      '<div class="eb-list" id="ebAnnList"></div>';
    P.querySelector('[data-a=close]').onclick = function () { closePanels(); };
    [].slice.call(P.querySelectorAll('[data-atab]')).forEach(function (b) {
      b.onclick = function () { EB.annTab = b.dataset.atab; paintAnns(); markTop(); };
    });
    var qi = $('ebAnnQ');
    qi.addEventListener('input', function () {
      EB.annQ = this.value;
      clearTimeout(EB.annQT);
      EB.annQT = setTimeout(paintAnns, 200);
    });
    P.querySelector('[data-bmadd]').onclick = addBookmark;
    var box = $('ebAnnList');
    if (!list.length) {
      box.innerHTML = '<div class="eb-empty">' + (EB.anns.length ? '没有匹配的条目' : '还没有书签和标注<br><span style="font-size:11px">按 B 给当前章加书签；选中文字可高亮、划下划线或写笔记</span>') + '</div>';
      return;
    }
    box.innerHTML = list.map(function (a) {
      var col = a.kind === 'bookmark' ? 'var(--eb-acc)' : a.color;
      return '<div class="eb-ann" data-annjump="' + a.id + '" style="border-left-color:' + col + '">' +
        (a.note ? '<div class="n">' + esc(a.note) + '</div>' : '') +
        (a.mark ? '<div class="q">' + esc(a.mark) + '</div>' : '') +
        '<div class="m"><span>' + (MARK_CN[a.kind] || '标注') + '</span><span>' + (a.ch || 0) + ' 章</span><span>' + ago(a.at) + '</span>' +
        '<button class="x" data-annedit="' + a.id + '">编辑</button><button class="x" data-anndel="' + a.id + '">删除</button></div></div>';
    }).join('');
    [].slice.call(box.querySelectorAll('[data-annjump]')).forEach(function (n) {
      n.onclick = function (ev) {
        if (ev.target.closest('[data-anndel]') || ev.target.closest('[data-annedit]')) return;
        jumpAnn(n.dataset.annjump);
      };
    });
    [].slice.call(box.querySelectorAll('[data-anndel]')).forEach(function (n) {
      n.onclick = function (ev) { ev.stopPropagation(); delAnn(n.dataset.anndel); };
    });
    [].slice.call(box.querySelectorAll('[data-annedit]')).forEach(function (n) {
      n.onclick = function (ev) { ev.stopPropagation(); editAnn(n.dataset.annedit); };
    });
  }

  function addBookmark() {
    if (!EB.ready) return toast('章节还在载入', 'err');
    var ch = EB.ch;
    var ref = EB.chs.get(ch) || {};
    if (!ref.href) return toast('这一章还没有可锚定的位置', 'err');
    var dup = null;
    EB.anns.forEach(function (a) { if (a.kind === 'bookmark' && a.ch === ch) dup = a; });
    if (dup) { EB.annTab = 'bookmark'; paintAnns(); return toast('第 ' + ch + ' 章已有书签'); }
    var p = chapterProgress();
    req('api/lantai/v1/annotations', {
      method: 'POST',
      body: {
        bookId: EB.bookId, href: ref.href, progression: (ch - 1 + p) / Math.max(1, EB.total),
        textBefore: '', textHighlight: '', textAfter: '', color: '#C9A24B', marker: 'BOOKMARK',
        note: isPaged() ? '本章 ' + Math.round(p * 100) + '% 处' : ''
      }
    }).then(function (dto) {
      EB.anns.unshift({
        id: dto.id, href: ref.href, ch: ch, color: '#C9A24B', kind: 'bookmark',
        note: dto.note || '', before: '', mark: '', after: '', progression: 0, at: Date.now()
      });
      toast('已在第 ' + ch + ' 章加书签', 'ok');
      if (EB.panel === 'ann') paintAnns();
    }).catch(function (e) { toast('书签保存失败：' + errText(e), 'err'); });
  }

  function editAnn(id) {
    var a = null;
    EB.anns.forEach(function (x) { if (x.id === id) a = x; });
    if (!a) return;
    var pick = { color: a.color, kind: a.kind };
    var ask = $('ebAsk');
    ask.innerHTML = '<div class="card"><h4>编辑' + (MARK_CN[a.kind] || '标注') + '</h4>' +
      '<div class="q">' + esc(a.mark || ('第 ' + a.ch + ' 章')) + '</div>' +
      '<div style="display:flex;gap:8px;margin:10px 0">' +
      COLORS.map(function (c) {
        return '<button data-ec="' + c + '" style="width:26px;height:26px;border-radius:50%;border:2px solid ' +
          (a.color === c ? 'var(--eb-acc)' : 'transparent') + ';background:' + c + ';cursor:pointer"></button>';
      }).join('') + '</div>' +
      '<div class="eb-seg" style="margin-bottom:10px">' +
      [['highlight', '高亮'], ['underline', '下划线'], ['comment', '笔记'], ['bookmark', '书签']].map(function (x) {
        return '<button class="eb-chip' + (a.kind === x[0] ? ' on' : '') + '" data-ek="' + x[0] + '">' + x[1] + '</button>';
      }).join('') + '</div>' +
      '<textarea id="ebEditNote" placeholder="笔记…">' + esc(a.note || '') + '</textarea>' +
      '<div class="ft"><button data-x>取消</button><button class="pri" data-ok>保存</button></div></div>';
    ask.classList.add('on');
    ask.querySelector('[data-x]').onclick = function () { ask.classList.remove('on'); };
    [].slice.call(ask.querySelectorAll('[data-ec]')).forEach(function (b) {
      b.onclick = function () {
        pick.color = b.dataset.ec;
        [].slice.call(ask.querySelectorAll('[data-ec]')).forEach(function (o) {
          o.style.borderColor = o === b ? 'var(--eb-acc)' : 'transparent';
        });
      };
    });
    [].slice.call(ask.querySelectorAll('[data-ek]')).forEach(function (b) {
      b.onclick = function () {
        pick.kind = b.dataset.ek;
        [].slice.call(ask.querySelectorAll('[data-ek]')).forEach(function (o) { o.classList.toggle('on', o === b); });
      };
    });
    ask.querySelector('[data-ok]').onclick = function () {
      var note = $('ebEditNote').value.trim();
      ask.classList.remove('on');
      req('api/lantai/v1/annotations/' + a.id, {
        method: 'PATCH',
        body: { color: pick.color, marker: String(pick.kind).toUpperCase(), note: note }
      }).then(function () {
        a.color = pick.color; a.kind = pick.kind; a.note = note; a.at = Date.now();
        repaintChapter(a.ch);
        paintAnns();
        toast('已更新');
      }).catch(function (e) { toast('更新失败：' + errText(e), 'err'); });
    };
    setTimeout(function () { var t = $('ebEditNote'); if (t) t.focus(); }, 40);
  }

  function jumpAnn(id) {
    var a = null;
    for (var i = 0; i < EB.anns.length; i++) if (EB.anns[i].id === id) a = EB.anns[i];
    if (!a) return;
    closePanels();
    var go = EB.secs.has(a.ch) ? Promise.resolve() : reset(a.ch).then(function () { scrollToSec(EB.secs.get(a.ch), 0); });
    go.then(function () {
      setTimeout(function () {
        var mk = EB.docEl && EB.docEl.querySelector('mark[data-ann="' + id + '"]');
        if (!mk) return toast('这一章没有找到标注位置', 'err');
        var vr = EB.view.getBoundingClientRect();
        var mr = mk.getBoundingClientRect();
        EB.view.scrollTop += mr.top - vr.top - EB.view.clientHeight * 0.36;
        mk.style.boxShadow = '0 0 0 3px ' + hexA(a.color, .5);
        setTimeout(function () { mk.style.boxShadow = ''; }, 1200);
      }, 120);
    });
  }

  function loadAnns() {
    return req('api/lantai/v1/annotations?bookId=' + EB.bookId)
      .then(function (list) {
        EB.anns = (list || []).map(function (a) {
          var n = chOfHref(a.href);
          return {
            id: a.id, href: a.href || '', ch: n, color: a.color || COLORS[0],
            kind: String(a.marker || 'HIGHLIGHT').toLowerCase(), note: a.note || '',
            before: a.textBefore || '', mark: a.textHighlight || '', after: a.textAfter || '',
            progression: typeof a.progression === 'number' ? a.progression : 0,
            at: new Date(a.lastModifiedDate || a.createdDate || Date.now()).getTime()
          };
        });
        EB.anns.forEach(function (a) { if (a.ch) repaintChapter(a.ch); });
        if (EB.panel === 'ann') paintAnns();
      })
      .catch(function () { });
  }

  function remapAnns() {
    var changed = false;
    EB.anns.forEach(function (a) {
      if (!a.ch) {
        var n = chOfHref(a.href);
        if (n) { a.ch = n; changed = true; }
      }
    });
    if (!changed) return;
    EB.anns.forEach(function (a) { if (a.ch === EB.ch) repaintChapter(a.ch); });
    if (EB.panel === 'ann') paintAnns();
  }

  function chOfHref(href) {
    var target = String(href || '').split('#')[0];
    if (!target) return 0;
    var m = target.match(/^#page=(\d+)$/);
    if (m) return +m[1];
    var hit = 0;
    EB.chs.forEach(function (c, n) { if (c.href === target) hit = n; });
    return hit;
  }

  function repaintChapter(n) {
    var sec = EB.secs.get(n);
    if (!sec) return;
    [].slice.call(sec.querySelectorAll('mark[data-ann]')).forEach(function (mk) {
      var txt = document.createTextNode(mk.textContent);
      mk.parentNode.replaceChild(txt, mk);
      txt.parentNode.normalize();
    });
    paintMarks(sec, n);
  }

  function textMap(root) {
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        if (!n.nodeValue || !n.nodeValue.length) return NodeFilter.FILTER_REJECT;
        var p = n.parentNode;
        if (p && /^(SCRIPT|STYLE|TEXTAREA)$/.test(p.nodeName)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var segs = [], total = 0, n;
    while ((n = walker.nextNode())) {
      segs.push({ node: n, start: total, end: total + n.nodeValue.length });
      total += n.nodeValue.length;
    }
    return { segs: segs, length: total, text: segs.map(function (s) { return s.node.nodeValue; }).join('') };
  }

  function locate(map, before, mark, after) {
    if (!mark) return null;
    var whole = map.text;
    if (before) {
      var tail = before.slice(-26);
      var at = whole.indexOf(tail + mark + (after || '').slice(0, 26));
      if (at >= 0) return { start: at + tail.length, end: at + tail.length + mark.length };
    }
    var idx = whole.indexOf(mark);
    if (idx < 0) return null;
    return { start: idx, end: idx + mark.length };
  }

  function paintMarks(root, n) {
    var list = EB.anns.filter(function (a) { return a.ch === n && a.mark; });
    if (!list.length) return;
    var map = textMap(root);
    list.forEach(function (a) {
      if (root.querySelector('mark[data-ann="' + a.id + '"]')) return;
      var pos = locate(map, a.before, a.mark, a.after);
      if (!pos) return;
      wrapRange(root, map, pos.start, pos.end, a);
    });
  }

  function wrapRange(root, map, start, end, a) {
    var segs = map.segs.filter(function (s) { return s.end > start && s.start < end; });
    for (var i = segs.length - 1; i >= 0; i--) {
      var s = segs[i];
      var from = Math.max(0, start - s.start);
      var to = Math.min(s.node.nodeValue.length, end - s.start);
      if (to <= from) continue;
      var node = s.node;
      if (from > 0) node = node.splitText(from);
      if (to - from < node.nodeValue.length) node.splitText(to - from);
      var mk = document.createElement('mark');
      mk.setAttribute('data-ann', a.id);
      mk.style.background = hexA(a.color, a.kind === 'highlight' ? .34 : .12);
      mk.style.borderBottom = a.kind === 'underline' ? '2px solid ' + a.color : 'none';
      mk.style.padding = '1px 0';
      mk.addEventListener('click', function (ev) {
        ev.stopPropagation();
        jumpAnn(this.getAttribute('data-ann'));
      });
      node.parentNode.insertBefore(mk, node);
      mk.appendChild(node);
    }
  }

  function currentSelection() {
    var sh = EB.shadow;
    if (sh && typeof sh.getSelection === 'function') {
      try {
        var s = sh.getSelection();
        if (s && s.rangeCount) return s;
      } catch (e) { }
    }
    return document.getSelection();
  }

  function onSelect() {
    if (!EB.open || !EB.shadow) return;
    var box = $('ebSel');
    var sel = currentSelection();
    if (!sel || !sel.rangeCount) { box.classList.remove('on'); EB.sel = null; return; }
    var range = sel.getRangeAt(0);
    var text = String(sel.toString() || '').replace(/\s+/g, ' ').trim();
    if (text.length < 2 || sel.isCollapsed) { box.classList.remove('on'); EB.sel = null; return; }
    var node = range.commonAncestorContainer;
    if (node.nodeType === 3) node = node.parentNode;
    if (!node || !EB.shadow.contains(node)) { box.classList.remove('on'); EB.sel = null; return; }
    var sec = node.closest ? node.closest('.sec') : null;
    if (!sec) { box.classList.remove('on'); EB.sel = null; return; }
    var n = +sec.dataset.ch;
    var ct = sec.querySelector('.ct') || sec;
    var map = textMap(ct);
    var probe = document.createRange();
    probe.setStart(ct, 0);
    probe.setEnd(range.startContainer, range.startOffset);
    var start = probe.toString().length;
    if (start < 0 || start > map.length) start = map.text.indexOf(text);
    if (start < 0) { box.classList.remove('on'); return; }
    var end = start + text.length;
    EB.sel = {
      ch: n, start: start, end: end, text: text,
      before: map.text.slice(Math.max(0, start - 40), start),
      after: map.text.slice(end, end + 40)
    };
    var rects = range.getClientRects();
    var rect = rects.length ? rects[0] : range.getBoundingClientRect();
    var w = 236;
    box.innerHTML =
      COLORS.slice(0, 4).map(function (c) {
        return '<button data-c="' + c + '" data-k="highlight" title="高亮"><span class="dot" style="background:' + c + '"></span></button>';
      }).join('') +
      '<div class="sep"></div>' +
      '<button data-k="underline" title="下划线">' + ico('edit', 14) + '</button>' +
      '<button data-note="1" title="写笔记">' + ico('marker', 14) + '</button>' +
      '<button data-copy="1" title="复制">' + ico('copy', 14) + '</button>' +
      '<button data-tts="1" title="朗读这段">' + ico('volume', 14) + '</button>';
    box.style.left = clamp(rect.left + rect.width / 2 - w / 2, 10, innerWidth - w - 10) + 'px';
    box.style.top = clamp(rect.top - 48, 54, innerHeight - 60) + 'px';
    box.classList.add('on');
    [].slice.call(box.querySelectorAll('button')).forEach(function (btn) {
      btn.onclick = function () {
        if (btn.dataset.copy) {
          navigator.clipboard && navigator.clipboard.writeText(text).then(function () { toast('已复制'); }).catch(function () { });
          clearSel();
          return;
        }
        if (btn.dataset.tts) {
          speakText(text);
          clearSel();
          return;
        }
        if (btn.dataset.note) createAnn('#C9A24B', 'comment', true);
        else createAnn(btn.dataset.c || '#C9A24B', btn.dataset.k || 'highlight', false);
      };
    });
  }

  function clearSel() {
    $('ebSel').classList.remove('on');
    EB.sel = null;
    var s = currentSelection();
    if (s) s.removeAllRanges();
  }

  function createAnn(color, kind, withNote) {
    var sel = EB.sel;
    if (!sel) return;
    clearSel();
    var a = {
      id: '', ch: sel.ch, color: color, kind: kind, note: '',
      before: sel.before, mark: sel.text.slice(0, 400), after: sel.after,
      progression: (sel.ch - 1) / Math.max(1, EB.total)
    };
    if (!withNote) return saveAnn(a);
    var ask = $('ebAsk');
    ask.innerHTML = '<div class="card"><h4>写一条笔记</h4><div class="q">' + esc(sel.text) + '</div>' +
      '<textarea id="ebNote" placeholder="想法、出处、待查…"></textarea>' +
      '<div class="ft"><button data-x>取消</button><button class="pri" data-ok>保存</button></div></div>';
    ask.classList.add('on');
    ask.querySelector('[data-x]').onclick = function () { ask.classList.remove('on'); };
    ask.querySelector('[data-ok]').onclick = function () {
      a.note = $('ebNote').value.trim();
      ask.classList.remove('on');
      saveAnn(a);
    };
    setTimeout(function () { var t = $('ebNote'); if (t) t.focus(); }, 40);
  }

  function saveAnn(a) {
    if (!rt()) return toast('后端不可用', 'err');
    var ref = EB.chs.get(a.ch) || {};
    if (!ref.href) return toast('这一章还没有可锚定的位置', 'err');
    req('api/lantai/v1/annotations', {
      method: 'POST',
      body: {
        bookId: EB.bookId, href: ref.href, progression: a.progression,
        textBefore: a.before, textHighlight: a.mark, textAfter: a.after,
        color: a.color, marker: String(a.kind).toUpperCase(), note: a.note || ''
      }
    }).then(function (dto) {
      var item = {
        id: dto.id, href: ref.href, ch: a.ch, color: a.color, kind: a.kind, note: a.note || '',
        before: a.before, mark: a.mark, after: a.after, progression: a.progression,
        at: Date.now()
      };
      EB.anns.unshift(item);
      var sec = EB.secs.get(a.ch);
      if (sec) paintMarks(sec, a.ch);
      if (EB.panel === 'ann') paintAnns();
      if (typeof renderNav === 'function') renderNav();
      toast('已保存「' + (MARK_CN[a.kind] || '标注') + '」');
    }).catch(function (e) { toast('保存失败：' + errText(e), 'err'); });
  }

  function delAnn(id) {
    req('api/lantai/v1/annotations/' + id, { method: 'DELETE' })
      .then(function () {
        var ch = 0;
        EB.anns = EB.anns.filter(function (a) { if (a.id === id) { ch = a.ch; return false; } return true; });
        if (ch) repaintChapter(ch);
        paintAnns();
        toast('标注已删除');
      })
      .catch(function (e) { toast('删除失败：' + errText(e), 'err'); });
  }

  function chapterText(n) {
    var sec = EB.secs.get(n);
    if (!sec) return '';
    var ct = sec.querySelector('.ct') || sec;
    return String(ct.textContent || '').replace(/\s+/g, ' ').trim();
  }

  function splitSentences(text) {
    var out = [];
    var buf = '';
    for (var i = 0; i < text.length; i++) {
      buf += text[i];
      if (/[。！？!?；;…]/.test(text[i]) || (text[i] === '.' && i + 1 < text.length && /\s/.test(text[i + 1]))) {
        if (buf.trim()) out.push(buf.trim());
        buf = '';
      }
      if (buf.length > 220) { if (buf.trim()) out.push(buf.trim()); buf = ''; }
    }
    if (buf.trim()) out.push(buf.trim());
    return out.filter(function (s) { return s.length > 0; });
  }

  var BLOCK_SEL = 'p,div,h1,h2,h3,h4,h5,h6,li,td,blockquote,section,article,figcaption,pre';
  var ttsHL = null;

  function nodePoints(el) {
    var nodes = [], off = 0;
    var w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) {
      var p = n.parentNode;
      if (p && /^(SCRIPT|STYLE)$/i.test(p.tagName)) continue;
      var v = n.nodeValue || '';
      if (!v) continue;
      nodes.push({ node: n, start: off, end: off + v.length });
      off += v.length;
    }
    return nodes;
  }

  function pointAt(nodes, offset) {
    for (var i = 0; i < nodes.length; i++) {
      if (offset <= nodes[i].end) return { node: nodes[i].node, offset: Math.max(0, Math.min(nodes[i].node.nodeValue.length, offset - nodes[i].start)) };
    }
    var last = nodes[nodes.length - 1];
    return { node: last.node, offset: last.node.nodeValue.length };
  }

  function sentenceRanges(text) {
    var out = [], start = 0, buf = 0;
    for (var i = 0; i < text.length; i++) {
      buf++;
      if (/[。！？!?；;…]/.test(text[i]) || (text[i] === '.' && i + 1 < text.length && /\s/.test(text[i + 1]))) {
        if (text.slice(start, i + 1).trim()) out.push([start, i + 1]);
        start = i + 1; buf = 0;
      } else if (buf > 220) {
        if (text.slice(start, i + 1).trim()) out.push([start, i + 1]);
        start = i + 1; buf = 0;
      }
    }
    if (text.slice(start).trim()) out.push([start, text.length]);
    return out;
  }

  function ttsSegments(sec) {
    var ct = sec.querySelector('.ct') || sec;
    var out = [];
    var blocks = [].slice.call(ct.querySelectorAll(BLOCK_SEL)).filter(function (el) {
      return !el.querySelector(BLOCK_SEL) && !/^(SCRIPT|STYLE|SVG)$/i.test(el.tagName);
    });
    if (!blocks.length) blocks = [ct];
    blocks.forEach(function (el) {
      var nodes = nodePoints(el);
      if (!nodes.length) return;
      var flat = '';
      nodes.forEach(function (x) { flat += x.node.nodeValue; });
      if (!flat.replace(/[\s\u00a0\u3000]+/g, '')) return;
      sentenceRanges(flat).forEach(function (r) {
        var t = flat.slice(r[0], r[1]).replace(/[\s\u00a0\u3000]+/g, ' ').trim();
        if (!t) return;
        out.push({ text: t, el: el, s: r[0], e: r[1], nodes: nodes });
      });
    });
    return out;
  }

  function ttsPaint(seg) {
    if (!seg || !seg.el) return;
    var nodes = seg.nodes || nodePoints(seg.el);
    if (!nodes.length) return;
    var a = pointAt(nodes, seg.s), b = pointAt(nodes, seg.e);
    var r = document.createRange();
    try {
      r.setStart(a.node, a.offset);
      r.setEnd(b.node, b.offset);
    } catch (e) { return; }
    if (window.CSS && CSS.highlights && typeof window.Highlight === 'function') {
      if (!ttsHL) ttsHL = new window.Highlight();
      ttsHL.clear();
      ttsHL.add(r);
      CSS.highlights.set('eb-tts', ttsHL);
    } else {
      seg.el.classList.add('tts-cur');
    }
    ttsReveal(seg.el);
  }

  function ttsClear() {
    if (window.CSS && CSS.highlights) CSS.highlights.delete('eb-tts');
    if (EB.shadow) [].slice.call(EB.shadow.querySelectorAll('.tts-cur')).forEach(function (el) { el.classList.remove('tts-cur'); });
  }

  function ttsReveal(el) {
    var v = EB.view;
    if (!v || !el) return;
    var er = el.getBoundingClientRect(), vr = v.getBoundingClientRect();
    if (!er.height) return;
    if (PRE.layout === 'paged') {
      if (er.left < vr.left - 1 || er.right > vr.right + 1) {
        v.scrollLeft += er.left - vr.left - 24;
        EB.page = Math.max(0, Math.round(v.scrollLeft / (v.clientWidth || 1)));
      }
    } else if (er.top < vr.top || er.bottom > vr.bottom) {
      v.scrollTop += er.top - vr.top - vr.height * 0.34;
    }
  }

  function pickVoice() {
    var list = ttsVoices();
    for (var i = 0; i < list.length; i++) if (list[i].name === PRE.ttsVoice) return list[i];
    return null;
  }

  function ttsToggle() {
    if (!window.speechSynthesis) return toast('当前浏览器不支持朗读', 'err');
    if (EB.ttsOn) return ttsStop();
    ttsStart();
  }

  function ttsStart(text) {
    var sec = text ? null : EB.secs.get(EB.ch);
    var list = sec ? ttsSegments(sec) : null;
    if (!list || !list.length) {
      var body = text || chapterText(EB.ch);
      if (!body) return toast('这一章没有可朗读的文字', 'err');
      list = splitSentences(body).map(function (t) { return { text: t }; });
    }
    if (!list.length) return toast('这一章没有可朗读的文字', 'err');
    EB.tts = { list: list, idx: 0 };
    EB.ttsOn = true;
    $('ebTts').classList.add('on');
    $('ebTts').innerHTML =
      '<button class="eb-btn" data-a="prev">' + ico('left') + '</button>' +
      '<button class="eb-btn" data-a="play">' + ico('pause') + '</button>' +
      '<button class="eb-btn" data-a="next">' + ico('right') + '</button>' +
      '<span class="tx" id="ebTtsTx"></span>' +
      '<button class="eb-btn" data-a="close">' + ico('close') + '</button>';
    [].slice.call($('ebTts').querySelectorAll('[data-a]')).forEach(function (b) {
      b.onclick = function () {
        var a = b.dataset.a;
        if (a === 'close') return ttsStop();
        if (a === 'prev') return ttsSeek(Math.max(0, EB.tts.idx - 1));
        if (a === 'next') return ttsSeek(Math.min(EB.tts.list.length - 1, EB.tts.idx + 1));
        if (a === 'play') ttsPauseToggle(b);
      };
    });
    speakCurrent();
    markTop();
  }

  function ttsPauseToggle(btn) {
    if (!window.speechSynthesis) return;
    if (window.speechSynthesis.paused) {
      window.speechSynthesis.resume();
      btn.innerHTML = ico('pause');
    } else {
      window.speechSynthesis.pause();
      btn.innerHTML = ico('play');
    }
  }

  function ttsSeek(i) {
    if (!EB.tts) return;
    EB.tts.idx = i;
    window.speechSynthesis.cancel();
    setTimeout(speakCurrent, 60);
  }

  function speakCurrent() {
    if (!EB.tts || !EB.ttsOn) return;
    var s = EB.tts.list[EB.tts.idx];
    if (!s) return ttsStop();
    var text = typeof s === 'string' ? s : s.text;
    var tx = $('ebTtsTx');
    if (tx) tx.textContent = (EB.tts.idx + 1) + '/' + EB.tts.list.length + ' ' + text.slice(0, 40);
    if (s && s.el) ttsPaint(s);
    var u = new SpeechSynthesisUtterance(text);
    u.rate = PRE.ttsRate;
    u.pitch = 1;
    var v = pickVoice();
    if (v) { u.voice = v; u.lang = v.lang; }
    else u.lang = 'zh-CN';
    u.onend = function () {
      if (!EB.ttsOn || !EB.tts) return;
      if (EB.tts.idx >= EB.tts.list.length - 1) {
        if (EB.ch < EB.total) {
          gotoChapter(EB.ch + 1, 0);
          setTimeout(ttsStart, 900);
          return;
        }
        return ttsStop();
      }
      EB.tts.idx++;
      speakCurrent();
    };
    u.onerror = function (ev) {
      if (!EB.ttsOn) return;
      if (ev && (ev.error === 'interrupted' || ev.error === 'canceled')) return;
      ttsStop();
      toast('朗读不可用：' + ((ev && ev.error) || '浏览器没有可用语音'), 'err');
    };
    window.speechSynthesis.speak(u);
  }

  function speakText(text) {
    if (!window.speechSynthesis) return toast('当前浏览器不支持朗读', 'err');
    window.speechSynthesis.cancel();
    var u = new SpeechSynthesisUtterance(text);
    u.rate = PRE.ttsRate;
    u.lang = 'zh-CN';
    var v = pickVoice();
    if (v) { u.voice = v; u.lang = v.lang; }
    window.speechSynthesis.speak(u);
    toast('正在朗读选中的 ' + text.length + ' 字');
  }

  function ttsRestart() {
    if (!EB.ttsOn || !EB.tts) return;
    var i = EB.tts.idx;
    window.speechSynthesis.cancel();
    setTimeout(function () {
      if (!EB.tts) return;
      EB.tts.idx = i;
      speakCurrent();
    }, 80);
  }

  function ttsStop() {
    EB.ttsOn = false;
    EB.tts = null;
    if (window.speechSynthesis) window.speechSynthesis.cancel();
    ttsClear();
    var p = $('ebTts'); if (p) { p.classList.remove('on'); p.innerHTML = ''; }
    markTop();
  }

  function wakeOn() {
    if (!navigator.wakeLock || !navigator.wakeLock.request) {
      toast('屏幕常亮需要 HTTPS 或 localhost');
      PRE.wake = false;
      savePrefs();
      if (EB.panel === 'set') paintSet('set');
      return;
    }
    navigator.wakeLock.request('screen').then(function (s) {
      EB.wakeLock = s;
      s.addEventListener('release', function () { EB.wakeLock = null; });
    }).catch(function () { toast('屏幕常亮申请被拒绝', 'err'); });
  }

  function wakeOff() {
    if (!EB.wakeLock) return;
    try { EB.wakeLock.release(); } catch (e) { }
    EB.wakeLock = null;
  }

  function fullscreen() {
    if (document.fullscreenElement) { document.exitFullscreen().catch(function () { }); return; }
    var r = $('eb');
    (r || document.documentElement).requestFullscreen().catch(function () { toast('浏览器拒绝了全屏请求', 'err'); });
  }

  function onKey(ev) {
    if (!EB.open) return;
    if (ev.target && /INPUT|TEXTAREA|SELECT/.test(ev.target.tagName)) return;
    var k = ev.key;
    if (k === 'Escape') {
      if ($('ebLb').classList.contains('on')) { $('ebLb').classList.remove('on'); return; }
      if ($('ebAsk').classList.contains('on')) { $('ebAsk').classList.remove('on'); return; }
      if (EB.panel) { closePanels(); return; }
      if (EB.sel) { clearSel(); return; }
      return close();
    }
    if ((ev.ctrlKey || ev.metaKey) && (k === 'f' || k === 'F')) { ev.preventDefault(); openPanel('find'); return; }
    if (ev.ctrlKey || ev.metaKey || ev.altKey) return;
    if (k === 'ArrowLeft') { ev.preventDefault(); if (isPaged()) turnPage(-1); else prevChapter(); }
    else if (k === 'ArrowRight') { ev.preventDefault(); if (isPaged()) turnPage(1); else nextChapter(); }
    else if (k === 'PageDown' || (k === ' ' && isPaged())) { ev.preventDefault(); turnPage(1); }
    else if (k === 'PageUp' && isPaged()) { ev.preventDefault(); turnPage(-1); }
    else if (k === 't' || k === 'T') { ev.preventDefault(); openPanel('toc'); }
    else if (k === 's' || k === 'S') { ev.preventDefault(); openPanel('set'); }
    else if (k === 'a' || k === 'A') { ev.preventDefault(); EB.annTab = 'all'; openPanel('ann'); }
    else if (k === 'b' || k === 'B') { ev.preventDefault(); addBookmark(); }
    else if (k === 'd' || k === 'D') { ev.preventDefault(); ttsToggle(); }
    else if (k === 'f' || k === 'F') { ev.preventDefault(); fullscreen(); }
  }

  function open(bookId, opts) {
    opts = opts || {};
    if (!rt()) return toast('后端不可用', 'err');
    if (EB.open && EB.bookId === bookId) {
      if (opts.chapter) gotoChapter(opts.chapter, opts.progress || 0);
      return;
    }
    if (EB.open) close(false);
    var b = book(bookId);
    if (!b) return toast('这本书不在书库里', 'err');
    if (b.status === 'UNSUPPORTED') return toast('这本书需要先安装转换器', 'err');

    mount();
    EB.open = true;
    if (opts.imm) { EB.immOnce = !PRE.imm; PRE.imm = true; }
    var sm = null;
    try { sm = series(b.s); } catch (e) { }
    EB.book = { title: (sm && sm.t) || b.name || '', profile: b.profile };
    EB.bookId = bookId;
    EB.total = b.chs > 0 ? b.chs : (b.pages > 0 ? b.pages : 1);
    EB.chs.clear();
    EB.secs.clear();
    EB.park.clear();
    EB.pending = {};
    EB.warmF = false; EB.warmB = false;
    EB.anns = [];
    EB.outline = null;
    EB.links = [];
    EB.inlines = [];
    pubKey = '';
    var resume = PRE.imm ? immResume(bookId) : null;
    var startOpt = opts.chapter || (resume && resume.ch) || 0;
    EB.ch = startOpt || 1;
    EB.seen = EB.ch;
    EB.ready = false;
    EB.bootDone = false;
    EB.bootAt = Date.now();
    EB.lo = 0;
    EB.hi = 0;
    EB.panel = null;
    $('ebToc').classList.remove('on');
    $('ebSet').classList.remove('on');
    $('eb').classList.add('on');
    $('eb').classList.remove('toc-open', 'set-open', 'idle');
    applyTheme();
    applyType();
    ensureShadow();
    if (EB.docEl) {
      EB.docEl.className = 'doc' + (isPaged() ? ' paged' : '');
      EB.docEl.removeAttribute('style');
    }
    if (EB.shadow) clearPub(EB.shadow);
    EB.docEl.innerHTML = '';
    paintTop();
    paintBot();
    $('ebView').scrollTop = 0;
    $('ebView').scrollLeft = 0;
    EB.page = 0;
    EB.pages = 1;

    EB.session = {
      id: (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : 'eb_' + bookId + '_' + Date.now(),
      book: bookId, dur: 0, pages: EB.ch, dev: 'webui', devName: '网页端', start: Date.now(), end: Date.now(), offline: false
    };
    if (typeof pushSession === 'function' && !PRE.imm) pushSession(EB.session);
    startBeat();
    if (PRE.wake) wakeOn();
    if (location.hash.indexOf('#/read/') !== 0) {
      history.replaceState(null, '', '#/read/' + bookId);
      document.title = '兰台 · ' + (EB.book.title || '') + ' · 阅读';
    }
    loadAnns();
    var q = 'api/lantai/v1/read/' + bookId + '/bootstrap?images=inline&w=0&window=3' +
      (startOpt ? '&chapter=' + startOpt : '');
    EB.docEl.innerHTML = '<div class="sec skel" style="min-height:340px"></div>';
    req(q).then(function (dto) {
      if (!EB.open || EB.bookId !== bookId) return;
      EB.total = dto.total || EB.total;
      (dto.chapters || []).forEach(function (c) { EB.chs.set(c.n, c); });
      EB.book = Object.assign({}, dto.book || {}, { profile: (dto.book && dto.book.profile) || b.profile });
      EB.docEl.innerHTML = '';
      EB.lo = 0; EB.hi = 0;
      var first = buildSec(dto.first, true);
      EB.docEl.appendChild(first);
      syncPub();
      EB.ready = true;
      var startCh = dto.first.chapter;
      EB.ch = startCh;
      EB.seen = startCh;
      paintTop();
      paintBot();
      var restore = 0;
      if (opts.chapter) restore = opts.progress || 0;
      else if (PRE.imm && resume && resume.ch === startCh) restore = resume.p || 0;
      else if (dto.progress && dto.progress.chapter === startCh) restore = dto.progress.progression || 0;
      else restore = opts.progress || 0;
      if (restore > 0.005) restoreTo(restore);
      else scrollToSec(first, 0);
      load(startCh + 1, 'after')
        .then(function () { return load(startCh + 2, 'after'); })
        .catch(function () { });
      load(startCh - 1, 'before').catch(function () { });
      if (startCh > 1) load(startCh - 2, 'before').catch(function () { });
      if (dto.annotations && dto.annotations.length && !EB.anns.length) loadAnns();
      EB.bootDone = true;
      remapAnns();
      bumpIdle();
    }).catch(function (e) {
      if (!EB.open) return;
      EB.docEl.innerHTML = '';
      tip('打不开这本书', errText(e) + '<br><span style="font-size:11px;opacity:.7">' + esc(EB.bookId) + '</span>');
    });
  }

  function restoreTo(prog) {
    EB.restore = prog;
    var tries = 0;
    var run = function () {
      if (!EB.open || EB.restore < 0) return;
      var sec = EB.secs.get(EB.ch);
      if (!sec) return;
      scrollToSec(sec, EB.restore);
      tries++;
      if (tries < 4) setTimeout(run, 260 + tries * 200);
      else EB.restore = -1;
    };
    setTimeout(run, 60);
  }

  function close(settle) {
    if (!EB.open) return;
    var id = EB.bookId;
    clearTimeout(EB.progTimer);
    clearTimeout(EB.restoreT);
    if (settle !== false) {
      if (PRE.imm) {
        try { sessionStorage.setItem(immKey(), JSON.stringify({ ch: EB.ch, p: chapterProgress() })); } catch (e) { }
        if (EB.session) toast('隐身阅读 · 本次不留记录');
      } else if (EB.session) {
        EB.session.end = Date.now();
        if (typeof pushSession === 'function') pushSession(EB.session);
        sendProgress();
        req('api/lantai/v1/reading/stop', { method: 'POST', body: { bookId: id, deviceId: 'webui' } }).catch(function () { });
        toast('已读 ' + dur(EB.session.dur) + ' · 停在第 ' + EB.ch + ' 章');
      }
    }
    EB.session = null;
    if (EB.immOnce) { PRE.imm = false; EB.immOnce = false; }
    EB.open = false;
    clearInterval(EB.hbTimer);
    ttsStop();
    wakeOff();
    var r = $('eb');
    if (r) r.classList.remove('on', 'idle');
    $('ebLb').classList.remove('on');
    $('ebAsk').classList.remove('on');
    $('ebSel').classList.remove('on');
    $('ebTip').classList.remove('on');
    closePanels();
    if (EB.shadow && EB.docEl) EB.docEl.innerHTML = '';
    EB.secs.clear();
    EB.park.clear();
    EB.pending = {};
    EB.warmF = false; EB.warmB = false;
    if (location.hash.indexOf('#/read/') === 0) {
      history.replaceState(null, '', '#/book/' + id);
      if (typeof render === 'function') render();
    }
  }

  window.EbookReader = {
    open: open,
    close: close,
    goto: gotoChapter,
    isOpen: function () { return EB.open; },
    current: function () { return { open: EB.open, chapter: EB.ch, total: EB.total, panel: EB.panel, theme: PRE.theme }; },
    prefs: PRE
  };
})();
