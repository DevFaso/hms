import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, basename } from 'node:path';
import { controlTemplates, controlStyleSources } from './form-control-styles.mjs';
import { controlsIn } from './form-control-design.mjs';

function fixture(fn) {
  const root = mkdtempSync(join(tmpdir(), 'hms-control-style-'));
  const write = (name, text) => {
    const path = join(root, name);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text);
  };
  try {
    write('src/styles.scss', '.global { color: black; }');
    fn(root, write);
  } finally {
    assert.equal(dirname(root), tmpdir());
    assert.ok(basename(root).startsWith('hms-control-style-'));
    rmSync(root, { recursive: true });
  }
}

test('metadata owns differently named HTML and external Sass resolves its own sibling imports', () =>
  fixture((root, write) => {
    const rel = 'src/app/admin/templates/view.html',
      text = '<input class="special">';
    const components = [
      {
        file: 'src/app/admin/owner.ts',
        text: "@Component({'templateUrl': './templates/view.html', 'styleUrl': './styles/view.scss'}) class Owner {}",
      },
    ];
    write('src/app/admin/styles/_tokens.scss', '$pad: 12px;');
    write('src/app/admin/styles/view.scss', "@use './tokens' as t; .special { padding: t.$pad; }");
    const templates = controlTemplates([{ rel, text }], components, root);
    const exceptions = {
      [controlsIn(text, rel)[0].key]: {
        reason: 'Search control has separately reviewed custom styling.',
      },
    };
    const styles = controlStyleSources(templates, { root, exceptions });
    assert.match(styles.get(rel), /padding: 12px/);
  }));

test('two inline components in one file keep distinct owners and styles', () =>
  fixture((root) => {
    const rel = 'src/app/admin/two.ts',
      text = '<input class="special">';
    const source =
      "@Component({template: '<input class=\"special\">', styles: ['.special {color: red}']}) class First {}\n@Component({template: '<input class=\"special\">', styles: ['.special {color: blue}']}) class Second {}";
    const templates = controlTemplates(
      [
        { rel, text, lineOffset: 0 },
        { rel, text, lineOffset: 1 },
      ],
      [{ file: rel, text: source }],
      root,
    );
    assert.deepEqual(
      templates.map((t) => t.componentIdentity),
      ['First', 'Second'],
    );
    const exceptions = Object.fromEntries(
      templates.map((t) => [
        controlsIn(text, rel, t.componentIdentity)[0].key,
        { reason: 'Custom control reviewed with its own component stylesheet.' },
      ]),
    );
    const styles = controlStyleSources(templates, { root, exceptions });
    assert.match(styles.get(`${rel}::First`), /color: red/);
    assert.doesNotMatch(styles.get(`${rel}::First`), /color: blue/);
    assert.match(styles.get(`${rel}::Second`), /color: blue/);
  }));

test('out-of-scope or non-excepted templates do not compile unrelated broken styles', () =>
  fixture((root) => {
    const rel = 'src/app/other/template.html',
      text = '<input>';
    const templates = controlTemplates(
      [{ rel, text }],
      [
        {
          file: 'src/app/other/owner.ts',
          text: "@Component({templateUrl: './template.html', styleUrl: './missing.scss'}) class Owner {}",
        },
      ],
      root,
    );
    const exceptions = {
      [controlsIn(text, rel)[0].key]: { reason: 'Custom control reviewed by its owning module.' },
    };
    assert.equal(controlStyleSources(templates, { root }).size, 0);
    assert.equal(
      controlStyleSources(templates, { root, exceptions, inScope: () => false }).size,
      0,
    );
  }));
