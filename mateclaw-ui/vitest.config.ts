import { defineConfig } from 'vitest/config'
import path from 'node:path'

// Two test conventions coexist in this repo:
//   - `test/**/*.test.ts` — pre-existing files using the Node `node:test`
//     runner (run via `node --test test/<file>.test.ts`).
//   - `src/**/__tests__/*.test.ts` — vitest tests for new code.
//
// Scope vitest to `src/**` so it never picks up the node:test files (which
// don't export describe/it/expect and would otherwise fail discovery).
export default defineConfig({
  test: {
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    environment: 'happy-dom',
    // Vite/Vitest default to resolving "localhost" during startup. Some
    // dev machines and CI sandboxes have a broken /etc/hosts and DNS lookup
    // for localhost fails with ENOTFOUND, even though 127.0.0.1 works. Pin the
    // internal API server + DOM URL to loopback so tests do not depend on host
    // file hygiene.
    api: { host: '127.0.0.1' },
    environmentOptions: {
      happyDOM: { url: 'http://127.0.0.1:3000' },
    },
  },
  server: {
    host: '127.0.0.1',
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
})
