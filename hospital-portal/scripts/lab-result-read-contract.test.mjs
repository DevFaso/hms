import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';

const controllerPath = fileURLToPath(
  new URL(
    '../../hospital-core/src/main/java/com/example/hms/controller/LabResultController.java',
    import.meta.url,
  ),
);
const servicePath = fileURLToPath(new URL('../src/app/services/lab.service.ts', import.meta.url));
const clients = {
  getLabResultById: 'getResult',
  getAllLabResults: 'listAllResults',
  getPendingRelease: 'listPendingRelease',
  compareLabResult: 'compareResult',
  compareSequentialResults: 'compareSequential',
  getCriticalResults: 'criticalResults',
  getUnacknowledgedCriticalResults: 'criticalUnacknowledged',
};

function getContracts(controller) {
  const mappings = [...controller.matchAll(/@(?:Get|Post|Put|Delete|Patch|Request)Mapping\b/g)];
  const contracts = [];
  for (let index = 0; index < mappings.length; index++) {
    if (mappings[index][0] !== '@GetMapping') continue;
    const block = controller.slice(mappings[index].index, mappings[index + 1]?.index);
    // Bound each signature to its mapping; never search into a later method.
    const signature = /\b(public|protected|private)\s+([^{};]+?)\s+(\w+)\s*\(/.exec(block);
    assert.ok(
      signature && signature[1] === 'public',
      `Cannot parse GET declaration at ${mappings[index].index}`,
    );
    contracts.push({ name: signature[3], wrapped: /\bApiResponseWrapper\s*</.test(signature[2]) });
  }
  assert.equal(contracts.length, [...controller.matchAll(/@GetMapping\b/g)].length);
  assert.equal(new Set(contracts.map((contract) => contract.name)).size, contracts.length);
  return contracts;
}

function verifyContracts(controller, sourceText, declaredClients = clients) {
  const service = ts.createSourceFile('lab.service.ts', sourceText, ts.ScriptTarget.Latest, true);
  const methods = new Map();
  function visit(node) {
    if (ts.isMethodDeclaration(node)) methods.set(node.name.getText(service), node);
    ts.forEachChild(node, visit);
  }
  visit(service);
  const contracts = getContracts(controller);
  assert.equal(
    contracts.length,
    Object.keys(declaredClients).length,
    'Every GET needs an explicit client classification',
  );
  for (const contract of contracts) {
    const client = declaredClients[contract.name];
    assert.ok(client, `Classify GET ${contract.name} before adding a client`);
    const method = methods.get(client);
    assert.ok(method, `Missing client ${client}`);
    const body = method.getText(service);
    if (contract.wrapped) {
      assert.match(body, /ApiWrapper\s*</, `${client} must declare the backend envelope`);
      assert.match(body, /\.data\b/, `${client} must decode the backend envelope`);
    } else {
      assert.doesNotMatch(
        body,
        /ApiWrapper|\.data\b/,
        `${client} must preserve the backend's raw DTO`,
      );
    }
  }
}

test('every lab-result GET client matches its backend response envelope', () => {
  verifyContracts(readFileSync(controllerPath, 'utf8'), readFileSync(servicePath, 'utf8'));
});

test('multiline and direct DTO GET declarations cannot disappear into a later wrapped method', () => {
  const controller = `
    @GetMapping("/multiline") public ResponseEntity<\n LabResultResponseDTO\n > multiline() { }
    @GetMapping("/direct") public LabResultResponseDTO direct() { }
    @GetMapping("/wrapped") public ResponseEntity<ApiResponseWrapper<Page<LabResultResponseDTO>>> wrapped() { }
  `;
  assert.deepEqual(getContracts(controller), [
    { name: 'multiline', wrapped: false },
    { name: 'direct', wrapped: false },
    { name: 'wrapped', wrapped: true },
  ]);
  assert.throws(() => verifyContracts(controller, '', {}), /Every GET needs/);
});

test('unreadable GET declarations fail before a following non-GET signature', () => {
  assert.throws(
    () =>
      getContracts(
        '@GetMapping("/raw") private DTO detail() {} @PostMapping public ResponseEntity<ApiResponseWrapper<DTO>> save() {}',
      ),
    /Cannot parse GET/,
  );
  assert.throws(
    () =>
      getContracts(
        '@GetMapping("/raw") private DTO detail() {} public ResponseEntity<ApiResponseWrapper<DTO>> helper() {}',
      ),
    /Cannot parse GET/,
  );
});

test('both raw-as-envelope and envelope-as-raw mismatches fail the contract', () => {
  assert.throws(
    () =>
      verifyContracts(
        '@GetMapping public DTO detail() {}',
        'class Client { getResult() { return this.http.get<ApiWrapper<DTO>>("/").pipe(map(r => r.data)); } }',
        { detail: 'getResult' },
      ),
    /must preserve/,
  );
  assert.throws(
    () =>
      verifyContracts(
        '@GetMapping public ResponseEntity<ApiResponseWrapper<DTO>> detail() {}',
        'class Client { getResult() { return this.http.get<DTO>("/"); } }',
        { detail: 'getResult' },
      ),
    /must declare/,
  );
});
