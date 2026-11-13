(function () {
  'use strict';

  var L = window.LantaiI18n || { t: function (k) { return k; } };
  function T(k, vars) {
    return L && L.t ? L.t(k, vars) : k;
  }

  var MODES = [['single', 'rd.mode.single', 'single'], ['dual', 'rd.mode.dual', 'double'], ['webtoon', 'rd.mode.webtoon', 'flow'], ['paged', 'rd.mode.paged', 'snap']];
  var FITS = [['contain', 'rd.fit.contain'], ['width', 'rd.fit.width'], ['height', 'rd.fit.height'], ['orig', 'rd.fit.orig']];
  var QS = [[0, 'rd.q.orig'], [1600, 'rd.q.hd'], [1080, 'rd.q.lite']];
  var MARGIN_PRESETS = [['none', 'rd.mp.none', 0], ['narrow', 'rd.mp.narrow', 16], ['std', 'rd.mp.std', 32], ['wide', 'rd.mp.wide', 72]];
  var COLORS = ['#C9A24B', '#B0402C', '#46523F', '#2F5D8A', '#7B4F6B', '#E3C377'];
  var MARKS = { highlight: 'rd.marker.highlight', underline: 'rd.marker.underline', bookmark: 'rd.marker.bookmark', comment: 'rd.marker.comment' };
  var MAX_ZIP = 300;
  function mk(marker) { return MARKS[marker] ? T(MARKS[marker]) : String(marker || ''); }

  var PRE = { mode: 'single', dir: 'LTR', fit: 'contain', quality: 0, preload: 2, gap: 8, bg: 'auto', imm: false, margins: { t: 0, b: 0, l: 0, r: 0 }, marginPreset: 'none' };
  try {
    var saved = JSON.parse(localStorage.getItem('lantai-comic') || '{}');
    Object.assign(PRE, saved);
    PRE.margins = Object.assign({ t: 0, b: 0, l: 0, r: 0 }, saved.margins || {});
  } catch (e) { }
  if (PRE.dir === 'TTB') PRE.dir = 'LTR';

  var CS = {
    open: false, bookId: '', book: null, total: 0, page: 1, seen: 1, wantPage: 0,
    refs: new Map(), refq: new Map(), fails: new Set(), preloaded: new Set(),
    mode: PRE.mode, dir: PRE.dir, fit: PRE.fit, quality: PRE.quality,
    anns: [], drawMode: null, drawColor: COLORS[0], drawing: null, annTab: 'all', annQ: '', annQT: 0,
    thumbs: false, side: null, zoom: 1, panX: 0, panY: 0,
    drag: null, ptrs: new Map(), pinch: null,
    wheelAt: 0, offTimer: null, idle: null,
    session: null, beats: 0, hbTimer: null, progTimer: null, started: false,
    lo: 0, hi: 0, flowObserver: null, scrollTimer: null, tinted: '',
    preTimer: null, preReady: false, trackDrag: false, tipAt: 0, ver: 0,
    barHover: false, thBusy: false, thRush: false, thLag: 0, thVel: 0,
    thLastX: 0, thLastT: 0, thIdle: null, thDown: null, thMoved: 0, menu: null,
    guideStep: 0, guideOpen: false, gtSettle: null,
    hopBusy: false, hopPrev: false, atEnd: false
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
    if (!e) return T('rd.err.request');
    if (e.status === 403) return T('rd.err.noPerm');
    if (e.status === 404) return T('rd.err.notFound');
    return e.message ? String(e.message).slice(0, 120) : 'HTTP ' + (e.status || 'err');
  }

  function b64url(s) {
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function verQ() { return CS.ver ? '&v=' + CS.ver : ''; }
  function imgUrl(page, w) {
    var ref = CS.refs.get(page);
    if (!ref) return '';
    var ww = w == null ? imgW() : w;
    return base() + 'api/lantai/v1/read/' + CS.bookId + '/img/' + encodeURIComponent(ref) + '?w=' + ww + '&fmt=' + (ww > 0 ? 'jpeg' : 'orig') + verQ();
  }
  function localRef(page) {
    var profile = (CS.book && CS.book.profile) || '';
    if (profile !== 'DIVINA' && profile !== 'PDF') return '';
    return b64url('p:' + page);
  }
  function ensureRef(page) {
    if (CS.refs.has(page)) return Promise.resolve(CS.refs.get(page));
    var local = localRef(page);
    if (local) { CS.refs.set(page, local); return Promise.resolve(local); }
    if (CS.refq.has(page)) return CS.refq.get(page);
    var p = req('api/lantai/v1/read/' + CS.bookId + '/chapter/' + page + '?images=url&w=0')
      .then(function (dto) {
        var ref = dto && dto.images && dto.images[0] && dto.images[0].ref;
        if (!ref) throw new Error(T('rd.err.noImage'));
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
      '<div class="cm-menu" id="cmMenu"></div>' +
      '<div class="cm-guide" id="cmGuide"></div>' +
      '<div class="cm-offline" id="cmOffline">' + T('rd.offline') + '<br><span style="color:rgba(233,228,216,.6);font-size:11px">' + T('rd.offlineNote') + '</span></div>';
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
    ['cmTop', 'cmBot', 'cmSide', 'cmThumbs', 'cmMenu'].forEach(function (id) {
      var n = $(id); if (!n) return;
      n.addEventListener('pointerenter', function () { CS.barHover = true; clearTimeout(CS.offTimer); });
      n.addEventListener('pointerleave', function () { CS.barHover = false; bumpIdle(); });
      n.addEventListener('pointerdown', function () { bumpIdle(); });
    });
    wireStrip($('cmStrip'));
    return r;
  }

  function open(bookId, opts) {
    opts = opts || {};
    if (!rt()) return toast(T('rd.err.backend'), 'err');
    if (CS.open && CS.bookId === bookId) {
      if (opts.page) goto(opts.page);
      return;
    }
    if (CS.open) close(false);
    var b = book(bookId);
    if (!b) return toast(T('rd.err.notInLib'), 'err');
    if (b.status === 'UNSUPPORTED') return toast(T('rd.err.unsupported'), 'err');

    mount();
    CS.open = true; CS.bookId = bookId; CS.book = b;
    if (opts.imm) { CS.immOnce = !PRE.imm; PRE.imm = true; }
    CS.total = b.pages > 0 ? b.pages : 0;
    CS.wantPage = Math.max(0, opts.page || immResume(bookId) || 0);
    CS.page = CS.wantPage || 1; CS.seen = CS.page;
    CS.refs.clear(); CS.refq.clear(); CS.fails.clear(); CS.preloaded.clear();
    CS.anns = []; CS.zoom = 1; CS.panX = 0; CS.panY = 0; CS.tinted = '';
    CS.mode = PRE.mode; CS.dir = PRE.dir; CS.fit = PRE.fit; CS.quality = PRE.quality;
    CS.beats = 0; CS.started = false; CS.ptrs.clear(); CS.pinch = null; CS.drag = null;
    CS.preReady = false; clearTimeout(CS.preTimer); CS.preTimer = null;
    CS.trackDrag = false; CS.tipAt = 0; CS.ver = 0; CS.barHover = false; CS.thumbs = false;
    CS.hopBusy = false; CS.hopPrev = !!opts.atEnd;
    CS.menu = null; CS.guideOpen = false; CS.guideStep = 0;
    resetThumbs();
    CS.session = {
      id: (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : 'cm_' + bookId + '_' + Date.now(),
      book: bookId, dur: 0, pages: CS.page, dev: 'webui', devName: L.t('网页端'), start: Date.now(), end: Date.now(), offline: false
    };

    $('cm').classList.add('on');
    applyVars();
    paintTop(); paintBot(); syncMode();
    $('cmFlow').style.display = 'none';
    $('cmStage').style.display = 'flex';
    $('cmStage').innerHTML = '<div class="cm-skel">' + T('rd.loading') + '</div>';
    if (location.hash !== '#/read/' + bookId) history.pushState(null, '', '#/read/' + bookId);
    var s = series(b.s);
    document.title = (s ? s.t : b.name) + ' · ' + T('rd.readingTitle');

    load();
    startBeat();
    wakeUp();
    maybeGuide();
  }

  function load() {
    var url = 'api/lantai/v1/read/' + CS.bookId + '/bootstrap?images=url&w=0';
    if (CS.wantPage) url += '&chapter=' + CS.wantPage;
    req(url).then(function (dto) {
      if (!CS.open) return;
      CS.total = dto.total || CS.total;
      CS.book = Object.assign({}, CS.book, { title: dto.book.title, name: dto.book.title, seriesTitle: dto.book.seriesTitle, profile: dto.book.profile });
      CS.ver = dto.book.modified || 0;
      var rd = dto.book.readingDirection;
      if (rd === 'RTL') CS.dir = 'RTL';
      else if (rd !== 'TTB' && rd !== 'WEBTOON') CS.dir = 'LTR';
      if (rd === 'TTB' || rd === 'WEBTOON') { if (!PRE.modeTouched) { PRE.mode = 'webtoon'; CS.mode = 'webtoon'; } }
      if (CS.dir === 'TTB') { CS.dir = 'LTR'; PRE.dir = 'LTR'; }
      var start = CS.wantPage || (dto.progress && dto.progress.chapter) || 1;
      CS.page = CS.hopPrev ? (CS.total || 1) : clamp(start, 1, CS.total || 1);
      CS.hopPrev = false;
      CS.seen = Math.max(CS.seen, CS.page);
      if (dto.first && dto.first.images && dto.first.images[0]) CS.refs.set(dto.first.chapter, dto.first.images[0].ref);
      CS.started = true;
      CS.anns = (dto.annotations || []).map(mapAnn).filter(function (a) { return a.page > 0; });
      applyVars(); syncMode(); paintTop(); paintBot();
      renderAll();
      sendProgress();
      hint(T('rd.hint.basics'));
      bumpIdle();
    }).catch(function (e) {
      if (!CS.open) return;
      $('cmStage').innerHTML = '<div class="cm-fail">' + T('rd.err.openFail') + '<br><span style="opacity:.7">' + esc(errText(e)) + '</span>' +
        '<button class="cm-btn" data-retry>' + T('rd.retry') + '</button></div>';
      var rb = $('cmStage').querySelector('[data-retry]');
      if (rb) rb.onclick = function () { $('cmStage').innerHTML = '<div class="cm-skel">' + T('rd.loading') + '</div>'; load(); };
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
    stopThumbWatch();
    CS.barHover = false;
    releaseWake();
    var r = $('cm');
    if (r) { r.classList.remove('on'); r.remove(); }
    if (CS.session && settle !== false) {
      CS.session.end = Date.now();
      if (PRE.imm) {
        toast(T('rd.toast.imm'), 'ok');
      } else {
        pushSession(CS.session);
        req('api/lantai/v1/reading/stop', { method: 'POST', body: { bookId: id, deviceId: 'webui' } }).catch(function () { });
        toast(T('rd.toast.closed', { dur: dur(CS.session.dur), page: CS.page }), 'ok');
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
    img.alt = T('rd.pageN', { n: page });
    img.draggable = false;
    img.decoding = 'async';
    box.appendChild(img);
    box.appendChild(el('div', 'cm-ann'));
    if (!CS.refs.has(page)) {
      var local = localRef(page);
      if (local) CS.refs.set(page, local);
    }
    if (CS.refs.has(page)) setSrc(box, page);
    else {
      img.style.display = 'none';
      var sk = el('div', 'cm-skel', T('rd.pageN', { n: page }));
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
        var f = el('div', 'cm-fail', T('rd.err.pageFail', { n: page }) + '<br><span style="opacity:.65">' + esc(errText(e)) + '</span><button class="cm-btn sm" data-re>' + T('rd.retry') + '</button>');
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
      var f = el('div', 'cm-fail', T('rd.err.pageFail', { n: page }) + '<button class="cm-btn sm" data-re>' + T('rd.retry') + '</button>');
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
    else renderPaged(true);
    paintBot();
    paintThumbs();
    schedulePreload(3000);
  }

  function renderPaged(force) {
    var stage = $('cmStage');
    var pages = spreadPages(CS.page);
    var key = pages.join(',');
    var frame = force ? null : stage.querySelector('.cm-frame');
    if (frame && frame.dataset.key === key) {
      frame.classList.remove('out');
      frame.style.transform = '';
      CS.zoom = 1; CS.panX = 0; CS.panY = 0;
      applyVars();
      return;
    }
    frame = el('div', 'cm-frame out' + (pages.length > 1 ? ' dual' : ''));
    frame.dataset.key = key;
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
    if (changed) { queueProgress(); if (!CS.trackDrag) preload(); }
    if (!silent) bumpIdle();
  }

  function turn(delta) {
    if (!CS.open) return;
    var step = CS.mode === 'dual' ? 2 : 1;
    var d = CS.dir === 'RTL' ? -delta : delta;
    var next = CS.page + d * step;
    if (next < 1) return hop(-1);
    if (next > (CS.total || 1)) return hop(1);
    goto(next);
  }

  function hop(dir) {
    if (!CS.open || CS.hopBusy) return;
    var runtime = rt();
    if (!runtime || !runtime.bookNeighbors) return;
    CS.hopBusy = true;
    clearTimeout(CS.progTimer);
    sendProgress();
    runtime.bookNeighbors(CS.bookId).then(function (n) {
      CS.hopBusy = false;
      var id = dir > 0 ? n.nextId : n.previousId;
      if (!id) {
        toast(dir > 0 ? T('rd.toast.lastBook') : T('rd.toast.firstBook'), 'info');
        return;
      }
      open(id, dir > 0 ? { page: 1 } : { atEnd: true });
    }, function () {
      CS.hopBusy = false;
      toast(T('rd.toast.hopFail'), 'err');
    });
  }

  function preload() {
    if (!CS.open || !CS.preReady || CS.trackDrag) return;
    var n = PRE.preload || 0;
    if (!n || CS.mode === 'webtoon' || CS.mode === 'paged') return;
    var pages = spreadPages(CS.page);
    var list = [];
    for (var i = 1; i <= n; i++) { list.push(pages[pages.length - 1] + i); list.push(pages[0] - i); }
    list.forEach(function (p) {
      if (p < 1 || p > CS.total) return;
      var key = p + '@' + CS.quality;
      if (CS.preloaded.has(key)) return;
      CS.preloaded.add(key);
      ensureRef(p).then(function () {
        var im = new Image();
        im.src = imgUrl(p);
      }).catch(function () { CS.preloaded.delete(key); });
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
      '<button class="cm-btn" data-a="exit" title="' + esc(T('rd.exit')) + '">' + svg('left', 16) + '</button>' +
      '<div class="cm-title"><b>' + esc(b.title || b.name || T('rd.comic')) + (PRE.imm ? '<i class="cm-imm">' + esc(T('rd.imm')) + '</i>' : '') + '</b>' +
      '<span>' + esc(s) + (b.profile ? ' · ' + esc(PROFILE_CN[b.profile] || b.profile) : '') + ' · ' + esc(T('rd.pagesN', { n: num(CS.total) })) + '</span></div>' +
      '<div class="cm-tools">' +
      '<button class="cm-btn" data-a="ann" title="' + esc(T('rd.tool.anns')) + '">' + svg('marker') + '</button>' +
      '<button class="cm-btn" data-a="set" title="' + esc(T('rd.tool.set')) + '">' + svg('settings') + '</button>' +
      '<button class="cm-btn" data-a="full" title="' + esc(T('rd.tool.full')) + '">' + svg('full') + '</button>' +
      '</div>';
    top.querySelector('[data-a="exit"]').onclick = function () { close(); };
    top.querySelector('[data-a="ann"]').onclick = function (ev) { ev.stopPropagation(); openMenu(ev.currentTarget, 'ann'); };
    top.querySelector('[data-a="set"]').onclick = function () { closeMenu(); side('set'); };
    top.querySelector('[data-a="full"]').onclick = function () { fullscreen(); };
    markToolbar();
  }

  function markToolbar() {
    var t = $('cmTop');
    if (t) {
      var ann = t.querySelector('[data-a="ann"]');
      if (ann) ann.classList.toggle('on', CS.side === 'anns' || !!CS.drawMode);
      var set = t.querySelector('[data-a="set"]');
      if (set) set.classList.toggle('on', CS.side === 'set');
    }
    var b = $('cmBot');
    if (b) {
      var th = b.querySelector('[data-a="thumbs"]');
      if (th) th.classList.toggle('on', CS.thumbs);
      var md = b.querySelector('[data-a="mode"]');
      if (md) md.classList.toggle('on', CS.menu === 'mode');
      var q = b.querySelector('[data-a="quality"]');
      if (q) q.classList.toggle('on', CS.menu === 'quality');
    }
  }

  function menuItems(kind) {
    if (kind === 'ann') {
      return [
        { icon: 'list', label: T('rd.tool.anns'), key: 'M', on: CS.side === 'anns', run: function () { side('anns'); } },
        { icon: 'bookmark', label: T('rd.tool.bm'), key: 'B', run: function () { toggleBookmark(); } },
        { icon: 'crop', label: T('rd.tool.rect'), key: 'R', on: CS.drawMode === 'rect', run: function () { setDraw(CS.drawMode === 'rect' ? null : 'rect'); } },
        { icon: 'brush', label: T('rd.tool.brush'), key: 'P', on: CS.drawMode === 'brush', run: function () { setDraw(CS.drawMode === 'brush' ? null : 'brush'); } }
      ];
    }
    if (kind === 'mode') {
      var out = MODES.map(function (m) {
        return { icon: m[2], label: T(m[1]), on: CS.mode === m[0], run: function () { setMode(m[0]); } };
      });
      out.push({ sep: T('rd.tool.dir') });
      [['LTR', 'rd.dir.ltr'], ['RTL', 'rd.dir.rtl']].forEach(function (d) {
        out.push({ icon: 'dir', label: T(d[1]), on: CS.dir === d[0], run: function () { setDir(d[0]); } });
      });
      return out;
    }
    return QS.map(function (q) {
      return { icon: 'bolt', label: T(q[1]), on: CS.quality === q[0], run: function () { setQuality(q[0]); } };
    });
  }

  function openMenu(anchor, kind) {
    var m = $('cmMenu'); if (!m || !anchor) return;
    if (CS.menu === kind) return closeMenu();
    CS.menu = kind;
    var items = menuItems(kind);
    m.innerHTML = items.map(function (it, i) {
      if (it.sep != null) return '<div class="cm-menu-sep">' + esc(it.sep) + '</div>';
      return '<button class="cm-menu-it' + (it.on ? ' on' : '') + '" data-mi="' + i + '">' +
        (it.icon ? svg(it.icon, 14) : '<i class="cm-menu-dot"></i>') +
        '<span>' + esc(it.label) + '</span>' +
        (it.key ? '<kbd>' + it.key + '</kbd>' : '') +
        '<i class="cm-menu-tick">' + (it.on ? svg('check', 12) : '') + '</i></button>';
    }).join('');
    m.classList.add('on');
    var r = anchor.getBoundingClientRect();
    var mw = m.offsetWidth, mh = m.offsetHeight;
    var left = clamp(r.left + r.width / 2 - mw / 2, 8, Math.max(8, innerWidth - mw - 8));
    var top = r.top > innerHeight * 0.55 ? r.top - mh - 8 : r.bottom + 8;
    if (top < 8) top = r.bottom + 8;
    if (top + mh > innerHeight - 8) top = Math.max(8, innerHeight - mh - 8);
    m.style.left = left + 'px';
    m.style.top = top + 'px';
    all('#cmMenu [data-mi]').forEach(function (btn) {
      btn.onclick = function (ev) {
        ev.stopPropagation();
        var it = items[+btn.dataset.mi];
        closeMenu();
        if (it && it.run) it.run();
      };
    });
    markToolbar();
    bumpIdle();
  }

  function closeMenu() {
    if (!CS.menu) return;
    CS.menu = null;
    var m = $('cmMenu');
    if (m) m.classList.remove('on');
    markToolbar();
  }

  function setMode(mode) {
    CS.mode = mode; PRE.mode = mode; PRE.modeTouched = true; savePrefs();
    syncMode(); renderAll(); paintBot();
    if (CS.side === 'set') paintSettings($('cmSide'));
    markToolbar();
  }

  function setDir(dir) {
    CS.dir = dir; PRE.dir = dir; savePrefs();
    if (CS.mode === 'webtoon' || CS.mode === 'paged') {
      CS.mode = 'single'; PRE.mode = 'single'; PRE.modeTouched = true;
      syncMode(); renderAll();
      toast(T('rd.toast.single'), 'ok');
    } else renderAll();
    paintBot();
    if (CS.side === 'set') paintSettings($('cmSide'));
    toast(T('rd.toast.dir', { v: T(dir === 'RTL' ? 'rd.dir.rtl' : 'rd.dir.ltr') }), 'ok');
  }

  function setQuality(q) {
    CS.quality = q; PRE.quality = q; savePrefs();
    CS.refs.clear(); CS.refq.clear(); CS.fails.clear(); CS.preloaded.clear();
    renderAll(); paintBot();
    if (CS.side === 'set') paintSettings($('cmSide'));
    var hit = QS.filter(function (x) { return x[0] === q; })[0] || QS[0];
    toast(T('rd.toast.q', { v: T(hit[1]) }), 'ok');
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
    if (rest) rest.textContent = T('rd.restN', { n: num(Math.max(0, total - CS.page)) });
  }

  function paintBot() {
    var bot = $('cmBot'); if (!bot) return;
    var total = CS.total || 1, p = pct();
    var marks = CS.anns.filter(function (a) { return a.marker === 'bookmark'; });
    var cur = MODES.filter(function (m) { return m[0] === CS.mode; })[0] || MODES[0];
    bot.innerHTML = '<div class="cm-bar">' +
      '<span data-pos style="min-width:86px;font-variant-numeric:tabular-nums">' + CS.page + ' / ' + num(total) + '</span>' +
      '<div class="cm-track" id="cmTrack"><i style="width:' + p + '%"></i><b style="left:' + p + '%"></b>' +
      marks.map(function (a) { return '<em style="left:' + (a.page / total * 100) + '%"></em>'; }).join('') +
      '<div class="cm-tip" id="cmTip"></div></div>' +
      '<span data-pct style="min-width:48px;text-align:right;font-variant-numeric:tabular-nums">' + p + '%</span>' +
      '<span data-rest style="opacity:.5;min-width:64px;text-align:right">' + esc(T('rd.restN', { n: num(Math.max(0, total - CS.page)) })) + '</span>' +
      '<div class="cm-btools">' +
      '<button class="cm-btn" data-a="mode" title="' + esc(T('rd.tool.mode')) + '">' + svg(cur[2]) + '<span class="cm-bl">' + esc(T(cur[1])) + '</span>' + svg('down', 10) + '</button>' +
      '<button class="cm-btn" data-a="quality" title="' + esc(T('rd.tool.q')) + '">' + svg('bolt') + '<span class="cm-bl">' + esc(CS.quality ? CS.quality + 'px' : T('rd.q.orig')) + '</span>' + svg('down', 10) + '</button>' +
      '<button class="cm-btn" data-a="thumbs" title="' + esc(T('rd.tool.thumbs')) + '">' + svg('grid') + '</button>' +
      '<button class="cm-btn" data-a="export" title="' + esc(T('rd.tool.export')) + '">' + svg('dl') + '</button>' +
      '</div></div>';
    bot.querySelector('[data-a="mode"]').onclick = function (ev) { ev.stopPropagation(); openMenu(ev.currentTarget, 'mode'); };
    bot.querySelector('[data-a="quality"]').onclick = function (ev) { ev.stopPropagation(); openMenu(ev.currentTarget, 'quality'); };
    bot.querySelector('[data-a="thumbs"]').onclick = function () { closeMenu(); toggleThumbs(); };
    bot.querySelector('[data-a="export"]').onclick = function () { closeMenu(); side('export'); };
    bindTrack();
    markToolbar();
  }

  function bindTrack() {
    var tr = $('cmTrack'); if (!tr) return;
    var tip = $('cmTip');
    var seek = function (ev) {
      var r = tr.getBoundingClientRect();
      return Math.max(1, Math.round(clamp((ev.clientX - r.left) / r.width, 0, 1) * CS.total));
    };
    var showTip = function (page, ev) {
      if (!tip) return;
      var r = tr.getBoundingClientRect();
      tip.classList.add('on');
      tip.style.left = clamp(ev.clientX - r.left, 62, r.width - 62) + 'px';
      if (CS.tipAt === page) return;
      CS.tipAt = page;
      tip.innerHTML = '<img src="' + base() + 'api/lantai/v1/read/' + CS.bookId + '/thumb/' + page + '?w=120' + verQ() + '" alt=""><span>' + esc(T('rd.pageN', { n: page })) + '</span>';
    };
    var preview = function (page, ev) {
      CS.page = page;
      CS.seen = Math.max(CS.seen, page);
      paintBotLight();
      paintThumbs();
      showTip(page, ev);
    };
    tr.onmousedown = function (ev) {
      ev.preventDefault();
      CS.trackDrag = true;
      var last = seek(ev);
      preview(last, ev);
      var move = function (e2) { last = seek(e2); preview(last, e2); };
      var up = function () {
        document.removeEventListener('mousemove', move);
        document.removeEventListener('mouseup', up);
        CS.trackDrag = false;
        goto(last);
        queueProgress();
        preload();
      };
      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
    };
    tr.onmousemove = function (ev) {
      if (!tip) return;
      showTip(seek(ev), ev);
    };
    tr.onmouseleave = function () { if (tip) tip.classList.remove('on'); };
  }

  function toggleThumbs(force) {
    CS.thumbs = force == null ? !CS.thumbs : force;
    $('cmThumbs').classList.toggle('on', CS.thumbs);
    $('cm').classList.toggle('thumbs-open', CS.thumbs);
    markToolbar();
    if (CS.thumbs) {
      all('#cmStrip .cm-th.fail').forEach(function (c) {
        c.classList.remove('fail');
        delete c.dataset.dead;
        delete c.dataset.loaded;
      });
      paintThumbs();
      var strip = $('cmStrip');
      var cur = strip ? strip.querySelector('.cm-th.on') : null;
      if (cur) cur.scrollIntoView({ inline: 'center', block: 'nearest' });
      setTimeout(function () { if (CS.thumbs) thScroll(); }, 280);
    }
    bumpIdle();
  }

  var TH_AHEAD = 0.25;
  var TH_TAIL = 3;
  var TH_RUSH_V = 1.6;
  var TH_DRAG_MIN = 6;
  var TH_CLICK_SLOP = 10;

  function wireStrip(strip) {
    if (!strip || strip.dataset.wired) return;
    strip.dataset.wired = '1';
    var st = null;
    var stop = function (ev) {
      if (!st || (ev && ev.pointerId !== st.id)) return;
      if (st.on) {
        strip.classList.remove('thdrag');
        CS.thMoved = Date.now();
        try { strip.releasePointerCapture(st.id); } catch (e) { }
      }
      st = null;
    };
    strip.addEventListener('pointerdown', function (ev) {
      CS.thDown = { x: ev.clientX, y: ev.clientY };
      if (ev.pointerType === 'touch' || ev.button !== 0) return;
      st = { id: ev.pointerId, x: ev.clientX, y: ev.clientY, sl: strip.scrollLeft, on: false };
    });
    strip.addEventListener('pointermove', function (ev) {
      if (!st || ev.pointerId !== st.id) return;
      var dx = ev.clientX - st.x;
      var dy = ev.clientY - st.y;
      if (!st.on) {
        if (Math.abs(dx) < TH_DRAG_MIN || Math.abs(dx) < Math.abs(dy)) return;
        st.on = true;
        strip.classList.add('thdrag');
        try { strip.setPointerCapture(ev.pointerId); } catch (e) { }
      }
      strip.scrollLeft = st.sl - dx;
    });
    strip.addEventListener('pointerup', stop);
    strip.addEventListener('pointercancel', stop);
    strip.addEventListener('dragstart', function (ev) { ev.preventDefault(); });
    strip.addEventListener('click', function (ev) {
      var c = ev.target.closest ? ev.target.closest('.cm-th') : null;
      if (!c) return;
      var down = CS.thDown;
      CS.thDown = null;
      ev.preventDefault();
      ev.stopPropagation();
      if (CS.thMoved && Date.now() - CS.thMoved < 320) return;
      if (down) {
        var dx = ev.clientX - down.x;
        var dy = ev.clientY - down.y;
        if (Math.abs(dx) > TH_CLICK_SLOP && Math.abs(dx) > Math.abs(dy)) return;
      }
      if (!c.dataset.p) return;
      goto(+c.dataset.p);
    }, true);
    strip.addEventListener('wheel', function (ev) {
      if (Math.abs(ev.deltaX) >= Math.abs(ev.deltaY)) return;
      if (strip.scrollWidth <= strip.clientWidth) return;
      ev.preventDefault();
      strip.scrollLeft += ev.deltaMode === 1 ? ev.deltaY * 16 : ev.deltaY;
    }, { passive: false });
  }

  function thumbUrl(p) {
    return base() + 'api/lantai/v1/read/' + CS.bookId + '/thumb/' + p + '?w=120' + verQ();
  }

  function thWindow() {
    var strip = $('cmStrip');
    if (!strip) return null;
    var kids = strip.children;
    if (!kids.length) return null;
    var x0 = kids[0].offsetLeft;
    var step = kids.length > 1 ? kids[1].offsetLeft - x0 : 82;
    if (!(step > 0)) step = 82;
    var pad = Math.round(strip.clientWidth * TH_AHEAD);
    var from = Math.max(0, Math.floor((strip.scrollLeft - pad - x0) / step));
    var to = Math.min(kids.length - 1, Math.ceil((strip.scrollLeft + strip.clientWidth + pad - x0) / step));
    return { strip: strip, kids: kids, from: from, to: to };
  }

  function thPlan() {
    var w = thWindow();
    if (!w) return [];
    var out = [], i, c;
    for (i = w.from; i <= w.to; i++) {
      c = w.kids[i];
      if (c && !c.dataset.loaded && !c.dataset.dead) out.push(c);
    }
    for (i = w.to + 1; i <= Math.min(w.kids.length - 1, w.to + TH_TAIL); i++) {
      c = w.kids[i];
      if (c && !c.dataset.loaded && !c.dataset.dead) out.push(c);
    }
    return out;
  }

  function thPump() {
    if (!CS.thumbs || CS.thRush || CS.thBusy) return;
    var plan = thPlan();
    if (!plan.length) return;
    thLoad(plan[0]);
  }

  function thSettle() {
    clearTimeout(CS.thIdle);
    CS.thIdle = setTimeout(function () {
      CS.thIdle = null;
      CS.thVel = 0;
      CS.thLag = 0;
      CS.thRush = false;
      thPump();
    }, 160);
  }

  function thLoad(c) {
    if (!c || c.dataset.loaded) { thPump(); return; }
    var idx = +c.dataset.p - 1;
    CS.thBusy = true;
    c.dataset.loaded = '1';
    var im = el('img');
    im.alt = '';
    im.decoding = 'async';
    var done = function (ok) {
      CS.thBusy = false;
      if (ok) c.classList.add('ok');
      else { c.classList.add('fail'); c.dataset.dead = '1'; }
      var w = thWindow();
      if (w && (idx < w.from || idx > w.to)) {
        CS.thLag++;
        if (CS.thLag >= 2) { CS.thRush = true; thSettle(); }
      } else {
        CS.thLag = 0;
      }
      thPump();
    };
    im.onload = function () { done(true); };
    im.onerror = function () { done(false); };
    im.src = c.dataset.src;
    c.insertBefore(im, c.firstChild);
  }

  function thScroll() {
    var strip = $('cmStrip');
    if (!strip || !CS.thumbs) return;
    var now = Date.now();
    var x = strip.scrollLeft;
    if (CS.thLastT) {
      var dt = Math.max(1, now - CS.thLastT);
      CS.thVel = CS.thVel * 0.55 + Math.abs(x - CS.thLastX) / dt * 0.45;
    }
    CS.thLastX = x;
    CS.thLastT = now;
    if (CS.thVel > TH_RUSH_V) CS.thRush = true;
    thSettle();
    if (!CS.thRush) thPump();
  }

  function thWatch() {
    var strip = $('cmStrip');
    if (!strip) return;
    stopThumbWatch();
    strip.addEventListener('scroll', thScroll, { passive: true });
    window.addEventListener('resize', thScroll);
  }

  function stopThumbWatch() {
    var strip = $('cmStrip');
    if (strip) strip.removeEventListener('scroll', thScroll);
    window.removeEventListener('resize', thScroll);
    clearTimeout(CS.thIdle);
    CS.thIdle = null;
    CS.thBusy = false;
    CS.thRush = false;
    CS.thLag = 0;
    CS.thVel = 0;
    CS.thLastX = 0;
    CS.thLastT = 0;
  }

  function resetThumbs() {
    stopThumbWatch();
    var strip = $('cmStrip');
    if (strip) { strip.dataset.total = ''; strip.innerHTML = ''; }
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
        html += '<div class="cm-th" data-p="' + p + '" data-src="' + thumbUrl(p) + '"><b>' + p + '</b></div>';
      }
      strip.innerHTML = html;
      wireStrip(strip);
      thWatch();
    }
    thPump();
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
    P.innerHTML = sideHead(T('rd.set.title')) + '<div class="cm-side-b">' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.mode') + '</div><div class="cm-chips">' +
      MODES.map(function (x) { return '<span class="cm-chip' + (CS.mode === x[0] ? ' on' : '') + '" data-mode="' + x[0] + '">' + svg(x[2], 13) + esc(T(x[1])) + '</span>'; }).join('') + '</div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.fit') + '</div><div class="cm-chips">' +
      FITS.map(function (x) { return '<span class="cm-chip' + (CS.fit === x[0] ? ' on' : '') + '" data-fit="' + x[0] + '">' + esc(T(x[1])) + '</span>'; }).join('') + '</div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.dir') + '</div><div class="cm-chips">' +
      [['LTR', 'rd.dir.ltr'], ['RTL', 'rd.dir.rtl']].map(function (x) { return '<span class="cm-chip' + (CS.dir === x[0] ? ' on' : '') + '" data-dir="' + x[0] + '">' + esc(T(x[1])) + '</span>'; }).join('') + '</div>' +
      '<div class="cm-note">' + T((CS.mode === 'webtoon' || CS.mode === 'paged') ? 'rd.set.dirScrollNote' : 'rd.set.dirNote') + '</div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.q') + '</div><div class="cm-chips">' +
      QS.map(function (x) { return '<span class="cm-chip' + (CS.quality === x[0] ? ' on' : '') + '" data-q="' + x[0] + '">' + esc(T(x[1])) + '</span>'; }).join('') + '</div>' +
      '<div class="cm-note">' + T('rd.set.qNote') + '</div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.margin') + '</div><div class="cm-chips" style="margin-bottom:9px">' +
      MARGIN_PRESETS.map(function (x) { return '<span class="cm-chip' + (PRE.marginPreset === x[0] ? ' on' : '') + '" data-mp="' + x[0] + '" data-v="' + x[2] + '">' + esc(T(x[1])) + '</span>'; }).join('') + '</div>' +
      '<div class="cm-mg">' + mr(T('rd.set.marginT'), 't', m.t) + mr(T('rd.set.marginB'), 'b', m.b) + mr(T('rd.set.marginL'), 'l', m.l) + mr(T('rd.set.marginR'), 'r', m.r) + '</div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.gap') + '</div>' + rg('gap', PRE.gap, 0, 60, 1, PRE.gap + ' px') + '</div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.pre') + '</div><div class="cm-chips">' +
      [0, 1, 2, 3, 5].map(function (n) { return '<span class="cm-chip' + (PRE.preload === n ? ' on' : '') + '" data-pre="' + n + '">' + (n ? esc(T('rd.set.preN', { n: n })) : T('rd.set.preOff')) + '</span>'; }).join('') + '</div>' +
      '<div class="cm-note">' + T('rd.set.preNote') + '</div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.bg') + '</div><div class="cm-chips">' +
      [['auto', 'rd.set.bgAuto'], ['black', 'rd.set.bgBlack']].map(function (x) { return '<span class="cm-chip' + (PRE.bg === x[0] ? ' on' : '') + '" data-bg="' + x[0] + '">' + esc(T(x[1])) + '</span>'; }).join('') + '</div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.imm') + '</div><div class="cm-chips">' +
      [[0, 'rd.set.off'], [1, 'rd.set.on']].map(function (x) { return '<span class="cm-chip' + ((PRE.imm ? 1 : 0) === x[0] ? ' on' : '') + '" data-imm="' + x[0] + '">' + esc(T(x[1])) + '</span>'; }).join('') + '</div>' +
      '<div class="cm-note">' + T('rd.set.immNote') + '</div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.keys') + '</div><div class="cm-keys">' +
      '<kbd>← →</kbd><span>' + T('rd.key.prevNext') + '</span><kbd>Space</kbd><span>' + T('rd.key.next') + '</span>' +
      '<kbd>T</kbd><span>' + T('rd.key.thumbs') + '</span><kbd>M</kbd><span>' + T('rd.key.anns') + '</span>' +
      '<kbd>B</kbd><span>' + T('rd.key.bm') + '</span><kbd>R</kbd><span>' + T('rd.key.rect') + '</span>' +
      '<kbd>F</kbd><span>' + T('rd.key.full') + '</span><kbd>+ −</kbd><span>' + T('rd.key.zoom') + '</span>' +
      '</div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.set.help') + '</div>' +
      '<button class="cm-btn" data-help="1" style="width:100%;height:32px">' + svg('info', 13) + '<span>' + T('rd.set.help') + '</span></button>' +
      '<div class="cm-note">' + T('rd.set.helpNote') + '</div></div>' +
      '</div>';

    P.querySelector('[data-a="close"]').onclick = closeSide;
    all('#cmSide [data-mode]').forEach(function (c) {
      c.onclick = function () { setMode(c.dataset.mode); };
    });
    all('#cmSide [data-fit]').forEach(function (c) {
      c.onclick = function () { CS.fit = c.dataset.fit; PRE.fit = CS.fit; savePrefs(); applyVars(); paintSettings(P); };
    });
    all('#cmSide [data-dir]').forEach(function (c) {
      c.onclick = function () { setDir(c.dataset.dir); };
    });
    all('#cmSide [data-q]').forEach(function (c) {
      c.onclick = function () { setQuality(+c.dataset.q); };
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
        toast(PRE.imm ? T('rd.toast.immOn') : T('rd.toast.immResume'));
      };
    });
    var hb = P.querySelector('[data-help]');
    if (hb) hb.onclick = function () { guide(true); };
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
      return (String(a.note || '') + ' ' + mk(a.marker) + ' p' + a.page).toLowerCase().indexOf(q) >= 0;
    }).sort(function (a, b) { return a.page - b.page || a.at - b.at; });
  }

  function paintAnnSide(P) {
    var all = CS.anns.length;
    var bm = CS.anns.filter(function (a) { return a.marker === 'bookmark'; }).length;
    var list = annList();
    var tab = CS.annTab || 'all';
    P.innerHTML = sideHead(T('rd.ann.title')) + '<div class="cm-side-b">' +
      '<div class="cm-chips" style="margin-bottom:9px">' +
      [['all', T('rd.ann.tabAll', { n: all })], ['bookmark', T('rd.ann.tabBm', { n: bm })], ['mark', T('rd.ann.tabMark', { n: all - bm })]].map(function (x) {
        return '<span class="cm-chip' + (tab === x[0] ? ' on' : '') + '" data-atab="' + x[0] + '">' + esc(x[1]) + '</span>';
      }).join('') + '</div>' +
      '<div class="cm-annq"><input id="cmAnnQ" placeholder="' + esc(T('rd.ann.searchQ')) + '" value="' + esc(CS.annQ || '') + '"></div>' +
      '<button class="cm-btn cm-annadd" data-bmadd="1">' + svg('bookmark', 13) + esc(T('rd.ann.addPage', { n: CS.page })) + '</button>' +
      (list.length ? list.map(function (a) {
        var shapes = a.shapes || [];
        var desc = a.marker === 'bookmark' ? mk('bookmark') : (shapes.length ? (shapes[0].t === 'p' ? T('rd.ann.brushTag') : T('rd.ann.rectTag')) + ' · ' + T('rd.ann.countN', { n: shapes.length }) : mk('comment'));
        return '<div class="cm-annitem" data-id="' + a.id + '" data-page="' + a.page + '"><i class="dot" style="background:' + esc(a.color) + '"></i>' +
          '<div class="tx"><b>' + esc(T('rd.ann.item', { n: a.page, desc: desc })) + '</b><span>' + (a.note ? esc(a.note) : mk(a.marker)) + '</span></div>' +
          '<span class="x" data-edit="' + a.id + '">' + svg('edit', 12) + '</span>' +
          '<span class="x" data-del="' + a.id + '">' + svg('close', 13) + '</span></div>';
      }).join('') : '<div class="cm-note">' + (all ? T('rd.ann.empty') : T('rd.ann.none')) + '</div>') +
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
      '<input id="cmAnnNote" placeholder="' + esc(T('rd.ann.note')) + '" value="' + esc(a.note || '') + '" style="width:100%;height:28px;border-radius:7px;border:1px solid var(--line);background:transparent;color:inherit;font-size:12px;padding:0 8px">' +
      '<div class="cm-chips"><span class="cm-chip" data-ecancel="1">' + esc(T('rd.common.cancel')) + '</span><span class="cm-chip on" data-esave="1">' + esc(T('rd.common.save')) + '</span></div></div>';
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
          toast(T('rd.toast.annUpdated'));
        }).catch(function (e) { toast(T('rd.err.update', { e: errText(e) }), 'err'); });
    };
  }

  function paintExport(P) {
    var pages = spreadPages(CS.page);
    P.innerHTML = sideHead(T('rd.exp.title')) + '<div class="cm-side-b">' +
      '<div class="cm-sec"><div class="t">' + T('rd.exp.cur') + '</div>' +
      '<button class="cm-btn" data-exp="cur" style="width:100%;height:34px">' + svg('dl') + esc(T('rd.exp.curBtn', { n: pages.join(', ') })) + '</button>' +
      '<div class="cm-note">' + T('rd.exp.curNote') + '</div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.exp.range') + '</div><div class="cm-export">' +
      '<div class="row"><input type="number" id="cmFrom" min="1" max="' + CS.total + '" value="' + Math.max(1, CS.page - 9) + '">' +
      '<span style="opacity:.6">' + T('rd.exp.to') + '</span><input type="number" id="cmTo" min="1" max="' + CS.total + '" value="' + CS.page + '">' +
      '<span style="opacity:.5;font-size:10.5px">' + esc(T('rd.exp.total', { n: num(CS.total) })) + '</span></div>' +
      '<button class="cm-btn" data-exp="range" style="width:100%;height:34px">' + svg('dl') + T('rd.exp.zip') + '</button>' +
      '<div class="cm-note">' + esc(T('rd.exp.zipNote', { n: MAX_ZIP })) + '</div></div></div>' +
      '<div class="cm-sec"><div class="t">' + T('rd.exp.all') + '</div>' +
      '<button class="cm-btn" data-exp="all" style="width:100%;height:34px">' + svg('dl') + T('rd.exp.allBtn') + '</button>' +
      '<div class="cm-note">' + esc(T('rd.exp.allNote', { n: MAX_ZIP })) + '</div></div></div>';
    P.querySelector('[data-a="close"]').onclick = closeSide;
    P.querySelector('[data-exp="cur"]').onclick = function () { downloadPages(pages, pages[0], pages[pages.length - 1]); };
    P.querySelector('[data-exp="range"]').onclick = function () {
      var a = clamp(+$('cmFrom').value || 1, 1, CS.total);
      var b = clamp(+$('cmTo').value || a, a, CS.total);
      if (b - a + 1 > MAX_ZIP) return toast(T('rd.exp.tooMany', { n: MAX_ZIP }), 'err');
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
      toast(T('rd.exp.start', { n: p }), 'ok');
    }).catch(function (e) { toast(T('rd.err.export', { e: errText(e) }), 'err'); });
  }

  function exportZip(a, b) {
    var link = document.createElement('a');
    link.href = base() + 'api/lantai/v1/read/' + CS.bookId + '/export?from=' + a + '&to=' + b;
    link.download = bookName() + '-' + a + '-' + b + '.zip';
    document.body.appendChild(link); link.click(); link.remove();
    toast(T('rd.exp.packing', { a: a, b: b }), 'ok');
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
    if (ev.target.closest('.cm-guide')) return;
    if (CS.menu && !ev.target.closest('.cm-menu,[data-a="ann"],[data-a="mode"],[data-a="quality"]')) closeMenu();
    if (ev.target.closest('.cm-top,.cm-bot,.cm-side,.cm-thumbs,.cm-float,.cm-btn,.cm-menu,.cm-guide')) return;
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
    if (CS.guideOpen) return;
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
    if (!show) { closeMenu(); toggleThumbs(false); if (CS.side) closeSide(); }
    bumpIdle();
  }

  function barHold() {
    return CS.barHover || CS.trackDrag || !!CS.menu;
  }

  function bumpIdle() {
    clearTimeout(CS.offTimer);
    CS.offTimer = null;
    if (CS.guideOpen) return;
    if (barHold()) return;
    CS.offTimer = setTimeout(function () {
      CS.offTimer = null;
      if (!CS.open || CS.thumbs || CS.side || CS.guideOpen) return;
      if (barHold()) return;
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
    if (ev.target.closest('.cm-top,.cm-bot,.cm-side,.cm-thumbs,.cm-menu,.cm-guide')) return;
    if (ev.ctrlKey) {
      ev.preventDefault();
      CS.zoom = clamp(CS.zoom - ev.deltaY * 0.004, 1, 4);
      if (CS.zoom <= 1.02) { CS.zoom = 1; CS.panX = 0; CS.panY = 0; }
      applyVars();
      return;
    }
    if (CS.mode === 'webtoon' || CS.mode === 'paged') {
      var flow = $('cmFlow');
      if (!flow) return;
      var edge = 12;
      if (ev.deltaY > 0 && CS.page >= (CS.total || 1) && flow.scrollTop + flow.clientHeight >= flow.scrollHeight - edge) {
        var dn = Date.now();
        if (dn - CS.wheelAt > 600) { CS.wheelAt = dn; hop(1); }
      } else if (ev.deltaY < 0 && CS.page <= 1 && flow.scrollTop <= edge) {
        var up = Date.now();
        if (up - CS.wheelAt > 600) { CS.wheelAt = up; hop(-1); }
      }
      return;
    }
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
    if (mode) hint(mode === 'brush' ? T('rd.hint.brush') : T('rd.hint.rect'));
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
    if (!img || !img.complete || !img.naturalWidth) return toast(T('rd.err.waitImg'), 'info');
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
      '<button class="cm-btn sm" data-ok title="' + esc(T('rd.common.save')) + '">' + svg('check', 13) + '</button>' +
      '<button class="cm-btn sm" data-no title="' + esc(T('rd.common.cancel')) + '">' + svg('close', 13) + '</button>';
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
      toast(T('rd.toast.annSaved', { n: page }), 'ok');
    }).catch(function (e) { toast(T('rd.err.annSave', { e: errText(e) }), 'err'); });
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
      toast(T('rd.toast.annDeleted'), 'ok');
    }).catch(function (e) { toast(T('rd.err.del', { e: errText(e) }), 'err'); });
  }

  function toggleBookmark() {
    var exists = CS.anns.filter(function (a) { return a.marker === 'bookmark' && a.page === CS.page; })[0];
    if (exists) return delAnn(exists.id);
    postAnn({ page: CS.page, color: COLORS[0], marker: 'BOOKMARK', note: '', shapes: null })
      .then(function () { renderAll(); toast(T('rd.toast.bmAdded', { n: CS.page }), 'ok'); })
      .catch(function (e) { toast(T('rd.err.bmSave', { e: errText(e) }), 'err'); });
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
          body: { bookId: CS.bookId, page: CS.page, deviceId: 'webui', deviceName: L.t('网页端'), client: 'lantai-webui' }
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
        device: { id: 'webui', name: L.t('网页端') },
        locator: { href: '#page=' + p, type: 'image/jpeg', locations: { position: p, progression: p / total, totalProgression: p / total } }
      }
    }).catch(function () { });
  }

  var wake = null;
  function wakeUp() {
    if (!navigator.wakeLock || !navigator.wakeLock.request) {
      hint(T('rd.err.wake'));
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

  var GUIDE_KEY = 'lantai-comic-guide';

  function guideSeen() {
    try { return localStorage.getItem(GUIDE_KEY) === '1'; } catch (e) { return false; }
  }

  function maybeGuide() {
    if (guideSeen()) return;
    setTimeout(function () { if (CS.open && !CS.guideOpen) guide(true); }, 900);
  }

  var GSTEPS = [
    { sel: '[data-a="exit"]', t: 'rd.exit', d: 'rd.guide.d1', pos: 'bottom' },
    { sel: '[data-a="ann"]', also: '#cmMenu', t: 'rd.tool.anns', d: 'rd.guide.d2', pos: 'bottom', open: 'ann', settle: 140 },
    { sel: '[data-a="set"]', t: 'rd.tool.set', d: 'rd.guide.d3', pos: 'bottom' },
    { sel: '[data-a="full"]', t: 'rd.tool.full', d: 'rd.guide.d4', pos: 'bottom' },
    { sel: '.cm-bar', t: 'rd.guide.progress', d: 'rd.guide.d5', pos: 'top' },
    { sel: '[data-a="mode"]', also: '#cmMenu', t: 'rd.tool.mode', d: 'rd.guide.d6', pos: 'top', open: 'mode', settle: 140 },
    { sel: '[data-a="quality"]', also: '#cmMenu', t: 'rd.tool.q', d: 'rd.guide.d7', pos: 'top', open: 'quality', settle: 140 },
    { sel: '[data-a="thumbs"]', also: '#cmThumbs', t: 'rd.tool.thumbs', d: 'rd.guide.d9', pos: 'top', open: 'thumbs', settle: 340 },
    { sel: '[data-a="export"]', t: 'rd.tool.export', d: 'rd.guide.d8', pos: 'top', settle: 300 },
    {
      zone: [
        { z: 'l', t: 'rd.guide.zoneL', n: 'rd.guide.zoneLNote', r: '0 – 22%' },
        { z: 'c', t: 'rd.guide.zoneC', n: 'rd.guide.zoneCNote', r: '22% – 78%' },
        { z: 'r', t: 'rd.guide.zoneR', n: 'rd.guide.zoneRNote', r: '78% – 100%' }
      ], t: 'rd.guide.zoneTitle', d: 'rd.guide.zoneNote', pos: 'topbar'
    },
    { keys: true, t: 'rd.guide.gesTitle', d: 'rd.guide.pinch', pos: 'topbar' }
  ];

  var GKEYS = [
    ['← →', 'rd.key.prevNext', 1], ['Space', 'rd.key.next', 1], ['+ −', 'rd.key.zoom', 1],
    ['PgUp / PgDn', 'rd.key.prevNext', 0], ['Home / End', 'rd.key.home', 0],
    ['T', 'rd.key.thumbs', 0], ['M', 'rd.key.anns', 0], ['B', 'rd.key.bm', 0],
    ['R', 'rd.key.rect', 0], ['P', 'rd.key.brush', 0], ['S', 'rd.key.set', 0],
    ['F', 'rd.key.full', 0], ['Esc', 'rd.key.esc', 0]
  ];

  function gtRect(sels) {
    var out = null;
    for (var i = 0; i < sels.length; i++) {
      var nodes = document.querySelectorAll(sels[i]);
      for (var j = 0; j < nodes.length; j++) {
        var b = nodes[j].getBoundingClientRect();
        if (!b.width && !b.height) continue;
        out = out ? {
          left: Math.min(out.left, b.left), top: Math.min(out.top, b.top),
          right: Math.max(out.right, b.right), bottom: Math.max(out.bottom, b.bottom)
        } : { left: b.left, top: b.top, right: b.right, bottom: b.bottom };
      }
    }
    if (!out) return null;
    out.width = out.right - out.left;
    out.height = out.bottom - out.top;
    return out;
  }

  function gtPlace(g, s) {
    var bub = g.querySelector('[data-gbub]');
    var hole = g.querySelector('[data-ghole]');
    if (!bub || !hole) return;
    var r = s.sel ? gtRect([s.sel].concat(s.also || [])) : null;
    var W = innerWidth, H = innerHeight, pad = 7, gap = 22, edge = 12;
    if (r) {
      hole.style.left = (r.left - pad) + 'px';
      hole.style.top = (r.top - pad) + 'px';
      hole.style.width = (r.width + pad * 2) + 'px';
      hole.style.height = (r.height + pad * 2) + 'px';
      hole.classList.add('on');
    } else {
      hole.classList.remove('on');
    }
    var bw = bub.offsetWidth, bh = bub.offsetHeight;
    var pos = s.pos || 'auto', top, left, ax, dir = pos;
    if (pos === 'topbar') {
      top = 74;
      left = clamp(W / 2 - bw / 2, edge, W - bw - edge);
      dir = 'none'; ax = '50%';
    } else if (!r) {
      top = H - bh - 120;
      left = clamp(W / 2 - bw / 2, edge, W - bw - edge);
      dir = 'none'; ax = '50%';
    } else {
      var below = H - r.bottom - edge, above = r.top - edge;
      if (dir === 'auto') dir = (below >= bh + gap || below >= above) ? 'bottom' : 'top';
      if (dir === 'bottom' && below < bh + gap && above > bh + gap) dir = 'top';
      if (dir === 'top' && above < bh + gap && below > bh + gap) dir = 'bottom';
      top = dir === 'bottom' ? r.bottom + gap : r.top - bh - gap;
      left = clamp(r.left + r.width / 2 - bw / 2, edge, W - bw - edge);
      ax = clamp(r.left + r.width / 2 - left, 22, bw - 22) + 'px';
    }
    top = clamp(top, edge, H - bh - edge);
    bub.dataset.dir = dir;
    bub.style.setProperty('--ax', ax);
    bub.style.left = left + 'px';
    bub.style.top = top + 'px';
    bub.classList.add('on');
  }

  function gtBuild() {
    var g = $('cmGuide'); if (!g) return null;
    if (g.dataset.built) return g;
    g.innerHTML =
      '<div class="cm-gt-mask" data-gmask></div>' +
      '<div class="cm-gt-hole" data-ghole></div>' +
      '<div class="cm-gt-zone" data-gzone></div>' +
      '<div class="cm-gt-keys" data-gkeys></div>' +
      '<div class="cm-gt-bub" data-gbub data-dir="bottom">' +
      '<div class="cm-gt-step" data-gstep></div>' +
      '<b class="cm-gt-t" data-gt></b>' +
      '<p class="cm-gt-d" data-gd></p>' +
      '<div class="cm-gt-f"><button class="cm-gt-skip" data-gskip></button>' +
      '<div class="cm-gt-nav"><button data-gprev></button><button class="primary" data-gnext></button></div>' +
      '</div></div>';
    g.dataset.built = '1';
    var block = function (ev) { ev.stopPropagation(); };
    ['pointerdown', 'pointerup', 'click', 'dblclick', 'wheel'].forEach(function (t) {
      g.querySelector('[data-gmask]').addEventListener(t, block);
      g.querySelector('[data-gbub]').addEventListener(t, block);
      g.querySelector('[data-gzone]').addEventListener(t, block);
    });
    g.querySelector('[data-gzone]').addEventListener('click', gtZoneClick);
    g.querySelector('[data-gskip]').onclick = function (ev) { ev.stopPropagation(); closeGuide(true, 'skip'); };
    g.querySelector('[data-gprev]').onclick = function (ev) { ev.stopPropagation(); gtGo(CS.guideStep - 1); };
    g.querySelector('[data-gnext]').onclick = function (ev) { ev.stopPropagation(); gtNext(); };
    return g;
  }

  function gtZoneClick(ev) {
    var z = ev.target.closest ? ev.target.closest('i') : null;
    if (!z) return;
    ev.stopPropagation();
    z.classList.remove('hit');
    void z.offsetWidth;
    z.classList.add('hit');
    var k = z.dataset.z;
    if (k === 'l') return turn(-1);
    if (k === 'r') return turn(1);
    toggleToolbar();
  }

  function gtNext() {
    if (CS.guideStep >= GSTEPS.length - 1) return closeGuide(true, 'done');
    gtGo(CS.guideStep + 1);
  }

  function gtGo(i) {
    var g = gtBuild(); if (!g) return;
    CS.guideStep = clamp(i, 0, GSTEPS.length - 1);
    var s = GSTEPS[CS.guideStep];
    clearTimeout(CS.gtSettle);
    closeMenu();
    if (CS.thumbs) toggleThumbs(false);
    if (CS.side) closeSide();
    if (CS.drawMode) setDraw(null);
    toggleToolbar(true);

    var bub = g.querySelector('[data-gbub]');
    var hole = g.querySelector('[data-ghole]');
    var zone = g.querySelector('[data-gzone]');
    var keys = g.querySelector('[data-gkeys]');
    var mask = g.querySelector('[data-gmask]');
    bub.classList.remove('on');
    zone.classList.remove('on');
    keys.classList.remove('on');
    hole.classList.remove('on');

    if (s.zone) {
      zone.innerHTML = s.zone.map(function (z) {
        return '<i class="' + z.z + '" data-z="' + z.z + '">' +
          (z.r ? '<em>' + esc(z.r) + '</em>' : '') +
          '<b>' + esc(T(z.t)) + '</b><span>' + esc(T(z.n)) + '</span></i>';
      }).join('');
      zone.classList.add('on');
      mask.classList.add('off');
    } else if (s.keys) {
      keys.innerHTML = '<h5>' + esc(T('rd.guide.keysTitle')) + '</h5><div class="cm-gt-kgrid">' +
        GKEYS.map(function (k) {
          return '<kbd' + (k[2] ? ' class="hot"' : '') + '>' + k[0] + '</kbd><span>' + esc(T(k[1])) + '</span>';
        }).join('') + '</div>';
      keys.classList.add('on');
      mask.classList.remove('off');
    } else {
      mask.classList.remove('off');
      if (s.open === 'thumbs') toggleThumbs(true);
      else if (s.open) {
        var a = document.querySelector('[data-a="' + s.open + '"]');
        if (a) openMenu(a, s.open);
      }
    }

    g.querySelector('[data-gstep]').textContent = T('rd.guide.stepN', { i: CS.guideStep + 1, n: GSTEPS.length });
    g.querySelector('[data-gt]').textContent = T(s.t);
    g.querySelector('[data-gd]').innerHTML = T(s.d);
    g.querySelector('[data-gskip]').textContent = T('rd.guide.skip');
    g.querySelector('[data-gprev]').textContent = T('rd.guide.prev');
    g.querySelector('[data-gprev]').disabled = CS.guideStep === 0;
    g.querySelector('[data-gnext]').textContent = T(CS.guideStep >= GSTEPS.length - 1 ? 'rd.guide.start' : 'rd.guide.next');

    var run = function () { if (CS.guideOpen) gtPlace(g, s); };
    requestAnimationFrame(function () { requestAnimationFrame(run); });
    if (s.settle) CS.gtSettle = setTimeout(run, s.settle);
    bumpIdle();
  }

  function guide(force) {
    var g = gtBuild(); if (!g) return;
    if (!force && CS.guideOpen) return closeGuide(true, 'skip');
    CS.guideOpen = true;
    CS.guideStep = 0;
    clearTimeout(CS.offTimer);
    CS.offTimer = null;
    toggleToolbar(true);
    g.classList.add('on');
    gtGo(0);
  }

  function closeGuide(mark, reason) {
    var g = $('cmGuide'); if (!g) return;
    CS.guideOpen = false;
    clearTimeout(CS.gtSettle);
    CS.gtSettle = null;
    closeMenu();
    if (CS.thumbs) toggleThumbs(false);
    if (CS.side) closeSide();
    g.classList.remove('on');
    ['[data-ghole]', '[data-gbub]', '[data-gzone]', '[data-gkeys]'].forEach(function (sel) {
      var n = g.querySelector(sel);
      if (n) n.classList.remove('on');
    });
    toggleToolbar(true);
    if (mark) { try { localStorage.setItem(GUIDE_KEY, '1'); } catch (e) { } }
    bumpIdle();
    if (reason === 'skip') toast(T('rd.guide.skipped'), 'ok');
    else if (reason === 'done') toast(T('rd.guide.done'), 'ok');
  }

  window.addEventListener('resize', function () {
    if (CS.guideOpen) gtPlace($('cmGuide'), GSTEPS[CS.guideStep]);
  });

  function fullscreen() {
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(function () { });
      return;
    }
    var r = $('cm');
    (r || document.documentElement).requestFullscreen().catch(function () {
      toast(T('rd.err.fullscreen'), 'err');
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
    if (CS.guideOpen) {
      if (k === 'Escape') { ev.preventDefault(); return closeGuide(true, 'skip'); }
      if (k === 'ArrowRight') { ev.preventDefault(); return gtNext(); }
      if (k === 'ArrowLeft') { ev.preventDefault(); return gtGo(CS.guideStep - 1); }
      if (k === ' ' || k === 'Enter') { ev.preventDefault(); return gtNext(); }
      return;
    }
    if (k === 'Escape') {
      if (CS.menu) return closeMenu();
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
    if (k === 'r' || k === 'R') return setDraw(CS.drawMode === 'rect' ? null : 'rect');
    if (k === 'p' || k === 'P') return setDraw(CS.drawMode === 'brush' ? null : 'brush');
    if (k === 'f' || k === 'F') return fullscreen();
    if (k === '+' || k === '=') { CS.zoom = clamp(CS.zoom + 0.25, 1, 4); return applyVars(); }
    if (k === '-') { CS.zoom = clamp(CS.zoom - 0.25, 1, 4); if (CS.zoom <= 1.02) CS.zoom = 1; return applyVars(); }
  });

  window.ComicReader = { open: open, close: close, isOpen: isOpen, goto: goto, prefs: PRE, state: CS };
})();
