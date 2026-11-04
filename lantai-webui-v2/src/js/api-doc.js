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
