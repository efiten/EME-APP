import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const DIR = fileURLToPath(new URL('../../app/src/test/resources/expected/', import.meta.url));

/** Same rules as ExpectedCsv.kt: header first, '#' lines and blank lines skipped, cells trimmed, empty = not asserted. */
export function readExpected(name) {
  const lines = readFileSync(DIR + name + '.csv', 'utf8')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l !== '' && !l.startsWith('#'));
  const header = lines[0].split(',').map((c) => c.trim());
  return lines.slice(1).map((line) => {
    const cells = line.split(',').map((c) => c.trim());
    return Object.fromEntries(header.map((h, i) => [h, cells[i] ?? '']));
  });
}
