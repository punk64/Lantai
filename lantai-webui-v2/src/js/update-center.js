(function () {
  const PREF_KEY = 'lantai-update-prefs';
  const CHECK_KEY = 'lantai-update-checked';
  const DEFAULT = { check: true, download: false, install: false };
  const PHASE = {
    upload: 'up.phUpload',
    download: 'up.phDownload',
    unzip: 'up.phUnzip',
    verify: 'up.phVerify',
    backup: 'up.phBackup',
    copy: 'up.phCopy',
    restart: 'up.phRestart'
  };

  let status = null, remote = null, err = '', busy = '', restartNote = '';
  let prog = null, remotePoll = null, inspectPoll = null, restartPoll = null, restartTick = null;
  let prefs = readPrefs();
  let fileInfo = null, uploadName = '';

  function readPrefs() {
    try {
      const v = JSON.parse(localStorage.getItem(PREF_KEY) || '{}');
      return Object.assign({}, DEFAULT, v && typeof v === 'object' ? v : {});
    } catch (e) { return Object.assign({}, DEFAULT); }
  }

  function savePrefs() {
    try { localStorage.setItem(PREF_KEY, JSON.stringify(prefs)); } catch (e) { }
  }

  function lastCheck() {
    const v = Number(localStorage.getItem(CHECK_KEY) || 0);
    return isFinite(v) ? v : 0;
  }

  function markCheck() {
    try { localStorage.setItem(CHECK_KEY, String(Date.now())); } catch (e) { }
  }

  function loading() {
    return '<div class="card"><div class="empty" style="padding:44px 0">' +
      (err ? '<div class="mini">' + esc(err) + '</div>' : '<div class="spinner" style="margin:0 auto 12px"></div><div class="mini">' + esc(L.t('common.loading')) + '</div>') +
      '</div></div>';
  }

  function phaseName(p) {
    const k = PHASE[p.phase];
    return L.t(k || 'up.phWorking');
  }

  function progBlock(p) {
    if (!p || p.idle) return '';
    const indet = p.pct < 0;
    const pct = Math.max(0, Math.min(100, p.pct || 0));
    return '<div style="margin-top:14px">' +
      '<div class="rowflex" style="justify-content:space-between;gap:12px;margin-bottom:7px">' +
      '<span class="mini">' + esc(phaseName(p)) + '</span>' +
      '<span class="mini mono">' + (indet ? '···' : pct + '%') + '</span></div>' +
      '<div class="uprog"><i' + (indet ? ' class="indet"' : ' style="width:' + pct + '%"') + '></i></div>' +
      (p.note ? '<div class="mini" style="margin-top:6px">' + esc(p.note) + '</div>' : '') +
      '</div>';
  }

  function paintProg() {
    const html = progBlock(prog);
    const inImport = !!prog && !prog.idle && prog.where === 'import';
    const a = document.getElementById('upcProg');
    if (a) a.innerHTML = inImport ? html : '';
    const b = document.getElementById('upcProgHero');
    if (b) b.innerHTML = html && !inImport ? html : '';
  }

  function setProg(where, phase, pct, note) {
    prog = { where: where, phase: phase, pct: pct, note: note || '' };
    paintProg();
  }

  function clearProg() {
    prog = null;
    paintProg();
  }

  function loadStatus() {
    return LantaiRuntime.request('api/lantai/v1/system/upgrade')
      .then(d => {
        if (!d || !d.version) { if (!err) err = L.t('up.unreachable'); return; }
        status = d;
        err = '';
      })
      .catch(e => { err = onlineErrText(e); });
  }

  function loadRemote(force) {
    return LantaiRuntime.request('api/lantai/v1/system/upgrade/remote' + (force ? '?refresh=true' : ''))
      .then(d => { remote = d || null; if (force) markCheck(); })
      .catch(e => {
        remote = Object.assign({}, remote || {}, {
          reachable: false,
          error: onlineErrText(e),
          releases: (remote && remote.releases) || [],
          latest: null
        });
      })
      .then(() => refreshBell());
  }

  function refreshBell() {
    if (window.LantaiAboutNotify && LantaiAboutNotify.dot) LantaiAboutNotify.dot();
  }

  function load(force) {
    if (busy) return Promise.resolve();
    busy = 'load';
    return Promise.all([loadStatus(), loadRemote(force)])
      .catch(() => { })
      .then(() => {
        busy = '';
        repaint();
        maybeAuto();
      });
  }

  function repaint() {
    if (S.route.indexOf('/settings') !== 0 || S.settingsTab !== 'upgrade') return;
    const box = document.getElementById('view');
    const top = box ? box.scrollTop : 0;
    render();
    const el = document.getElementById('view');
    if (el) el.scrollTop = top;
    paintProg();
  }

  function pctOf(a, b) {
    if (!b) return 0;
    return Math.max(0, Math.min(100, Math.round(a / b * 100)));
  }

  function newestRelease() {
    const r = remote && remote.latest;
    return r && r.version ? r : null;
  }

  function newer(v) {
    if (!v || !status) return false;
    const a = String(v).replace(/^v/i, '').split(/[.\-+_]/).map(x => parseInt(x, 10) || 0);
    const b = String(status.version || '').replace(/^v/i, '').split(/[.\-+_]/).map(x => parseInt(x, 10) || 0);
    for (let i = 0; i < Math.max(a.length, b.length); i++) {
      const x = a[i] || 0, y = b[i] || 0;
      if (x !== y) return x > y;
    }
    return false;
  }

  function restartHint(mode) {
    return L.t(mode === 'container' ? 'up.restartContainer' : 'up.manualHint');
  }

  function heroHtml() {
    const cur = status ? status.version : '—';
    const rel = newestRelease();
    const lat = rel ? rel.version : null;
    const same = rel && !newer(rel.version);
    const cached = remote && remote.cached && newer(remote.cached.version) ? remote.cached : null;
    const unreachable = remote && remote.reachable === false;

    const title = lat ? (same ? L.t('up.uptodate') : L.t('up.latest') + ' ' + lat) : (unreachable ? L.t('up.unreachable') : L.t('up.norelease'));
    const tone = same ? 'ok' : lat ? 'gold' : 'warn';

    return '<div class="card up-hero" style="margin-bottom:16px">' +
      '<div class="upc-row">' +
      '<div class="upc-ver"><span class="mini">' + esc(L.t('up.current')) + '</span><b>' + esc(cur) + '</b></div>' +
      '<div class="upc-arrow">' + ico('right') + '</div>' +
      '<div class="upc-ver lat"><span class="mini">' + esc(L.t('up.latest')) + '</span><b>' + esc(lat || '—') + '</b></div>' +
      '<div class="upc-act"><button class="btn pri" data-upc-check' + (busy ? ' disabled' : '') + '>' + ico('sync') + esc(busy === 'check' ? L.t('up.checking') : L.t('up.check')) + '</button></div>' +
      '</div>' +
      '<div class="upc-state"><span class="badge ' + tone + '">' + esc(title) + '</span>' +
      (rel && rel.publishedAt ? '<span class="mini">' + esc(L.t('up.published')) + ' ' + esc(String(rel.publishedAt).slice(0, 10)) + '</span>' : '') +
      (rel && rel.sizeBytes ? '<span class="mini">' + esc(L.t('up.size')) + ' ' + bytes(rel.sizeBytes) + '</span>' : '') +
      (remote && remote.checkedAt ? '<span class="mini">' + esc(L.t('up.checkedAt', { t: String(remote.checkedAt).slice(0, 16).replace('T', ' ') })) + '</span>' : '') +
      '</div>' +
      (unreachable && remote.error ? '<div class="note red" style="margin-top:10px">' + esc(L.t('up.unreachable')) + ' · ' + esc(String(remote.error).slice(0, 200)) + '</div>' : '') +
      (rel && rel.notes ? '<div class="upc-notes">' + esc(String(rel.notes)).replace(/\n/g, '<br>') + '</div>' : '') +
      (cached
        ? '<div class="rowflex" style="margin-top:14px;gap:8px;flex-wrap:wrap">' +
          '<span class="note gold" style="flex:1;min-width:200px">' + esc(L.t('up.ready')) + ' · v' + esc(cached.version) + ' · ' + bytes(cached.sizeBytes) + '</span>' +
          '<button class="btn pri" data-upc-apply>' + ico('bolt') + esc(L.t('up.install')) + '</button>' +
          '<button class="btn sm ghost" data-upc-verify>' + esc(L.t('up.reverify')) + '</button></div>'
        : rel && newer(rel.version)
          ? '<div class="rowflex" style="margin-top:14px;gap:8px"><button class="btn pri" data-upc-fetch' + (busy ? ' disabled' : '') + '>' + ico('dl') + esc(busy === 'fetch' ? L.t('up.downloading') : L.t('up.download')) + '</button>' +
            '<span class="mini" style="align-self:center">' + esc(rel.assetName || '') + '</span></div>'
          : '') +
      '<div id="upcProgHero">' + (prog && prog.where !== 'import' ? progBlock(prog) : '') + '</div>' +
      '<div id="upcMsg"></div>' +
      '</div>';
  }

  function optsHtml() {
    const box = (k, label, hint, dis) => '<div class="list-i" style="border:0;padding:10px 0">' +
      '<div style="flex:1;min-width:0"><div class="gt">' + esc(label) + '</div><div class="gs mini">' + esc(hint || '') + '</div></div>' +
      '<label class="sw' + (dis ? ' dis' : '') + '"><input type="checkbox" data-upc-pref="' + k + '"' + (prefs[k] ? ' checked' : '') + (dis ? ' disabled' : '') + '><i></i></label></div>';
    return '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>' + ico('settings') + ' ' + esc(L.t('up.title')) + '</h3></div>' +
      box('check', L.t('up.autoCheck'), L.t('up.autoCheckHint')) +
      box('download', L.t('up.autoDownload'), '', !prefs.check) +
      box('install', L.t('up.autoInstall'), L.t('up.autoInstallHint'), !prefs.download) +
      '<div class="note" style="margin-top:8px">' + esc(L.t('up.feed')) + ' · <span class="mono mini">' + esc((remote && remote.source) || '—') + '</span><br>' + esc(L.t('up.feedHint')) + '</div>' +
      '</div>';
  }

  function envHtml() {
    const S1 = status || {};
    const ok = S1.jarPath && S1.jarWritable;
    return '<div class="card" style="margin-bottom:16px"><div class="card-h"><h3>' + ico('server') + ' ' + esc(L.t('up.current')) + '</h3>' +
      '<div class="r"><span class="badge ok">' + esc(S1.version || '—') + '</span></div></div>' +
      kvMono(L.t('up.jarPath'), S1.jarPath || '—') +
      kvMono(L.t('up.jarWritable'), S1.jarWritable ? L.t('up.jarWritableYes') : L.t('up.jarWritableNo')) +
      kvMono(L.t('up.deploy'), S1.containerized ? L.t('up.deployContainer') : L.t('up.deployBare')) +
      kvMono(L.t('up.restartNote'), restartHint(S1.restartMode)) +
      kvMono(L.t('up.rollback'), S1.backupVersion || L.t('up.backupNone')) +
      (ok ? '' : '<div class="note red" style="margin-top:11px">' + esc(L.t('up.jarWritableNo')) + '</div>') +
      '</div>';
  }

  function stagedHtml() {
    const sv = status && status.stagedVersion;
    const staged = fileInfo || (busy === 'upload' ? null : sv && newer(sv) ? { version: sv } : null);
    if (!staged) return '';
    return '<div class="card up-staged" style="margin-bottom:16px">' +
      '<div class="card-h"><h3>' + ico('check') + ' ' + esc(L.t('up.ready')) + '</h3>' +
      (staged.version ? '<div class="r"><span class="badge ok">v' + esc(staged.version) + '</span></div>' : '') + '</div>' +
      kvMono(L.t('up.target'), staged.version + (staged.currentVersion ? ' (' + staged.currentVersion + ')' : '')) +
      (staged.fileName ? kvMono(L.t('up.size'), staged.fileName + ' · ' + bytes(staged.sizeBytes)) : '') +
      (staged.sha256 ? kvMono(L.t('up.sha'), staged.sha256) : '') +
      (staged.builtAt ? kvMono(L.t('up.builtAt'), staged.builtAt) : '') +
      '<div class="rowflex" style="gap:8px;margin-top:14px"><button class="btn pri" data-upc-apply>' + ico('bolt') + esc(L.t('up.applyNow')) + '</button>' +
      '<button class="btn ghost" data-upc-discard>' + esc(L.t('up.discardShort')) + '</button></div></div>';
  }

  function importHtml() {
    const busyUp = busy === 'upload';
    return '<div class="card up-import" style="margin-bottom:16px">' +
      '<div class="card-h"><h3>' + ico('layers') + ' ' + esc(L.t('up.importTitle')) + '</h3></div>' +
      '<div class="dropzone' + (busyUp ? ' on' : '') + '" data-upc-drop tabindex="0" role="button">' +
      '<input type="file" accept=".zip,.lup" id="upFile" hidden>' +
      '<svg class="dz-ico" viewBox="0 0 24 24" aria-hidden="true"><use href="#i-dl"/></svg>' +
      '<div class="dz-t">' + esc(L.t('up.dropTitle')) + '</div>' +
      '<div class="dz-s">' + esc(L.t('up.dropHint')) + '</div>' +
      (uploadName ? '<div class="dz-file">' + ico('check') + esc(uploadName) + '</div>' : '') +
      '</div>' +
      '<div class="note" style="margin-top:12px">' + esc(L.t('up.importHint')) + '</div>' +
      '<div id="upcProg">' + (prog && prog.where === 'import' ? progBlock(prog) : '') + '</div>' +
      '</div>';
  }

  function rollbackHtml() {
    const v = status && status.backupVersion;
    return '<div class="card"><div class="card-h"><h3>' + ico('clock') + ' ' + esc(L.t('up.rollback')) + '</h3></div>' +
      '<div class="note">' + esc(L.t('up.rollbackHint')) + '</div>' +
      '<div class="rowflex" style="margin-top:12px"><button class="btn danger" data-upc-rollback' + (v ? '' : ' disabled') + '>' + ico('sync') + esc(L.t('up.rollback')) + ' ' + esc(v || '') + '</button></div></div>';
  }

  function html() {
    if (!status) { if (!err && !busy) load(false); return loading(); }
    if (!busy) { prog = null; }
    return '<div id="upcTop">' + restartNote + '</div>' + heroHtml() + optsHtml() + envHtml() + stagedHtml() + importHtml() + rollbackHtml();
  }

  function msg(text, tone) {
    const el = document.getElementById('upcMsg');
    if (el) el.innerHTML = '<div class="note ' + (tone || '') + '" style="margin-top:12px">' + esc(text) + '</div>';
  }

  function stopRemotePoll() {
    if (remotePoll) { clearInterval(remotePoll); remotePoll = null; }
  }

  function startRemotePoll() {
    stopRemotePoll();
    const tick = () => LantaiRuntime.request('api/lantai/v1/system/upgrade/remote/status')
      .then(p => {
        if (!p || p.idle || p.phase === 'done' || p.phase === 'error') return;
        setProg(prog ? prog.where : 'hero', p.phase, p.pct);
      })
      .catch(() => { });
    tick();
    remotePoll = setInterval(tick, 400);
  }

  function stopInspectPoll() {
    if (inspectPoll) { clearInterval(inspectPoll); inspectPoll = null; }
  }

  function startInspectPoll(where) {
    stopInspectPoll();
    const tick = () => LantaiRuntime.request('api/lantai/v1/system/upgrade/inspect/status')
      .then(p => {
        if (!p || p.idle || !p.phase) return;
        if (p.phase === 'done' || p.phase === 'error') return;
        setProg(where, p.phase, p.pct);
      })
      .catch(() => { });
    tick();
    inspectPoll = setInterval(tick, 350);
  }

  function stopRestartPoll() {
    if (restartPoll) { clearInterval(restartPoll); restartPoll = null; }
    if (restartTick) { clearInterval(restartTick); restartTick = null; }
  }

  function restartVeil() {
    let el = document.getElementById('upcVeil');
    if (el) return el;
    el = document.createElement('div');
    el.id = 'upcVeil';
    el.className = 'upveil';
    el.innerHTML = '<div class="upveil-card">' +
      '<div class="upveil-ring"></div>' +
      '<h3>' + esc(L.t('up.restartTitle')) + '</h3>' +
      '<p>' + esc(L.t('up.restartMsg')) + '</p>' +
      '<div class="uprog"><i class="indet"></i></div>' +
      '<div class="upveil-sub" id="upcVeilSub"></div>' +
      '</div>';
    document.body.appendChild(el);
    document.body.style.overflow = 'hidden';
    return el;
  }

  function veilSub(text, withBtn) {
    const box = document.getElementById('upcVeilSub');
    if (!box) return;
    box.innerHTML = '<span>' + esc(text) + '</span>' +
      (withBtn ? '<button class="btn sm" id="upcVeilReload">' + esc(L.t('up.restartReload')) + '</button>' : '');
    if (withBtn) {
      const b = document.getElementById('upcVeilReload');
      if (b) b.onclick = () => location.reload();
    }
  }

  function waitRestart() {
    stopRestartPoll();
    restartVeil();
    const started = Date.now();
    let down = false;
    veilSub(L.t('up.restartWait', { n: 0 }));
    restartTick = setInterval(() => {
      const secs = Math.floor((Date.now() - started) / 1000);
      if (secs > 180) {
        stopRestartPoll();
        veilSub(L.t('up.restartTimeout'), true);
        return;
      }
      veilSub(L.t('up.restartWait', { n: secs }));
    }, 1000);
    restartPoll = setInterval(() => {
      fetch((LantaiRuntime.base || '/') + 'actuator/health', { cache: 'no-store', credentials: 'same-origin' })
        .then(r => {
          if (!r.ok) { down = true; return; }
          if (down || Date.now() - started > 30000) { stopRestartPoll(); location.reload(); }
        })
        .catch(() => { down = true; });
    }, 2000);
  }

  function doFetch() {
    const rel = newestRelease();
    if (!rel || busy) return;
    busy = 'fetch';
    setProg('hero', 'download', 0);
    repaint();
    startRemotePoll();
    LantaiRuntime.request('api/lantai/v1/system/upgrade/fetch', {
      method: 'POST',
      body: { version: rel.version, assetUrl: rel.assetUrl, assetName: rel.assetName }
    }).then(d => {
      stopRemotePoll();
      busy = '';
      clearProg();
      remote = null;
      toast(L.t('up.ready') + ' · v' + d.version, 'ok');
      load(false).then(() => { if (prefs.install) doApply(); });
    }).catch(e => {
      stopRemotePoll();
      busy = '';
      clearProg();
      repaint();
      toast(onlineErrText(e), 'err', 8000);
    });
  }

  function doVerify() {
    if (busy) return;
    busy = 'verify';
    setProg('hero', 'verify', -1);
    repaint();
    LantaiRuntime.request('api/lantai/v1/system/upgrade/verify', { method: 'POST' })
      .then(d => { busy = ''; clearProg(); toast(L.t('up.ready') + ' · v' + d.version, 'ok'); load(false); })
      .catch(e => { busy = ''; clearProg(); repaint(); toast(onlineErrText(e), 'err', 8000); });
  }

  function doApply() {
    const staged = fileInfo || (remote && remote.cached) || null;
    const target = (staged && staged.version) || (status && status.stagedVersion) || '?';
    if (!window.confirm(L.t('up.applyNow') + ' ' + target + ' ?')) return;
    busy = 'apply';
    setProg('hero', 'backup', 0);
    repaint();
    startInspectPoll('hero');
    let settled = false;
    const settle = () => {
      if (settled) return;
      settled = true;
      stopInspectPoll();
      busy = '';
      restartVeil();
      clearProg();
      repaint();
      waitRestart();
    };
    const guard = setTimeout(() => {
      if (settled) return;
      toast(L.t('up.restartPending'), 'info', 8000);
      settle();
    }, 12000);
    LantaiRuntime.request('api/lantai/v1/system/upgrade/apply', { method: 'POST' })
      .then(d => {
        if (settled) return;
        clearTimeout(guard);
        restartNote = '<div class="note gold">' + esc(d.version) + ' · ' + esc(restartHint(d.restartMode)) + '</div>';
        settle();
      })
      .catch(e => {
        if (settled) return;
        clearTimeout(guard);
        busy = '';
        clearProg();
        repaint();
        toast(onlineErrText(e), 'err', 9000);
      });
  }

  function doRollback() {
    const v = status && status.backupVersion;
    if (!v || !window.confirm(L.t('up.rollback') + ' ' + v + ' ?')) return;
    busy = 'rollback';
    setProg('hero', 'copy', 0);
    repaint();
    startInspectPoll('hero');
    let settled = false;
    const settle = () => {
      if (settled) return;
      settled = true;
      stopInspectPoll();
      busy = '';
      restartVeil();
      clearProg();
      repaint();
      waitRestart();
    };
    const guard = setTimeout(() => { if (!settled) settle(); }, 12000);
    LantaiRuntime.request('api/lantai/v1/system/upgrade/rollback', { method: 'POST' })
      .then(d => {
        if (settled) return;
        clearTimeout(guard);
        restartNote = '<div class="note gold">' + esc(d.version) + ' · ' + esc(restartHint(d.restartMode)) + '</div>';
        settle();
      })
      .catch(e => {
        if (settled) return;
        clearTimeout(guard);
        busy = '';
        clearProg();
        repaint();
        toast(onlineErrText(e), 'err', 9000);
      });
  }

  function uploadErr(xhr) {
    const raw = String(xhr.responseText || '');
    try {
      const j = JSON.parse(raw);
      if (j && j.message) return String(j.message);
    } catch (e) { }
    return raw.slice(0, 300) || ('HTTP ' + xhr.status);
  }

  function doUpload(picked) {
    const f = document.getElementById('upFile');
    const file = picked || (f && f.files && f.files[0]);
    if (!file) return toast(L.t('up.dropTitle'), 'err');
    fileInfo = null;
    uploadName = file.name;
    busy = 'upload';
    setProg('import', 'upload', 0);
    repaint();
    const fd = new FormData();
    fd.append('file', file);
    const xhr = new XMLHttpRequest();
    xhr.open('POST', LantaiRuntime.base + 'api/lantai/v1/system/upgrade/inspect', true);
    xhr.withCredentials = true;
    xhr.upload.onprogress = ev => {
      if (!ev.lengthComputable) return;
      setProg('import', 'upload', pctOf(ev.loaded, ev.total));
    };
    xhr.upload.onload = () => {
      setProg('import', 'unzip', 0);
      startInspectPoll('import');
    };
    xhr.onload = () => {
      stopInspectPoll();
      busy = '';
      clearProg();
      let data = null;
      if (xhr.status >= 200 && xhr.status < 300) {
        try { data = JSON.parse(xhr.responseText); } catch (_) { data = null; }
      }
      if (data) {
        fileInfo = data;
        toast(L.t('up.ready') + ' · v' + data.version, 'ok');
        repaint();
        return;
      }
      repaint();
      toast(uploadErr(xhr), 'err', 9000);
    };
    xhr.onerror = () => { stopInspectPoll(); busy = ''; clearProg(); repaint(); toast('HTTP', 'err'); };
    xhr.onabort = () => { stopInspectPoll(); busy = ''; clearProg(); repaint(); };
    xhr.send(fd);
  }

  function doDiscard() {
    LantaiRuntime.request('api/lantai/v1/system/upgrade/downloads', { method: 'DELETE' })
      .then(() => { fileInfo = null; uploadName = ''; remote = null; toast(L.t('up.discardShort'), 'ok'); load(true); })
      .catch(e => toast(onlineErrText(e), 'err'));
  }

  function maybeAuto() {
    if (!remote) return;
    const rel = newestRelease();
    if (!rel || !newer(rel.version)) return;
    if (prefs.download && !busy && !(remote.cached && newer(remote.cached.version))) doFetch();
  }

  function wire(box) {
    const on = (sel, fn) => { box.querySelectorAll(sel).forEach(el => el.onclick = fn); };
    on('[data-upc-check]', () => {
      if (busy) return;
      busy = 'check';
      repaint();
      Promise.all([loadStatus(), loadRemote(true)])
        .catch(() => { })
        .then(() => {
          busy = '';
          repaint();
          const rel = newestRelease();
          if (!status || !remote || remote.reachable === false) {
            const why = remote && remote.error ? ' · ' + String(remote.error).slice(0, 140) : '';
            toast(L.t('up.unreachable') + why, 'err', 7000);
          } else if (!rel) {
            toast(L.t('up.norelease'), 'warn', 6000);
          } else if (newer(rel.version)) {
            toast(L.t('up.foundNew', { v: rel.version }), 'ok', 6000);
          } else {
            toast(L.t('up.uptodateToast', { v: status.version }), 'ok', 5000);
          }
        });
    });
    on('[data-upc-fetch]', doFetch);
    on('[data-upc-verify]', doVerify);
    on('[data-upc-apply]', doApply);
    on('[data-upc-rollback]', doRollback);
    on('[data-upc-discard]', doDiscard);
    const dz = box.querySelector('[data-upc-drop]');
    if (dz) {
      const inp = dz.querySelector('#upFile');
      if (inp) inp.onclick = e => e.stopPropagation();
      dz.onclick = e => {
        if (e.target === inp || !inp || busy) return;
        inp.click();
      };
      dz.onkeydown = e => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        e.preventDefault();
        if (inp && !busy) inp.click();
      };
      if (inp) inp.onchange = () => { if (inp.files && inp.files[0]) doUpload(inp.files[0]); };
      dz.ondragover = e => { e.preventDefault(); dz.classList.add('over'); };
      dz.ondragleave = () => dz.classList.remove('over');
      dz.ondrop = e => {
        e.preventDefault();
        dz.classList.remove('over');
        if (busy) return;
        const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
        if (f) doUpload(f);
      };
    }
    box.querySelectorAll('[data-upc-pref]').forEach(el => el.onchange = () => {
      prefs[el.dataset.upcPref] = el.checked;
      if (el.dataset.upcPref === 'check' && !el.checked) { prefs.download = false; prefs.install = false; }
      if (el.dataset.upcPref === 'download' && !el.checked) prefs.install = false;
      savePrefs();
      repaint();
    });
    paintProg();
    if (!status && !busy && !err) load(false);
    else if (status && !remote && !busy) load(!lastCheck() || Date.now() - lastCheck() > 4 * 3600 * 1000);
  }

  window.LantaiUpdateCenter = { html: html, wire: wire, load: load, state: () => ({ status, remote, prefs }) };
})();
