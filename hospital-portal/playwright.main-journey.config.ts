import { defineConfig } from '@playwright/test';
import base from './playwright.slow-network.config';
import { MAIN_JOURNEY_ROUTES } from './e2e/helpers/main-journey-routes';

const routes = MAIN_JOURNEY_ROUTES.map((route) =>
  route.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
).join('|');

export default defineConfig({
  ...base,
  testMatch: ['a11y.spec.ts'],
  grep: new RegExp(
    `routed accessibility: (?:${routes})$|main form refusal|public account form|patient registration|checkout:|dispensing:`,
  ),
  timeout: 90000,
});
