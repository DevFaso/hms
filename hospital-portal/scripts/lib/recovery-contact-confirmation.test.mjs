import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import ts from 'typescript';
import { walk } from './walk.mjs';
import { templatesIn } from './inline-templates.mjs';

const portal = fileURLToPath(new URL('../../', import.meta.url));
const app = join(portal, 'src/app');

test('all recovery-code HTTP callers submit the explicit confirmed destination', () => {
  const callers = [];
  for (const file of walk(app, ['.ts']).filter((path) => !path.endsWith('.spec.ts'))) {
    const source = readFileSync(file, 'utf8');
    const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
    function visit(node) {
      if (
        ts.isCallExpression(node) &&
        node.expression.getText(ast).endsWith('.sendRecoveryVerificationCode')
      ) {
        callers.push(file);
        assert.equal(node.arguments.length, 2, file);
        assert.equal(node.arguments[1].getText(ast), 'contact.contactValue', file);
      }
      ts.forEachChild(node, visit);
    }
    visit(ast);
  }
  assert.equal(callers.length, 1, 'review the confirmation boundary of every added caller');
  const profile = readFileSync(join(app, 'profile/profile.ts'), 'utf8');
  assert.match(profile, /await this\.confirm\.ask\(/u);
  assert.match(profile, /!confirmed\s*\|\|/u);
  assert.match(profile, /params: \{ contact: value \}/u);
  assert.match(profile, /confirmedRecoveryContact\?\.value !== contact\.contactValue/u);
});

test('recovery contact labels cover every backend enum and no template embeds the US example', () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const source = readFileSync(
    join(root, 'hospital-core/src/main/java/com/example/hms/enums/RecoveryContactType.java'),
    'utf8',
  );
  const codes = source.match(/\b[A-Z][A-Z_]+\b/gu);
  for (const { file, text } of templatesIn(app)) {
    assert.ok(!text.includes('+1234567890'), file);
    assert.ok(!/\{\{\s*\w+\.contactType\s*\}\}/u.test(text), file);
  }
  for (const language of ['en', 'fr', 'es']) {
    const bundle = JSON.parse(
      readFileSync(join(portal, `src/assets/i18n/${language}.json`), 'utf8'),
    );
    for (const code of codes)
      assert.ok(bundle.PORTAL.ENUM.RECOVERY_CONTACT_TYPE[code], `${language}.${code}`);
    const labels = bundle.PROFILE;
    for (const key of [
      'RECOVERY_CONFIRM_TITLE',
      'RECOVERY_CONFIRM_ADDRESS',
      'RECOVERY_SEND_CODE',
    ]) {
      assert.ok(labels[key], `${language}.${key}`);
    }
    assert.ok(labels.RECOVERY_CONFIRM_ADDRESS.includes('{{contact}}'));
  }
});
