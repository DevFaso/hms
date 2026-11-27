import { defineConfig, devices } from '@playwright/test';

// Isolated, mocked local UI checks; never reuse another worktree's server.
export default defineConfig({
  testDir: './e2e',
  testMatch: 'layout-overflow.spec.ts',
  timeout: 90_000,
  workers: 1,
  reporter: 'list',
  use: { ...devices['Desktop Chrome'], baseURL: 'http://127.0.0.1:4319', headless: true },
  webServer: {
    command: 'npm start -- --host 127.0.0.1 --port 4319',
    url: 'http://127.0.0.1:4319',
    reuseExistingServer: false,
    timeout: 300_000,
  },
});
