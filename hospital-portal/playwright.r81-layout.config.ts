import process from 'node:process';
import { defineConfig } from '@playwright/test';
import base from './playwright.production.config';

// Use the isolated production build: the development server cannot link the
// partially compiled browser library in this environment.
export default defineConfig({
  ...base,
  testMatch: 'layout-overflow.spec.ts',
  timeout: 90_000,
  workers: 1,
  webServer: {
    command: process.env.PLAYWRIGHT_PREBUILT === '1'
      ? 'node scripts/serve-built-portal.mjs'
      : 'npm run build && node scripts/serve-built-portal.mjs',
    url: 'http://127.0.0.1:4281',
    reuseExistingServer: false,
    timeout: 600_000,
  },
});
