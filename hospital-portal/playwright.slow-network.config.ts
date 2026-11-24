import { defineConfig } from '@playwright/test';
import base from './playwright.production.config';

export default defineConfig({
  ...base,
  testMatch: ['slow-network.spec.ts', 'api-timeout.spec.ts'],
});
