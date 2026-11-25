import { defineConfig } from '@playwright/test';
import base from './playwright.slow-network.config';

export default defineConfig({
  ...base,
  testMatch: ['go-live-record-rights.spec.ts', 'go-live-recipient-verification.spec.ts'],
});
