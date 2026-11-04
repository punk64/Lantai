(function () {
  var NAMES = [
    ['zh-Hans', '简体中文'], ['zh-Hant', '繁體中文'], ['en', 'English'],
    ['ja', '日本語'], ['ko', '한국어'], ['fr', 'Français'], ['de', 'Deutsch'],
    ['es', 'Español'], ['it', 'Italiano'], ['pt-BR', 'Português (Brasil)'],
    ['ru', 'Русский'], ['vi', 'Tiếng Việt'], ['ar', 'العربية'], ['bg', 'Български'],
    ['ca', 'Català'], ['cs', 'Čeština'], ['da', 'Dansk'], ['el', 'Ελληνικά'],
    ['eo', 'Esperanto'], ['fa', 'فارسی'], ['fi', 'Suomi'], ['gl', 'Galego'],
    ['he', 'עברית'], ['hr', 'Hrvatski'], ['hu', 'Magyar'], ['id', 'Bahasa Indonesia'],
    ['ml', 'മലയാളം'], ['nb', 'Norsk bokmål'], ['nl', 'Nederlands'], ['pl', 'Polski'],
    ['pt', 'Português'], ['sk', 'Slovenčina'], ['sl', 'Slovenščina'], ['sv', 'Svenska'],
    ['ta', 'தமிழ்'], ['th', 'ไทย'], ['ti', 'ትግርኛ'], ['tr', 'Türkçe'], ['uk', 'Українська']
  ];
  var UP = window.LANTAI_I18N_UPSTREAM || {};
  var OWN = window.LANTAI_I18N_DICT || {};
  var DICT = {};
  var KEY = 'lantai.lang';
  var cur = 'zh-Hans';

  function merge(code) {
    var out = {}, k;
    var up = UP[code];
    if (up) for (k in up) if (Object.prototype.hasOwnProperty.call(up, k)) out[k] = up[k];
    var own = OWN[code];
    if (own) for (k in own) if (Object.prototype.hasOwnProperty.call(own, k)) out[k] = own[k];
    return out;
  }

  function known(code) {
    for (var i = 0; i < NAMES.length; i++) if (NAMES[i][0] === code) return true;
    return false;
  }

  function ensure(code) {
    if (DICT[code]) return true;
    if (!OWN[code]) return false;
    DICT[code] = merge(code);
    return true;
  }

  try {
    var saved = localStorage.getItem(KEY);
    if (saved && known(saved)) cur = saved;
  } catch (e) { }

  ensure(cur);

  function lookup(code, key) {
    var t = DICT[code];
    return t && Object.prototype.hasOwnProperty.call(t, key) ? t[key] : null;
  }

  function t(key, vars) {
    ensure(cur);
    var out = lookup(cur, key);
    if (out == null) out = lookup('en', key);
    if (out == null) out = lookup('zh-Hans', key);
    if (out == null) return key;
    if (vars) {
      Object.keys(vars).forEach(function (k) {
        out = out.split('{' + k + '}').join(String(vars[k]));
      });
    }
    return out;
  }

  function setHtmlLang() {
    try { document.documentElement.setAttribute('lang', cur); } catch (e) { }
  }

  function lang(code) {
    if (code && known(code) && ensure(code)) {
      cur = code;
      try { localStorage.setItem(KEY, code); } catch (e) { }
      setHtmlLang();
    }
    return cur;
  }

  function langs() {
    return NAMES.map(function (x) { return { code: x[0], name: x[1] }; });
  }

  function name(code) {
    for (var i = 0; i < NAMES.length; i++) if (NAMES[i][0] === code) return NAMES[i][1];
    return code;
  }

  function apply(root) {
    var scope = root || document;
    var nodes = scope.querySelectorAll('[data-i18n]');
    for (var i = 0; i < nodes.length; i++) nodes[i].textContent = t(nodes[i].dataset.i18n);
    var phs = scope.querySelectorAll('[data-i18n-ph]');
    for (var j = 0; j < phs.length; j++) phs[j].setAttribute('placeholder', t(phs[j].dataset.i18nPh));
    var tts = scope.querySelectorAll('[data-i18n-title]');
    for (var k = 0; k < tts.length; k++) tts[k].setAttribute('title', t(tts[k].dataset.i18nTitle));
    setHtmlLang();
  }

  setHtmlLang();
  window.LantaiI18n = { t: t, lang: lang, langs: langs, name: name, apply: apply };
})();
