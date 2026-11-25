import { defineConfig } from '@playwright/test';
import base from './playwright.production.config';

export default defineConfig({
  ...base,
  testMatch: ['clinical-form-display.spec.ts'],
  timeout: 90000,
  use: { ...base.use, actionTimeout: 10000 },
});
