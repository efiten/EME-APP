import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';

const gradle = readFileSync(new URL('../app/build.gradle.kts', import.meta.url), 'utf8');
const appVersion = /versionName\s*=\s*"([^"]+)"/.exec(gradle)[1];

export default defineConfig({
  base: './',
  define: { __APP_VERSION__: JSON.stringify(appVersion) },
  test: {
    include: ['test/**/*.test.js'],
  },
});
