#!/usr/bin/env node
/**
 * The accessible-name scan (QA D988, D999, D1006, D993). Each case is a shape
 * from the round-16 report, plus the named shapes that must pass.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { nameFindings, visibleText } from './icon-button-scan.mjs';

const rules = (html) => nameFindings(html).map((f) => `${f.rule}:${f.anchor}`);

test('D999: an icon-only <button> with no aria-label is announced by the ligature', () => {
  const html = `<button class="icon-btn" (click)="edit(a)" title="Modifier">
      <span class="material-symbols-outlined">edit</span>
    </button>
    <button (click)="remove(a)"><span class="material-symbols-outlined">delete</span></button>`;
  // The anchor carries the action, so two "delete" buttons on one page are two sites.
  assert.deepEqual(rules(html), [
    'icon-button:button edit (click)=edit(a)',
    'icon-button:button delete (click)=remove(a)',
  ]);
});

test('aria-label, [attr.aria-label] or aria-labelledby names the button', () => {
  const html = `<button aria-label="Modifier"><span class="material-symbols-outlined">edit</span></button>
    <button [attr.aria-label]="'X.EDIT' | translate"><span class="material-symbols-outlined">edit</span></button>
    <button aria-labelledby="lbl"><span class="material-symbols-outlined">edit</span></button>`;
  assert.deepEqual(rules(html), []);
});

test('a button with visible text, a translated label or an <img alt> is named by its content', () => {
  const html = `<button><span class="material-symbols-outlined">save</span> {{ 'X.SAVE' | translate }}</button>
    <button><span class="material-symbols-outlined">add</span><span class="sr-only">Ajouter</span></button>
    <button><img src="x.png" alt="Supprimer" /></button>
    <button>@if (busy()) { <span class="material-symbols-outlined">sync</span> } @else { Envoyer }</button>`;
  assert.deepEqual(rules(html), []);
});

test('an <a (click)>, <a routerLink>, <a href> or role="button" element is a control too; <img alt=""> is an icon', () => {
  const html = `<a (click)="open()"><span class="material-symbols-outlined">open_in_new</span></a>
    <div role="button" (click)="x()"><img src="i.svg" alt="" /></div>
    <a routerLink="/billing"><span class="material-symbols-outlined">payments</span></a>
    <a [routerLink]="['/lab']"><span class="material-symbols-outlined">science</span></a>
    <a href="/help" target="_blank"><span class="material-symbols-outlined">help</span></a>
    <a routerLink="/x" [attr.aria-label]="'X' | translate"><span class="material-symbols-outlined">x</span></a>
    <a class="plain">text link</a>`;
  assert.deepEqual(rules(html), [
    'icon-button:a open_in_new (click)=open()',
    'icon-button:div icon (click)=x()',
    'icon-button:a payments routerLink=/billing',
    "icon-button:a science [routerLink]=['/lab']",
    'icon-button:a help href=/help',
  ]);
});

test('D993: an <input>/<textarea>/<select> with no label, aria-label or <label for> fails', () => {
  const html = `<textarea [(ngModel)]="reason" placeholder="Motif"></textarea>
    <input type="text" formControlName="scan" placeholder="Scanner" />
    <select [ngModel]="role"><option>a</option></select>`;
  assert.deepEqual(rules(html), [
    'field-label:textarea text',
    'field-label:input scan',
    'field-label:select role',
  ]);
});

test('a <label for>, an enclosing <label>, aria-label or a hidden/submit input pass', () => {
  const html = `<label for="q">Recherche</label><input id="q" type="search" />
    <label>Nom <input type="text" name="n" /></label>
    <select aria-label="Rôle"></select>
    <input type="hidden" name="csrf" /><input type="submit" value="Go" />`;
  assert.deepEqual(rules(html), []);
});

test('visibleText strips icons, comments and control flow but keeps text', () => {
  assert.equal(visibleText(`<!-- c --><span class="material-symbols-outlined">x</span>`), '');
  assert.equal(visibleText(`@if (a) { <mat-icon>x</mat-icon> } @else { Oui }`), 'Oui');
  assert.equal(visibleText(`<svg><path d="M0"/></svg><i class="fa fa-x"></i>&nbsp;`), '');
});
