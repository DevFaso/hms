/// <reference types="node" />

import process from 'node:process';
import path from 'node:path';
import { defineConfig, devices } from '@playwright/test';

const defaultBaseURL = process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:4200';
const smokeBaseURL = process.env.SMOKE_BASE_URL ?? defaultBaseURL;
const shouldStartLocalServer =
  !process.env.SMOKE_BASE_URL && defaultBaseURL.startsWith('http://localhost');

const STORAGE_STATE = path.join(__dirname, 'e2e/.auth/user.json');

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  timeout: 30_000,
  expect: { timeout: 10_000 },

  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }], ['json', { outputFile: 'playwright-report/results.json' }]]
    : [['list'], ['html', { open: 'on-failure' }], ['json', { outputFile: 'playwright-report/results.json' }]],

  use: {
    headless: true,
    baseURL: defaultBaseURL,
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },

  projects: [
    /* ── Setup ─────────────────────────── */
    {
      name: 'setup',
      testMatch: /global-setup\.ts/,
    },

    /* ── Authenticated tests ───────────── */
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: STORAGE_STATE,
      },
      dependencies: ['setup'],
      testIgnore: [/global-setup\.ts/, /auth\.spec\.ts/, /smoke\.spec\.ts/, /mobile\.spec\.ts/],
    },

    /* ── Unauthenticated tests ─────────── */
    {
      name: 'no-auth',
      use: { ...devices['Desktop Chrome'] },
      testMatch: /auth\.spec\.ts/,
    },

    /* ── Smoke (staging/prod) ──────────── */
    {
      name: 'smoke',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: smokeBaseURL,
        storageState: STORAGE_STATE,
      },
      dependencies: ['setup'],
      testMatch: /smoke\.spec\.ts/,
    },

    /* ── Mobile viewport ───────────────── */
    {
      name: 'mobile',
      use: {
        ...devices['Pixel 5'],
        storageState: STORAGE_STATE,
      },
      dependencies: ['setup'],
      testMatch: /mobile\.spec\.ts/,
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
