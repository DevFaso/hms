import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';

function rawGetMethods(controller) {
  return controller
    .split(/@GetMapping\b/)
    .slice(1)
    .flatMap((endpoint) => {
      // Inspect the first declaration after this mapping, never a later method.
      const declaration = endpoint.match(/\bpublic\s+([^{};()]+?)\s+(\w+)\s*\(/);
      assert.ok(declaration, 'Cannot parse a lab-result GET declaration');
      const [, returnType, name] = declaration;
      const response = returnType.match(/^ResponseEntity\s*<([\s\S]+)>$/);
      assert.ok(response, `Classify the unsupported GET return type for ${name}`);
      return response[1].includes('ApiResponseWrapper') ? [] : [name];
    });
}

test('a multiline raw GET cannot be hidden by the next wrapped GET', () => {
  assert.deepEqual(
    rawGetMethods(`
      @GetMapping("/new")
      public ResponseEntity<
        LabResultResponseDTO> newRead() { return null; }
      @GetMapping("/wrapped")
      public ResponseEntity<ApiResponseWrapper<Page<LabResultResponseDTO>>> wrapped() {
        return null;
      }
    `),
    ['newRead'],
  );
});

test('an unsupported GET return type fails before the next wrapped GET', () => {
  assert.throws(
    () =>
      rawGetMethods(`
        @GetMapping("/new")
        public LabResultResponseDTO newRead() { return null; }
        @GetMapping("/wrapped")
        public ResponseEntity<ApiResponseWrapper<Page<LabResultResponseDTO>>> wrapped() {
          return null;
        }
      `),
    /Classify the unsupported GET return type for newRead/,
  );
});

test('an unparseable GET fails instead of disappearing', () => {
  assert.throws(() => rawGetMethods('@GetMapping("/new")'), /Cannot parse/);
});

test('all raw lab-result GET clients match the backend response envelopes', () => {
  const controller = readFileSync(
    new URL(
      '../../hospital-core/src/main/java/com/example/hms/controller/LabResultController.java',
      import.meta.url,
    ),
    'utf8',
  );
  const service = ts.createSourceFile(
    'lab.service.ts',
    readFileSync(new URL('../src/app/services/lab.service.ts', import.meta.url), 'utf8'),
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
  for (const name of rawGetMethods(controller)) {
    const client = clients[name];
    assert.ok(client, `Classify the raw read ${name} before adding a client`);
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
