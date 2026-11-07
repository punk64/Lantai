(function () {
  function createPager(opts) {
    var fetchPage = opts.fetch;
    var size = opts.size || 50;
    var radius = opts.radius === undefined ? 1 : opts.radius;
    var handlers = {};
    var pages = new Map();
    var inflight = new Map();
    var userSeq = 0;
    var query = '';
    var current = 1;
    var total = 0;

    function on(name, fn) {
      (handlers[name] = handlers[name] || []).push(fn);
      return pager;
    }

    function emit(name, value) {
      var list = handlers[name] || [];
      for (var i = 0; i < list.length; i++) {
        try { list[i](value); } catch (e) {}
      }
    }

    function key(page) {
      return query + '\u0000' + page;
    }

    function maxPage() {
      return total ? Math.max(1, Math.ceil(total / size)) : 0;
    }

    function evict() {
      var from = current - radius;
      var to = current + radius;
      pages.forEach(function (v, k) {
        var p = Number(k.slice(k.lastIndexOf('\u0000') + 1));
        if (p < from || p > to) pages.delete(k);
      });
    }

    function fetchInto(page) {
      var k = key(page);
      if (pages.has(k)) return Promise.resolve(pages.get(k));
      if (inflight.has(k)) return inflight.get(k);
      var p = fetchPage(page, size).then(function (res) {
        inflight.delete(k);
        var rows = (res && res.rows) || [];
        var count = res && typeof res.total === 'number' ? res.total : 0;
        var entry = { rows: rows, total: count, page: page };
        if (count) total = count;
        pages.set(k, entry);
        evict();
        return entry;
      }, function () {
        inflight.delete(k);
        return null;
      });
      inflight.set(k, p);
      return p;
    }

    function prefetch() {
      var last = maxPage();
      var list = [];
      for (var d = 1; d <= radius; d++) {
        if (current - d >= 1) list.push(current - d);
        if (!last || current + d <= last) list.push(current + d);
      }
      for (var i = 0; i < list.length; i++) fetchInto(list[i]);
    }

    function goto(page) {
      page = Math.max(1, Number(page) || 1);
      var last = maxPage();
      if (last && page > last) page = last;
      current = page;
      var token = ++userSeq;
      var hit = pages.get(key(page));
      if (hit) {
        emit('loading', false);
        emit('render', hit);
        prefetch();
        return Promise.resolve(hit);
      }
      emit('loading', true);
      return fetchInto(page).then(function (entry) {
        if (token !== userSeq) return null;
        emit('loading', false);
        if (!entry) {
          emit('error');
          return null;
        }
        emit('render', entry);
        prefetch();
        return entry;
      });
    }

    function setQuery(next) {
      next = String(next || '');
      if (next === query) return false;
      query = next;
      pages.clear();
      inflight.clear();
      total = 0;
      current = 1;
      userSeq++;
      return true;
    }

    function setSize(next) {
      next = Math.max(1, Number(next) || 50);
      if (next === size) return false;
      size = next;
      pages.clear();
      inflight.clear();
      total = 0;
      userSeq++;
      return true;
    }

    function invalidate() {
      pages.clear();
      inflight.clear();
      total = 0;
      userSeq++;
    }

    var pager = {
      on: on,
      goto: goto,
      prefetch: prefetch,
      setQuery: setQuery,
      setSize: setSize,
      invalidate: invalidate,
      size: function () { return size; },
      page: function () { return current; },
      total: function () { return total; },
      setTotal: function (n) { total = Number(n) || 0; },
      current: function () { return pages.get(key(current)) || null; }
    };
    return pager;
  }

  window.LantaiPager = { create: createPager };
})();
