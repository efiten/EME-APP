// Build step: app/src/main/res/values*/strings.xml -> src/generated/strings.json
// and app/src/main/assets/land110m.txt -> src/generated/land110m.txt (git-ignored copy, one source of truth).
// { "<lang>": { "<name>": "<text>" } }, folder `values` = en, `values-xx` = xx.

import { copyFileSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const RES = join(HERE, '../../app/src/main/res');
const OUT = join(HERE, '../src/generated/strings.json');
const LAND = join(HERE, '../../app/src/main/assets/land110m.txt');
const LAND_OUT = join(HERE, '../src/generated/land110m.txt');

const ENTITIES = { lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" };
const BACKSLASH = String.fromCharCode(92);
const ESCAPES = { n: '\n', t: '\t' };

/** Android string text: entities decoded, \-escapes resolved, "..." keeps whitespace, other whitespace collapsed. */
function unescapeAndroid(raw) {
  let out = '';
  let quoted = false;
  let pendingSpace = false;
  const emit = (ch) => {
    if (pendingSpace && out !== '') out += ' ';
    pendingSpace = false;
    out += ch;
  };
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c === '&') {
      const m = /^&(?:#x([0-9a-fA-F]+)|#(\d+)|([a-z]+));/.exec(raw.slice(i));
      if (m) {
        const ch = m[1] ? String.fromCodePoint(parseInt(m[1], 16)) : m[2] ? String.fromCodePoint(+m[2]) : ENTITIES[m[3]];
        if (ch !== undefined) {
          emit(ch);
          i += m[0].length - 1;
          continue;
        }
      }
    }
    if (c === BACKSLASH && i + 1 < raw.length) {
      const n = raw[++i];
      if (n === 'u') {
        emit(String.fromCharCode(parseInt(raw.slice(i + 1, i + 5), 16)));
        i += 4;
      } else {
        emit(ESCAPES[n] ?? n);
      }
    } else if (c === '"') {
      quoted = !quoted;
    } else if (/\s/.test(c) && !quoted) {
      pendingSpace = true;
    } else {
      emit(c);
    }
  }
  return out;
}

/** -> [{ name, text, translatable }] for the <string> elements of one strings.xml. */
export function parseStrings(xml) {
  const result = [];
  const re = /<string\b([^>]*)>([\s\S]*?)<\/string>/g;
  const body = xml.replace(/<!--[\s\S]*?-->/g, '');
  for (const m of body.matchAll(re)) {
    const name = /\bname="([^"]*)"/.exec(m[1])[1];
    result.push({ name, text: unescapeAndroid(m[2]), translatable: !/\btranslatable="false"/.test(m[1]) });
  }
  return result;
}

export function buildStrings(resDir = RES) {
  const all = {};
  for (const folder of readdirSync(resDir).sort()) {
    const lang = folder === 'values' ? 'en' : /^values-([a-z]{2})$/.exec(folder)?.[1];
    if (!lang) continue;
    const entries = parseStrings(readFileSync(join(resDir, folder, 'strings.xml'), 'utf8'));
    all[lang] = Object.fromEntries(entries.map((e) => [e.name, e.text]));
  }
  return all;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(buildStrings(), null, 2) + '\n');
  copyFileSync(LAND, LAND_OUT);
}
