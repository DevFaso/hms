import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');
const dto = read(
  '../../hospital-core/src/main/java/com/example/hms/payload/dto/DepartmentResponseDTO.java',
);
const backendFields = new Set(
  [...dto.matchAll(/^\s*private\s+[\w<>, ?]+\s+(\w+);/gm)].map((m) => m[1]),
);

// D1790: the portal read `dept.code` while the API sends `departmentCode`, so
// the edit form opened with an empty code. Every field of a portal interface
// that models DepartmentResponseDTO must be a field the DTO really has.
const portalFiles = {
  list: ['../src/app/departments/department-list.ts', 'Department'],
  detail: ['../src/app/departments/department-detail.ts', 'DepartmentDetail'],
};

for (const [name, [path, iface]] of Object.entries(portalFiles)) {
  test(`portal ${name} model only uses DepartmentResponseDTO field names`, () => {
    const src = read(path);
    const body = src.match(new RegExp(`interface ${iface} [{]([^}]*)[}]`))?.[1];
    assert.ok(body, `interface ${iface} not found`);
    const fields = [...body.matchAll(/^\s*(\w+)\??:/gm)].map((m) => m[1]);
    assert.ok(fields.length > 3);
    const unknown = fields.filter((f) => !backendFields.has(f));
    assert.deepEqual(unknown, [], `not in DepartmentResponseDTO: ${unknown.join(', ')}`);
  });
}
