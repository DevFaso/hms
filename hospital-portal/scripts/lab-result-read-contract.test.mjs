import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';

test('all raw lab-result GET clients match the backend response envelopes', () => {
  const controller = readFileSync(
    '../hospital-core/src/main/java/com/example/hms/controller/LabResultController.java',
    'utf8',
  );
  const service = ts.createSourceFile(
    'lab.service.ts',
    readFileSync('src/app/services/lab.service.ts', 'utf8'),
    ts.ScriptTarget.Latest,
    true,
  );
  const clients = {
    getLabResultById: 'getResult',
    compareLabResult: 'compareResult',
    compareSequentialResults: 'compareSequential',
    getCriticalResults: 'criticalResults',
    getUnacknowledgedCriticalResults: 'criticalUnacknowledged',
  };
  const methods = new Map();
  function visit(node) {
    if (ts.isMethodDeclaration(node)) methods.set(node.name.getText(service), node);
    ts.forEachChild(node, visit);
  }
  visit(service);
  let checked = 0;
  for (const match of controller.matchAll(
    /@GetMapping(?:\([^)]*\))?[\s\S]*?public ResponseEntity<([^\n]+)>\s+(\w+)\s*\(/g,
  )) {
    if (match[1].includes('ApiResponseWrapper')) continue;
    const client = clients[match[2]];
    assert.ok(client, `Classify the raw read ${match[2]} before adding a client`);
    const method = methods.get(client);
    assert.ok(method, `Missing client ${client}`);
    assert.doesNotMatch(
      method.getText(service),
      /ApiWrapper|\.data\b/,
      `${client} must preserve the backend's raw DTO`,
    );
    checked++;
  }
  assert.equal(checked, Object.keys(clients).length);
});
