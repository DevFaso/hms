import { defineConfig } from '@playwright/test';
import base from './playwright.slow-network.config';
export default defineConfig({ ...base, testMatch: ['identity-decisions.spec.ts'] });
