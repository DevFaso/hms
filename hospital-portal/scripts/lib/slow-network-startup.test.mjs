// QA D2351 / D2352 guard (r63, slow 3G, cheap Android phone): the start-up
// costs that made the patient portal unusable on a slow or dropped connection.
//
// - an icon font loaded with `display=swap` (or no box) showed ligature names
//   ("chat campaign notifications") as words over the page, for 85 s, and
//   pushed the menu button out from under the finger;
// - the Material Symbols request asked for every FILL/weight/grade axis that
//   the portal never uses, which is most of that font's size;
// - nothing showed until the application had started (a blank page);
// - an English fallback language downloaded en.json first and showed "Sign In";
// - nginx sent the translation files with no-store, so every reload fetched them again.
//
// Each rule reads the one file it protects, so a revert names the cost it brings back.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const portal = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (path) => readFileSync(join(portal, path), 'utf8');

export function iconFontOffenders(indexHtml) {
  const offenders = [];
  const links = [...indexHtml.matchAll(/href="(https:\/\/fonts\.googleapis\.com\/[^"]+)"/g)].map(
    (m) => m[1].replace(/&amp;/g, '&'),
  );
  for (const href of links.filter((h) => /Material(\+|%20)(Symbols|Icons)/.test(h))) {
    if (!/[?&]display=block\b/.test(href)) {
      offenders.push(`${href}: an icon font needs display=block (never its name as words)`);
    }
    if (/Material\+Symbols/.test(href) && /FILL,GRAD@[^&]*0\.\.1|,100\.\.700,/.test(href)) {
      offenders.push(`${href}: asks for axes the portal never uses (the 85 s download)`);
    }
  }
  if (
    !/\.material-symbols-outlined,\s*\.material-icons\s*\{[^}]*width:\s*1em[^}]*overflow:\s*hidden/s.test(
      indexHtml,
    )
  ) {
    offenders.push('index.html: icons are not held to a 1em box before their font loads');
  }
  if (!/html:not\(\.icons-ready\)/.test(indexHtml)) {
    offenders.push('index.html: icons are not hidden until their font has loaded');
  }
  if (!/<app-root>\s*(<!--[^]*?-->\s*)?<div class="boot-screen"/.test(indexHtml)) {
    offenders.push('index.html: <app-root> is empty: a slow phone shows a blank page');
  }
  return offenders;
}

test('QA D2351: the icon fonts are light, boxed and never shown as words', () => {
  assert.deepEqual(iconFontOffenders(read('src/index.html')), []);
});

test('QA D2351: the guard sees each regression', () => {
  const good = read('src/index.html');
  assert.notDeepEqual(iconFontOffenders(good.replace(/display=block/g, 'display=swap')), []);
  assert.notDeepEqual(
    iconFontOffenders(good.replace('@24,400,0,0', '@20..48,100..700,0..1,-50..200')),
    [],
  );
  assert.notDeepEqual(iconFontOffenders(good.replace(/<div class="boot-screen"/, '<div')), []);
});

test('QA D2351: the fallback language is the product language (no en.json download first)', () => {
  const config = read('src/app/app.config.ts');
  const block = config.match(/provideTranslateService\(\{([^}]*)\}\)/s)?.[1] ?? '';
  assert.match(block, /fallbackLang:\s*DEFAULT_LANG/);
  assert.match(config, /provideAppInitializer\(\(\) => translationsReady\(/);
});

test('QA D2351: nginx revalidates the translation files instead of re-downloading them', () => {
  const nginx = read('nginx.conf');
  const block = nginx.match(/location \^~ \/assets\/i18n\/ \{([^}]*)\}/)?.[1] ?? '';
  assert.match(block, /Cache-Control "no-cache"/);
  assert.doesNotMatch(block, /no-store/);
});

test('QA D2352: a page that fails offline is never reloaded into a blank browser error', () => {
  const recovery = read('src/app/core/chunk-recovery.service.ts');
  assert.match(recovery, /if \(!this\.browser\.online\(\)\)/);
  assert.match(recovery, /navigateIfReachable/);
  const config = read('src/app/app.config.ts');
  const chain = config.match(/withInterceptors\(\[([^]*?)\]\)/)?.[1] ?? '';
  const names = chain
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => /^[a-zA-Z]+,?$/.test(l))
    .map((l) => l.replace(',', ''));
  assert.equal(names.at(-1), 'connectivityInterceptor', 'innermost, so it sees the network');
});
