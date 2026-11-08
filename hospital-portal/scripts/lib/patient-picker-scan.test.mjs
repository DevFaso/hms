#!/usr/bin/env node
/**
 * The hand-written patient-picker scan (QA D947). The failing case is the
 * appointment form's markup as reported; the passing cases are the shared
 * picker and a worklist that merely lists patients.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { pickerFindings, usesSharedPicker } from './patient-picker-scan.mjs';

const APPOINTMENT_FORM = `
  <input id="patientSearch" type="text" [ngModel]="patientSearch()" (input)="onPatientSearch($event)" />
  @if (patientDropdownOpen()) {
    <ul class="picker-dropdown">
      @for (p of patientSuggestions(); track p.id) {
        <li class="picker-option" (mousedown)="selectPatient(p)">{{ p.firstName }}</li>
      }
    </ul>
  }`;

test('D947: a patient search input plus its own suggestion list is a hand-written picker', () => {
  const [finding] = pickerFindings(APPOINTMENT_FORM);
  assert.equal(finding.rule, 'hand-written-picker');
  assert.equal(finding.anchor, 'patientSuggestions()');
  assert.equal(finding.line, 5);
});

test('the *ngFor spelling and a (click) handler are the same picker', () => {
  const html = `<input [(ngModel)]="patientQuery" (keyup)="searchPatients()" />
    <li *ngFor="let p of patientResults" (click)="pickPatient(p)">{{ p.lastName }}</li>`;
  assert.equal(pickerFindings(html).length, 1);
  assert.equal(pickerFindings(html)[0].anchor, 'patientResults');
});

test('the shared picker passes, and so does a list of patients with no search input', () => {
  const shared = `<app-patient-picker [hospitalId]="h()" (selected)="onPatient($event)"></app-patient-picker>`;
  assert.deepEqual(pickerFindings(shared), []);
  assert.ok(usesSharedPicker(shared));
  const worklist = `@for (p of patientSuggestions(); track p.id) { <li (click)="selectPatient(p)">{{ p.name }}</li> }`;
  assert.deepEqual(pickerFindings(worklist), []);
});

test('a search input whose list does not select a patient is a filter, not a picker', () => {
  const html = `<input [(ngModel)]="patientSearch" />
    @for (p of filteredPatients(); track p.id) { <tr (click)="openChart(p.id)"><td>{{ p.name }}</td></tr> }`;
  assert.deepEqual(pickerFindings(html), []);
});
