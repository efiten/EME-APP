import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';

const gradle = readFileSync(new URL('../app/build.gradle.kts', import.meta.url), 'utf8');
const appVersion = /versionName\s*=\s*"([^"]+)"/.exec(gradle)[1];

export default defineConfig({
  base: './',
  define: { __APP_VERSION__: JSON.stringify(appVersion) },
  // npm run dev: the Go server (web/server, go run . -listen 127.0.0.1:5200) answers /api
  server: { proxy: { '/api': 'http://127.0.0.1:5200' } },
  test: {
    include: ['test/**/*.test.js'],
  },
});
