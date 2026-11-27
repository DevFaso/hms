import { defineConfig } from '@playwright/test';
import base from './playwright.production.config';

// Go-live P5: the agreed WCAG 2.1 AA scope (docs/qa/wcag-scope.md) on the
// production bundle. Every test is independent and fully mocked.
export default defineConfig({
  ...base,
  testMatch: ['wcag-scope.spec.ts'],
  workers: process.env.CI ? 3 : 1,
  timeout: 90000,
});
