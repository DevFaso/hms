import { defineConfig } from '@playwright/test';
import base from './playwright.production.config';

export default defineConfig({ ...base, testMatch: ['form-display.spec.ts'] });
