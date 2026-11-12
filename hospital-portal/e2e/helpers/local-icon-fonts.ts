import fs from 'node:fs';
import path from 'node:path';
import type { Page } from '@playwright/test';

/** Serve actual icon glyphs from a pinned test dependency, without contacting Google. */
export async function installLocalIconFonts(page: Page): Promise<void> {
  const font = fs.readFileSync(
    path.resolve(
      __dirname,
      '../../node_modules/@fontsource-variable/material-symbols-outlined/files/material-symbols-outlined-latin-full-normal.woff2',
    ),
  );
  const css = `
    @font-face { font-family: 'Material Symbols Outlined'; font-style: normal;
      font-weight: 100 700; src: url('/cdx-a11y-icons.woff2') format('woff2'); }
    .material-symbols-outlined, .material-icons { font-family: 'Material Symbols Outlined';
      font-weight: normal; font-style: normal; font-size: 24px; line-height: 1;
      letter-spacing: normal; text-transform: none; display: inline-block;
      white-space: nowrap; word-wrap: normal; direction: ltr; font-feature-settings: 'liga'; }
  `;
  await page.route('**/cdx-a11y-icons.woff2', (route) =>
    route.fulfill({ contentType: 'font/woff2', body: font }),
  );
  await page.route('https://fonts.googleapis.com/**', (route) =>
    new URL(route.request().url()).pathname.endsWith('.woff2')
      ? route.fallback()
      : route.fulfill({ contentType: 'text/css', body: css }),
  );
}
