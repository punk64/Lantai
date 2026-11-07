const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const vm = require('vm');

const root = __dirname;
const src = path.join(root, 'src');
const dist = path.join(root, 'dist');

const read = p => fs.readFileSync(p, 'utf8');
const hash = s => crypto.createHash('sha256').update(s).digest('hex').slice(0, 10);

fs.rmSync(dist, { recursive: true, force: true });
fs.mkdirSync(path.join(dist, 'css'), { recursive: true });
fs.mkdirSync(path.join(dist, 'js'), { recursive: true });

const css = read(path.join(src, 'css', 'app.css')) + '\n' + read(path.join(src, 'css', 'comic.css')) + '\n' + read(path.join(src, 'css', 'ebook.css'));

const I18N_SRC = ['i18n.locales.js', 'i18n.dict.js', 'i18n.extra-a.js', 'i18n.extra-b.js', 'i18n.extra-c.js', 'i18n.extra-d.js', 'i18n.reader.js', 'i18n.greet.js'];
const i18nBox = { window: {} };
vm.createContext(i18nBox);
for (const f of I18N_SRC) vm.runInContext(read(path.join(src, 'js', f)), i18nBox);
const DICT = i18nBox.window.LANTAI_I18N_DICT;
const BUILTIN = 'zh-Hans';

const langBlock = code =>
  '(function(){var D=window.LANTAI_I18N_DICT=window.LANTAI_I18N_DICT||{};D[' + JSON.stringify(code) + ']=' + JSON.stringify(DICT[code]) + ';})();';

const MAIN_SRC = ['i18n.js', 'ext-lang.js', 'runtime.js', 'app.js'];
const js = [read(path.join(src, 'js', 'i18n.locales.js')), langBlock(BUILTIN)]
  .concat(MAIN_SRC.map(f => read(path.join(src, 'js', f))))
  .join('\n');

const CHUNKS = {
  reader: ['ebook.js', 'comic.js'],
  docs: ['help-export.js', 'scrape-help.js', 'api-i18n.js', 'api-i18n.more.js', 'api-doc.js'],
  sysx: ['about-notify.js', 'update-center.js']
};

const cssName = 'css/lantai.' + hash(css) + '.css';
const jsName = 'js/lantai.' + hash(js) + '.js';

fs.writeFileSync(path.join(dist, cssName), css);
fs.writeFileSync(path.join(dist, jsName), js);

const chunkMap = {};
for (const name of Object.keys(CHUNKS)) {
  const body = CHUNKS[name].map(f => read(path.join(src, 'js', f))).join('\n');
  const file = 'js/' + name + '.' + hash(body) + '.js';
  fs.writeFileSync(path.join(dist, file), body);
  chunkMap[name] = file;
}

const langMap = {};
let langBytes = 0;
for (const code of Object.keys(DICT)) {
  if (code === BUILTIN) continue;
  const body = langBlock(code);
  const file = 'js/lang-' + code + '.' + hash(body) + '.js';
  fs.writeFileSync(path.join(dist, file), body);
  langMap[code] = file;
  langBytes += Buffer.byteLength(body, 'utf8');
}

fs.writeFileSync(
  path.join(dist, 'index.html'),
  read(path.join(src, 'index.html'))
    .replace('%%CSS%%', cssName)
    .replace('%%JS%%', jsName)
    .replace('%%CHUNKS%%', JSON.stringify(chunkMap))
    .replace('%%LANGS%%', JSON.stringify(langMap))
);

console.log(cssName + ' ' + (css.length / 1024).toFixed(1) + ' KB');
console.log(jsName + ' ' + (Buffer.byteLength(js, 'utf8') / 1024).toFixed(1) + ' KB');
for (const k of Object.keys(chunkMap)) {
  console.log(chunkMap[k] + ' ' + (fs.statSync(path.join(dist, chunkMap[k])).size / 1024).toFixed(1) + ' KB');
}
console.log('langs ' + Object.keys(langMap).length + ' files ' + (langBytes / 1024).toFixed(1) + ' KB total');
