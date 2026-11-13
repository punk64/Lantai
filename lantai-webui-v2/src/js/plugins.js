(function () {
  var LP = {
    list: null, err: '', busy: false,
    idx: null, idxErr: '', idxBusy: false, idxUrl: '',
    logsErr: '', logsId: '', logs: null,
    caps: null, capsErr: '', capsBusy: false, audio: null, audioUrl: '',
    jobs: null, jobId: '', upload: null, pollTimer: null, handled: {},
    engine: {}, view: 'grid', stateTimer: null, stateBusy: false,
    loadedAt: 0, stateErr: '', panels: null, panelsBusy: false,
    pending: false, capsAt: 0, panelsAt: 0, grantsAt: {}, known: null
  };

  function storedView() {
    try {
      var v = localStorage.getItem('lantai.plugins.view');
      return v === 'list' ? 'list' : 'grid';
    } catch (e) { return 'grid'; }
  }
  LP.view = storedView();

  function setView(v) {
    LP.view = v === 'list' ? 'list' : 'grid';
    try { localStorage.setItem('lantai.plugins.view', LP.view); } catch (e) { }
    rerender();
  }

  var CAP_CN = {
    'library.read': L.t('读取书库'),
    'metadata.write': L.t('写元数据'),
    'progress.write': L.t('写阅读进度'),
    'library.write': L.t('改动书库'),
    'library.import': L.t('导入书籍'),
    'event.observe': L.t('订阅事件'),
    'task.schedule': L.t('定时任务'),
    'storage.blob': L.t('私有存储'),
    'tts.speak': L.t('朗读合成'),
    'notify.send': L.t('发送通知'),
    'credential.store': L.t('保存凭据'),
    'source.provide': L.t('提供在线源'),
    'metadata.provide': L.t('提供书籍信息'),
    'font.provide': L.t('提供字体'),
    'page.transform': L.t('处理页图'),
    'text.transform': L.t('处理正文'),
    'search.extend': L.t('扩展搜索'),
    'convert.provide': L.t('格式转换'),
    'ui.panel': L.t('插件页面')
  };

  function stateText(s) {
    if (s === 'READY') return L.t('插件就绪');
    if (s === 'PENDING') return L.t('待启动');
    if (s === 'IDLE') return L.t('已休眠');
    if (s === 'DISABLED') return L.t('已停用');
    if (s === 'UNAVAILABLE') return L.t('不可用');
    if (s === 'AUTH_FAILED') return L.t('鉴权失败');
    if (s === 'INCOMPATIBLE') return L.t('版本不兼容');
    return s || '';
  }

  function stateClass(s) {
    if (s === 'READY') return 'ok';
    if (s === 'PENDING' || s === 'IDLE') return 'gold';
    if (s === 'UNAVAILABLE') return 'warn';
    if (s === 'AUTH_FAILED' || s === 'INCOMPATIBLE') return 'bad';
    return '';
  }

  function modeText(m) {
    if (m === 'MANAGED') return L.t('受管进程');
    if (m === 'EXTERNAL') return L.t('外部服务');
    return m || '';
  }

  function capText(c) {
    return CAP_CN[c] || c;
  }

  function stamp(v) {
    if (!v) return '—';
    return String(v).replace('T', ' ').slice(0, 16);
  }

  function rerender() {
    var box = document.getElementById('plBody');
    if (box) window.lpRenderPlugins(box);
  }

  function stageKeys(job) {
    var repo = job.phase === 'download' || (job.stages && typeof job.stages.download === 'number');
    return repo ? ['download', 'extract', 'install'] : ['upload', 'extract', 'install'];
  }

  function stageName(key) {
    if (key === 'download') return L.t('下载插件包');
    if (key === 'extract') return L.t('解压插件包');
    if (key === 'install') return L.t('安装并启动');
    return L.t('上传插件包');
  }

  function activeStage(job) {
    if (job.phase === 'done') return 'done';
    if (job.phase === 'error') return '';
    if (job.phase === 'register' || job.phase === 'start' || job.phase === 'handshake') return 'install';
    return job.phase;
  }

  function stagePct(job, key) {
    var stages = job.stages || {};
    if (typeof stages[key] === 'number') return stages[key];
    if (key === 'upload') return job.phase === 'upload' ? -1 : 0;
    return 0;
  }

  function stageRow(pct, state, label) {
    var done = state === 'done';
    var indet = state === 'active' && pct < 0;
    var shown = done ? 100 : Math.max(0, pct);
    var text = done ? '100%' : (pct < 0 ? '—' : Math.round(shown) + '%');
    var dim = state === 'idle' ? ' style="color:var(--tx-3)"' : '';
    var fill = indet ? 'width:100%;opacity:.35' : 'width:' + shown + '%';
    return '<div style="margin-bottom:9px">' +
      '<div class="rowflex" style="justify-content:space-between;gap:12px;margin-bottom:5px">' +
      '<span class="mini"' + dim + '>' + esc(label) + '</span>' +
      '<span class="mini mono">' + text + '</span></div>' +
      '<div class="uprog"><i style="' + fill + '"></i></div></div>';
  }

  function progressHtml(view) {
    var keys = stageKeys(view);
    var active = activeStage(view);
    var cursor = keys.length;
    for (var index = 0; index < keys.length; index++) {
      if (keys[index] === active) {
        cursor = index;
        break;
      }
    }
    var rows = keys.map(function (key, position) {
      if (position < cursor) return stageRow(100, 'done', stageName(key));
      if (position === cursor) return stageRow(stagePct(view, key), 'active', stageName(key));
      return stageRow(0, 'idle', stageName(key));
    }).join('');
    return rows + (view.label ? '<div class="mini" style="margin-top:2px;color:var(--tx-3)">' + esc(L.ts(view.label)) + '</div>' : '');
  }

  function findJob(id) {
    return (LP.jobs || []).filter(function (j) { return j.jobId === id; })[0] || null;
  }

  function jobTitle(j) {
    var installing = j.kind !== 'upgrade';
    if (j.phase === 'error') return installing ? L.t('安装失败') : L.t('升级失败');
    if (j.finished) return installing ? L.t('安装完成') : L.t('升级完成');
    return installing ? L.t('正在安装插件') : L.t('正在升级插件');
  }

  function jobCard(j) {
    var failed = j.phase === 'error';
    var badge = failed
      ? '<span class="badge bad">' + L.t('失败') + '</span>'
      : j.finished
        ? '<span class="badge ok">' + L.t('完成') + '</span>'
        : '<span class="badge gold">' + L.t('进行中') + '</span>';
    var name = j.pluginName || j.pluginId || '';
    return '<div class="card" style="margin-bottom:14px">' +
      '<div class="card-h"><h3>' + ico('upload') + ' ' + esc(jobTitle(j)) +
      (name ? ' <span class="mini mono" style="color:var(--tx-3)">' + esc(name) + '</span>' : '') +
      ' ' + badge + '</h3>' +
      '<div class="r">' + (j.finished ? '<button class="btn xs" data-lp-job="' + esc(j.jobId) + '">' + ico('check') + ' ' + L.t('知道了') + '</button>' : '') + '</div></div>' +
      (failed ? '<div class="note bad">' + esc(L.ts(j.error || j.label)) + '</div>' : progressHtml(j)) +
      '<div class="mini" style="margin-top:8px;color:var(--tx-3)">' + L.t('开始于 ') + esc(stamp(j.startedDate)) + '</div>' +
      '</div>';
  }

  function renderJobs() {
    var box = document.getElementById('lpJobs');
    if (box) {
      box.innerHTML = (LP.jobs || []).map(jobCard).join('');
      box.querySelectorAll('[data-lp-job]').forEach(function (el) {
        el.onclick = function () { dismissJob(el.dataset.lpJob); };
      });
    }
    paintModalProgress();
  }

  function paintModalProgress() {
    var el = document.getElementById('lpProg');
    if (!el) return;
    if (LP.upload) {
      el.innerHTML = progressHtml({ phase: 'upload', pct: LP.upload.pct, label: LP.upload.label, stages: { upload: LP.upload.pct } });
      return;
    }
    var job = findJob(LP.jobId);
    if (!job) {
      el.innerHTML = '';
      return;
    }
    el.innerHTML = job.phase === 'error'
      ? '<div class="note bad">' + esc(job.error || job.label) + '</div>'
      : progressHtml(job);
  }

  function stopJobs() {
    if (LP.pollTimer) {
      clearTimeout(LP.pollTimer);
      LP.pollTimer = null;
    }
  }

  function scheduleJobs() {
    stopJobs();
    if (!document.getElementById('lpJobs')) return;
    var running = (LP.jobs || []).some(function (j) { return !j.finished; });
    if (!running) return;
    LP.pollTimer = setTimeout(loadJobs, 1000);
  }

  function loadJobs() {
    if (!window.LantaiRuntime) return;
    LantaiRuntime.request('api/lantai/v1/plugins/install/jobs')
      .then(function (rows) {
        LP.jobs = Array.isArray(rows) ? rows : [];
        renderJobs();
        settleJobs();
        scheduleJobs();
      })
      .catch(function () {
        scheduleJobs();
      });
  }

  function settleJobs() {
    var fresh = (LP.jobs || []).filter(function (j) { return j.finished && !LP.handled[j.jobId]; });
    if (!fresh.length) return;
    var reload = false;
    fresh.forEach(function (j) {
      LP.handled[j.jobId] = true;
      if (j.phase === 'error') {
        toast((j.kind === 'upgrade' ? L.t('升级失败：') : L.t('安装失败：')) + (j.error || j.label), 'err');
        var btn = document.getElementById('lpInstall') || document.getElementById('lpUpDo');
        if (btn) btn.disabled = false;
        if (LP.jobId === j.jobId) LP.upload = null;
        return;
      }
      reload = true;
      toast(j.label || L.t('已完成'), 'ok');
      if (LP.jobId === j.jobId) {
        LP.jobId = '';
        LP.upload = null;
        closeModalIfOpen();
      }
    });
    if (reload) {
      LP.list = null;
      LP.loadedAt = 0;
      LP.grantedCache = null;
      LP.grantsAt = {};
      LP.capsAt = 0;
      LP.panelsAt = 0;
      if (window.LantaiPluginStateChanged) window.LantaiPluginStateChanged();
      rerender();
    }
  }

  function focusJob(jobId) {
    LP.jobId = jobId;
    LP.upload = null;
    loadJobs();
  }

  function dismissJob(jobId) {
    LantaiRuntime.request('api/lantai/v1/plugins/install/jobs/' + encodeURIComponent(jobId), { method: 'DELETE' })
      .catch(function () { })
      .then(function () {
        LP.jobs = (LP.jobs || []).filter(function (j) { return j.jobId !== jobId; });
        renderJobs();
      });
  }

  function closeModalIfOpen() {
    if (document.getElementById('modal')) closeModal();
  }

  function uploadErrorText(xhr) {
    var text = xhr.responseText || '';
    try {
      var parsed = JSON.parse(text);
      return L.ts(parsed.message || parsed.error || ('HTTP ' + xhr.status));
    } catch (e) { }
    return text || ('HTTP ' + xhr.status);
  }

  function startUpload(url, file, btn) {
    LP.upload = { pct: 1, label: L.t('正在上传 ') + fmtBytes(file.size) };
    paintModalProgress();
    var fd = new FormData();
    fd.append('file', file);
    var xhr = new XMLHttpRequest();
    xhr.open('POST', LantaiRuntime.base + url, true);
    xhr.withCredentials = true;
    xhr.upload.onprogress = function (ev) {
      if (!ev.lengthComputable) return;
      LP.upload = {
        pct: Math.max(1, Math.min(100, Math.round(ev.loaded / ev.total * 100))),
        label: L.t('正在上传 ') + fmtBytes(ev.loaded) + ' / ' + fmtBytes(ev.total)
      };
      paintModalProgress();
    };
    xhr.upload.onload = function () {
      if (!LP.upload) return;
      LP.upload = { pct: 100, label: L.t('上传完成，等待服务端接收') };
      paintModalProgress();
    };
    xhr.onload = function () {
      if (xhr.status >= 200 && xhr.status < 300) {
        var jobId = '';
        try { jobId = JSON.parse(xhr.responseText || '{}').jobId || ''; } catch (e) { }
        if (jobId) return focusJob(jobId);
        LP.upload = null;
        paintModalProgress();
        btn.disabled = false;
        toast(L.t('安装失败：') + L.t('服务端没有返回任务号'), 'err');
        return;
      }
      LP.upload = null;
      paintModalProgress();
      btn.disabled = false;
      toast(L.t('安装失败：') + uploadErrorText(xhr), 'err');
    };
    xhr.onerror = function () {
      LP.upload = null;
      paintModalProgress();
      btn.disabled = false;
      toast(L.t('安装失败：') + L.t('网络中断'), 'err');
    };
    xhr.send(fd);
  }

  function load(force) {
    if (LP.busy) {
      if (force) LP.pending = true;
      return;
    }
    var stale = !LP.loadedAt || Date.now() - LP.loadedAt > 15000;
    if (LP.list !== null && !force && !stale) return;
    if (!window.LantaiRuntime) {
      LP.list = [];
      LP.err = L.t('后端不可用');
      rerender();
      return;
    }
    LP.busy = true;
    LantaiRuntime.request('api/lantai/v1/plugins')
      .then(function (list) {
        LP.list = Array.isArray(list) ? list : [];
        LP.err = '';
        LP.loadedAt = Date.now();
        trackPanelPlugins();
      })
      .catch(function (e) {
        LP.list = LP.list || [];
        LP.err = L.t('加载失败：') + onlineErrText(e);
      })
      .then(function () {
        LP.busy = false;
        LP.engine = {};
        rerender();
        loadEngines();
        if (LP.pending) {
          LP.pending = false;
          load(true);
        }
      });
  }

  function trackPanelPlugins() {
    var seen = LP.known;
    var next = {};
    var fresh = [];
    (LP.list || []).forEach(function (p) {
      next[p.id] = true;
      if (!seen || seen[p.id]) return;
      if ((p.capabilities || []).indexOf('ui.panel') >= 0) fresh.push(p);
    });
    LP.known = next;
    if (fresh.length) warnPanelPlugins(fresh);
  }

  function warnPanelPlugins(fresh) {
    var names = fresh.map(function (p) { return esc(p.name); }).join('、');
    openModal({
      title: L.t('插件页面权限未开启'),
      width: 560,
      body: '<div class="note bad" style="line-height:1.8">' +
        L.t('新装的插件声明了页面入口，宿主默认不授予该权限，需要你手动开启。') +
        '</div>' +
        '<div class="mini" style="margin-top:12px;line-height:1.9">' + names + '<br>' +
        L.t('开启后插件界面会在兰台页面里同源加载，可以读取当前页面数据、以你的登录态调用接口。只给核实过来源的插件开启。') +
        '</div>',
      foot: '<button class="btn" data-x>' + L.t('稍后') + '</button>' +
        '<button class="btn pri" id="lpPanelWarnGo">' + L.t('去授权') + '</button>',
      after: function () {
        $('#modal [data-x]').onclick = closeModal;
        $('#modal #lpPanelWarnGo').onclick = function () {
          closeModal();
          if (fresh[0]) openDetail(fresh[0].id);
        };
      }
    });
  }

  function noticeHtml() {
    return '<div class="note" style="margin-bottom:16px;line-height:1.8">' +
      L.t('本项目不提供、不维护、不分发任何插件或插件仓库。这里只托管第三方插件的运行环境：安装包、索引地址与能力授权都由你自行添加，其来源与合法性由你判断并负责。') +
      '</div>' +
      '<div class="note bad" style="margin-bottom:16px;line-height:1.8">' +
      '<b>' + L.t('插件不是沙箱。') + '</b>' +
      L.t('安装后宿主会在系统里再起一个独立进程，它与兰台同权限——兰台以管理员或 root 运行时，插件进程同样是管理员或 root，可以读写兰台能访问的文件、发起任意外连。装前请核实插件来源与安全性。') +
      '<br><b style="color:var(--bad)">' +
      L.t('插件开发约定只是文本建议，不是安全防护。') +
      '</b>' +
      L.t('宿主能强制的是清单格式、能力白名单、令牌范围与宿主接口鉴权、超时和响应体积上限；插件进程自身的行为宿主无法约束。') +
      '</div>';
  }

  function shellHtml() {
    return '<div class="card" style="margin-bottom:16px">' +
      '<div class="card-h"><h3>' + ico('puzzle') + ' ' + L.t('安装插件') + '</h3>' +
      '<div class="r"><button class="btn sm" data-lp-guide>' + ico('file') + ' ' + L.t('开发指南') + '</button> ' +
      '<button class="btn sm" data-lp-open-install>' + ico('plus') + ' ' + L.t('添加') + '</button></div></div>' +
      '<div class="mini" style="line-height:1.8">' +
      L.t('受管插件由宿主解压、分配端口并拉起进程；外部插件只登记你自己已经跑起来的服务地址。插件装在数据目录的 plugins 下，不随镜像分发，卸载即删。') +
      '</div></div>';
  }

  function pluginsHtml() {
    if (LP.list === null) {
      return '<div class="empty" style="padding:40px"><h4>' + L.t('正在加载插件') + '</h4></div>';
    }
    if (!LP.list.length) {
      return '<div class="empty" style="padding:40px"><h4>' + L.t('还没有安装任何插件') + '</h4><p>' +
        L.t('插件用于补齐宿主不自带的能力，例如朗读合成或通知渠道。') + '</p>' +
        '<button class="btn pri" data-lp-open-install>' + ico('plus') + ' ' + L.t('安装第一个插件') + '</button></div>';
    }
    return toolbarHtml() + (LP.view === 'list' ? listHtml() : gridHtml());
  }

  function toolbarHtml() {
    var ready = (LP.list || []).filter(function (p) { return p.state === 'READY'; }).length;
    return '<div class="pl-bar">' +
      '<span class="mini" id="lpStat">' + L.t('共 ') + LP.list.length + L.t(' 个插件') +
      ' · ' + ready + L.t(' 个就绪') +
      (LP.stateErr ? ' · <span style="color:var(--bad)">' + L.t('状态读取失败') + '</span>' : '') + '</span>' +
      '<div class="r">' +
      '<button class="btn xs" data-lp-mb="' + (LP.view === 'grid' ? 'list' : 'grid') + '">' +
      ico(LP.view === 'grid' ? 'list' : 'grid') + ' ' + (LP.view === 'grid' ? L.t('列表') : L.t('网格')) + '</button>' +
      '<button class="btn xs" data-lp-reload>' + ico('sync') + ' ' + L.t('刷新') + '</button>' +
      '<button class="btn xs pri" data-lp-open-install>' + ico('plus') + ' ' + L.t('安装') + '</button>' +
      '</div></div>';
  }

  function gridHtml() {
    return '<div class="pl-grid">' + LP.list.map(function (p) { return cardTiny(p); }).join('') + '</div>';
  }

  function listHtml() {
    var rows = LP.list.map(function (p) { return listRow(p); }).join('');
    return '<div class="pl-list">' +
      '<div class="pl-row" style="cursor:default;background:var(--bg-3);font-size:11px;color:var(--tx-3);padding:8px 14px">' +
      '<span>' + L.t('插件') + '</span><span>' + L.t('状态') + '</span>' +
      '<span class="pl-hide">' + L.t('端口') + '</span><span style="text-align:right">' + L.t('操作') + '</span></div>' +
      rows + '</div>';
  }

  function statusBadge(p) {
    return '<span class="badge ' + stateClass(p.state) + '" data-lp-st="' + esc(p.id) + '">' + esc(stateText(p.state)) + '</span>';
  }

  function cardTiny(p) {
    var cards = primaryActions(p);
    return '<div class="pl-card' + (p.enabled ? '' : ' off') + '" data-lp-card="' + esc(p.id) + '" tabindex="0" role="button">' +
      '<div class="pl-top">' +
      '<span class="pl-ico">' + ico('puzzle') + '</span>' +
      '<span style="min-width:0;flex:1"><span class="pl-nm">' + esc(p.name) + '</span>' +
      '<span class="pl-sub">v' + esc(p.version || '') + ' · ' + esc(modeText(p.mode)) + '</span></span>' +
      '</div>' +
      '<div class="pl-tags">' + statusBadge(p) + engineBadge(p) +
      (p.enabled ? '' : ' <span class="badge">' + L.t('已停用') + '</span>') + '</div>' +
      '<div class="pl-desc">' + esc(L.ts(p.statusMessage || p.pluginId)) + '</div>' +
      '<div class="pl-acts">' + cards + '</div>' +
      '</div>';
  }

  function listRow(p) {
    return '<div class="pl-row" data-lp-card="' + esc(p.id) + '" tabindex="0" role="button">' +
      '<div style="min-width:0"><div class="pl-nm">' + esc(p.name) + ' <span class="mini mono" style="color:var(--tx-3)">v' + esc(p.version || '') + '</span></div>' +
      '<div class="pl-sub">' + esc(p.pluginId) + '</div></div>' +
      '<div>' + statusBadge(p) + '</div>' +
      '<div class="mini mono pl-hide">' + (p.port ? p.port : '—') + '</div>' +
      '<div style="display:flex;gap:6px;justify-content:flex-end">' + primaryActions(p) + '</div>' +
      '</div>';
  }

  function primaryActions(p) {
    var declared = uiCard(p);
    var allow = declared.length ? declared : ['toggle', 'panel', 'upgrade'];
    var out = [];
    if (allow.indexOf('toggle') >= 0 && p.mode === 'MANAGED') {
      out.push('<button class="btn xs" data-lp-act="toggle" data-lp-id="' + esc(p.id) + '">' + ico('power') + ' ' + (p.enabled ? L.t('停用') : L.t('启用')) + '</button>');
    }
    if (allow.indexOf('panel') >= 0 && hasPanel(p)) {
      out.push('<button class="btn xs" data-lp-gopanel="' + esc(p.id) + '">' + ico('globe') + ' ' + L.t('打开') + '</button>');
    }
    if (allow.indexOf('upgrade') >= 0 && p.mode === 'MANAGED') {
      out.push('<button class="btn xs" data-lp-act="upgrade" data-lp-id="' + esc(p.id) + '">' + ico('upload') + ' ' + L.t('更新') + '</button>');
    }
    out.push('<button class="btn xs" data-lp-detail="' + esc(p.id) + '">' + L.t('详情') + '</button>');
    return out.join('');
  }

  function uiCard(p) {
    var ui = p.ui && p.ui.card;
    if (!ui) return [];
    var rows = Array.isArray(ui.actions) ? ui.actions : null;
    if (!rows) return [];
    var white = ['toggle', 'panel', 'upgrade', 'refresh'];
    return rows.filter(function (x) { return white.indexOf(x) >= 0; });
  }

  function hasPanel(p) {
    if ((p.capabilities || []).indexOf('ui.panel') < 0) return false;
    if (!p.enabled || p.state !== 'READY') return false;
    return !!(LP.panels && LP.panels.some(function (x) { return x.instanceId === p.id; }));
  }

  function openPanel(p) {
    var entry = (LP.panels || []).filter(function (x) { return x.instanceId === p.id; })[0];
    if (!entry) return toast(L.t('这个插件没有可用的面板入口'), 'err');
    location.hash = '#/plugin-panel/' + p.id + '?panel=' + encodeURIComponent(entry.panelId);
  }

  function panelLockNote(declared, granted) {
    if ((declared || []).indexOf('ui.panel') < 0) return '';
    if (granted && granted.indexOf('ui.panel') >= 0) return '';
    return '<div class="note bad" style="margin-top:10px;line-height:1.8">' +
      L.t('页面权限默认关闭，需要在下面的「授权」里手动勾选「插件页面」后才会出现入口。开启后插件界面在兰台页面里同源加载，可读取当前页面数据。') +
      '</div>';
  }

  function openDetail(id) {
    var p = findPlugin(id);
    if (!p) return;
    var declared = p.capabilities || [];
    var granted = LP.grantedCache && LP.grantedCache[p.id];
    var caps = declared.length
      ? declared.map(function (c) {
        var on = granted ? granted.indexOf(c) >= 0 : null;
        var cls = on === true ? 'badge ok' : on === false ? 'badge' : 'badge gold';
        return '<span class="' + cls + '" style="margin:0 6px 6px 0">' + esc(capText(c)) + (on === true ? ' ✓' : '') + '</span>';
      }).join('')
      : '<span class="mini" style="color:var(--tx-3)">' + L.t('未声明能力') + '</span>';
    var tasks = (p.tasks || []).length
      ? (p.tasks || []).map(function (t) {
        return '<div class="mini mono" style="padding:3px 0">' + esc(t.name) + ' · ' + esc(t.cron) + ' · ' + esc(t.endpoint) + ' · ' + t.timeoutSeconds + 's</div>';
      }).join('')
      : '<span class="mini" style="color:var(--tx-3)">' + L.t('无定时任务') + '</span>';
    var facts = [
      [L.t('标识'), '<span class="mono">' + esc(p.pluginId) + '</span>'],
      [L.t('形态'), esc(modeText(p.mode))],
      [L.t('端口'), p.port ? '<span class="mono">' + p.port + '</span>' : '—'],
      [L.t('堆上限'), p.memoryMb ? '<span class="mono">' + p.memoryMb + ' MB</span>' : '—'],
      [L.t('进程'), p.pid ? '<span class="mono">' + p.pid + '</span>' : '—'],
      [L.t('最近心跳'), '<span class="mono">' + esc(stamp(p.lastHealthDate)) + '</span>']
    ].map(function (f) {
      return '<div><b>' + f[0] + '</b><span>' + f[1] + '</span></div>';
    }).join('');
    var schema = p.config && p.config.properties ? p.config : null;
    var settings = {};
    try { settings = JSON.parse(p.settings || '{}') || {}; } catch (e) { settings = {}; }
    var form = schema
      ? Object.keys(schema.properties).map(function (k) { return fieldHtml(k, schema.properties[k] || {}, settings[k]); }).join('')
      : '<span class="mini" style="color:var(--tx-3)">' + L.t('这个插件没有声明配置项。') + '</span>';
    openModal({
      title: esc(p.name) + ' <span class="mini mono" style="color:var(--tx-3)">v' + esc(p.version || '') + '</span>',
      width: 720,
      body:
        '<div class="pl-sec"><h5>' + L.t('概况') + '</h5>' +
        '<div style="margin-bottom:10px">' + statusBadge(p) + engineBadge(p) +
        (p.enabled ? '' : ' <span class="badge">' + L.t('已停用') + '</span>') + '</div>' +
        '<div class="pl-facts">' + facts + '</div>' +
        (p.statusMessage ? '<div class="note gold" style="margin-top:10px;margin-bottom:0">' + esc(L.ts(p.statusMessage)) + '</div>' : '') +
        '</div>' +
        '<div class="pl-sec"><h5>' + L.t('能力') + '</h5>' + caps + panelLockNote(declared, granted) + '</div>' +
        '<div class="pl-sec"><h5>' + L.t('定时任务') + '</h5>' + tasks + '</div>' +
        '<div class="pl-sec"><h5>' + L.t('配置') + '</h5>' + form +
        (schema ? '<div style="margin-top:10px"><button class="btn sm pri" id="lpDetSave">' + ico('check') + ' ' + L.t('保存配置') + '</button>' +
          (p.mode === 'MANAGED' ? ' <span class="mini">' + L.t('保存后受管进程会自动重启以读取新配置') + '</span>' : '') + '</div>' : '') +
        '</div>' +
        '<div class="pl-sec"><h5>' + L.t('数据与维护') + '</h5>' +
        '<div style="display:flex;gap:8px;flex-wrap:wrap">' +
        '<button class="btn sm" data-lp-act="refresh" data-lp-id="' + esc(p.id) + '">' + ico('sync') + ' ' + L.t('重连') + '</button>' +
        '<button class="btn sm" data-lp-act="grants" data-lp-id="' + esc(p.id) + '">' + ico('shield') + ' ' + L.t('授权') + '</button>' +
        '<button class="btn sm" data-lp-act="data" data-lp-id="' + esc(p.id) + '">' + ico('disk') + ' ' + L.t('数据') + '</button>' +
        (p.mode === 'MANAGED' ? '<button class="btn sm" data-lp-act="logs" data-lp-id="' + esc(p.id) + '">' + ico('file') + ' ' + L.t('日志') + '</button>' : '') +
        (p.mode === 'MANAGED' && p.state === 'READY' ? '<button class="btn sm" data-lp-act="release" data-lp-id="' + esc(p.id) + '">' + ico('power') + ' ' + L.t('释放内存') + '</button>' : '') +
        (hasPanel(p) ? '<button class="btn sm" data-lp-gopanel="' + esc(p.id) + '">' + ico('globe') + ' ' + L.t('打开面板') + '</button>' : '') +
        '<button class="btn sm danger" data-lp-act="delete" data-lp-id="' + esc(p.id) + '">' + ico('trash') + ' ' + L.t('卸载') + '</button>' +
        '</div></div>',
      foot: '<button class="btn" data-x>' + L.t('关闭') + '</button>',
      after: function () {
        $('#modal [data-x]').onclick = closeModal;
        var save = $('#modal #lpDetSave');
        if (save) save.onclick = function () { saveSettings(p, save); };
        wire($('#modal'));
        loadGrantsFor(p.id);
      }
    });
  }

  function engineBadge(p) {
    if (p.mode !== 'MANAGED' || p.state !== 'READY') return '';
    var e = LP.engine[p.id];
    if (!e) return '';
    if (e.ready) return ' <span class="badge ok">' + L.t('引擎运行中') + '</span>';
    if (e.error) return ' <span class="badge bad">' + L.t('引擎启动失败') + '</span>';
    return ' <span class="badge gold">' + L.t('引擎启动中') + '</span>';
  }

  function loadEngines() {
    var targets = (LP.list || []).filter(function (p) {
      return p.mode === 'MANAGED' && p.state === 'READY' && (p.capabilities || []).indexOf('source.provide') >= 0;
    });
    targets.forEach(function (p) {
      LantaiRuntime.request('api/lantai/v1/plugins/' + encodeURIComponent(p.id) + '/panel/status')
        .then(function (s) {
          LP.engine[p.id] = s || {};
          rerender();
        })
        .catch(function () { });
    });
  }

  function capsHtml() {
    var body;
    if (LP.capsErr) {
      body = '<div class="note bad">' + esc(LP.capsErr) + '</div>';
    } else if (LP.caps === null) {
      body = '<div class="mini">' + L.t('正在读取能力接管状态…') + '</div>';
    } else if (!LP.caps.length) {
      body = '<div class="mini">' + L.t('宿主没有可被接管的朗读或通知能力。') + '</div>';
    } else {
      body = LP.caps.map(function (c) {
        var tts = c.capability === 'tts.speak';
        return '<div class="rowflex" style="gap:10px;align-items:center;padding:10px 0;border-bottom:1px solid var(--line)">' +
          '<div style="flex:1;min-width:0">' +
          '<div>' + esc(capText(c.capability)) + ' <span class="mini mono" style="color:var(--tx-3)">' + esc(c.capability) + '</span></div>' +
          '<div class="mini">' +
          (c.available
            ? L.t('由 ') + esc(c.pluginName || c.pluginId) + L.t(' 接管')
            : L.t('尚无插件接管，请在下方插件卡片的授权里勾选该能力并设为默认实现')) +
          '</div></div>' +
          '<span class="badge ' + (c.available ? 'ok' : '') + '">' + (c.available ? L.t('已接管') : L.t('未接管')) + '</span>' +
          (c.available ? '<button class="btn xs" data-lp-cap="' + esc(c.capability) + '">' + (tts ? L.t('试听') : L.t('发送测试')) + '</button>' : '') +
          '</div>';
      }).join('');
    }
    return '<div class="card" style="margin-bottom:16px">' +
      '<div class="card-h"><h3>' + ico('bell') + ' ' + L.t('朗读与通知接管') + '</h3>' +
      '<div class="r"><button class="btn xs" data-lp-caps>' + ico('sync') + ' ' + L.t('刷新') + '</button></div></div>' +
      '<div class="mini" style="line-height:1.8;margin-bottom:8px">' +
      L.t('宿主自己不出声、也不发通知：这两件事交给被授权的插件。这里显示当前生效的实现，并可以直接试一次。') +
      '</div>' + body + '</div>';
  }

  function loadCaps(force) {
    if (LP.capsBusy) {
      if (force) LP.capsPending = true;
      return;
    }
    var stale = !LP.capsAt || Date.now() - LP.capsAt > 30000;
    if (LP.caps !== null && !force && !stale) return;
    if (!window.LantaiRuntime) {
      LP.caps = [];
      LP.capsErr = L.t('后端不可用');
      rerender();
      return;
    }
    LP.capsBusy = true;
    LantaiRuntime.request('api/lantai/v1/capabilities')
      .then(function (rows) {
        LP.caps = Array.isArray(rows) ? rows : [];
        LP.capsErr = '';
        LP.capsAt = Date.now();
      })
      .catch(function (e) {
        LP.caps = LP.caps || [];
        LP.capsErr = L.t('加载失败：') + onlineErrText(e);
      })
      .then(function () {
        LP.capsBusy = false;
        rerender();
        if (LP.capsPending) {
          LP.capsPending = false;
          loadCaps(true);
        }
      });
  }

  function testCapability(cap, el) {
    if (cap === 'tts.speak') return testSpeech(el);
    if (cap === 'notify.send') return testNotify(el);
  }

  function testSpeech(el) {
    el.disabled = true;
    fetch(LantaiRuntime.base + 'api/lantai/v1/capabilities/tts.speak', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: L.t('这是一段朗读测试，用于确认插件已经接管朗读合成。') })
    }).then(function (res) {
      if (res.ok) return res.blob();
      return res.text().catch(function () { return ''; }).then(function (text) {
        var err = new Error(text || ('HTTP ' + res.status));
        err.status = res.status;
        throw err;
      });
    }).then(function (blob) {
      if (LP.audio) {
        try { LP.audio.pause(); } catch (e) { }
      }
      if (LP.audioUrl) URL.revokeObjectURL(LP.audioUrl);
      LP.audioUrl = URL.createObjectURL(blob);
      LP.audio = new Audio(LP.audioUrl);
      LP.audio.onended = function () { el.disabled = false; };
      return LP.audio.play().then(function () {
        toast(L.t('正在播放插件合成的音频'), 'ok');
      });
    }).catch(function (e) {
      el.disabled = false;
      toast(L.t('朗读失败：') + onlineErrText(e), 'err');
    });
  }

  function testNotify(el) {
    el.disabled = true;
    LantaiRuntime.request('api/lantai/v1/capabilities/notify.send', {
      method: 'POST',
      body: {
        title: L.t('兰台测试通知'),
        body: L.t('如果你收到这条通知，说明插件已经接管出站通知。'),
        level: 'info'
      }
    }).then(function (r) {
      el.disabled = false;
      toast(L.t('已交给插件发送：') + (r.pluginName || r.pluginId || ''), 'ok');
    }).catch(function (e) {
      el.disabled = false;
      toast(L.t('发送失败：') + onlineErrText(e), 'err');
    });
  }

  function wire(box) {
    box.querySelectorAll('[data-lp-act]').forEach(function (el) {
      el.onclick = function () { act(el.dataset.lpAct, el.dataset.lpId, el); };
    });
    box.querySelectorAll('[data-lp-open-install]').forEach(function (el) {
      el.onclick = installModal;
    });
    box.querySelectorAll('[data-lp-guide]').forEach(function (el) {
      el.onclick = guideModal;
    });
    box.querySelectorAll('[data-lp-cap]').forEach(function (el) {
      el.onclick = function () { testCapability(el.dataset.lpCap, el); };
    });
    box.querySelectorAll('[data-lp-caps]').forEach(function (el) {
      el.onclick = function () { el.disabled = true; loadCaps(true); };
    });
    box.querySelectorAll('[data-lp-card]').forEach(function (el) {
      var open = function () { openDetail(el.dataset.lpCard); };
      el.onclick = function (ev) {
        if (ev.target.closest('button')) return;
        open();
      };
      el.onkeydown = function (ev) {
        if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); open(); }
      };
    });
    box.querySelectorAll('[data-lp-detail]').forEach(function (el) {
      el.onclick = function (ev) { ev.stopPropagation(); openDetail(el.dataset.lpDetail); };
    });
    box.querySelectorAll('[data-lp-gopanel]').forEach(function (el) {
      el.onclick = function (ev) {
        ev.stopPropagation();
        var p = findPlugin(el.dataset.lpGopanel);
        if (p) openPanel(p);
      };
    });
    box.querySelectorAll('[data-lp-mb]').forEach(function (el) {
      el.onclick = function () { setView(el.dataset.lpMb); };
    });
    box.querySelectorAll('[data-lp-reload]').forEach(function (el) {
      el.onclick = function () { el.disabled = true; load(true); };
    });
  }

  window.lpRenderPlugins = function (box) {
    if (!box) return;
    stopJobs();
    var scroll = box.scrollTop || window.scrollY || 0;
    box.innerHTML = noticeHtml() + shellHtml() + '<div id="lpJobs"></div>' + capsHtml() + pluginsHtml();
    wire(box);
    renderJobs();
    load(false);
    loadGrants();
    loadCaps(false);
    loadJobs();
    loadPanels();
    startStates();
    if (scroll) window.scrollTo(0, scroll);
  };

  function loadPanels(force) {
    if (LP.panelsBusy) {
      if (force) LP.panelsPending = true;
      return;
    }
    var stale = !LP.panelsAt || Date.now() - LP.panelsAt > 30000;
    if (LP.panels !== null && !force && !stale) return;
    LP.panelsBusy = true;
    LantaiRuntime.request('api/lantai/v1/plugins/panels')
      .then(function (rows) { LP.panels = Array.isArray(rows) ? rows : []; LP.panelsAt = Date.now(); })
      .catch(function () { LP.panels = LP.panels || []; })
      .then(function () {
        LP.panelsBusy = false;
        rerender();
        if (LP.panelsPending) {
          LP.panelsPending = false;
          loadPanels(true);
        }
      });
  }

  function stopStates() {
    if (LP.stateTimer) {
      clearTimeout(LP.stateTimer);
      LP.stateTimer = null;
    }
  }

  function startStates() {
    stopStates();
    if (!document.getElementById('plBody')) return;
    LP.stateTimer = setTimeout(pollStates, 5000);
  }

  function pollStates() {
    LP.stateTimer = null;
    if (document.getElementById('plBody') === null) return;
    if (document.querySelector('#modal.on, .modal.on') || LP.busy) {
      LP.stateTimer = setTimeout(pollStates, 5000);
      return;
    }
    if (LP.stateBusy) return;
    LP.stateBusy = true;
    LantaiRuntime.request('api/lantai/v1/plugins/status')
      .then(function (rows) {
        LP.stateErr = '';
        var stateChanged = false;
        var enableChanged = false;
        var seen = {};
        (rows || []).forEach(function (s) {
          seen[s.id] = 1;
          var p = findPlugin(s.id);
          if (!p) return;
          if (p.enabled !== s.enabled) enableChanged = true;
          if (p.state !== s.state || p.pid !== s.pid || p.statusMessage !== s.statusMessage) stateChanged = true;
          p.state = s.state;
          p.enabled = s.enabled;
          p.pid = s.pid;
          p.statusMessage = s.statusMessage;
          p.lastHealthDate = s.lastHealthDate;
        });
        var unknown = (rows || []).some(function (s) { return !findPlugin(s.id); });
        var missing = (LP.list || []).some(function (p) { return !seen[p.id]; });
        if (unknown || missing) {
          LP.loadedAt = 0;
          LP.grantedCache = null;
          LP.grantsAt = {};
          LP.capsAt = 0;
          LP.panelsAt = 0;
          load(true);
          return;
        }
        if (enableChanged) { rerender(); return; }
        if (stateChanged) paintStates();
      })
      .catch(function () { LP.stateErr = '1'; })
      .then(function () {
        LP.stateBusy = false;
        if (document.getElementById('plBody')) LP.stateTimer = setTimeout(pollStates, 5000);
      });
  }

  function paintStates() {
    var box = document.getElementById('plBody');
    if (!box) return;
    (LP.list || []).forEach(function (p) {
      box.querySelectorAll('[data-lp-st="' + p.id + '"]').forEach(function (el) {
        el.className = 'badge ' + stateClass(p.state);
        el.textContent = stateText(p.state);
      });
    });
    var stat = document.getElementById('lpStat');
    if (stat) {
      var ready = (LP.list || []).filter(function (p) { return p.state === 'READY'; }).length;
      stat.innerHTML = L.t('共 ') + LP.list.length + L.t(' 个插件') + ' · ' + ready + L.t(' 个就绪');
    }
  }

  function findPlugin(id) {
    return (LP.list || []).filter(function (p) { return p.id === id; })[0] || null;
  }

  function loadGrants() {
    var list = LP.list || [];
    if (!list.length) return;
    LP.grantedCache = LP.grantedCache || {};
    var now = Date.now();
    list.forEach(function (p) {
      var at = LP.grantsAt[p.id] || 0;
      if (LP.grantedCache[p.id] && now - at < 30000) return;
      loadGrantsFor(p.id);
    });
  }

  function loadGrantsFor(id) {
    if (!id) return;
    LP.grantedCache = LP.grantedCache || {};
    if (LP.grantsAt[id] && Date.now() - LP.grantsAt[id] < 2000) return;
    LP.grantsAt[id] = Date.now();
    LantaiRuntime.request('api/lantai/v1/plugins/' + id + '/grants')
      .then(function (rows) {
        LP.grantedCache[id] = (rows || []).map(function (g) { return g.capability; });
        rerender();
      })
      .catch(function () { LP.grantsAt[id] = 0; });
  }

  function act(type, id, el) {
    var p = findPlugin(id);
    if (!p) return;
    if (type === 'refresh') return doRefresh(p, el);
    if (type === 'toggle') return doToggle(p, el);
    if (type === 'grants') return grantsModal(p);
    if (type === 'data') return dataModal(p);
    if (type === 'upgrade') return upgradeModal(p);
    if (type === 'logs') return logsModal(p);
    if (type === 'release') return doRelease(p, el);
    if (type === 'delete') return doDelete(p);
  }

  function doRelease(p, el) {
    askConfirm(L.t('释放内存'), L.t('将停止该插件的进程并释放它占用的内存，正在执行的任务会被中断；下次访问时自动重新启动。'), function () {
      if (el) el.disabled = true;
      LantaiRuntime.request('api/lantai/v1/plugins/' + p.id + '/release', { method: 'POST' })
        .then(function () {
          toast(L.t('已释放内存'), 'ok');
          closeModalIfOpen();
          LP.list = null;
          if (window.LantaiPluginStateChanged) window.LantaiPluginStateChanged();
          rerender();
        })
        .catch(function (e) { if (el) el.disabled = false; toast(L.t('操作失败：') + onlineErrText(e), 'err'); });
    });
  }

  function doRefresh(p, el) {
    el.disabled = true;
    LantaiRuntime.request('api/lantai/v1/plugins/' + p.id + '/refresh', { method: 'POST' })
      .then(function (r) {
        toast(L.t('已重连：') + stateText(r.state), r.state === 'READY' ? 'ok' : 'err');
        closeModalIfOpen();
        LP.list = null;
        if (window.LantaiPluginStateChanged) window.LantaiPluginStateChanged();
        rerender();
      })
      .catch(function (e) { el.disabled = false; toast(L.t('重连失败：') + onlineErrText(e), 'err'); });
  }

  function doToggle(p, el) {
    el.disabled = true;
    LantaiRuntime.request('api/lantai/v1/plugins/' + p.id, { method: 'PATCH', body: { enabled: !p.enabled } })
      .then(function (r) {
        toast(r.enabled ? L.t('已启用') : L.t('已停用'), 'ok');
        closeModalIfOpen();
        LP.list = null;
        if (window.LantaiPluginStateChanged) window.LantaiPluginStateChanged();
        rerender();
      })
      .catch(function (e) { el.disabled = false; toast(L.t('操作失败：') + onlineErrText(e), 'err'); });
  }

  function doDelete(p) {
    const ask = function (n) {
      const online = n > 0
        ? L.t(' 该插件加入书架的 ') + n + L.t(' 部在线作品会一并移出在线书库。')
        : '';
      askConfirm(
        L.t('卸载插件'),
        L.t('将停止进程、吊销插件令牌、删除插件目录，并清空已授予的能力。此操作不可撤销。') + online,
        function (purge) {
          LantaiRuntime.request('api/lantai/v1/plugins/' + p.id + '?purgeDownloads=' + (purge === true), { method: 'DELETE' })
            .then(function () {
              toast(L.t('已卸载'), 'ok');
              if (LP.grantedCache) delete LP.grantedCache[p.id];
              closeModalIfOpen();
              LP.list = null;
              if (n > 0 && window.LantaiOnline) window.LantaiOnline.refresh();
              if (window.LantaiPluginStateChanged) window.LantaiPluginStateChanged();
              rerender();
            })
            .catch(function (e) { toast(L.t('卸载失败：') + onlineErrText(e), 'err'); });
        },
        n > 0 ? { check: L.t('同时删除这些作品已下载的存档文件') } : null
      );
    };
    LantaiRuntime.request('api/lantai/v1/plugins/' + p.id + '/online-count')
      .then(function (r) { ask((r && r.count) || 0); })
      .catch(function () { ask(0); });
  }

  function installModal() {
    LP.idx = null;
    LP.idxErr = '';
    LP.idxUrl = '';
    LP.jobId = '';
    LP.upload = null;
    openModal({
      title: L.t('安装插件'),
      width: 660,
      body: '<div class="field"><label>' + L.t('本地插件包') + '</label>' +
        '<label class="btn ghost" style="cursor:pointer;display:inline-flex;align-items:center;gap:8px">' + ico('upload') + ' ' + L.t('选择 zip 文件') +
        '<input type="file" accept=".zip" id="lpZipFile" hidden></label>' +
        '<div class="mini" id="lpZipName" style="margin-top:8px">' + L.t('未选择文件') + '</div></div>' +
        '<div class="mini" style="margin-top:7px">' + L.t('包根必须含 plugin.json，宿主按清单里的 id 与版本决定安装目录。') + '</div>' +
        '<div id="lpProg" style="margin-top:16px"></div>' +
        '<div id="lpSamples" style="margin-top:16px"></div>' +
        '<details style="margin-top:18px;padding-top:14px;border-top:1px solid var(--line)">' +
        '<summary style="cursor:pointer;font-size:12.5px">' + L.t('进阶：从仓库索引安装') + '</summary>' +
        '<div class="field" style="margin-top:12px"><label>' + L.t('仓库索引地址') + '</label>' +
        '<div class="rowflex" style="gap:8px"><input class="inp" id="lpIndex" placeholder="https://…/index.json" style="flex:1">' +
        '<button class="btn" id="lpFetch">' + L.t('拉取索引') + '</button></div></div>' +
        '<div class="mini" style="margin-top:7px;line-height:1.75">' +
        L.t('索引地址由你填写，宿主只做代理与校验，不保存、不预置、不推荐任何地址。') +
        '</div><div id="lpIdx"></div></details>' +
        '<div class="note" style="margin-top:14px">' +
        L.t('下载只做 sha256 完整性校验，不做签名验证；安装后会比对包内 plugin.json 的 id 与索引条目是否一致。') +
        '</div>',
      foot: '<button class="btn" data-x>' + L.t('取消') + '</button>' +
        '<button class="btn pri" id="lpInstall">' + L.t('安装') + '</button>',
      after: function () {
        $('#modal [data-x]').onclick = closeModal;
        $('#modal #lpZipFile').onchange = function () {
          var f = this.files && this.files[0];
          var el = $('#modal #lpZipName');
          if (el) el.textContent = f ? f.name + ' · ' + Math.max(1, Math.round(f.size / 1024)) + ' KB' : L.t('未选择文件');
        };
        $('#modal #lpInstall').onclick = function () { installUpload($('#modal #lpZipFile'), this); };
        $('#modal #lpFetch').onclick = function () { fetchIndex($('#lpIndex').value.trim(), this); };
        loadSamples();
      }
    });
  }

  function loadSamples() {
    var box = $('#modal #lpSamples');
    if (!box) return;
    LantaiRuntime.request('api/lantai/v1/plugins/samples')
      .then(function (rows) {
        if (!rows || !rows.length) return;
        box.innerHTML = '<div class="mini" style="margin-bottom:8px">' + L.t('宿主内置示例，可直接安装试用。') + '</div>' +
          rows.map(function (s) {
            return '<div class="rowflex" style="gap:10px;align-items:center;padding:9px 11px;border:1px solid var(--line);border-radius:8px;margin-bottom:8px">' +
              '<div style="flex:1;min-width:0"><b style="font-size:12.5px">' + esc(s.name) + '</b>' +
              '<div class="mini" style="margin-top:3px">' + esc(s.pluginId) + ' · ' + esc(s.version) + ' · ' + Math.max(1, Math.round((s.sizeBytes || 0) / 1024)) + ' KB</div>' +
              '<div class="mini" style="margin-top:3px;line-height:1.6">' + esc(s.description || '') + '</div></div>' +
              '<button class="btn sm" data-lp-sample="' + esc(s.id) + '">' + L.t('安装') + '</button></div>';
          }).join('');
        box.querySelectorAll('[data-lp-sample]').forEach(function (el) {
          el.onclick = function () { installSample(el.dataset.lpSample, el); };
        });
      })
      .catch(function () { box.innerHTML = ''; });
  }

  function installSample(sampleId, btn) {
    btn.disabled = true;
    LantaiRuntime.request('api/lantai/v1/plugins/samples/' + encodeURIComponent(sampleId) + '/install', { method: 'POST' })
      .then(function (p) {
        closeModal();
        toast(L.t('已安装 ') + p.name + ' ' + p.version, 'ok');
        LP.list = null;
        rerender();
      })
      .catch(function (e) {
        btn.disabled = false;
        toast(L.t('安装失败：') + onlineErrText(e), 'err');
      });
  }

  function installUpload(input, btn) {
    var f = input && input.files && input.files[0];
    if (!f) return toast(L.t('请选择插件包'), 'err');
    btn.disabled = true;
    startUpload('api/lantai/v1/plugins/install/upload/job', f, btn);
  }

  function fetchIndex(url, btn) {
    if (!url) return toast(L.t('请填写索引地址'), 'err');
    btn.disabled = true;
    LP.idxBusy = true;
    LantaiRuntime.request('api/lantai/v1/plugins/repository/index', { method: 'POST', body: { indexUrl: url } })
      .then(function (r) {
        LP.idx = r.entries || [];
        LP.idxErr = '';
        LP.idxUrl = url;
      })
      .catch(function (e) {
        LP.idx = null;
        LP.idxErr = onlineErrText(e);
      })
      .then(function () {
        btn.disabled = false;
        LP.idxBusy = false;
        renderIndex();
      });
  }

  function renderIndex() {
    var box = $('#modal #lpIdx');
    if (!box) return;
    if (LP.idxErr) {
      box.innerHTML = '<div class="note red" style="margin-top:12px">' + esc(LP.idxErr) + '</div>';
      return;
    }
    if (!LP.idx) {
      box.innerHTML = '';
      return;
    }
    if (!LP.idx.length) {
      box.innerHTML = '<div class="note gold" style="margin-top:12px">' +
        L.t('没有可用条目。缺 sha256、协议不兼容、地址不合法的条目会被直接丢弃。') + '</div>';
      return;
    }
    box.innerHTML = '<div style="margin-top:12px;max-height:34vh;overflow:auto">' + LP.idx.map(function (e) {
      return '<div class="rowflex" style="gap:10px;align-items:center;padding:9px 0;border-bottom:1px solid var(--line)">' +
        '<div style="flex:1;min-width:0">' +
        '<div>' + esc(e.name) + ' <span class="mini mono">' + esc(e.version) + '</span>' +
        ' <span class="badge ' + (e.protocol === 2 ? 'moss' : '') + '">v' + e.protocol + '</span></div>' +
        '<div class="mini mono" style="word-break:break-all">' + esc(e.pluginId) + '</div>' +
        '<div class="mini">' + (e.capabilities || []).map(function (c) { return esc(capText(c)); }).join(' · ') + '</div>' +
        '</div>' +
        '<button class="btn xs pri" data-lp-inst="' + esc(e.pluginId) + '" data-lp-ver="' + esc(e.version) + '">' + L.t('安装') + '</button>' +
        '</div>';
    }).join('') + '</div>';
    box.querySelectorAll('[data-lp-inst]').forEach(function (b) {
      b.onclick = function () { installFromIndex(b.dataset.lpInst, b.dataset.lpVer, b); };
    });
  }

  function installFromIndex(pluginId, version, btn) {
    btn.disabled = true;
    LantaiRuntime.request('api/lantai/v1/plugins/repository/install/job', {
      method: 'POST',
      body: { indexUrl: LP.idxUrl, pluginId: pluginId, version: version }
    })
      .then(function (r) {
        focusJob(r.jobId);
      })
      .catch(function (e) {
        btn.disabled = false;
        toast(L.t('安装失败：') + onlineErrText(e), 'err');
      });
  }

  function grantsModal(p) {
    LantaiRuntime.request('api/lantai/v1/plugins/' + p.id + '/grants')
      .then(function (rows) { showGrants(p, rows || []); })
      .catch(function (e) { toast(L.t('读取授权失败：') + onlineErrText(e), 'err'); });
  }

  function showGrants(p, rows) {
    var declared = p.capabilities || [];
    var granted = rows.map(function (g) { return g.capability; });
    var defaultCap = '';
    rows.forEach(function (g) { if (g.isDefault) defaultCap = g.capability; });
    if (!declared.length) {
      openModal({
        title: L.t('能力授权'),
        width: 520,
        body: '<div class="note">' + L.t('该插件在清单里没有声明任何能力，因此没有可授予的权限。') + '</div>',
        foot: '<button class="btn" data-x>' + L.t('关闭') + '</button>',
        after: function () { $('#modal [data-x]').onclick = closeModal; }
      });
      return;
    }
    openModal({
      title: L.t('能力授权') + ' · ' + esc(p.name),
      width: 640,
      body: '<div class="mini" style="margin-bottom:12px;line-height:1.75">' +
        L.t('勾选即授予对应权限范围，插件令牌的 scope 会同步更新。同一能力有多个插件时，「默认」决定由谁来处理。') + '</div>' +
        declared.map(function (c) {
          var on = granted.indexOf(c) >= 0;
          return '<div class="rowflex" style="gap:10px;align-items:center;padding:9px 0;border-bottom:1px solid var(--line)">' +
            '<label class="rowflex" style="gap:9px;align-items:center;flex:1;cursor:pointer">' +
            '<input type="checkbox" data-lp-cap="' + esc(c) + '"' + (on ? ' checked' : '') + '>' +
            '<span>' + esc(capText(c)) + '</span><span class="mini mono" style="color:var(--tx-3)">' + esc(c) + '</span></label>' +
            '<label class="mini rowflex" style="gap:5px;align-items:center;cursor:pointer">' +
            '<input type="radio" name="lpdef" data-lp-def="' + esc(c) + '"' + (defaultCap === c ? ' checked' : '') + '>' + L.t('默认') + '</label>' +
            '</div>';
        }).join('') +
        '<div class="note" style="margin-top:12px">' + L.t('未勾选的能力会立即失效，插件再调用对应端点会被拒绝。') + '</div>',
      foot: '<button class="btn" data-x>' + L.t('取消') + '</button>' +
        '<button class="btn pri" id="lpGrantSave">' + L.t('保存') + '</button>',
      after: function () {
        $('#modal [data-x]').onclick = closeModal;
        $('#modal #lpGrantSave').onclick = function () {
          var picked = [];
          $$('#modal [data-lp-cap]').forEach(function (el) { if (el.checked) picked.push(el.dataset.lpCap); });
          var def = '';
          $$('#modal [data-lp-def]').forEach(function (el) { if (el.checked) def = el.dataset.lpDef; });
          if (def && picked.indexOf(def) < 0) return toast(L.t('默认实现必须在已勾选的能力里'), 'err');
          var btn = this;
          var run = function () { saveGrants(p, picked, def, btn); };
          if (picked.indexOf('ui.panel') >= 0 && granted.indexOf('ui.panel') < 0) {
            return askConfirm(
              L.t('授予插件页面权限'),
              L.t('该插件界面会在兰台页面里同源加载，可读取当前页面数据、以你的登录态调用接口；插件进程还与兰台同权限运行。只给核实过来源的插件开启。'),
              run
            );
          }
          run();
        };
      }
    });
  }

  function saveGrants(p, picked, def, btn) {
    btn.disabled = true;
    LantaiRuntime.request('api/lantai/v1/plugins/' + p.id + '/grants', {
      method: 'PUT',
      body: { capabilities: picked, defaultCapability: def || null }
    })
      .then(function () {
        LP.grantedCache = LP.grantedCache || {};
        LP.grantedCache[p.id] = picked;
        closeModal();
        toast(L.t('授权已更新'), 'ok');
        LP.list = null;
        rerender();
      })
      .catch(function (e) {
        btn.disabled = false;
        toast(L.t('保存失败：') + onlineErrText(e), 'err');
      });
  }

  function upgradeModal(p) {
    LP.jobId = '';
    LP.upload = null;
    openModal({
      title: L.t('升级插件') + ' · ' + esc(p.name),
      width: 620,
      body: '<div class="mini" style="line-height:1.75">' +
        L.t('当前版本 ') + esc(p.version) + L.t('。升级会停掉旧进程再起新版本，新版本起不来会自动回滚到旧版本并保留旧目录。') +
        '</div>' +
        '<div class="field" style="margin-top:14px"><label>' + L.t('本地插件包') + '</label>' +
        '<label class="btn ghost" style="cursor:pointer;display:inline-flex;align-items:center;gap:8px">' + ico('upload') + ' ' + L.t('选择 zip 文件') +
        '<input type="file" accept=".zip" id="lpUpFile" hidden></label>' +
        '<div class="mini" id="lpUpName" style="margin-top:8px">' + L.t('未选择文件') + '</div></div>' +
        '<div id="lpProg" style="margin-top:16px"></div>' +
        '<details style="margin-top:16px;padding-top:14px;border-top:1px solid var(--line)">' +
        '<summary style="cursor:pointer;font-size:12.5px">' + L.t('进阶：从仓库索引升级') + '</summary>' +
        '<div class="field" style="margin-top:12px"><label>' + L.t('仓库索引地址') + '</label>' +
        '<div class="rowflex" style="gap:8px"><input class="inp" id="lpUpIndex" placeholder="https://…/index.json" style="flex:1">' +
        '<button class="btn" id="lpUpFetch">' + L.t('拉取索引') + '</button></div></div>' +
        '<div id="lpUpIdx"></div></details>',
      foot: '<button class="btn" data-x>' + L.t('取消') + '</button>' +
        '<button class="btn pri" id="lpUpDo">' + L.t('升级') + '</button>',
      after: function () {
        $('#modal [data-x]').onclick = closeModal;
        $('#modal #lpUpFile').onchange = function () {
          var f = this.files && this.files[0];
          var el = $('#modal #lpUpName');
          if (el) el.textContent = f ? f.name + ' · ' + Math.max(1, Math.round(f.size / 1024)) + ' KB' : L.t('未选择文件');
        };
        $('#modal #lpUpDo').onclick = function () { upgradeUpload(p, $('#modal #lpUpFile'), this); };
        $('#modal #lpUpFetch').onclick = function () {
          var url = $('#lpUpIndex').value.trim();
          if (!url) return toast(L.t('请填写索引地址'), 'err');
          var btn = this;
          btn.disabled = true;
          LantaiRuntime.request('api/lantai/v1/plugins/repository/index', { method: 'POST', body: { indexUrl: url } })
            .then(function (r) {
              LP.idxUrl = url;
              var own = (r.entries || []).filter(function (e) { return e.pluginId === p.pluginId; });
              renderUpgradeIndex(p, own);
            })
            .catch(function (e) {
              var box = $('#modal #lpUpIdx');
              if (box) box.innerHTML = '<div class="note red" style="margin-top:12px">' + esc(onlineErrText(e)) + '</div>';
            })
            .then(function () { btn.disabled = false; });
        };
      }
    });
  }

  function upgradeUpload(p, input, btn) {
    var f = input && input.files && input.files[0];
    if (!f) return toast(L.t('请选择插件包'), 'err');
    btn.disabled = true;
    startUpload('api/lantai/v1/plugins/' + encodeURIComponent(p.id) + '/upgrade/upload/job', f, btn);
  }

  function renderUpgradeIndex(p, entries) {
    var box = $('#modal #lpUpIdx');
    if (!box) return;
    if (!entries.length) {
      box.innerHTML = '<div class="note gold" style="margin-top:12px">' +
        L.t('索引里没有这个插件的条目。') + '</div>';
      return;
    }
    box.innerHTML = '<div style="margin-top:12px">' + entries.map(function (e) {
      var same = e.version === p.version;
      return '<div class="rowflex" style="gap:10px;align-items:center;padding:9px 0;border-bottom:1px solid var(--line)">' +
        '<div style="flex:1;min-width:0"><div><span class="mini mono">' + esc(e.version) + '</span>' +
        (same ? ' <span class="badge">' + L.t('当前版本') + '</span>' : '') + '</div>' +
        '<div class="mini">' + (e.capabilities || []).map(function (c) { return esc(capText(c)); }).join(' · ') + '</div></div>' +
        '<button class="btn xs pri" data-lp-up="' + esc(e.version) + '"' + (same ? ' disabled' : '') + '>' + L.t('升级到此版本') + '</button>' +
        '</div>';
    }).join('') + '</div>';
    box.querySelectorAll('[data-lp-up]').forEach(function (b) {
      b.onclick = function () {
        b.disabled = true;
        LantaiRuntime.request('api/lantai/v1/plugins/' + p.id + '/upgrade/repository/job', {
          method: 'POST',
          body: { indexUrl: LP.idxUrl, pluginId: p.pluginId, version: b.dataset.lpUp }
        })
          .then(function (r) {
            focusJob(r.jobId);
          })
          .catch(function (e) {
            b.disabled = false;
            toast(L.t('升级失败：') + onlineErrText(e), 'err');
          });
      };
    });
  }

  function guideModal() {
    openModal({
      title: L.t('插件开发指南'),
      width: 920,
      body: '<div id="lpGuide" class="mini">' + L.t('正在读取…') + '</div>',
      foot: '<button class="btn" data-x>' + L.t('关闭') + '</button>',
      after: function () {
        $('#modal [data-x]').onclick = closeModal;
        loadGuide();
      }
    });
  }

  function loadGuide() {
    var box = $('#modal #lpGuide');
    if (!box) return;
    LantaiRuntime.request('api/lantai/v1/plugins/guide')
      .then(function (g) { box.innerHTML = guideHtml(g || {}); })
      .catch(function (e) { box.innerHTML = '<div class="note red">' + esc(onlineErrText(e)) + '</div>'; });
  }

  function guideHtml(g) {
    var caps = (g.capabilities || []).map(function (c) {
      return '<tr><td class="mono mini">' + esc(c.name) + '</td>' +
        '<td><span class="badge">' + esc(L.t(c.kind || '')) + '</span></td>' +
        '<td class="mini">' + esc(L.t(c.summary || '')) + '</td></tr>';
    }).join('');
    var rules = (g.rules || []).map(function (r) {
      return '<li><span class="mono mini">' + esc(r.id) + '</span> ' + esc(L.t(r.text || '')) + '</li>';
    }).join('');
    var checks = (g.checks || []).map(function (c) {
      return '<li>' + esc(L.t(c || '')) + '</li>';
    }).join('');
    var limits = (g.limits || []).map(function (x) {
      return '<tr><td class="mini">' + esc(L.t(x.name || '')) + '</td><td class="mini mono">' + esc(L.t(x.value || '')) + '</td></tr>';
    }).join('');
    return '<div class="note">' + L.t('协议 v') + esc(String(g.protocol || 2)) + ' · ' + L.t('最低宿主版本 ') + esc(g.minHost || '') +
      L.t('。宿主只开放下表能力；声明表外能力、清单字段越界或包结构不合规的插件包一律拒收。') + '</div>' +
      '<h4 style="margin:16px 0 8px">' + L.t('开放能力') + '</h4>' +
      '<div class="tbl-wrap"><table class="tbl"><thead><tr><th>' + L.t('能力') + '</th><th>' + L.t('类型') + '</th><th>' + L.t('说明') + '</th></tr></thead><tbody>' + caps + '</tbody></table></div>' +
      '<h4 style="margin:18px 0 8px">' + L.t('开发约定') + '</h4>' +
      '<ol class="mini" style="line-height:1.9;padding-left:18px;margin:0">' + rules + '</ol>' +
      '<h4 style="margin:18px 0 8px">' + L.t('安装校验') + '</h4>' +
      '<ol class="mini" style="line-height:1.9;padding-left:18px;margin:0">' + checks + '</ol>' +
      '<h4 style="margin:18px 0 8px">' + L.t('资源上限') + '</h4>' +
      '<div class="tbl-wrap"><table class="tbl"><tbody>' + limits + '</tbody></table></div>' +
      '<h4 style="margin:18px 0 8px">' + L.t('最小清单示例') + '</h4>' +
      '<pre class="mono" style="padding:12px;border:1px solid var(--line);border-radius:8px;overflow:auto;font-size:11.5px;line-height:1.6">' + esc(g.sample || '') + '</pre>' +
      '<div class="mini" style="margin-top:12px;color:var(--tx-3)">' + L.t('完整协议与两份 yaml 契约见仓库 docs 目录。') + '</div>';
  }

  function dataModal(p) {
    var schema = p.config && p.config.properties ? p.config : null;
    var settings = {};
    try { settings = JSON.parse(p.settings || '{}') || {}; } catch (e) { settings = {}; }
    var form = schema
      ? Object.keys(schema.properties).map(function (k) { return fieldHtml(k, schema.properties[k] || {}, settings[k]); }).join('')
      : '<div class="mini" style="color:var(--tx-3)">' + L.t('这个插件没有声明配置项。') + '</div>';
    openModal({
      title: L.t('数据') + ' · ' + esc(p.name),
      width: 780,
      body:
        '<div style="margin-bottom:18px"><div class="mini" style="color:var(--tx-3);margin-bottom:8px">' + L.t('配置') + '</div>' + form +
        (schema
          ? '<div style="margin-top:12px"><button class="btn sm pri" id="lpSetSave">' + ico('check') + ' ' + L.t('保存配置') + '</button>' +
            (p.mode === 'MANAGED' ? ' <span class="mini">' + L.t('保存后受管进程会自动重启以读取新配置') + '</span>' : '') + '</div>'
          : '') +
        '</div>' +
        '<div style="margin-bottom:18px"><div class="mini" style="color:var(--tx-3);margin-bottom:8px">' + L.t('已存凭据') + '</div><div id="lpCredBox" class="mini">' + L.t('正在读取…') + '</div></div>' +
        '<div style="margin-bottom:18px"><div class="mini" style="color:var(--tx-3);margin-bottom:8px">' + L.t('私有存储用量') + '</div><div id="lpKvBox" class="mini">' + L.t('正在读取…') + '</div></div>' +
        '<div><div class="mini" style="color:var(--tx-3);margin-bottom:8px">' + L.t('最近调用') + '</div><div id="lpCallBox" class="mini">' + L.t('正在读取…') + '</div></div>',
      foot: '<button class="btn" data-x>' + L.t('关闭') + '</button>',
      after: function () {
        $('#modal [data-x]').onclick = closeModal;
        var save = $('#modal #lpSetSave');
        if (save) save.onclick = function () { saveSettings(p, save); };
        loadCredentials(p.id);
        loadKv(p.id);
        loadCalls(p.id);
      }
    });
  }

  function fieldHtml(key, schema, value) {
    var label = esc(schema.title || key);
    var tip = schema.description ? '<div class="mini" style="color:var(--tx-3)">' + esc(schema.description) + '</div>' : '';
    var control;
    if (Array.isArray(schema.enum) && schema.enum.length) {
      control = '<select class="inp" data-lp-set="' + esc(key) + '">' +
        schema.enum.map(function (v) {
          return '<option value="' + esc(v) + '"' + (String(value) === String(v) ? ' selected' : '') + '>' + esc(v) + '</option>';
        }).join('') + '</select>';
    } else if (schema.type === 'boolean') {
      control = '<label class="rowflex" style="gap:8px;align-items:center"><input type="checkbox" data-lp-set="' + esc(key) + '"' + (value ? ' checked' : '') + '> <span class="mini">' + L.t('启用') + '</span></label>';
    } else if (schema.type === 'number' || schema.type === 'integer') {
      control = '<input class="inp" type="number" step="any" data-lp-set="' + esc(key) + '" value="' + esc(value == null ? '' : value) + '">';
    } else {
      control = '<input class="inp" data-lp-set="' + esc(key) + '" value="' + esc(value == null ? '' : value) + '">';
    }
    return '<div style="margin-bottom:10px"><div style="margin-bottom:4px">' + label + '</div>' + control + tip + '</div>';
  }

  function saveSettings(p, btn) {
    var next = {};
    $('#modal').querySelectorAll('[data-lp-set]').forEach(function (el) {
      var k = el.dataset.lpSet;
      if (el.type === 'checkbox') next[k] = el.checked;
      else if (el.type === 'number') { if (el.value !== '') next[k] = Number(el.value); }
      else if (el.value !== '') next[k] = el.value;
    });
    btn.disabled = true;
    LantaiRuntime.request('api/lantai/v1/plugins/' + p.id + '/settings', { method: 'PATCH', body: next })
      .then(function (r) {
        toast(L.t('配置已保存'), 'ok');
        p.settings = r.settings;
        closeModal();
        LP.list = null;
        rerender();
      })
      .catch(function (e) { btn.disabled = false; toast(L.t('保存失败：') + onlineErrText(e), 'err'); });
  }

  function loadCredentials(id) {
    var box = $('#modal #lpCredBox');
    if (!box) return;
    LantaiRuntime.request('api/lantai/v1/plugins/' + id + '/credentials')
      .then(function (rows) {
        if (!rows || !rows.length) {
          box.innerHTML = '<div class="mini" style="color:var(--tx-3)">' + L.t('这个插件还没有代存任何凭据。') + '</div>';
          return;
        }
        box.innerHTML = rows.map(function (c) {
          return '<div class="rowflex" style="gap:10px;align-items:center;padding:7px 0;border-bottom:1px solid var(--line)">' +
            '<div style="flex:1;min-width:0"><span class="mono">' + esc(c.name) + '</span> <span class="mini" style="color:var(--tx-3)">' +
            c.size + ' B · ' + esc(stamp(c.lastModifiedDate)) + '</span></div>' +
            '<button class="btn xs danger" data-lp-cred="' + esc(c.name) + '">' + ico('trash') + '</button>' +
            '</div>';
        }).join('');
        box.querySelectorAll('[data-lp-cred]').forEach(function (el) {
          el.onclick = function () {
            askConfirm(L.t('删除凭据'), L.t('删除后插件需要重新授权才能再拿到这个值。'), function () {
              LantaiRuntime.request('api/lantai/v1/plugins/' + id + '/credentials/' + encodeURIComponent(el.dataset.lpCred), { method: 'DELETE' })
                .then(function () { toast(L.t('已删除'), 'ok'); loadCredentials(id); })
                .catch(function (e) { toast(L.t('删除失败：') + onlineErrText(e), 'err'); });
            });
          };
        });
      })
      .catch(function (e) { box.textContent = L.t('读取失败：') + onlineErrText(e); });
  }

  function loadKv(id) {
    var box = $('#modal #lpKvBox');
    if (!box) return;
    LantaiRuntime.request('api/lantai/v1/plugins/' + id + '/kv')
      .then(function (r) {
        var pct = r.limit ? Math.min(100, Math.round((r.usage / r.limit) * 100)) : 0;
        box.innerHTML = '<div class="rowflex" style="gap:10px;align-items:center">' +
          '<div style="flex:1;height:6px;background:var(--line);border-radius:3px;overflow:hidden"><div style="width:' + pct + '%;height:100%;background:var(--ac)"></div></div>' +
          '<div class="mini mono">' + fmtBytes(r.usage) + ' / ' + fmtBytes(r.limit) + '</div></div>' +
          '<div class="mini" style="color:var(--tx-3);margin-top:6px">' + L.t('共 ') + r.count + L.t(' 个键') + '</div>';
      })
      .catch(function (e) { box.textContent = L.t('读取失败：') + onlineErrText(e); });
  }

  function loadCalls(id) {
    var box = $('#modal #lpCallBox');
    if (!box) return;
    LantaiRuntime.request('api/lantai/v1/plugins/' + id + '/calls?limit=20')
      .then(function (rows) {
        if (!rows || !rows.length) {
          box.innerHTML = '<div class="mini" style="color:var(--tx-3)">' + L.t('还没有调用记录。') + '</div>';
          return;
        }
        box.innerHTML = rows.map(function (r) {
          return '<div class="rowflex" style="gap:10px;align-items:center;padding:6px 0;border-bottom:1px solid var(--line)">' +
            '<div style="flex:1;min-width:0"><span class="mono">' + esc(r.capability) + '</span> <span class="mini" style="color:var(--tx-3)">' + esc(stamp(r.startedDate)) + '</span></div>' +
            '<span class="mini mono">' + r.durationMs + ' ms</span>' +
            (r.success ? '<span class="badge ok">' + L.t('成功') + '</span>' : '<span class="badge bad">' + esc(r.message ? L.ts(r.message) : L.t('失败')) + '</span>') +
            '</div>';
        }).join('');
      })
      .catch(function (e) { box.textContent = L.t('读取失败：') + onlineErrText(e); });
  }

  function fmtBytes(n) {
    if (!n) return '0 B';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(1) + ' MB';
  }

  function logsModal(p) {
    openModal({
      title: L.t('插件日志') + ' · ' + esc(p.name),
      width: 760,
      body: '<div class="mini">' + L.t('读取插件目录下 logs/plugin.log 的尾部，最多 200 行。') + '</div>' +
        '<pre id="lpLogBox" class="mono" style="margin-top:10px;max-height:54vh;overflow:auto;padding:12px;border:1px solid var(--line);border-radius:8px;font-size:11.5px;line-height:1.65;white-space:pre-wrap">' +
        L.t('正在读取…') + '</pre>',
      foot: '<button class="btn" data-x>' + L.t('关闭') + '</button>' +
        '<button class="btn" id="lpLogReload">' + ico('sync') + ' ' + L.t('刷新') + '</button>',
      after: function () {
        $('#modal [data-x]').onclick = closeModal;
        $('#modal #lpLogReload').onclick = function () { loadLogs(p.id); };
        loadLogs(p.id);
      }
    });
  }

  window.lpRenderPluginPanel = function (box, entry) {
    if (!box) return;
    box.innerHTML = '<iframe id="lpPanelFrame" src="' + esc(entry.url) + '" style="width:100%;height:72vh;border:1px solid var(--line);border-radius:8px;background:var(--bg)"></iframe>';
  };

  function loadLogs(id) {
    var box = $('#modal #lpLogBox');
    if (!box) return;
    LantaiRuntime.request('api/lantai/v1/plugins/' + id + '/logs?lines=200')
      .then(function (r) {
        var lines = (r && r.lines) || [];
        box.textContent = lines.length ? lines.join('\n') : L.t('日志为空。插件可能还没有输出，或它不是受管进程。');
        box.scrollTop = box.scrollHeight;
      })
      .catch(function (e) {
        box.textContent = L.t('读取失败：') + onlineErrText(e);
      });
  }
})();
