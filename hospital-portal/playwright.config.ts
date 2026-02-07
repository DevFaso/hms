/// <reference types="node" />

import process from 'node:process';
import { defineConfig, devices } from '@playwright/test';

const defaultBaseURL = process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:4200';
const smokeBaseURL = process.env.SMOKE_BASE_URL ?? defaultBaseURL;
const shouldStartLocalServer =
  !process.env.SMOKE_BASE_URL && defaultBaseURL.startsWith('http://localhost');

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  reporter: [['list'], ['json', { outputFile: 'playwright-report/results.json' }]],
  use: { headless: true, baseURL: defaultBaseURL },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'smoke',
      use: { ...devices['Desktop Chrome'], baseURL: smokeBaseURL },
    },
  ],
  ...(shouldStartLocalServer && {
    webServer: {
      command: 'npm start',
      url: defaultBaseURL,
      reuseExistingServer: true,
      timeout: 120_000,
    },
  }),
});
