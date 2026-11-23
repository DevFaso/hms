import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: ['slow-network.spec.ts', 'api-timeout.spec.ts'],
  workers: 1,
  timeout: 60000,
  use: {
    baseURL: 'http://127.0.0.1:4281',
    headless: true,
    ...(process.env.PLAYWRIGHT_CHROME_PATH
      ? { launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROME_PATH } }
      : {}),
  },
  reporter: [['list'], ['html', { open: 'never' }]],
  webServer: {
    command: 'npm run build && node scripts/serve-built-portal.mjs',
    url: 'http://127.0.0.1:4281',
    reuseExistingServer: false,
    timeout: 240000,
  },
});
