// QA D1451 guard: a template event expression that can answer false cancels the event.
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { booleanMethods, canBeFalse, eventCancelFindings } from './event-cancel-scan.mjs';

const dialog = (binding) =>
  `<div class="modal-backdrop" ${binding}><div class="modal"><input type="checkbox" /></div></div>`;

test('the D1451 idiom is refused on an ancestor of a field, and names the expression', () => {
  const [hit, ...rest] = eventCancelFindings(
    dialog('(click)="$event.target === $event.currentTarget && close()"'),
  );
  assert.equal(rest.length, 0);
  assert.equal(hit.rule, 'event-cancelled-by-false');
  assert.match(hit.anchor, /currentTarget && close/);
});

test('every expression that can answer false', () => {
  for (const expression of [
    'a && b()',
    'x === y && b()',
    '!busy',
    'open = !open',
    'open = false',
    'false',
    'a > 1',
    'a !== b',
    'cond ? false : go()',
    'cond ? go() : (a && b())',
    'first(); a && b()',
    'other || (a && b())',
    '(a && b())',
  ]) {
    assert.equal(canBeFalse(expression), true, expression);
  }
});

test('expressions that cannot answer false', () => {
  for (const expression of [
    'close()',
    'open = true',
    'selected.set(false)',
    'cond === 1 ? a() : b()',
    'busy || cancel()',
    'a ?? b()',
    "go('a && b')",
    'go(a && b)',
    'go(a === b)',
    'x?.close()',
    'a && b(); close()',
    '$event.stopPropagation()',
    '',
  ]) {
    assert.equal(canBeFalse(expression), false, expression);
  }
});

test('a method of the component that returns a boolean answers false too', () => {
  const source = `export class X {
  guarded(a: string): boolean {
    return a !== '';
  }
  unsaid() {
    if (this.a) return false;
    this.b = 1;
  }
  async later(): Promise<boolean> {
    return false;
  }
  fine(): void {
    this.c.filter(() => true);
  }
}`;
  const methods = booleanMethods(source);
  assert.equal(canBeFalse('guarded("x")', methods), true);
  assert.equal(canBeFalse('this.guarded()', methods), true);
  assert.equal(canBeFalse('unsaid()', methods), true);
  assert.equal(canBeFalse('fine()', methods), false);
  assert.equal(canBeFalse('other.guarded()', methods), false);
});

test('only the events a false cancels, on what holds or is a control', () => {
  const on = (html) => eventCancelFindings(html).length;
  assert.equal(on('<input (keydown)="a && b()" />'), 1);
  assert.equal(on('<a href="x" (click)="a && b()">x</a>'), 1);
  assert.equal(on('<label (click)="a && b()"><input /></label>'), 1);
  assert.equal(on('<div (keydown.enter)="a && b()"><input /></div>'), 1);
  assert.equal(on('<div (click)="a && b()"><app-thing></app-thing></div>'), 1);
  assert.equal(on('<app-thing (click)="a && b()"></app-thing>'), 1);
  assert.equal(on('<div (click)="a && b()"><button type="button">x</button></div>'), 1);
  // A button of its own holds nothing a cancelled click could lose.
  assert.equal(on('<button type="button" (click)="open = !open">x</button>'), 0);
  assert.equal(on('<div (click)="a && b()"><span>x</span></div>'), 0);
  // An output, not a DOM event: Angular cancels nothing.
  assert.equal(on('<div (escaped)="!busy && close()"><input /></div>'), 0);
  assert.equal(on('<div appBackdropClose (backdropClose)="close()"><input /></div>'), 0);
});
