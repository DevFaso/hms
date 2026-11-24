import { defineConfig } from '@playwright/test';
import base from './playwright.slow-network.config';

export default defineConfig({
  ...base,
  testMatch: ['a11y-keyboard-care.ts', 'a11y-main-refusals.ts'],
  timeout: 90000,
});
