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
  const mappings = [
    ...controller.matchAll(
      /@(?:Get|Post|Put|Delete|Patch|Request)Mapping\b(?:\s*\((?:[^()"]|"(?:\\.|[^"\\])*")*\))?/g,
    ),
  ];
  const contracts = [];
  for (let index = 0; index < mappings.length; index++) {
    const mapping = mappings[index][0];
    const declarationStart = mappings[index].index + mapping.length;
    const block = controller.slice(declarationStart, mappings[index + 1]?.index);
    assert.ok(!/^\s*\(/.test(block), 'Cannot parse mapping annotation');
    const requestMapping = mapping.startsWith('@RequestMapping');
    // A class mapping supplies the base path, not a handler. An unrestricted
    // method-level RequestMapping also accepts GET, so it needs classification.
    if (requestMapping && /\bpublic\s+class\b/.test(block)) continue;
    if (
      !mapping.startsWith('@GetMapping') &&
      !(requestMapping && (!/\bmethod\s*=/.test(mapping) || /\bRequestMethod\.GET\b/.test(mapping)))
    )
      continue;
    // Bound each signature to its mapping; never search into a later method.
    const signature = /\b(public|protected|private)\s+([^{};]+?)\s+(\w+)\s*\(/.exec(block);
    assert.ok(
      signature && signature[1] === 'public',
      `Cannot parse GET declaration at ${mappings[index].index}`,
    );
    const returnType = signature[2].replace(/\s/g, '');
    const bodyType = /^ResponseEntity<(.+)>$/.exec(returnType)?.[1] ?? returnType;
    assert.ok(
      !['?', 'Object', 'java.lang.Object', 'ResponseEntity'].includes(bodyType) &&
        !bodyType.startsWith('?'),
      `Ambiguous GET response for ${signature[3]}: declare a concrete DTO or envelope`,
    );
    contracts.push({ name: signature[3], wrapped: /\bApiResponseWrapper\s*</.test(signature[2]) });
  }
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

test('RequestMapping GETs require classification, including mixed HTTP methods', () => {
  const controller = `
    @RequestMapping("/lab-results") public class Controller {
    @RequestMapping(value = "/detail", method = RequestMethod.GET) public DTO detail() {}
    @RequestMapping(value = "/mixed", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<ApiResponseWrapper<DTO>> mixed() {}
    @RequestMapping("/unrestricted") public DTO unrestricted() {}
    @RequestMapping(method = RequestMethod.POST) public DTO save() {}
    }
  `;
  assert.deepEqual(getContracts(controller), [
    { name: 'detail', wrapped: false },
    { name: 'mixed', wrapped: true },
    { name: 'unrestricted', wrapped: false },
  ]);
  assert.throws(() => verifyContracts(controller, '', {}), /Every GET needs/);
});

test('ambiguous response bodies require a concrete backend contract', () => {
  for (const type of ['ResponseEntity<?>', 'ResponseEntity<Object>', 'ResponseEntity', 'Object']) {
    assert.throws(
      () => getContracts(`@GetMapping public ${type} detail() {}`),
      /Ambiguous GET response/,
    );
  }
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
