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

(function () {
  'use strict';

  var MODES = [['single', '单页', 'single'], ['dual', '双页', 'double'], ['webtoon', '长条', 'flow'], ['paged', '分页', 'snap']];
  var FITS = [['contain', '适应'], ['width', '满宽'], ['height', '满高'], ['orig', '原始']];
  var QS = [[0, '原图'], [1600, '高清'], [1080, '省流']];
  var MARGIN_PRESETS = [['none', '无', 0], ['narrow', '窄', 16], ['std', '标准', 32], ['wide', '宽', 72]];
  var COLORS = ['#C9A24B', '#B0402C', '#46523F', '#2F5D8A', '#7B4F6B', '#E3C377'];
  var MARK_CN = { highlight: '高亮', underline: '下划线', bookmark: '书签', comment: '标注' };
  var MAX_ZIP = 300;

  var PRE = { mode: 'single', dir: 'LTR', fit: 'contain', quality: 0, preload: 2, gap: 8, bg: 'auto', imm: false, margins: { t: 0, b: 0, l: 0, r: 0 }, marginPreset: 'none' };
  try {
    var saved = JSON.parse(localStorage.getItem('lantai-comic') || '{}');
    Object.assign(PRE, saved);
    PRE.margins = Object.assign({ t: 0, b: 0, l: 0, r: 0 }, saved.margins || {});
  } catch (e) { }
  if (PRE.dir === 'TTB') PRE.dir = 'LTR';

  var CS = {
    open: false, bookId: '', book: null, total: 0, page: 1, seen: 1, wantPage: 0,
    refs: new Map(), refq: new Map(), fails: new Set(),
    mode: PRE.mode, dir: PRE.dir, fit: PRE.fit, quality: PRE.quality,
    anns: [], drawMode: null, drawColor: COLORS[0], drawing: null, annTab: 'all', annQ: '', annQT: 0,
    thumbs: false, side: null, zoom: 1, panX: 0, panY: 0,
    drag: null, ptrs: new Map(), pinch: null,
    wheelAt: 0, offTimer: null, idle: null,
    session: null, beats: 0, hbTimer: null, progTimer: null, started: false,
    lo: 0, hi: 0, flowObserver: null, scrollTimer: null, tinted: '',
    preTimer: null, preReady: false
  };

  function rt() { return window.LantaiRuntime; }
  function base() { return String((window.LantaiRuntime && LantaiRuntime.base) || '/'); }
  function req(p, o) {
    var opt = o || {};
    if (PRE.imm) opt = Object.assign({}, opt, { headers: Object.assign({ 'X-Lantai-Incognito': '1' }, opt.headers || {}) });
    return rt().request(p, opt);
  }
  function el(tag, cls, html) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (html != null) n.innerHTML = html;
    return n;
  }
  function svg(name, size) {
    return '<svg width="' + (size || 15) + '" height="' + (size || 15) + '"><use href="#i-' + name + '"/></svg>';
  }
  function $(id) { return document.getElementById(id); }
  function all(sel) { return [].slice.call(document.querySelectorAll(sel)); }
  function savePrefs() {
    try { localStorage.setItem('lantai-comic', JSON.stringify(PRE)); } catch (e) { }
  }
  function immKey() { return 'lantai.imm.c.' + CS.bookId; }
  function immResume(bookId) {
    if (!PRE.imm) return 0;
    try { return +sessionStorage.getItem('lantai.imm.c.' + bookId) || 0; } catch (e) { return 0; }
  }
  function imgW() { return CS.quality > 0 ? CS.quality : 0; }
  function errText(e) {
    if (!e) return '请求失败';
    if (e.status === 403) return '没有这个书库的阅读权限';
    if (e.status === 404) return '书籍或页面不存在';
    return e.message ? String(e.message).slice(0, 120) : 'HTTP ' + (e.status || 'err');
  }

  function imgUrl(page, w) {
    var ref = CS.refs.get(page);
    if (!ref) return '';
    var ww = w == null ? imgW() : w;
    return base() + 'api/lantai/v1/read/' + CS.bookId + '/img/' + encodeURIComponent(ref) + '?w=' + ww + '&fmt=' + (ww > 0 ? 'jpeg' : 'orig');
  }

  function ensureRef(page) {
    if (CS.refs.has(page)) return Promise.resolve(CS.refs.get(page));
    if (CS.refq.has(page)) return CS.refq.get(page);
    var p = req('api/lantai/v1/read/' + CS.bookId + '/chapter/' + page + '?images=url&w=0')
      .then(function (dto) {
        var ref = dto && dto.images && dto.images[0] && dto.images[0].ref;
        if (!ref) throw new Error('这一页没有图片');
        CS.refs.set(page, ref);
        CS.refq.delete(page);
        return ref;
      })
      .catch(function (e) { CS.refq.delete(page); throw e; });
    CS.refq.set(page, p);
    return p;
  }

  function mount() {
    var r = $('cm');
    if (r) return r;
    r = el('div', 'cm');
    r.id = 'cm';
    r.innerHTML =
      '<div class="cm-glow" id="cmGlow"></div>' +
      '<div class="cm-flow" id="cmFlow" style="display:none"></div>' +
      '<div class="cm-stage" id="cmStage"></div>' +
      '<div class="cm-top" id="cmTop"></div>' +
      '<div class="cm-bot" id="cmBot"></div>' +
      '<div class="cm-thumbs" id="cmThumbs"><div class="cm-strip" id="cmStrip"></div></div>' +
      '<div class="cm-side" id="cmSide"></div>' +
      '<div class="cm-hint" id="cmHint"></div>' +
      '<div class="cm-toast" id="cmToast"></div>' +
      '<div class="cm-float" id="cmFloat"></div>' +
      '<div class="cm-offline" id="cmOffline">网络已断开<br><span style="color:rgba(233,228,216,.6);font-size:11px">已载入的页面仍可继续阅读</span></div>';
    document.body.appendChild(r);
    r.addEventListener('pointerdown', onDown);
    r.addEventListener('pointermove', onMove);
    r.addEventListener('pointerup', onUp);
    r.addEventListener('pointercancel', onUp);
    r.addEventListener('wheel', onWheel, { passive: false });
    r.addEventListener('dblclick', function (ev) {
      if (ev.target.closest('.cm-top,.cm-bot,.cm-side,.cm-thumbs,.cm-float')) return;
      CS.zoom = CS.zoom > 1 ? 1 : 2;
      CS.panX = 0; CS.panY = 0;
      applyVars();
    });
    return r;
  }

  function open(bookId, opts) {
    opts = opts || {};
    if (!rt()) return toast('后端不可用', 'err');
    if (CS.open && CS.bookId === bookId) {
      if (opts.page) goto(opts.page);
      return;
    }
    if (CS.open) close(false);
    var b = book(bookId);
    if (!b) return toast('这本书不在书库里', 'err');
    if (b.status === 'UNSUPPORTED') return toast('这本书需要先转成 CBZ 才能阅读', 'err');

    mount();
    CS.open = true; CS.bookId = bookId; CS.book = b;
    if (opts.imm) { CS.immOnce = !PRE.imm; PRE.imm = true; }
    CS.total = b.pages > 0 ? b.pages : 0;
    CS.wantPage = Math.max(0, opts.page || immResume(bookId) || 0);
    CS.page = CS.wantPage || 1; CS.seen = CS.page;
    CS.refs.clear(); CS.refq.clear(); CS.fails.clear();
    CS.anns = []; CS.zoom = 1; CS.panX = 0; CS.panY = 0; CS.tinted = '';
    CS.mode = PRE.mode; CS.dir = PRE.dir; CS.fit = PRE.fit; CS.quality = PRE.quality;
    CS.beats = 0; CS.started = false; CS.ptrs.clear(); CS.pinch = null; CS.drag = null;
    CS.preReady = false; clearTimeout(CS.preTimer); CS.preTimer = null;
    CS.session = {
      id: (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : 'cm_' + bookId + '_' + Date.now(),
      book: bookId, dur: 0, pages: CS.page, dev: 'webui', devName: '网页端', start: Date.now(), end: Date.now(), offline: false
    };

    $('cm').classList.add('on');
    applyVars();
    paintTop(); paintBot(); syncMode();
    $('cmFlow').style.display = 'none';
    $('cmStage').style.display = 'flex';
    $('cmStage').innerHTML = '<div class="cm-skel">正在读取这本书</div>';
    if (location.hash !== '#/read/' + bookId) history.pushState(null, '', '#/read/' + bookId);
    var s = series(b.s);
    document.title = (s ? s.t : b.name) + ' · 兰台阅读';

    load();
    startBeat();
    wakeUp();
  }

  function load() {
    var url = 'api/lantai/v1/read/' + CS.bookId + '/bootstrap?images=url&w=0';
    if (CS.wantPage) url += '&chapter=' + CS.wantPage;
    req(url).then(function (dto) {
      if (!CS.open) return;
      CS.total = dto.total || CS.total;
      CS.book = Object.assign({}, CS.book, { title: dto.book.title, name: dto.book.title, seriesTitle: dto.book.seriesTitle, profile: dto.book.profile });
      var rd = dto.book.readingDirection;
      if (rd === 'RTL') CS.dir = 'RTL';
      else if (rd !== 'TTB' && rd !== 'WEBTOON') CS.dir = 'LTR';
      if (rd === 'TTB' || rd === 'WEBTOON') { if (!PRE.modeTouched) { PRE.mode = 'webtoon'; CS.mode = 'webtoon'; } }
      if (CS.dir === 'TTB') { CS.dir = 'LTR'; PRE.dir = 'LTR'; }
      var start = CS.wantPage || (dto.progress && dto.progress.chapter) || 1;
      CS.page = clamp(start, 1, CS.total || 1);
      CS.seen = Math.max(CS.seen, CS.page);
      if (dto.first && dto.first.images && dto.first.images[0]) CS.refs.set(dto.first.chapter, dto.first.images[0].ref);
      CS.started = true;
      CS.anns = (dto.annotations || []).map(mapAnn).filter(function (a) { return a.page > 0; });
      applyVars(); syncMode(); paintTop(); paintBot();
      renderAll();
      sendProgress();
      hint('左右点击翻页 · 中间呼出工具栏 · 长按拖动也能翻');
      bumpIdle();
    }).catch(function (e) {
      if (!CS.open) return;
      $('cmStage').innerHTML = '<div class="cm-fail">这本书打不开<br><span style="opacity:.7">' + esc(errText(e)) + '</span>' +
        '<button class="cm-btn" data-retry>重试</button></div>';
      var rb = $('cmStage').querySelector('[data-retry]');
      if (rb) rb.onclick = function () { $('cmStage').innerHTML = '<div class="cm-skel">正在读取这本书</div>'; load(); };
    });
  }

  function close(settle) {
    if (!CS.open) return;
    var id = CS.bookId;
    CS.open = false;
    clearInterval(CS.hbTimer); CS.hbTimer = null;
    clearTimeout(CS.progTimer); clearTimeout(CS.offTimer); clearTimeout(CS.idle); clearTimeout(CS.preTimer);
    CS.preTimer = null; CS.preReady = false;
    if (CS.flowObserver) { CS.flowObserver.disconnect(); CS.flowObserver = null; }
    releaseWake();
    var r = $('cm');
    if (r) { r.classList.remove('on'); r.remove(); }
    if (CS.session && settle !== false) {
      CS.session.end = Date.now();
      if (PRE.imm) {
        toast('隐身阅读 · 本次不留记录', 'ok');
      } else {
        pushSession(CS.session);
        req('api/lantai/v1/reading/stop', { method: 'POST', body: { bookId: id, deviceId: 'webui' } }).catch(function () { });
        toast('已读 ' + dur(CS.session.dur) + ' · 停在第 ' + CS.page + ' 页', 'ok');
      }
    }
    CS.session = null;
    if (CS.immOnce) { PRE.imm = false; CS.immOnce = false; }
    if (location.hash.indexOf('#/read/') === 0) {
      history.replaceState(null, '', '#/book/' + id);
      if (typeof render === 'function') render();
    }
  }

  function isOpen(id) { return CS.open && (!id || CS.bookId === id); }

  function applyVars() {
    var r = $('cm'); if (!r) return;
    var m = PRE.margins;
    r.style.setProperty('--cmt', m.t + 'px');
    r.style.setProperty('--cmb', m.b + 'px');
    r.style.setProperty('--cml', m.l + 'px');
    r.style.setProperty('--cmr', m.r + 'px');
    r.style.setProperty('--cmgap', PRE.gap + 'px');
    r.classList.toggle('cm-fit-width', CS.fit === 'width');
    r.classList.toggle('cm-fit-height', CS.fit === 'height');
    r.classList.toggle('cm-fit-orig', CS.fit === 'orig');
    r.classList.toggle('cm-zoom', CS.zoom > 1);
    r.style.setProperty('--cmz', String(CS.zoom));
    r.style.setProperty('--cmx', CS.panX + 'px');
    r.style.setProperty('--cmy', CS.panY + 'px');
    $('cmGlow').style.display = PRE.bg === 'black' ? 'none' : '';
  }

  function syncMode() {
    var flow = $('cmFlow'), stage = $('cmStage');
    var scroll = CS.mode === 'webtoon' || CS.mode === 'paged';
    flow.style.display = scroll ? '' : 'none';
    stage.style.display = scroll ? 'none' : 'flex';
    flow.classList.toggle('snap', CS.mode === 'paged');
  }

  function spreadPages(page) {
    if (CS.mode !== 'dual') return [page];
    if (page <= 1) return [1];
    var start = page % 2 === 0 ? page : page - 1;
    var out = [start];
    if (start + 1 <= CS.total) out.push(start + 1);
    return out;
  }

  function pageBox(page, inFlow, defer) {
    var box = el('div', 'cm-page');
    box.dataset.p = page;
    var img = el('img', 'cm-img');
    img.alt = '第 ' + page + ' 页';
    img.draggable = false;
    img.decoding = 'async';
    box.appendChild(img);
    box.appendChild(el('div', 'cm-ann'));
    if (CS.refs.has(page)) setSrc(box, page);
    else {
      img.style.display = 'none';
      var sk = el('div', 'cm-skel', '第 ' + page + ' 页');
      if (inFlow) {
        var fw = $('cmFlow') ? $('cmFlow').clientWidth : innerWidth;
        sk.style.minHeight = Math.round(fw * 1.42) + 'px';
      }
      box.insertBefore(sk, img);
      if (defer) box.dataset.defer = '1';
      else ensureRef(page).then(function () {
        if (!box.isConnected) return;
        setSrc(box, page);
      }).catch(function (e) {
        if (!box.isConnected) return;
        if (sk.parentNode) sk.remove();
        if (img.parentNode) img.remove();
        var f = el('div', 'cm-fail', '第 ' + page + ' 页载入失败<br><span style="opacity:.65">' + esc(errText(e)) + '</span><button class="cm-btn sm" data-re>重试</button>');
        box.appendChild(f);
        var rb = f.querySelector('[data-re]');
        if (rb) rb.onclick = function (ev) { ev.stopPropagation(); CS.fails.delete(page); renderAll(); };
      });
    }
    paintPageAnns(box, page);
    return box;
  }

  function setSrc(box, page) {
    var img = box.querySelector('img.cm-img');
    if (!img) return;
    img.onload = function () {
      img.classList.add('ready');
      img.style.display = '';
      var sk = box.querySelector('.cm-skel');
      if (sk && sk.parentNode) sk.remove();
      paintPageAnns(box, page);
      if (CS.mode !== 'webtoon' && page === CS.page) tint(img);
      markReady(page);
    };
    img.onerror = function () {
      img.classList.remove('ready');
      if (CS.fails.has(page)) return;
      CS.fails.add(page);
      var sk = box.querySelector('.cm-skel');
      if (sk && sk.parentNode) sk.remove();
      img.style.display = 'none';
      if (box.querySelector('.cm-fail')) return;
      var f = el('div', 'cm-fail', '第 ' + page + ' 页载入失败<button class="cm-btn sm" data-re>重试</button>');
      box.appendChild(f);
      var rb = f.querySelector('[data-re]');
      if (rb) rb.onclick = function (ev) { ev.stopPropagation(); CS.fails.delete(page); CS.refs.delete(page); renderAll(); };
    };
    img.src = imgUrl(page);
  }

  function tint(img) {
    if (PRE.bg !== 'auto') return;
    try {
      var c = document.createElement('canvas');
      c.width = 1; c.height = 1;
      var g = c.getContext('2d');
      g.drawImage(img, 0, 0, 1, 1);
      var d = g.getImageData(0, 0, 1, 1).data;
      var key = d[0] + ',' + d[1] + ',' + d[2];
      if (key === CS.tinted) return;
      CS.tinted = key;
      $('cmGlow').style.background = 'radial-gradient(62% 62% at 50% 44%, rgba(' + d[0] + ',' + d[1] + ',' + d[2] + ',.34), transparent 74%)';
    } catch (e) { }
  }

  function renderAll() {
    if (!CS.open || !CS.started) return;
    CS.preReady = false;
    if (CS.mode === 'webtoon' || CS.mode === 'paged') renderFlow();
    else renderPaged();
    paintBot();
    paintThumbs();
    schedulePreload(3000);
  }

  function renderPaged() {
    var stage = $('cmStage');
    var pages = spreadPages(CS.page);
    var frame = el('div', 'cm-frame out' + (pages.length > 1 ? ' dual' : ''));
    pages.forEach(function (p) { frame.appendChild(pageBox(p, false)); });
    stage.innerHTML = '';
    stage.appendChild(frame);
    var flow = $('cmFlow');
    if (flow) { flow.innerHTML = ''; CS.lo = 0; CS.hi = 0; }
    var dir = CS.dir === 'RTL' ? -1 : 1;
    frame.style.transform = 'translateX(' + (dir * 34) + 'px)';
    requestAnimationFrame(function () {
      if (!frame.isConnected) return;
      frame.classList.remove('out');
      frame.style.transform = '';
    });
    CS.zoom = 1; CS.panX = 0; CS.panY = 0;
    applyVars();
  }

  function renderFlow() {
    var flow = $('cmFlow');
    var anchor = CS.page;
    var from = Math.max(1, anchor - 3);
    var to = Math.min(CS.total, anchor + 8);
    CS.lo = from; CS.hi = to;
    flow.innerHTML = '';
    var st = $('cmStage');
    if (st) st.innerHTML = '';
    for (var p = from; p <= to; p++) flow.appendChild(pageBox(p, true, Math.abs(p - anchor) > 1));
    bindFlow();
    requestAnimationFrame(function () {
      var target = flow.querySelector('[data-p="' + anchor + '"]');
      if (target) flow.scrollTop = Math.max(0, target.offsetTop - (flow.clientHeight - target.clientHeight) / 2);
    });
  }

  function extend(dir) {
    var flow = $('cmFlow');
    if (!flow) return;
    if (dir > 0) {
      if (CS.hi >= CS.total) return;
      var to = Math.min(CS.total, CS.hi + 8);
      for (var p = CS.hi + 1; p <= to; p++) flow.appendChild(pageBox(p, true));
      CS.hi = to;
    } else {
      if (CS.lo <= 1) return;
      var from = Math.max(1, CS.lo - 8);
      var before = flow.scrollHeight;
      var frag = document.createDocumentFragment();
      for (var p2 = from; p2 < CS.lo; p2++) frag.appendChild(pageBox(p2, true));
      flow.insertBefore(frag, flow.firstChild);
      CS.lo = from;
      flow.scrollTop += flow.scrollHeight - before;
    }
  }

  function goto(page, silent) {
    if (!CS.open) return;
    page = clamp(Math.round(page), 1, CS.total || 1);
    if (CS.mode === 'dual' && page > 1) page = page % 2 === 0 ? page : page - 1;
    var changed = page !== CS.page;
    CS.page = page;
    CS.seen = Math.max(CS.seen, page);
    if (!silent) {
      if (CS.mode === 'webtoon' || CS.mode === 'paged') {
        var flow = $('cmFlow');
        var box = flow && flow.querySelector('[data-p="' + page + '"]');
        if (box) flow.scrollTo({ top: Math.max(0, box.offsetTop - (flow.clientHeight - box.clientHeight) / 2), behavior: CS.mode === 'paged' ? 'smooth' : 'auto' });
        else renderFlow();
      } else renderPaged();
      paintBot();
    } else paintBotLight();
    paintThumbs();
    if (changed) { queueProgress(); preload(); }
    if (!silent) bumpIdle();
  }

  function turn(delta) {
    if (!CS.open) return;
    var step = CS.mode === 'dual' ? 2 : 1;
    var d = CS.dir === 'RTL' ? -delta : delta;
    goto(CS.page + d * step);
  }

  function preload() {
    if (!CS.open || !CS.preReady) return;
    var n = PRE.preload || 0;
    if (!n || CS.mode === 'webtoon' || CS.mode === 'paged') return;
    var pages = spreadPages(CS.page);
    var list = [];
    for (var i = 1; i <= n; i++) { list.push(pages[pages.length - 1] + i); list.push(pages[0] - i); }
    list.forEach(function (p) {
      if (p < 1 || p > CS.total) return;
      ensureRef(p).then(function () {
        var im = new Image();
        im.src = imgUrl(p);
      }).catch(function () { });
    });
  }

  function schedulePreload(delay) {
    clearTimeout(CS.preTimer);
    CS.preTimer = setTimeout(function () {
      CS.preTimer = null;
      preload();
    }, delay == null ? 80 : delay);
  }

  function markReady(page) {
    if (CS.preReady) return;
    if (Math.abs(page - CS.page) > 1) return;
    CS.preReady = true;
    flushDeferred();
    schedulePreload(60);
  }

  function flushDeferred() {
    var flow = $('cmFlow');
    if (!flow) return;
    [].slice.call(flow.querySelectorAll('[data-defer]')).forEach(function (box) {
      if (!box.isConnected) return;
      delete box.dataset.defer;
      var p = +box.dataset.p;
      ensureRef(p).then(function () {
        if (!box.isConnected) return;
        setSrc(box, p);
      }).catch(function () { });
    });
  }

  function paintTop() {
    var top = $('cmTop'); if (!top) return;
    var b = CS.book || {};
    var s = b.seriesTitle || '';
    top.innerHTML =
      '<button class="cm-btn" data-a="exit" title="返回书架">' + svg('left', 16) + '</button>' +
      '<div class="cm-title"><b>' + esc(b.title || b.name || '漫画') + (PRE.imm ? '<i class="cm-imm">隐身</i>' : '') + '</b>' +
      '<span>' + esc(s) + (b.profile ? ' · ' + esc(PROFILE_CN[b.profile] || b.profile) : '') + ' · ' + num(CS.total) + ' 页</span></div>' +
      '<div class="cm-tools">' +
      '<button class="cm-btn" data-a="thumbs" title="缩略图条 T">' + svg('grid') + '</button>' +
      '<button class="cm-btn" data-a="anns" title="书签与标注 M">' + svg('list') + '</button>' +
      '<button class="cm-btn" data-a="bookmark" title="加书签 B">' + svg('bookmark') + '</button>' +
      '<button class="cm-btn" data-a="draw" title="框选标注 R">' + svg('crop') + '</button>' +
      '<button class="cm-btn" data-a="set" title="阅读设置 S">' + svg('settings') + '</button>' +
      '<button class="cm-btn" data-a="full" title="全屏 F">' + svg('full') + '</button>' +
      '</div>';
    top.querySelector('[data-a="exit"]').onclick = function () { close(); };
    top.querySelector('[data-a="thumbs"]').onclick = function () { toggleThumbs(); };
    top.querySelector('[data-a="anns"]').onclick = function () { side('anns'); };
    top.querySelector('[data-a="bookmark"]').onclick = function () { toggleBookmark(); };
    top.querySelector('[data-a="draw"]').onclick = function () { setDraw(CS.drawMode ? null : 'rect'); };
    top.querySelector('[data-a="set"]').onclick = function () { side('set'); };
    top.querySelector('[data-a="full"]').onclick = function () { fullscreen(); };
    markToolbar();
  }

  function markToolbar() {
    var t = $('cmTop'); if (!t) return;
    t.querySelector('[data-a="thumbs"]').classList.toggle('on', CS.thumbs);
    t.querySelector('[data-a="anns"]').classList.toggle('on', CS.side === 'anns');
    t.querySelector('[data-a="set"]').classList.toggle('on', CS.side === 'set');
    t.querySelector('[data-a="draw"]').classList.toggle('on', !!CS.drawMode);
  }

  function pct() { return Math.round(CS.page / (CS.total || 1) * 1000) / 10; }

  function paintBotLight() {
    var bot = $('cmBot'); if (!bot) return;
    var total = CS.total || 1, p = pct();
    var pos = bot.querySelector('[data-pos]');
    if (pos) pos.textContent = CS.page + ' / ' + num(total);
    var bar = bot.querySelector('.cm-track i');
    if (bar) bar.style.width = p + '%';
    var knob = bot.querySelector('.cm-track b');
    if (knob) knob.style.left = p + '%';
    var pc = bot.querySelector('[data-pct]');
    if (pc) pc.textContent = p + '%';
    var rest = bot.querySelector('[data-rest]');
    if (rest) rest.textContent = '剩 ' + num(Math.max(0, total - CS.page)) + ' 页';
  }

  function paintBot() {
    var bot = $('cmBot'); if (!bot) return;
    var total = CS.total || 1, p = pct();
    var marks = CS.anns.filter(function (a) { return a.marker === 'bookmark'; });
    bot.innerHTML = '<div class="cm-bar">' +
      '<span data-pos style="min-width:86px;font-variant-numeric:tabular-nums">' + CS.page + ' / ' + num(total) + '</span>' +
      '<div class="cm-track" id="cmTrack"><i style="width:' + p + '%"></i><b style="left:' + p + '%"></b>' +
      marks.map(function (a) { return '<em style="left:' + (a.page / total * 100) + '%"></em>'; }).join('') +
      '<div class="cm-tip" id="cmTip"></div></div>' +
      '<span data-pct style="min-width:48px;text-align:right;font-variant-numeric:tabular-nums">' + p + '%</span>' +
      '<span data-rest style="opacity:.5;min-width:64px;text-align:right">剩 ' + num(Math.max(0, total - CS.page)) + ' 页</span>' +
      MODES.map(function (m) {
        return '<button class="cm-btn' + (CS.mode === m[0] ? ' on' : '') + '" data-m="' + m[0] + '" title="' + m[1] + '">' + svg(m[2]) + '</button>';
      }).join('') +
      '<button class="cm-btn" data-a="quality" title="画质">' + svg('bolt') + '<span style="font-size:10.5px">' + (CS.quality ? CS.quality + 'px' : '原图') + '</span></button>' +
      '<button class="cm-btn" data-a="export" title="导出">' + svg('dl') + '</button>' +
      '<button class="cm-btn" data-a="dir" title="阅读方向">' + svg('dir') + '<span style="font-size:10.5px">' + CS.dir + '</span></button>' +
      '</div>';
    all('#cmBot [data-m]').forEach(function (b) {
      b.onclick = function () {
        CS.mode = b.dataset.m; PRE.mode = CS.mode; PRE.modeTouched = true; savePrefs();
        syncMode(); renderAll();
      };
    });
    bot.querySelector('[data-a="quality"]').onclick = cycleQuality;
    bot.querySelector('[data-a="export"]').onclick = function () { side('export'); };
    bot.querySelector('[data-a="dir"]').onclick = function () {
      if (CS.mode === 'webtoon' || CS.mode === 'paged') {
        CS.mode = 'single'; PRE.mode = 'single'; PRE.modeTouched = true;
        syncMode(); renderAll();
        toast('滚动模式没有左右方向，已切回单页', 'info');
        return;
      }
      CS.dir = CS.dir === 'LTR' ? 'RTL' : 'LTR';
      PRE.dir = CS.dir; savePrefs(); paintBot();
      toast('阅读方向：' + (CS.dir === 'RTL' ? '从右往左' : '从左往右'), 'ok');
    };
    bindTrack();
  }

  function bindTrack() {
    var tr = $('cmTrack'); if (!tr) return;
    var tip = $('cmTip');
    var seek = function (ev) {
      var r = tr.getBoundingClientRect();
      return Math.max(1, Math.round(clamp((ev.clientX - r.left) / r.width, 0, 1) * CS.total));
    };
    tr.onmousedown = function (ev) {
      ev.preventDefault();
      var last = seek(ev);
      goto(last);
      var move = function (e2) { last = seek(e2); goto(last, true); };
      var up = function () {
        document.removeEventListener('mousemove', move);
        document.removeEventListener('mouseup', up);
        goto(last);
      };
      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
    };
    tr.onmousemove = function (ev) {
      if (!tip) return;
      var page = seek(ev);
      var r = tr.getBoundingClientRect();
      tip.classList.add('on');
      tip.style.left = clamp(ev.clientX - r.left, 62, r.width - 62) + 'px';
      tip.innerHTML = '<img src="' + base() + 'api/lantai/v1/read/' + CS.bookId + '/thumb/' + page + '?w=120" alt=""><span>第 ' + page + ' 页</span>';
    };
    tr.onmouseleave = function () { if (tip) tip.classList.remove('on'); };
  }

  function cycleQuality() {
    var i = QS.findIndex(function (x) { return x[0] === CS.quality; });
    i = (i + 1) % QS.length;
    CS.quality = QS[i][0]; PRE.quality = CS.quality; savePrefs();
    CS.refs.clear(); CS.refq.clear(); CS.fails.clear();
    renderAll(); paintBot();
    toast('画质：' + QS[i][1] + (CS.quality ? '（宽 ' + CS.quality + 'px）' : '（原图直出）'), 'ok');
  }

  function toggleThumbs(force) {
    CS.thumbs = force == null ? !CS.thumbs : force;
    $('cmThumbs').classList.toggle('on', CS.thumbs);
    $('cm').classList.toggle('thumbs-open', CS.thumbs);
    var bot = $('cmBot');
    if (bot) bot.classList.toggle('off', CS.thumbs);
    markToolbar();
    if (CS.thumbs) {
      paintThumbs();
      var cur = $('cmStrip').querySelector('.cm-th.on');
      if (cur) cur.scrollIntoView({ inline: 'center', block: 'nearest' });
    }
    bumpIdle();
  }

  function paintThumbs() {
    if (!CS.thumbs) return;
    var strip = $('cmStrip'); if (!strip) return;
    var marks = {};
    CS.anns.forEach(function (a) { marks[a.page] = true; });
    if (strip.dataset.total !== String(CS.total)) {
      strip.dataset.total = String(CS.total);
      var html = '';
      for (var p = 1; p <= CS.total; p++) {
        html += '<div class="cm-th" data-p="' + p + '"><img loading="lazy" src="' + base() + 'api/lantai/v1/read/' + CS.bookId + '/thumb/' + p + '?w=120" alt=""><b>' + p + '</b></div>';
      }
      strip.innerHTML = html;
      all('#cmStrip .cm-th').forEach(function (c) { c.onclick = function () { goto(+c.dataset.p); }; });
    }
    var cur = spreadPages(CS.page);
    all('#cmStrip .cm-th').forEach(function (c) {
      var p = +c.dataset.p;
      c.classList.toggle('on', cur.indexOf(p) >= 0);
      c.classList.toggle('mark', !!marks[p]);
    });
  }

  function side(kind) {
    CS.side = CS.side === kind ? null : kind;
    var P = $('cmSide');
    $('cm').classList.toggle('side-open', !!CS.side);
    if (!CS.side) { P.classList.remove('on'); markToolbar(); return; }
    if (kind === 'set') paintSettings(P);
    else if (kind === 'anns') paintAnnSide(P);
    else if (kind === 'export') paintExport(P);
    P.classList.add('on');
    markToolbar();
    bumpIdle();
  }

  function sideHead(title) {
    return '<div class="cm-side-h"><h4>' + esc(title) + '</h4>' +
      '<div style="margin-left:auto"><button class="cm-btn sm" data-a="close">' + svg('close', 13) + '</button></div></div>';
  }
  function closeSide() {
    CS.side = null;
    $('cmSide').classList.remove('on');
    var r = $('cm');
    if (r) r.classList.remove('side-open');
    markToolbar();
  }

  function paintSettings(P) {
    var m = PRE.margins;
    P.innerHTML = sideHead('阅读设置') + '<div class="cm-side-b">' +
      '<div class="cm-sec"><div class="t">阅读模式</div><div class="cm-chips">' +
      MODES.map(function (x) { return '<span class="cm-chip' + (CS.mode === x[0] ? ' on' : '') + '" data-mode="' + x[0] + '">' + svg(x[2], 13) + x[1] + '</span>'; }).join('') + '</div></div>' +
      '<div class="cm-sec"><div class="t">页面适应</div><div class="cm-chips">' +
      FITS.map(function (x) { return '<span class="cm-chip' + (CS.fit === x[0] ? ' on' : '') + '" data-fit="' + x[0] + '">' + x[1] + '</span>'; }).join('') + '</div></div>' +
      '<div class="cm-sec"><div class="t">阅读方向</div><div class="cm-chips">' +
      [['LTR', '从左往右'], ['RTL', '从右往左']].map(function (x) { return '<span class="cm-chip' + (CS.dir === x[0] ? ' on' : '') + '" data-dir="' + x[0] + '">' + x[1] + '</span>'; }).join('') + '</div>' +
      '<div class="cm-note">' + ((CS.mode === 'webtoon' || CS.mode === 'paged') ? '当前是滚动模式，左右方向不生效；点方向会切回单页' : '方向镜像翻页热区与键盘左右键') + '</div></div>' +
      '<div class="cm-sec"><div class="t">画质</div><div class="cm-chips">' +
      QS.map(function (x) { return '<span class="cm-chip' + (CS.quality === x[0] ? ' on' : '') + '" data-q="' + x[0] + '">' + x[1] + '</span>'; }).join('') + '</div>' +
      '<div class="cm-note">原图零转码最快；高清 / 省流由服务端按宽度降采样，省流量</div></div>' +
      '<div class="cm-sec"><div class="t">边距</div><div class="cm-chips" style="margin-bottom:9px">' +
      MARGIN_PRESETS.map(function (x) { return '<span class="cm-chip' + (PRE.marginPreset === x[0] ? ' on' : '') + '" data-mp="' + x[0] + '" data-v="' + x[2] + '">' + x[1] + '</span>'; }).join('') + '</div>' +
      '<div class="cm-mg">' + mr('上', 't', m.t) + mr('下', 'b', m.b) + mr('左', 'l', m.l) + mr('右', 'r', m.r) + '</div></div>' +
      '<div class="cm-sec"><div class="t">长条页间距</div>' + rg('gap', PRE.gap, 0, 60, 1, PRE.gap + ' px') + '</div>' +
      '<div class="cm-sec"><div class="t">预加载</div><div class="cm-chips">' +
      [0, 1, 2, 3, 5].map(function (n) { return '<span class="cm-chip' + (PRE.preload === n ? ' on' : '') + '" data-pre="' + n + '">' + (n ? n + ' 页' : '关闭') + '</span>'; }).join('') + '</div>' +
      '<div class="cm-note">提前取相邻页的图片，翻页时直接出图</div></div>' +
      '<div class="cm-sec"><div class="t">背景光晕</div><div class="cm-chips">' +
      [['auto', '跟随页面'], ['black', '纯黑']].map(function (x) { return '<span class="cm-chip' + (PRE.bg === x[0] ? ' on' : '') + '" data-bg="' + x[0] + '">' + x[1] + '</span>'; }).join('') + '</div></div>' +
      '<div class="cm-sec"><div class="t">隐身阅读</div><div class="cm-chips">' +
      [[0, '关闭'], [1, '开启']].map(function (x) { return '<span class="cm-chip' + ((PRE.imm ? 1 : 0) === x[0] ? ' on' : '') + '" data-imm="' + x[0] + '">' + x[1] + '</span>'; }).join('') + '</div>' +
      '<div class="cm-note">开启后服务端不写阅读会话与进度，翻到哪页只存本标签页；标注照常保存</div></div>' +
      '<div class="cm-sec"><div class="t">快捷键</div><div class="cm-keys">' +
      '<kbd>← →</kbd><span>上一页 / 下一页</span><kbd>Space</kbd><span>下一页</span><kbd>T</kbd><span>缩略图条</span>' +
      '<kbd>M</kbd><span>标注列表</span><kbd>B</kbd><span>加书签</span><kbd>R</kbd><span>框选标注</span>' +
      '<kbd>F</kbd><span>全屏</span><kbd>+ −</kbd><span>缩放</span>' +
      '</div></div></div>';

    P.querySelector('[data-a="close"]').onclick = closeSide;
    all('#cmSide [data-mode]').forEach(function (c) {
      c.onclick = function () {
        CS.mode = c.dataset.mode; PRE.mode = CS.mode; PRE.modeTouched = true; savePrefs();
        syncMode(); renderAll(); paintSettings(P);
      };
    });
    all('#cmSide [data-fit]').forEach(function (c) {
      c.onclick = function () { CS.fit = c.dataset.fit; PRE.fit = CS.fit; savePrefs(); applyVars(); paintSettings(P); };
    });
    all('#cmSide [data-dir]').forEach(function (c) {
      c.onclick = function () {
        CS.dir = c.dataset.dir; PRE.dir = CS.dir; savePrefs();
        if (CS.mode === 'webtoon' || CS.mode === 'paged') {
          CS.mode = 'single'; PRE.mode = 'single'; PRE.modeTouched = true;
          toast('已切到单页，方向生效', 'ok');
        }
        syncMode(); renderAll(); paintBot(); paintSettings(P);
      };
    });
    all('#cmSide [data-q]').forEach(function (c) {
      c.onclick = function () {
        CS.quality = +c.dataset.q; PRE.quality = CS.quality; savePrefs();
        CS.refs.clear(); CS.refq.clear(); CS.fails.clear();
        renderAll(); paintBot(); paintSettings(P);
      };
    });
    all('#cmSide [data-mp]').forEach(function (c) {
      c.onclick = function () {
        var v = +c.dataset.v;
        PRE.margins = { t: v, b: v, l: v, r: v };
        PRE.marginPreset = c.dataset.mp; savePrefs(); applyVars(); paintSettings(P);
      };
    });
    all('#cmSide [data-mg]').forEach(function (inp) {
      inp.oninput = function () {
        PRE.margins[inp.dataset.mg] = +inp.value;
        PRE.marginPreset = 'custom';
        var lab = inp.parentNode.querySelector('span');
        if (lab) lab.textContent = inp.value + ' px';
        savePrefs(); applyVars();
      };
    });
    var gi = P.querySelector('[data-sl="gap"]');
    if (gi) gi.oninput = function () {
      PRE.gap = +gi.value; savePrefs(); applyVars();
      var lab = gi.parentNode.querySelector('span');
      if (lab) lab.textContent = gi.value + ' px';
    };
    all('#cmSide [data-pre]').forEach(function (c) {
      c.onclick = function () { PRE.preload = +c.dataset.pre; savePrefs(); preload(); paintSettings(P); };
    });
    all('#cmSide [data-bg]').forEach(function (c) {
      c.onclick = function () {
        PRE.bg = c.dataset.bg; savePrefs(); applyVars();
        if (PRE.bg === 'auto') CS.tinted = '';
        renderAll(); paintSettings(P);
      };
    });
    all('#cmSide [data-imm]').forEach(function (c) {
      c.onclick = function () {
        PRE.imm = c.dataset.imm === '1'; savePrefs();
        try {
          if (PRE.imm) sessionStorage.setItem(immKey(), String(CS.page));
          else sessionStorage.removeItem(immKey());
        } catch (e) { }
        paintTop(); paintSettings(P);
        toast(PRE.imm ? '隐身阅读已开启，本次不留记录' : '已恢复记录进度');
      };
    });
  }

  function mr(label, key, val) {
    return '<div><div style="font-size:10.5px;opacity:.55;margin-bottom:2px">' + label + '</div>' +
      '<div class="cm-range"><input type="range" data-mg="' + key + '" min="0" max="120" step="2" value="' + val + '"><span>' + val + ' px</span></div></div>';
  }
  function rg(sl, val, min, max, step, text) {
    return '<div class="cm-range"><input type="range" data-sl="' + sl + '" min="' + min + '" max="' + max + '" step="' + step + '" value="' + val + '"><span>' + text + '</span></div>';
  }

  function annList() {
    var tab = CS.annTab || 'all';
    var q = String(CS.annQ || '').trim().toLowerCase();
    return CS.anns.filter(function (a) {
      if (tab === 'bookmark' && a.marker !== 'bookmark') return false;
      if (tab === 'mark' && a.marker === 'bookmark') return false;
      if (!q) return true;
      return (String(a.note || '') + ' ' + (MARK_CN[a.marker] || '') + ' p' + a.page).toLowerCase().indexOf(q) >= 0;
    }).sort(function (a, b) { return a.page - b.page || a.at - b.at; });
  }

  function paintAnnSide(P) {
    var all = CS.anns.length;
    var bm = CS.anns.filter(function (a) { return a.marker === 'bookmark'; }).length;
    var list = annList();
    var tab = CS.annTab || 'all';
    P.innerHTML = sideHead('书签与标注') + '<div class="cm-side-b">' +
      '<div class="cm-chips" style="margin-bottom:9px">' +
      [['all', '全部 ' + all], ['bookmark', '书签 ' + bm], ['mark', '标注 ' + (all - bm)]].map(function (x) {
        return '<span class="cm-chip' + (tab === x[0] ? ' on' : '') + '" data-atab="' + x[0] + '">' + x[1] + '</span>';
      }).join('') + '</div>' +
      '<div class="cm-annq"><input id="cmAnnQ" placeholder="搜索页码或备注" value="' + esc(CS.annQ || '') + '"></div>' +
      '<button class="cm-btn cm-annadd" data-bmadd="1">' + svg('bookmark', 13) + '给第 ' + CS.page + ' 页加书签</button>' +
      (list.length ? list.map(function (a) {
        var shapes = a.shapes || [];
        var desc = a.marker === 'bookmark' ? '书签' : (shapes.length ? (shapes[0].t === 'p' ? '画笔' : '框选') + ' · ' + shapes.length + ' 处' : '标注');
        return '<div class="cm-annitem" data-id="' + a.id + '" data-page="' + a.page + '"><i class="dot" style="background:' + esc(a.color) + '"></i>' +
          '<div class="tx"><b>第 ' + a.page + ' 页 · ' + desc + '</b><span>' + (a.note ? esc(a.note) : (MARK_CN[a.marker] || '')) + '</span></div>' +
          '<span class="x" data-edit="' + a.id + '">' + svg('edit', 12) + '</span>' +
          '<span class="x" data-del="' + a.id + '">' + svg('close', 13) + '</span></div>';
      }).join('') : '<div class="cm-note">' + (all ? '没有匹配的条目' : '还没有书签和标注。按 R 框选区域标注；按 B 给当前页加书签。') + '</div>') +
      '</div>';
    P.querySelector('[data-a="close"]').onclick = closeSide;
    all('#cmSide [data-atab]').forEach(function (c) {
      c.onclick = function () { CS.annTab = c.dataset.atab; paintAnnSide(P); };
    });
    var q = $('cmAnnQ');
    if (q) q.oninput = function () {
      CS.annQ = this.value;
      clearTimeout(CS.annQT);
      CS.annQT = setTimeout(function () { paintAnnSide(P); var n = $('cmAnnQ'); if (n) { n.focus(); n.setSelectionRange(n.value.length, n.value.length); } }, 240);
    };
    var addBtn = P.querySelector('[data-bmadd]');
    if (addBtn) addBtn.onclick = function () { toggleBookmark(); };
    all('#cmSide .cm-annitem').forEach(function (item) {
      item.onclick = function (ev) {
        if (ev.target.closest('[data-del]') || ev.target.closest('[data-edit]')) return;
        goto(+item.dataset.page);
        closeSide();
      };
    });
    all('#cmSide [data-del]').forEach(function (btn) {
      btn.onclick = function (ev) { ev.stopPropagation(); delAnn(btn.dataset.del); };
    });
    all('#cmSide [data-edit]').forEach(function (btn) {
      btn.onclick = function (ev) {
        ev.stopPropagation();
        var a = null;
        CS.anns.forEach(function (x) { if (x.id === btn.dataset.edit) a = x; });
        if (a) editAnnInline(btn.closest('.cm-annitem'), a, P);
      };
    });
  }

  function editAnnInline(item, a, P) {
    var pick = a.color;
    item.innerHTML = '<div style="width:100%;display:flex;flex-direction:column;gap:7px">' +
      '<div class="cm-chips">' + COLORS.map(function (c) {
        return '<span class="cm-chip' + (a.color === c ? ' on' : '') + '" data-ec="' + c + '" style="min-width:26px;padding:0 4px"><i class="dot" style="background:' + c + ';display:inline-block;width:12px;height:12px;border-radius:50%"></i></span>';
      }).join('') + '</div>' +
      '<input id="cmAnnNote" placeholder="备注" value="' + esc(a.note || '') + '" style="width:100%;height:28px;border-radius:7px;border:1px solid var(--line);background:transparent;color:inherit;font-size:12px;padding:0 8px">' +
      '<div class="cm-chips"><span class="cm-chip" data-ecancel="1">取消</span><span class="cm-chip on" data-esave="1">保存</span></div></div>';
    item.onclick = null;
    all('#cmSide [data-ec]').forEach(function (b) {
      b.onclick = function () {
        pick = b.dataset.ec;
        all('#cmSide [data-ec]').forEach(function (o) { o.classList.toggle('on', o === b); });
      };
    });
    item.querySelector('[data-ecancel]').onclick = function () { paintAnnSide(P); };
    item.querySelector('[data-esave]').onclick = function () {
      var note = (item.querySelector('#cmAnnNote') || {}).value || '';
      req('api/lantai/v1/annotations/' + a.id, { method: 'PATCH', body: { color: pick, note: note } })
        .then(function () {
          a.color = pick; a.note = note; a.at = Date.now();
          renderAll();
          paintAnnSide(P);
          toast('已更新标注');
        }).catch(function (e) { toast('更新失败：' + errText(e), 'err'); });
    };
  }

  function paintExport(P) {
    var pages = spreadPages(CS.page);
    P.innerHTML = sideHead('导出') + '<div class="cm-side-b">' +
      '<div class="cm-sec"><div class="t">当前页</div>' +
      '<button class="cm-btn" data-exp="cur" style="width:100%;height:34px">' + svg('dl') + '下载第 ' + pages.join('、') + ' 页（原图）</button>' +
      '<div class="cm-note">直接取服务端原图，不经画布重编码，保留原始格式与画质</div></div>' +
      '<div class="cm-sec"><div class="t">页区间</div><div class="cm-export">' +
      '<div class="row"><input type="number" id="cmFrom" min="1" max="' + CS.total + '" value="' + Math.max(1, CS.page - 9) + '">' +
      '<span style="opacity:.6">至</span><input type="number" id="cmTo" min="1" max="' + CS.total + '" value="' + CS.page + '">' +
      '<span style="opacity:.5;font-size:10.5px">共 ' + num(CS.total) + ' 页</span></div>' +
      '<button class="cm-btn" data-exp="range" style="width:100%;height:34px">' + svg('dl') + '打包下载 ZIP</button>' +
      '<div class="cm-note">服务端按原图打包，单次最多 ' + MAX_ZIP + ' 页</div></div></div>' +
      '<div class="cm-sec"><div class="t">整本</div>' +
      '<button class="cm-btn" data-exp="all" style="width:100%;height:34px">' + svg('dl') + '整本打包（分批）</button>' +
      '<div class="cm-note">按 ' + MAX_ZIP + ' 页一批连续下载，浏览器可能提示允许多文件下载</div></div></div>';
    P.querySelector('[data-a="close"]').onclick = closeSide;
    P.querySelector('[data-exp="cur"]').onclick = function () { downloadPages(pages, pages[0], pages[pages.length - 1]); };
    P.querySelector('[data-exp="range"]').onclick = function () {
      var a = clamp(+$('cmFrom').value || 1, 1, CS.total);
      var b = clamp(+$('cmTo').value || a, a, CS.total);
      if (b - a + 1 > MAX_ZIP) return toast('一次最多 ' + MAX_ZIP + ' 页，请缩小范围', 'err');
      exportZip(a, b);
    };
    P.querySelector('[data-exp="all"]').onclick = function () { exportWhole(); };
  }

  function bookName() {
    var b = CS.book || {};
    return String(b.title || b.name || 'comic').replace(/[\\/:*?"<>|]/g, '_');
  }

  function downloadPages(pages, a, b) {
    if (pages.length > 1) return exportZip(a, b);
    var p = pages[0];
    ensureRef(p).then(function () {
      var link = document.createElement('a');
      link.href = imgUrl(p, 0);
      link.download = bookName() + '-p' + p;
      document.body.appendChild(link); link.click(); link.remove();
      toast('开始下载第 ' + p + ' 页', 'ok');
    }).catch(function (e) { toast('导出失败：' + errText(e), 'err'); });
  }

  function exportZip(a, b) {
    var link = document.createElement('a');
    link.href = base() + 'api/lantai/v1/read/' + CS.bookId + '/export?from=' + a + '&to=' + b;
    link.download = bookName() + '-' + a + '-' + b + '.zip';
    document.body.appendChild(link); link.click(); link.remove();
    toast('正在打包第 ' + a + '–' + b + ' 页', 'ok');
  }

  function exportWhole() {
    var i = 1;
    var step = function () {
      var to = Math.min(CS.total, i + MAX_ZIP - 1);
      exportZip(i, to);
      i = to + 1;
      if (i <= CS.total) setTimeout(step, 1500);
    };
    step();
  }

  function bindFlow() {
    var flow = $('cmFlow');
    if (!flow || flow.dataset.bound === '1') return;
    flow.dataset.bound = '1';
    var last = 0;
    flow.addEventListener('scroll', function () {
      var now = Date.now();
      if (now - last < 80) return;
      last = now;
      if (flow.scrollTop + flow.clientHeight > flow.scrollHeight - 1400) extend(1);
      if (flow.scrollTop < 1600) extend(-1);
      syncCurrent(flow);
    }, { passive: true });
  }

  function syncCurrent(flow) {
    var boxes = [].slice.call(flow.children);
    if (!boxes.length) return;
    var mid = flow.scrollTop + flow.clientHeight / 2;
    var best = boxes.reduce(function (a, c) {
      return Math.abs(c.offsetTop + c.clientHeight / 2 - mid) < Math.abs(a.offsetTop + a.clientHeight / 2 - mid) ? c : a;
    }, boxes[0]);
    var p = +best.dataset.p;
    if (!p || p === CS.page) return;
    CS.page = p;
    CS.seen = Math.max(CS.seen, p);
    paintBotLight();
    paintThumbs();
    queueProgress();
  }

  function onDown(ev) {
    if (!CS.open) return;
    if (ev.target.closest('.cm-top,.cm-bot,.cm-side,.cm-thumbs,.cm-float,.cm-btn')) return;
    if (CS.drawMode) return startDraw(ev);
    CS.ptrs.set(ev.pointerId, { x: ev.clientX, y: ev.clientY });
    if (CS.ptrs.size === 2) {
      var p = Array.from(CS.ptrs.values());
      CS.pinch = { d: Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y), z: CS.zoom };
      CS.drag = null;
      clearTimeout(CS.idle);
      return;
    }
    CS.drag = { x: ev.clientX, y: ev.clientY, sx: ev.clientX, sy: ev.clientY, t: Date.now(), on: false, px: CS.panX, py: CS.panY };
    clearTimeout(CS.idle);
    CS.idle = setTimeout(function () {
      if (!CS.drag) return;
      CS.drag.on = true;
      var f = currentFrame();
      if (f) f.classList.add('drag');
    }, 220);
  }

  function onMove(ev) {
    if (!CS.open) return;
    if (CS.ptrs.has(ev.pointerId)) CS.ptrs.set(ev.pointerId, { x: ev.clientX, y: ev.clientY });
    if (CS.pinch && CS.ptrs.size === 2) {
      var arr = Array.from(CS.ptrs.values());
      var d = Math.hypot(arr[0].x - arr[1].x, arr[0].y - arr[1].y);
      CS.zoom = clamp(CS.pinch.z * (d / CS.pinch.d), 1, 4);
      if (CS.zoom <= 1.02) { CS.zoom = 1; CS.panX = 0; CS.panY = 0; }
      applyVars();
      return;
    }
    var dg = CS.drag;
    if (!dg) return;
    var dx = ev.clientX - dg.sx, dy = ev.clientY - dg.sy;
    if (!dg.on) {
      if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
        if (Date.now() - dg.t < 220) { CS.drag = null; return; }
        dg.on = true;
      } else return;
    }
    var f = currentFrame();
    if (!f) return;
    if (CS.zoom > 1) {
      CS.panX = dg.px + dx; CS.panY = dg.py + dy;
      applyVars();
      return;
    }
    f.style.transform = 'translateX(' + clamp(dx, -innerWidth * 0.42, innerWidth * 0.42) + 'px)';
    f.style.opacity = String(1 - Math.min(0.35, Math.abs(dx) / (innerWidth * 1.6)));
  }

  function onUp(ev) {
    if (!CS.open) return;
    CS.ptrs.delete(ev.pointerId);
    if (CS.pinch) {
      if (CS.ptrs.size < 2) CS.pinch = null;
      CS.drag = null;
      return;
    }
    clearTimeout(CS.idle);
    var dg = CS.drag;
    CS.drag = null;
    var f = currentFrame();
    if (!dg) return;
    if (f) {
      f.classList.remove('drag');
      f.style.transform = '';
      f.style.opacity = '';
    }
    var dx = ev.clientX - dg.sx, dy = ev.clientY - dg.sy;
    if (CS.zoom > 1) return;
    if (dg.on) {
      var th = Math.min(160, innerWidth * 0.18);
      if (Math.abs(dx) > th) return turn(dx < 0 ? 1 : -1);
      return;
    }
    if (Math.abs(dx) < 9 && Math.abs(dy) < 9) tap(ev.clientX);
  }

  function tap(x) {
    if (CS.mode === 'webtoon' || CS.mode === 'paged') return toggleToolbar();
    if (x < innerWidth * 0.22) return turn(-1);
    if (x > innerWidth * 0.78) return turn(1);
    toggleToolbar();
  }

  function toggleToolbar(force) {
    var top = $('cmTop'), bot = $('cmBot');
    if (!top || !bot) return;
    var hidden = top.classList.contains('off');
    var show = force == null ? hidden : force;
    top.classList.toggle('off', !show);
    bot.classList.toggle('off', !show);
    if (!show) { toggleThumbs(false); if (CS.side) closeSide(); }
    bumpIdle();
  }

  function bumpIdle() {
    clearTimeout(CS.offTimer);
    CS.offTimer = setTimeout(function () {
      if (!CS.open || CS.thumbs || CS.side) return;
      var top = $('cmTop'), bot = $('cmBot');
      if (top) top.classList.add('off');
      if (bot) bot.classList.add('off');
    }, 3200);
  }

  function currentFrame() {
    var st = $('cmStage');
    return st ? st.querySelector('.cm-frame') : null;
  }

  function onWheel(ev) {
    if (!CS.open) return;
    if (ev.target.closest('.cm-top,.cm-bot,.cm-side,.cm-thumbs')) return;
    if (ev.ctrlKey) {
      ev.preventDefault();
      CS.zoom = clamp(CS.zoom - ev.deltaY * 0.004, 1, 4);
      if (CS.zoom <= 1.02) { CS.zoom = 1; CS.panX = 0; CS.panY = 0; }
      applyVars();
      return;
    }
    if (CS.mode === 'webtoon' || CS.mode === 'paged') return;
    ev.preventDefault();
    if (CS.zoom > 1) return;
    var now = Date.now();
    if (now - CS.wheelAt < 280 || Math.abs(ev.deltaY) < 6) return;
    CS.wheelAt = now;
    turn(ev.deltaY > 0 ? 1 : -1);
  }

  function setDraw(mode) {
    CS.drawMode = mode;
    all('#cm .cm-ann[data-draw]').forEach(function (n) { n.remove(); });
    if (mode) hint(mode === 'brush' ? '自由画笔：在页面上拖动画线，松手后选颜色保存' : '框选：在页面上拖出矩形，松手后选颜色保存');
    else hint('');
    markToolbar();
  }

  function pageAt(x, y) {
    var list = document.elementsFromPoint(x, y);
    for (var i = 0; i < list.length; i++) {
      var c = list[i].closest ? list[i].closest('.cm-page') : null;
      if (c) return c;
    }
    return null;
  }

  function startDraw(ev) {
    var box = pageAt(ev.clientX, ev.clientY);
    if (!box) return;
    var img = box.querySelector('img.cm-img');
    if (!img || !img.complete || !img.naturalWidth) return toast('等图片载入完成再框选', 'info');
    var r = img.getBoundingClientRect();
    var layer = el('div', 'cm-ann');
    layer.dataset.draw = '1';
    box.appendChild(layer);
    var x = clamp((ev.clientX - r.left) / r.width, 0, 1);
    var y = clamp((ev.clientY - r.top) / r.height, 0, 1);
    CS.drawing = { page: +box.dataset.p, box: box, img: img, rect: r, pts: [[x, y]], x0: x, y0: y, x1: x, y1: y };
    drawPreview(layer, CS.drawing);
    var move = function (e2) {
      var d = CS.drawing; if (!d) return;
      var x2 = clamp((e2.clientX - d.rect.left) / d.rect.width, 0, 1);
      var y2 = clamp((e2.clientY - d.rect.top) / d.rect.height, 0, 1);
      d.x1 = x2; d.y1 = y2;
      if (CS.drawMode === 'brush') d.pts.push([x2, y2]);
      drawPreview(layer, d);
    };
    var up = function () {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      var d = CS.drawing; if (!d) return;
      drawPreview(layer, d);
      showFloat(d, layer);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  function drawPreview(layer, d) {
    var w = d.img.naturalWidth || d.rect.width;
    var h = d.img.naturalHeight || d.rect.height;
    var shape;
    if (CS.drawMode === 'brush') {
      shape = '<polyline class="sh p" points="' + d.pts.map(function (p) { return (p[0] * w).toFixed(1) + ',' + (p[1] * h).toFixed(1); }).join(' ') + '" stroke="' + CS.drawColor + '"/>';
    } else {
      var x = Math.min(d.x0, d.x1) * w, y = Math.min(d.y0, d.y1) * h;
      var ww = Math.abs(d.x1 - d.x0) * w, hh = Math.abs(d.y1 - d.y0) * h;
      shape = '<rect class="sh r" x="' + x + '" y="' + y + '" width="' + ww + '" height="' + hh + '" stroke="' + CS.drawColor + '" fill="' + CS.drawColor + '22"/>';
    }
    layer.innerHTML = '<svg viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none">' + shape + '</svg>';
  }

  function showFloat(d, layer) {
    var fl = $('cmFloat');
    var r = d.rect;
    fl.innerHTML = COLORS.map(function (c) {
      return '<i class="cm-sw' + (c === CS.drawColor ? ' on' : '') + '" data-c="' + c + '" style="background:' + c + '"></i>';
    }).join('') +
      '<button class="cm-btn sm" data-ok title="保存">' + svg('check', 13) + '</button>' +
      '<button class="cm-btn sm" data-no title="取消">' + svg('close', 13) + '</button>';
    fl.style.left = clamp(r.left + r.width / 2 - 118, 8, innerWidth - 250) + 'px';
    fl.style.top = Math.max(58, r.top + 10) + 'px';
    fl.classList.add('on');
    all('#cmFloat [data-c]').forEach(function (s) {
      s.onclick = function () { CS.drawColor = s.dataset.c; drawPreview(layer, d); showFloat(d, layer); };
    });
    fl.querySelector('[data-ok]').onclick = function () { fl.classList.remove('on'); saveShape(d, layer); };
    fl.querySelector('[data-no]').onclick = function () {
      fl.classList.remove('on');
      layer.remove();
      CS.drawing = null;
      setDraw(null);
    };
  }

  function round3(v) { return Math.round(v * 1000) / 1000; }

  function saveShape(d, layer) {
    var shapes = [];
    if (CS.drawMode === 'brush') {
      if (d.pts.length < 3) { layer.remove(); CS.drawing = null; return setDraw(null); }
      shapes.push({ t: 'p', c: CS.drawColor, pts: d.pts.map(function (p) { return [round3(p[0]), round3(p[1])]; }) });
    } else {
      var x = Math.min(d.x0, d.x1), y = Math.min(d.y0, d.y1);
      var w = Math.abs(d.x1 - d.x0), h = Math.abs(d.y1 - d.y0);
      if (w < 0.008 || h < 0.008) { layer.remove(); CS.drawing = null; return setDraw(null); }
      shapes.push({ t: 'r', c: CS.drawColor, x: round3(x), y: round3(y), w: round3(w), h: round3(h) });
    }
    var page = d.page;
    postAnn({ page: page, color: CS.drawColor, marker: 'COMMENT', note: '', shapes: shapes }).then(function () {
      CS.drawing = null;
      setDraw(null);
      renderAll();
      toast('标注已保存 · 第 ' + page + ' 页', 'ok');
    }).catch(function (e) { toast('标注保存失败：' + errText(e), 'err'); });
  }

  function postAnn(o) {
    return req('api/lantai/v1/annotations', {
      method: 'POST',
      body: {
        bookId: CS.bookId,
        href: '#page=' + o.page,
        progression: CS.total ? o.page / CS.total : 0,
        textBefore: '', textHighlight: '', textAfter: '',
        color: o.color, marker: o.marker, note: o.note || '',
        selection: o.shapes ? JSON.stringify(o.shapes) : null
      }
    }).then(function (dto) {
      CS.anns.push(mapAnn(dto));
      return dto;
    });
  }

  function mapAnn(dto) {
    var shapes = [];
    if (dto.selection) { try { shapes = JSON.parse(dto.selection) || []; } catch (e) { shapes = []; } }
    var page = 0;
    var m = /#page=(\d+)/.exec(dto.href || '');
    if (m) page = +m[1];
    else if (dto.progression && CS.total) page = clamp(Math.round(dto.progression * CS.total), 1, CS.total);
    return {
      id: dto.id, page: page, href: dto.href, color: dto.color || '#C9A24B',
      marker: String(dto.marker || 'HIGHLIGHT').toLowerCase(), note: dto.note || '',
      shapes: shapes, at: new Date(dto.createdDate || dto.lastModifiedDate || Date.now()).getTime()
    };
  }

  function delAnn(id) {
    req('api/lantai/v1/annotations/' + id, { method: 'DELETE' }).then(function () {
      CS.anns = CS.anns.filter(function (a) { return a.id !== id; });
      renderAll();
      if (CS.side === 'anns') paintAnnSide($('cmSide'));
      toast('标注已删除', 'ok');
    }).catch(function (e) { toast('删除失败：' + errText(e), 'err'); });
  }

  function toggleBookmark() {
    var exists = CS.anns.filter(function (a) { return a.marker === 'bookmark' && a.page === CS.page; })[0];
    if (exists) return delAnn(exists.id);
    postAnn({ page: CS.page, color: COLORS[0], marker: 'BOOKMARK', note: '', shapes: null })
      .then(function () { renderAll(); toast('已在第 ' + CS.page + ' 页加书签', 'ok'); })
      .catch(function (e) { toast('书签保存失败：' + errText(e), 'err'); });
  }

  function paintPageAnns(box, page) {
    var layer = box.querySelector('.cm-ann');
    if (!layer || layer.dataset.draw === '1') return;
    var list = CS.anns.filter(function (a) { return a.page === page && a.shapes && a.shapes.length; });
    if (!list.length) { layer.innerHTML = ''; return; }
    var img = box.querySelector('img.cm-img');
    if (!img) return;
    if (!img.complete || !img.naturalWidth) {
      img.addEventListener('load', function once() { img.removeEventListener('load', once); paintPageAnns(box, page); });
      return;
    }
    var w = img.naturalWidth, h = img.naturalHeight;
    var shapes = '';
    list.forEach(function (a) {
      a.shapes.forEach(function (s) {
        if (s.t === 'p') {
          shapes += '<polyline class="sh p" points="' + (s.pts || []).map(function (p) { return (p[0] * w).toFixed(1) + ',' + (p[1] * h).toFixed(1); }).join(' ') + '" stroke="' + esc(s.c) + '"/>';
        } else {
          shapes += '<rect class="sh r" x="' + (s.x * w) + '" y="' + (s.y * h) + '" width="' + (s.w * w) + '" height="' + (s.h * h) + '" stroke="' + esc(s.c) + '" fill="' + esc(s.c) + '22"/>';
        }
      });
    });
    layer.innerHTML = '<svg viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none">' + shapes + '</svg>';
  }

  function hint(text) {
    var h = $('cmHint'); if (!h) return;
    if (!text) { h.classList.remove('on'); return; }
    h.textContent = text;
    h.classList.add('on');
    clearTimeout(h._t);
    h._t = setTimeout(function () { h.classList.remove('on'); }, 3600);
  }

  function startBeat() {
    clearInterval(CS.hbTimer);
    CS.hbTimer = setInterval(function () {
      if (!CS.open) return;
      CS.beats++;
      CS.session.dur += 30000;
      CS.session.pages = Math.max(CS.session.pages, CS.seen);
      if (!PRE.imm) {
        pushSession(CS.session);
        req('api/lantai/v1/reading/heartbeat', {
          method: 'POST',
          body: { bookId: CS.bookId, page: CS.page, deviceId: 'webui', deviceName: '网页端', client: 'lantai-webui' }
        }).catch(function () { });
      }
    }, 30000);
  }

  function queueProgress() {
    clearTimeout(CS.progTimer);
    CS.progTimer = setTimeout(sendProgress, 900);
  }

  function sendProgress() {
    if (!CS.open) return;
    var total = CS.total || 1, p = CS.page;
    if (PRE.imm) {
      try { sessionStorage.setItem(immKey(), String(p)); } catch (e) { }
      return;
    }
    if (typeof PROGRESS !== 'undefined') {
      PROGRESS[CS.bookId] = { page: p, pages: total, completed: p >= total, device: 'webui', at: Date.now(), locator: { href: '#page=' + p, position: p, totalProgression: p / total } };
    }
    req('api/v1/books/' + CS.bookId + '/progression', {
      method: 'PUT',
      body: {
        modified: new Date().toISOString(),
        device: { id: 'webui', name: '网页端' },
        locator: { href: '#page=' + p, type: 'image/jpeg', locations: { position: p, progression: p / total, totalProgression: p / total } }
      }
    }).catch(function () { });
  }

  var wake = null;
  function wakeUp() {
    if (!navigator.wakeLock || !navigator.wakeLock.request) {
      hint('当前环境不支持屏幕常亮（HTTPS 或 localhost 才可用）');
      return;
    }
    var ask = function () {
      if (!CS.open || wake) return;
      navigator.wakeLock.request('screen').then(function (s) {
        wake = s;
        s.addEventListener('release', function () { wake = null; });
      }).catch(function () { });
    };
    ask();
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible' && CS.open && !wake) ask();
    });
  }
  function releaseWake() {
    if (!wake) return;
    try { wake.release(); } catch (e) { }
    wake = null;
  }

  function fullscreen() {
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(function () { });
      return;
    }
    var r = $('cm');
    (r || document.documentElement).requestFullscreen().catch(function () {
      toast('浏览器拒绝了全屏请求', 'err');
    });
  }

  window.addEventListener('offline', function () {
    var n = $('cmOffline');
    if (n && CS.open) n.classList.add('on');
  });
  window.addEventListener('online', function () {
    var n = $('cmOffline');
    if (n) n.classList.remove('on');
  });
  document.addEventListener('fullscreenchange', function () {
    var t = $('cmTop');
    if (!t) return;
    var b = t.querySelector('[data-a="full"]');
    if (b) b.classList.toggle('on', !!document.fullscreenElement);
  });

  document.addEventListener('keydown', function (ev) {
    if (!CS.open) return;
    if (ev.target && /INPUT|TEXTAREA|SELECT/.test(ev.target.tagName)) return;
    var k = ev.key;
    if (k === 'Escape') {
      if ($('cmFloat').classList.contains('on')) { $('cmFloat').classList.remove('on'); setDraw(null); return; }
      if (CS.side) return closeSide();
      if (CS.drawMode) return setDraw(null);
      if (CS.thumbs) return toggleThumbs(false);
      if (CS.zoom > 1) { CS.zoom = 1; CS.panX = 0; CS.panY = 0; return applyVars(); }
      return close();
    }
    if (k === 'ArrowRight' || k === 'PageDown') { ev.preventDefault(); return turn(1); }
    if (k === 'ArrowLeft' || k === 'PageUp') { ev.preventDefault(); return turn(-1); }
    if (k === ' ') { ev.preventDefault(); return turn(ev.shiftKey ? -1 : 1); }
    if (k === 'Home') { ev.preventDefault(); return goto(1); }
    if (k === 'End') { ev.preventDefault(); return goto(CS.total); }
    if (k === 't' || k === 'T') return toggleThumbs();
    if (k === 'm' || k === 'M') return side('anns');
    if (k === 's' || k === 'S') return side('set');
    if (k === 'b' || k === 'B') return toggleBookmark();
    if (k === 'r' || k === 'R') return setDraw(CS.drawMode ? null : 'rect');
    if (k === 'f' || k === 'F') return fullscreen();
    if (k === '+' || k === '=') { CS.zoom = clamp(CS.zoom + 0.25, 1, 4); return applyVars(); }
    if (k === '-') { CS.zoom = clamp(CS.zoom - 0.25, 1, 4); if (CS.zoom <= 1.02) CS.zoom = 1; return applyVars(); }
  });

  window.ComicReader = { open: open, close: close, isOpen: isOpen, goto: goto, prefs: PRE, state: CS };
})();
