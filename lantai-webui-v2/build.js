const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const root = __dirname;
const src = path.join(root, 'src');
const dist = path.join(root, 'dist');

const read = p => fs.readFileSync(p, 'utf8');
const hash = s => crypto.createHash('sha256').update(s).digest('hex').slice(0, 10);

fs.rmSync(dist, { recursive: true, force: true });
fs.mkdirSync(path.join(dist, 'css'), { recursive: true });
fs.mkdirSync(path.join(dist, 'js'), { recursive: true });

const css = read(path.join(src, 'css', 'app.css')) + '\n' + read(path.join(src, 'css', 'comic.css')) + '\n' + read(path.join(src, 'css', 'ebook.css'));
const js = read(path.join(src, 'js', 'i18n.locales.js')) + '\n' + read(path.join(src, 'js', 'i18n.dict.js')) + '\n' + read(path.join(src, 'js', 'i18n.js')) + '\n' + read(path.join(src, 'js', 'runtime.js')) + '\n' + read(path.join(src, 'js', 'app.js')) + '\n' + read(path.join(src, 'js', 'comic.js')) + '\n' + read(path.join(src, 'js', 'ebook.js'));

const cssName = 'css/lantai.' + hash(css) + '.css';
const jsName = 'js/lantai.' + hash(js) + '.js';

fs.writeFileSync(path.join(dist, cssName), css);
fs.writeFileSync(path.join(dist, jsName), js);
fs.writeFileSync(
  path.join(dist, 'index.html'),
  read(path.join(src, 'index.html')).replace('%%CSS%%', cssName).replace('%%JS%%', jsName)
);

console.log(cssName + ' ' + (css.length / 1024).toFixed(1) + ' KB');
console.log(jsName + ' ' + (js.length / 1024).toFixed(1) + ' KB');
