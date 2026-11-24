import { defineConfig } from '@playwright/test';
import base from './playwright.slow-network.config';

export default defineConfig({ ...base, testMatch: ['form-display.spec.ts'] });
