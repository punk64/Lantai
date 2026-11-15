(function () {
  var T = {
    af: ['Afrikaans', 'Afrikaans', '南非荷兰文'],
    am: ['አማርኛ', 'Amharic', '阿姆哈拉文'],
    ar: ['العربية', 'Arabic', '阿拉伯文'],
    az: ['Azərbaycan dili', 'Azerbaijani', '阿塞拜疆文'],
    be: ['Беларуская', 'Belarusian', '白俄罗斯文'],
    bg: ['Български', 'Bulgarian', '保加利亚文'],
    bn: ['বাংলা', 'Bengali', '孟加拉文'],
    br: ['Brezhoneg', 'Breton', '布列塔尼文'],
    bs: ['Bosanski', 'Bosnian', '波斯尼亚文'],
    ca: ['Català', 'Catalan', '加泰罗尼亚文'],
    ceb: ['Cebuano', 'Cebuano', '宿务文'],
    co: ['Corsu', 'Corsican', '科西嘉文'],
    cs: ['Čeština', 'Czech', '捷克文'],
    cy: ['Cymraeg', 'Welsh', '威尔士文'],
    da: ['Dansk', 'Danish', '丹麦文'],
    de: ['Deutsch', 'German', '德文'],
    el: ['Ελληνικά', 'Greek', '希腊文'],
    en: ['English', 'English', '英文'],
    eo: ['Esperanto', 'Esperanto', '世界文'],
    es: ['Español', 'Spanish', '西班牙文'],
    et: ['Eesti', 'Estonian', '爱沙尼亚文'],
    eu: ['Euskara', 'Basque', '巴斯克文'],
    fa: ['فارسی', 'Persian', '波斯文'],
    fi: ['Suomi', 'Finnish', '芬兰文'],
    fil: ['Filipino', 'Filipino', '菲律宾文'],
    fr: ['Français', 'French', '法文'],
    fy: ['Frysk', 'Frisian', '弗里斯兰文'],
    ga: ['Gaeilge', 'Irish', '爱尔兰文'],
    gd: ['Gàidhlig', 'Scottish Gaelic', '苏格兰盖尔文'],
    gl: ['Galego', 'Galician', '加利西亚文'],
    gu: ['ગુજરાતી', 'Gujarati', '古吉拉特文'],
    ha: ['Hausa', 'Hausa', '豪萨文'],
    he: ['עברית', 'Hebrew', '希伯来文'],
    hi: ['हिन्दी', 'Hindi', '印地文'],
    hr: ['Hrvatski', 'Croatian', '克罗地亚文'],
    ht: ['Kreyòl ayisyen', 'Haitian Creole', '海地克里奥尔文'],
    hu: ['Magyar', 'Hungarian', '匈牙利文'],
    hy: ['Հայերեն', 'Armenian', '亚美尼亚文'],
    id: ['Bahasa Indonesia', 'Indonesian', '印尼文'],
    ig: ['Igbo', 'Igbo', '伊博文'],
    is: ['Íslenska', 'Icelandic', '冰岛文'],
    it: ['Italiano', 'Italian', '意大利文'],
    ja: ['日本語', 'Japanese', '日文'],
    jv: ['Basa Jawa', 'Javanese', '爪哇文'],
    ka: ['ქართული', 'Georgian', '格鲁吉亚文'],
    kk: ['Қазақ тілі', 'Kazakh', '哈萨克文'],
    km: ['ខ្មែរ', 'Khmer', '高棉文'],
    kn: ['ಕನ್ನಡ', 'Kannada', '卡纳达文'],
    ko: ['한국어', 'Korean', '韩文'],
    ku: ['Kurdî', 'Kurdish', '库尔德文'],
    ky: ['Кыргызча', 'Kyrgyz', '吉尔吉斯文'],
    la: ['Latina', 'Latin', '拉丁文'],
    lb: ['Lëtzebuergesch', 'Luxembourgish', '卢森堡文'],
    lo: ['ລາວ', 'Lao', '老挝文'],
    lt: ['Lietuvių', 'Lithuanian', '立陶宛文'],
    lv: ['Latviešu', 'Latvian', '拉脱维亚文'],
    mg: ['Malagasy', 'Malagasy', '马达加斯加文'],
    mi: ['Te Reo Māori', 'Maori', '毛利文'],
    mk: ['Македонски', 'Macedonian', '马其顿文'],
    ml: ['മലയാളം', 'Malayalam', '马拉雅拉姆文'],
    mn: ['Монгол', 'Mongolian', '蒙古文'],
    mr: ['मराठी', 'Marathi', '马拉地文'],
    ms: ['Bahasa Melayu', 'Malay', '马来文'],
    mt: ['Malti', 'Maltese', '马耳他文'],
    my: ['မြန်မာ', 'Burmese', '缅甸文'],
    nb: ['Norsk bokmål', 'Norwegian Bokmål', '挪威文'],
    ne: ['नेपाली', 'Nepali', '尼泊尔文'],
    nl: ['Nederlands', 'Dutch', '荷兰文'],
    no: ['Norsk', 'Norwegian', '挪威文'],
    ny: ['Chichewa', 'Chichewa', '齐切瓦文'],
    or: ['ଓଡ଼ିଆ', 'Odia', '奥里亚文'],
    pa: ['ਪੰਜਾਬੀ', 'Punjabi', '旁遮普文'],
    pl: ['Polski', 'Polish', '波兰文'],
    ps: ['پښتو', 'Pashto', '普什图文'],
    pt: ['Português', 'Portuguese', '葡萄牙文'],
    ro: ['Română', 'Romanian', '罗马尼亚文'],
    ru: ['Русский', 'Russian', '俄文'],
    rw: ['Ikinyarwanda', 'Kinyarwanda', '卢旺达文'],
    sd: ['سنڌي', 'Sindhi', '信德文'],
    si: ['සිංහල', 'Sinhala', '僧伽罗文'],
    sk: ['Slovenčina', 'Slovak', '斯洛伐克文'],
    sl: ['Slovenščina', 'Slovenian', '斯洛文尼亚文'],
    sm: ['Gagana Sāmoa', 'Samoan', '萨摩亚文'],
    sn: ['chiShona', 'Shona', '绍纳文'],
    so: ['Soomaali', 'Somali', '索马里文'],
    sq: ['Shqip', 'Albanian', '阿尔巴尼亚文'],
    sr: ['Српски', 'Serbian', '塞尔维亚文'],
    st: ['Sesotho', 'Sesotho', '塞索托文'],
    su: ['Basa Sunda', 'Sundanese', '巽他文'],
    sv: ['Svenska', 'Swedish', '瑞典文'],
    sw: ['Kiswahili', 'Swahili', '斯瓦希里文'],
    ta: ['தமிழ்', 'Tamil', '泰米尔文'],
    te: ['తెలుగు', 'Telugu', '泰卢固文'],
    tg: ['Тоҷикӣ', 'Tajik', '塔吉克文'],
    th: ['ไทย', 'Thai', '泰文'],
    ti: ['ትግርኛ', 'Tigrinya', '提格里尼亚文'],
    tk: ['Türkmen', 'Turkmen', '土库曼文'],
    tr: ['Türkçe', 'Turkish', '土耳其文'],
    tt: ['Татарча', 'Tatar', '鞑靼文'],
    ug: ['ئۇيغۇرچە', 'Uyghur', '维吾尔文'],
    uk: ['Українська', 'Ukrainian', '乌克兰文'],
    ur: ['اردو', 'Urdu', '乌尔都文'],
    uz: ['Oʻzbek', 'Uzbek', '乌兹别克文'],
    vi: ['Tiếng Việt', 'Vietnamese', '越南文'],
    xh: ['isiXhosa', 'Xhosa', '科萨文'],
    yi: ['ייִדיש', 'Yiddish', '意第绪文'],
    yo: ['Yorùbá', 'Yoruba', '约鲁巴文'],
    zh: ['中文', 'Chinese', '中文'],
    zu: ['isiZulu', 'Zulu', '祖鲁文']
  };
  var HYPHEN = {
    'zh-hans': ['简体中文', 'Chinese (Simplified)', '简体中文'],
    'zh-hant': ['繁體中文', 'Chinese (Traditional)', '繁体中文'],
    'zh-tw': ['繁體中文', 'Chinese (Traditional)', '繁体中文'],
    'zh-hk': ['繁體中文', 'Chinese (Traditional)', '繁体中文'],
    'pt-br': ['Português (Brasil)', 'Portuguese (Brazil)', '巴西葡文'],
    'pt-pt': ['Português', 'Portuguese (Portugal)', '葡萄牙文'],
    'sr-latn': ['Srpski', 'Serbian (Latin)', '塞尔维亚文'],
    'nb-no': ['Norsk bokmål', 'Norwegian Bokmål', '挪威文']
  };
  var SPECIAL = {
    all: ['全部', 'All', '全部'],
    multi: ['多语言', 'Multi', '多语言'],
    other: ['其他', 'Other', '其他'],
    misc: ['其他', 'Other', '其他'],
    unknown: ['未知', 'Unknown', '未知']
  };

  function key(code) {
    return String(code || '').trim().toLowerCase().replace(/_/g, '-');
  }

  function entry(code) {
    var k = key(code);
    if (!k) return null;
    return HYPHEN[k] || SPECIAL[k] || T[k] || T[k.split('-')[0]] || null;
  }

  function zhUi() {
    var api = window.LantaiI18n || window.L;
    var l = (api && api.lang) ? String(api.lang() || '') : '';
    return l === 'zh-Hans' || l === 'zh-Hant';
  }

  function name(code) {
    var e = entry(code);
    if (!e) {
      var k = key(code);
      if (!k) return '—';
      return k.length <= 3 && k.indexOf('-') < 0 ? k.toUpperCase() : code;
    }
    return zhUi() ? e[2] : e[0];
  }

  function alias(code) {
    var e = entry(code);
    if (!e) return '';
    return zhUi() ? e[1] : e[2];
  }

  function label(codes) {
    var list = String(codes == null ? '' : codes).split(',').map(function (x) { return x.trim(); }).filter(Boolean);
    if (!list.length) return '—';
    var seen = {};
    return list.filter(function (c) {
      var k = key(c);
      if (seen[k]) return false;
      seen[k] = 1;
      return true;
    }).map(name).join(' / ');
  }

  window.LantaiLang = { name: name, alias: alias, label: label, entry: entry, key: key };
})();
