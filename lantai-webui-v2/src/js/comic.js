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
    lo: 0, hi: 0, flowObserver: null, scrollTimer: null, tinted: ''
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
    return base() + 'api/lantai/v1/read/' + CS.bookId + '/img/' + encodeURIComponent(ref) + '?w=' + (w == null ? imgW() : w) + '&fmt=orig';
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
    clearTimeout(CS.progTimer); clearTimeout(CS.offTimer); clearTimeout(CS.idle);
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

  function pageBox(page, inFlow) {
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
      ensureRef(page).then(function () {
        if (!box.isConnected) return;
        setSrc(box, page);
        preload();
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
    if (CS.mode === 'webtoon' || CS.mode === 'paged') renderFlow();
    else renderPaged();
    paintBot();
    paintThumbs();
    preload();
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
    for (var p = from; p <= to; p++) flow.appendChild(pageBox(p, true));
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
    var n = PRE.preload || 0;
    if (!n || CS.mode === 'webtoon' || CS.mode === 'paged') return;
    var pages = spreadPages(CS.page);
    var list = [];
    for (var i = 1; i <= n; i++) { list.push(pages[pages.length - 1] + i); list.push(pages[0] - i); }
    list.forEach(function (p) {
      if (p < 1 || p > CS.total) return;
      ensureRef(p).then(function (ref) {
        var im = new Image();
        im.src = base() + 'api/lantai/v1/read/' + CS.bookId + '/img/' + encodeURIComponent(ref) + '?w=' + imgW() + '&fmt=orig';
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
