#!/usr/bin/env node
/**
 * Every declared enum domain has a tier-2 group in EnumLabelService.LABELS.
 *
 * The pipe's fallback chain is i18n key -> English LABELS group -> Title-Case
 * prettifier. A domain with no LABELS group skips the middle step, so an
 * unported key renders "Pre Op Clearance Pending" instead of the curated
 * "Pre-Op Clearance Pending". #663 added five domains without one and nothing
 * noticed; twelve declared domains had none by the time it was found.
 *
 * The groups are read out of the TypeScript with the compiler's parser, not a
 * regex, so a comment that happens to look like `fooType: {` cannot satisfy it.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

const PORTAL_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SERVICE = resolve(PORTAL_DIR, 'src', 'app', 'core', 'enum-label.service.ts');
const DOMAINS = resolve(PORTAL_DIR, 'scripts', 'i18n-enum-domains.json');
const EN = resolve(PORTAL_DIR, 'src', 'assets', 'i18n', 'en.json');

/** `LABELS` group name -> its keys, straight from the class's static initializer. */
function labelsGroups() {
  const file = ts.createSourceFile(
    SERVICE,
    readFileSync(SERVICE, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
  );
  let groups = null;
  const visit = (node) => {
    if (
      ts.isPropertyDeclaration(node) &&
      node.name.getText(file) === 'LABELS' &&
      node.initializer &&
      ts.isObjectLiteralExpression(node.initializer)
    ) {
      groups = new Map();
      for (const prop of node.initializer.properties) {
        if (!ts.isPropertyAssignment(prop) || !ts.isObjectLiteralExpression(prop.initializer)) {
          continue;
        }
        const keys = prop.initializer.properties
          .filter(ts.isPropertyAssignment)
          .map((p) => (ts.isStringLiteral(p.name) ? p.name.text : p.name.getText(file)));
        groups.set(prop.name.getText(file), keys);
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(file);
  return groups;
}

const toUpperSnake = (camel) => camel.replaceAll(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase();

test('the parser finds the LABELS map at all', () => {
  // Zero groups would make the next test vacuously green.
  const groups = labelsGroups();
  assert.ok(groups, 'no static LABELS initializer found in enum-label.service.ts');
  assert.ok(groups.size >= 50, `only ${groups.size} LABELS groups parsed`);
});

test('every declared domain has a LABELS group', () => {
  const groups = labelsGroups();
  const declared = Object.keys(JSON.parse(readFileSync(DOMAINS, 'utf8'))).filter(
    (k) => !k.startsWith('$'),
  );
  const missing = declared.filter((d) => !groups.has(d));
  assert.deepEqual(missing, [], `declared in i18n-enum-domains.json with no LABELS group`);
});

test('a new LABELS group keys nothing its locale group does not', () => {
  // The groups added for the tier-2 net were copied from en.json; a key only
  // the fallback knows would be a label no locale can translate.
  const groups = labelsGroups();
  const en = JSON.parse(readFileSync(EN, 'utf8')).PORTAL.ENUM;
  for (const domain of [
    'immunizationStatus',
    'marTaskStatus',
    'newbornAlertType',
    'notificationType',
    'orderSourceType',
    'organizationType',
    'platformReleaseStatus',
    'platformServiceStatus',
    'postpartumAlertType',
    'procedureOrderStatus',
    'vitalTaskType',
    'shiftType',
    'leaveType',
    'employmentType',
  ]) {
    const localeKeys = Object.keys(en[toUpperSnake(domain)] ?? {});
    assert.deepEqual(groups.get(domain), localeKeys, domain);
  }
});
