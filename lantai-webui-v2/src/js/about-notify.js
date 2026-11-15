(function () {
  const REPO = 'https://github.com/punk64/Lantai';
  const READ_KEY = 'lantai-notify-read';
  let remote = null, busy = false, failed = '';

  function readSet() {
    try {
      const v = JSON.parse(localStorage.getItem(READ_KEY) || '[]');
      return Array.isArray(v) ? v : [];
    } catch (e) { return []; }
  }

  function markRead(list) {
    const set = readSet();
    (list || []).forEach(v => { if (v && set.indexOf(v) < 0) set.push(v); });
    try { localStorage.setItem(READ_KEY, JSON.stringify(set.slice(-200))); } catch (e) { }
  }

  function versionParts(v) {
    return String(v || '').replace(/^v/i, '').split(/[.\-+_]/).map(x => parseInt(x, 10) || 0);
  }

  function cmp(a, b) {
    const pa = versionParts(a), pb = versionParts(b);
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
      const x = pa[i] || 0, y = pb[i] || 0;
      if (x !== y) return x - y;
    }
    return 0;
  }

  function current() {
    const s = sysInfo();
    return s.version || (window.LantaiRuntime && LantaiRuntime.system && LantaiRuntime.system.version) || '';
  }

  function newest() {
    const list = (remote && remote.releases) || [];
    let best = null;
    list.forEach(r => {
      if (!r || r.prerelease || !r.version) return;
      if (!best || cmp(r.version, best.version) > 0) best = r;
    });
    return best;
  }

  function unreadCount() {
    const cur = current();
    const set = readSet();
    const list = (remote && remote.releases) || [];
    return list.filter(r => r && !r.prerelease && r.version && (!cur || cmp(r.version, cur) > 0) && set.indexOf(r.version) < 0).length;
  }

  function paintDot() {
    const n = unreadCount();
    ['notifyDot', 'notifyDotTop'].forEach(id => {
      const el = document.getElementById(id);
      if (el) el.style.display = n > 0 ? '' : 'none';
    });
  }

  function loadRemote(force) {
    if (!isAdmin() || busy) return Promise.resolve(remote);
    if (remote && !force && remote.__at && Date.now() - remote.__at < 4 * 3600 * 1000) return Promise.resolve(remote);
    busy = true;
    failed = '';
    return LantaiRuntime.request('api/lantai/v1/system/upgrade/remote' + (force ? '?refresh=true' : ''))
      .then(d => { remote = d || null; if (remote) remote.__at = Date.now(); })
      .catch(e => { failed = onlineErrText(e); })
      .then(() => { busy = false; paintDot(); return remote; });
  }

  function aboutRow(k, v, mono) {
    return '<div class="kv"><span class="k">' + esc(k) + '</span><span class="v' + (mono ? ' mono' : '') + '">' + esc(v || '—') + '</span></div>';
  }

  function openAbout() {
    const s = sysInfo();
    const jvm = s.jvm || {};
    const os = s.os || {};
    const plat = [os.name, os.arch].filter(Boolean).join(' / ');
    openModal({
      title: L.t('about.title'),
      width: 520,
      body: '<div style="display:flex;align-items:center;gap:14px;margin-bottom:16px">' +
        '<svg viewBox="0 0 512 512" style="width:52px;height:52px;border-radius:12px;box-shadow:0 0 0 1px var(--acc-line)"><use href="#logo-full"/></svg>' +
        '<div><div style="font-family:var(--f-disp);font-size:21px;letter-spacing:4px;color:var(--acc-2)">兰台 Lantai</div>' +
        '<div class="mini" style="color:var(--tx-3)">v' + esc(current() || '—') + '</div></div></div>' +
        aboutRow(L.t('about.version'), current()) +
        aboutRow(L.t('about.runtime'), plat) +
        aboutRow('Java', jvm.version || '') +
        aboutRow(L.t('about.repo'), 'github.com/punk64/Lantai', true) +
        aboutRow(L.t('about.license'), 'MIT') +
        aboutRow(L.t('about.upstream'), 'Komga (gotson/komga) · MIT') +
        '<div class="rowflex" style="margin-top:16px;gap:8px">' +
        '<button class="btn pri" data-about-update>' + ico('sync') + esc(L.t('about.check')) + '</button>' +
        '<button class="btn" data-about-repo>' + ico('ext') + 'GitHub</button></div>',
      foot: '<button class="btn" data-x>' + esc(L.t('common.close')) + '</button>',
      after() {
        const x = document.querySelector('#modal [data-x]');
        if (x) x.onclick = closeModal;
        const up = document.querySelector('#modal [data-about-update]');
        if (up) up.onclick = () => { closeModal(); go('/settings/upgrade'); };
        const rp = document.querySelector('#modal [data-about-repo]');
        if (rp) rp.onclick = () => window.open(REPO, '_blank');
      },
    });
  }

  function releaseItem(r) {
    const set = readSet();
    const fresh = set.indexOf(r.version) < 0 && (!current() || cmp(r.version, current()) > 0);
    const notes = String(r.notes || '').trim();
    return '<div class="ntf' + (fresh ? ' fresh' : '') + '">' +
      '<div class="ntf-h"><b>v' + esc(r.version) + '</b>' +
      (r.name && r.name !== ('v' + r.version) ? '<span class="mini">' + esc(r.name) + '</span>' : '') +
      '<span class="mini" style="margin-left:auto">' + esc((r.publishedAt || '').slice(0, 10)) + '</span></div>' +
      (notes ? '<div class="ntf-b">' + esc(notes).replace(/\n/g, '<br>') + '</div>' : '<div class="ntf-b mini" style="color:var(--tx-4)">' + esc(L.t('up.noNotes')) + '</div>') +
      (r.assetName ? '<div class="ntf-f"><span class="mini mono">' + esc(r.assetName) + '</span>' +
        '<button class="btn xs" data-ntf-go>' + esc(L.t('notify.view')) + '</button></div>' : '') +
      '</div>';
  }

  function openNotify() {
    const list = (remote && remote.releases) || [];
    const body = busy && !list.length
      ? '<div class="empty" style="padding:40px 0"><div class="spinner" style="margin:0 auto 12px"></div><div class="mini">' + esc(L.t('api.loading')) + '</div></div>'
      : (failed ? '<div class="note red" style="margin-bottom:12px">' + esc(L.t('up.unreachable')) + ' · ' + esc(failed) + '</div>' : '') +
        (list.length ? list.map(releaseItem).join('') : '<div class="empty" style="padding:40px 0">' + ico('bell') + '<p>' + esc(L.t('notify.empty')) + '</p></div>');
    openDrawer({
      title: L.t('notify.title'),
      width: 460,
      body: '<div class="rowflex" style="margin-bottom:12px;gap:8px">' +
        '<button class="btn sm" data-ntf-check>' + ico('sync') + esc(L.t('up.check')) + '</button>' +
        '<button class="btn sm ghost" style="margin-left:auto" data-ntf-readall>' + esc(L.t('notify.markAll')) + '</button></div>' + body,
      after() {
        const ck = document.querySelector('#drawer [data-ntf-check]');
        if (ck) ck.onclick = () => loadRemote(true).then(() => refreshNotify());
        const ra = document.querySelector('#drawer [data-ntf-readall]');
        if (ra) ra.onclick = () => {
          markRead(((remote && remote.releases) || []).map(r => r.version));
          paintDot();
          refreshNotify();
        };
        document.querySelectorAll('#drawer [data-ntf-go]').forEach(b => b.onclick = () => { closeDrawer(); go('/settings/upgrade'); });
        markRead(((remote && remote.releases) || []).map(r => r.version));
        paintDot();
      },
    });
    if (!remote && !busy) loadRemote(false).then(() => refreshNotify());
  }

  function refreshNotify() {
    const box = document.querySelector('#drawer');
    if (!box || !box.classList.contains('on')) return;
    const t = document.querySelector('#drawer #dTitle');
    if (t && t.textContent !== L.t('notify.title')) return;
    const list = (remote && remote.releases) || [];
    const b = document.querySelector('#drawer #dBody');
    if (!b) return;
    const head = b.querySelector('.rowflex');
    const rest = list.length ? list.map(releaseItem).join('') : '<div class="empty" style="padding:40px 0">' + ico('bell') + '<p>' + esc(L.t('notify.empty')) + '</p></div>';
    b.innerHTML = (head ? head.outerHTML : '') + rest;
    const ck = b.querySelector('[data-ntf-check]');
    if (ck) ck.onclick = () => loadRemote(true).then(() => refreshNotify());
    const ra = b.querySelector('[data-ntf-readall]');
    if (ra) ra.onclick = () => { markRead(list.map(r => r.version)); paintDot(); refreshNotify(); };
    b.querySelectorAll('[data-ntf-go]').forEach(x => x.onclick = () => { closeDrawer(); go('/settings/upgrade'); });
  }

  function bind() {
    const a = document.getElementById('aboutBtn');
    if (a) a.onclick = openAbout;
    const n = document.getElementById('notifyBtn');
    if (n) n.onclick = openNotify;
    const nt = document.getElementById('notifyTop');
    if (nt) nt.onclick = openNotify;
    loadRemote(false).then(() => paintDot());
  }

  window.LantaiAboutNotify = { about: openAbout, notify: openNotify, dot: paintDot, remote: () => remote, reload: loadRemote };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bind);
  else bind();
})();
