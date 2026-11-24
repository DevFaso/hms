// Fills the CSP script-src of nginx/security-headers.conf with the sha256 of
// every inline <script> in the BUILT index.html, so the policy allows exactly
// those scripts without 'unsafe-inline'. Runs in the Dockerfile build stage,
// after `ng build`: the build rewrites index.html (minification, the critical
// CSS swap script), so hashing src/index.html would be wrong.
//
//   node scripts/write-csp-script-hashes.mjs <built index.html> <template> <output>
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { argv, exit } from 'node:process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const HASHES_PLACEHOLDER = '__CSP_SCRIPT_HASHES__';

/** The CSP source expression of every inline (no src) script in the page, in document order. */
export function inlineScriptHashes(html) {
  const hashes = [];
  for (const match of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
    const attributes = match[1];
    if (/\bsrc\s*=/i.test(attributes)) continue;
    // The HTML parser turns CRLF and lone CR into LF before a script runs, and
    // the browser hashes that text: a Windows checkout ships CRLF index.html.
    const text = match[2].replace(/\r\n?/g, '\n');
    const digest = createHash('sha256').update(text, 'utf8').digest('base64');
    hashes.push(`'sha256-${digest}'`);
  }
  return [...new Set(hashes)];
}

/** The template with its placeholder replaced; refuses a template without exactly one. */
export function applyScriptHashes(template, hashes) {
  const count = template.split(HASHES_PLACEHOLDER).length - 1;
  if (count !== 1) {
    throw new Error(`Expected exactly one ${HASHES_PLACEHOLDER} in the template, found ${count}.`);
  }
  return template.replace(HASHES_PLACEHOLDER, hashes.join(' '));
}

if (argv[1] && fileURLToPath(import.meta.url) === resolve(argv[1])) {
  const [indexPath, templatePath, outputPath] = argv.slice(2);
  if (!indexPath || !templatePath || !outputPath) {
    console.error('usage: write-csp-script-hashes.mjs <index.html> <template> <output>');
    exit(2);
  }
  const hashes = inlineScriptHashes(readFileSync(indexPath, 'utf8'));
  writeFileSync(outputPath, applyScriptHashes(readFileSync(templatePath, 'utf8'), hashes));
  console.log(`CSP: ${hashes.length} inline script hash(es) written to ${outputPath}`);
}
