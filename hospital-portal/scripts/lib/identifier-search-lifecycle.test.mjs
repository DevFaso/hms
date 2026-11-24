import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import ts from 'typescript';

const ROOT = join(import.meta.dirname, '..', '..', 'src', 'app');
function sources(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const file = join(dir, entry.name);
    if (entry.isDirectory()) return sources(file);
    return entry.name.endsWith('.ts') && !entry.name.endsWith('.spec.ts') ? [file] : [];
  });
}
function tokens(node) {
  const scanner = ts.createScanner(
    ts.ScriptTarget.Latest,
    true,
    ts.LanguageVariant.Standard,
    node.getText(),
  );
  let text = '';
  while (scanner.scan() !== ts.SyntaxKind.EndOfFileToken) text += scanner.getTokenText();
  return text;
}

test('every protected exact-identifier search rejects stale terms and lost permissions before displaying rows', () => {
  let calls = 0;
  for (const file of sources(ROOT)) {
    const source = ts.createSourceFile(
      file,
      readFileSync(file, 'utf8'),
      ts.ScriptTarget.Latest,
      true,
    );
    function visit(node) {
      if (
        ts.isCallExpression(node) &&
        ts.isPropertyAccessExpression(node.expression) &&
        node.expression.name.text === 'searchNationalUniqueId'
      ) {
        calls++;
        let owner = node.parent;
        while (owner && !ts.isClassDeclaration(owner)) owner = owner.parent;
        assert.ok(owner, `${file}: exact identifier lookup needs an owned view lifecycle`);
        const body = tokens(owner);
        const change = owner.members.find(
          (member) => member.name?.getText(source) === 'onSearchChange',
        );
        if (change) {
          assert.ok(
            tokens(change).includes('this.iuRequest++'),
            `${file}: invalidate immediately, before debounce`,
          );
          assert.ok(
            tokens(change).includes('this.iuMatchIds.set(newSet<string>())'),
            `${file}: clear previous identifier matches`,
          );
          assert.ok(
            body.includes('request!==this.iuRequest'),
            `${file}: refuse an outdated response`,
          );
          assert.ok(
            body.includes('iu!==normalizedNationalUniqueId(this.searchTerm.trim())'),
            `${file}: compare the current typed identifier`,
          );
          assert.ok(
            (body.match(/hasAnyActiveRoleExactly/g) ?? []).length >= 2,
            `${file}: recheck permission when the response arrives`,
          );
        } else {
          // The shared picker retains its existing server authorization gate and snapshots every search context.
          const input = owner.members.find(
            (member) => member.name?.getText(source) === 'onQueryChange',
          );
          assert.ok(
            input,
            `${file}: new exact-identifier caller needs an explicit freshness contract`,
          );
          assert.ok(
            tokens(input).includes('++this.searchRevision'),
            `${file}: invalidate on every keystroke`,
          );
          for (const method of ['setNationalUniqueIdMode', 'select', 'clear', 'selected']) {
            const reset = owner.members.find((member) => member.name?.getText(source) === method);
            assert.ok(
              reset && tokens(reset).includes('this.searchRevision++'),
              `${file}: invalidate when ${method} changes`,
            );
          }
          for (const comparison of [
            'revision!==this.searchRevision',
            'q!==this.query()',
            'iuMode!==this.nationalUniqueIdMode()',
          ]) {
            assert.ok(
              body.includes(comparison),
              `${file}: missing response context comparison ${comparison}`,
            );
          }
        }
      }
      ts.forEachChild(node, visit);
    }
    visit(source);
  }
  assert.ok(calls >= 2, 'the census must find the real protected directory lookup');
});
