#!/usr/bin/env node
/**
 * The tag tokenizer the round-16 accessibility gates share. Each case is a
 * shape a line-oriented grep got wrong on this tree: attributes spread over
 * lines by prettier, a `>` inside a bound value, a void element with no close.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { tags, innerHtml, hasAttr, attrValue, classTokens } from './html-tags.mjs';

test('reads attributes spread over several lines as one tag', () => {
  const html = `<div
      class="modal"
      role="dialog"
      aria-modal="true"
      [attr.aria-label]="'X' | translate"
    >`;
  const [tag] = tags(html);
  assert.equal(tag.name, 'div');
  assert.equal(tag.attrs.get('role'), 'dialog');
  assert.ok(hasAttr(tag, 'aria-modal'));
  assert.ok(hasAttr(tag, 'aria-label'));
  assert.equal(attrValue(tag, 'aria-label'), "'X' | translate");
});

test('a > inside a quoted binding does not end the tag', () => {
  const html = `<button (click)="count > 0 && open()" class="x">Go</button>`;
  const [open, close] = tags(html);
  assert.equal(open.attrs.get('(click)'), 'count > 0 && open()');
  assert.equal(close.type, 'close');
  assert.equal(innerHtml(html, tags(html), 0), 'Go');
});

test('void and self-closing elements never wait for a close tag', () => {
  const html = `<input type="text" /><input type="search"><br><span>x</span>`;
  const all = tags(html);
  assert.deepEqual(
    all.map((t) => [t.name, t.type, t.selfClosing]),
    [
      ['input', 'open', true],
      ['input', 'open', true],
      ['br', 'open', true],
      ['span', 'open', false],
      ['span', 'close', false],
    ],
  );
  assert.equal(innerHtml(html, all, 0), null);
});

test('innerHtml matches the close tag at the same depth', () => {
  const html = `<div class="a"><div class="b">inner</div> tail</div>`;
  const all = tags(html);
  assert.equal(innerHtml(html, all, 0), '<div class="b">inner</div> tail');
  assert.equal(innerHtml(html, all, 1), 'inner');
});

test('class tokens include [class.x] bindings', () => {
  const [tag] = tags(`<div class="modal modal-lg" [class.open]="isOpen()">`);
  assert.deepEqual([...classTokens(tag)], ['modal', 'modal-lg', 'open']);
});

test('comments are skipped, and an attribute without a value is kept', () => {
  const html = `<!-- <div role="dialog"> --><input disabled aria-label="Nom">`;
  const all = tags(html);
  assert.equal(all.length, 1);
  assert.equal(all[0].attrs.get('disabled'), null);
  assert.equal(all[0].attrs.get('aria-label'), 'Nom');
});

test('a < inside an interpolation is an operator, and the tag after it is still seen', () => {
  const html = `<span>{{ count<10 ? 'few' : 'many' }}</span>
<button (click)="del()"><span class="material-symbols-outlined">delete</span></button>`;
  const all = tags(html);
  assert.deepEqual(
    all.map((t) => `${t.type}:${t.name}:${t.line}`),
    [
      'open:span:1',
      'close:span:1',
      'open:button:2',
      'open:span:2',
      'close:span:2',
      'close:button:2',
    ],
  );
});
