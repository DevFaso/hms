import {
  ComponentFixture,
  TestBed,
  discardPeriodicTasks,
  fakeAsync,
  flush,
  tick,
} from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, Type } from '@angular/core';
import { provideRouter } from '@angular/router';
import { TranslateService, provideTranslateService } from '@ngx-translate/core';

import fr from '../../assets/i18n/fr.json';
import { RoleContextService } from '../core/role-context.service';
import {
  SUBMIT_SCOPE_SELECTOR,
  fieldControlFor,
  scopeOf,
  serverFieldErrorsInterceptor,
} from '../shared/forms/server-field-errors';
import { isBlank as isBlankControl } from '../shared/forms/submit-guard.directive';
import { roleContextStub } from './role-context.stub';
import { FORM_BEHAVIOUR_COMPONENTS } from './form-behaviour.registry';

/**
 * QA round 29 — the BEHAVIOURAL form guard (the D1245 / D1256 / D1272 /
 * D1301 / D1341 class, NOT_FIXED in rounds 27–29). The text guards read
 * templates, and QA kept finding the forms they could not see. This spec
 * mounts EVERY component the registry enumerates from the code
 * (scripts/lib/form-behaviour-registry.mjs: a template with a submit, or a
 * field and a button, in the covered directories) and drives what it
 * renders, then what each of its buttons opens (one level: dialogs, panels,
 * row forms):
 *
 * 1. Empty submit: every submit (typed submit, `appSubmitGuard`, or the
 *    primary button of its place) whose place holds a blank required control
 *    is pressed. No write may go out, every blank required control must be
 *    marked (`aria-invalid="true"` and an `aria-describedby` message with
 *    text), and every control left marked shows a star in its label (French).
 * 2. Server refusal: every control of a submitting place must be reachable by
 *    the server key it answers to (`data-server-field`, `formcontrolname`,
 *    `name`, id) through the shared mapper (shared/forms/server-field-errors.ts),
 *    the one every refused write goes through: a key two controls answer to,
 *    or a control no key reaches, fails.
 *
 * Reads are answered with one synthetic row (so a list renders a row and its
 * row actions can be pressed); a screen that cannot render a synthetic row is
 * driven again with empty lists. A component that mounts neither way is
 * listed in NOT_MOUNTED with why; a stale entry fails.
 */

/** Where the router lands when a screen navigates (a detail page's back link, a redirect). */
@Component({ selector: 'app-form-behaviour-elsewhere', template: '' })
class ElsewhereComponent {}

interface Reach {
  mode: string;
  scopes: number;
  presses: number;
  controls: number;
}

/** What the harness reached in each component (printed once, and held to a floor). */
const reached = new Map<string, Reach>();

/** Components this harness cannot mount on their own, each with why. */
const NOT_MOUNTED: Readonly<Record<string, string>> = {
  'appointments/appointment-form':
    'writes RoleContextService.activeHospitalId, a getter on the shared stub; its forms are driven by appointment-form.spec.ts',
  'patients/patient-demographics-edit/patient-demographics-edit.component':
    'a child form that needs its required `patient` input from the chart; driven by patient-demographics-edit.component.spec.ts',
};

/**
 * The fewest places (forms, dialogs, sections with a submit) the harness must
 * reach over all components: a change that stops it reaching them (a mount
 * that silently fails, a selector that drifts) fails here instead of passing
 * on nothing.
 */
const MIN_SCOPES_REACHED = 15;

const STAFF_ROLES = [
  'ROLE_RECEPTIONIST',
  'ROLE_BILLING_SPECIALIST',
  'ROLE_PHARMACIST',
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_HOSPITAL_ADMIN',
];

const REQUIRED_CONTROLS =
  'input[required], select[required], textarea[required], [aria-required="true"]';
/** A submit: typed so, guarded by appSubmitGuard, or the primary button of its place. */
const SUBMITS = [
  'button[type="submit"]',
  'input[type="submit"]',
  'button[data-submit-blocked]',
  'button[appsubmitguard]',
  'button.btn-primary',
].join(', ');
/** Matched only as a primary button (not typed submit, not guarded). */
const PRIMARY_ONLY =
  'button.btn-primary:not([type="submit"]):not([data-submit-blocked]):not([appsubmitguard])';
const FIELDS =
  'input:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]):not([type="file"]), select, textarea';
/** Buttons that leave the page, sign out or destroy data are never pressed by the explorer. */
const NOT_PRESSED = /logout|déconnex|supprim|delete|imprim|print|télécharg|download|export/i;

const LIST_PROPS =
  /(?:items|lines|list|entries|rows|children|results|content|payments|lots|options|tags|roles|permissions|values|details)$/i;

/**
 * One synthetic record: every property a template reads answers by its name
 * (an id, a date, an amount, a flag, a list, else a word).
 */
function syntheticRow(depth = 0): Record<string, unknown> {
  return new Proxy({} as Record<string, unknown>, {
    get(_t, prop) {
      if (typeof prop !== 'string' || prop === 'then' || prop === 'toJSON') return undefined;
      return valueFor(prop, depth);
    },
  });
}

function valueFor(prop: string, depth: number): unknown {
  if (
    /^(?:is|has|can|should|allow)[A-Z]|(?:active|enabled|required|controlled|deleted|locked)$/i.test(
      prop,
    )
  ) {
    return false;
  }
  if (/(?:^id|Id|Uuid)$/.test(prop)) return `${prop}-1`;
  if (/(?:date|Date|dob|Dob)$/.test(prop)) return '2026-11-02';
  if (/(?:At|Time|time)$/.test(prop)) return '2026-11-02T10:00:00';
  if (
    /(?:amount|total|balance|price|quantity|count|qty|size|cost|fee|due|paid|age|stock|dose)$/i.test(
      prop,
    )
  ) {
    return 1000;
  }
  if (/status$/i.test(prop)) return 'PENDING';
  if (LIST_PROPS.test(prop)) return depth < 1 ? [syntheticRow(depth + 1)] : [];
  if (
    depth < 1 &&
    /(?:patient|staff|hospital|invoice|prescription|medication|appointment|order|user)$/i.test(prop)
  ) {
    return syntheticRow(depth + 1);
  }
  return 'Test';
}

/** The answer to every read: a list that is also a page. */
function answer(rows: boolean): unknown[] {
  const content = rows ? [syntheticRow()] : [];
  return Object.assign([...content], {
    content,
    items: content,
    data: content,
    results: content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 20,
    page: 0,
  });
}

/** Answers the pending reads (a few rounds: an answer can start the next read); drops the writes. */
function settle(
  ctrl: HttpTestingController,
  fixture: ComponentFixture<unknown>,
  rows: boolean,
): void {
  for (let round = 0; round < 4; round++) {
    const pending = ctrl.match(() => true);
    if (pending.length === 0) break;
    for (const req of pending) {
      if (req.cancelled || req.request.method !== 'GET') continue;
      if (req.request.responseType === 'json') req.flush(answer(rows));
      else req.flush(new Blob([]));
    }
    fixture.detectChanges();
    tick(10);
    fixture.detectChanges();
  }
}

function usable(el: HTMLElement): boolean {
  return (
    !(el as HTMLInputElement).disabled &&
    el.closest('[hidden]') === null &&
    el.getAttribute('type') !== 'hidden'
  );
}

function isBlank(el: HTMLElement): boolean {
  // An Angular forms control whose model satisfies `required` holds an
  // answer the DOM cannot show (a synthetic value outside the select's
  // options, a timestamp in a date input): not blank.
  if (el.classList.contains('ng-valid') && el.hasAttribute('required')) return false;
  return isBlankControl(el);
}

/** The visible label text of a control (label[for], wrapping label, aria-labelledby, legend, aria-label). */
function labelText(el: HTMLElement): string | null {
  const doc = el.ownerDocument;
  const parts: string[] = [];
  if (el.id) {
    doc
      .querySelectorAll(`label[for="${CSS.escape(el.id)}"]`)
      .forEach((l) => parts.push(l.textContent ?? ''));
  }
  const wrapping = el.closest('label');
  if (wrapping) parts.push(wrapping.textContent ?? '');
  for (const id of (el.getAttribute('aria-labelledby') ?? '').split(/\s+/).filter(Boolean)) {
    parts.push(doc.getElementById(id)?.textContent ?? '');
  }
  if (parts.length === 0) {
    const legend = el.closest('fieldset')?.querySelector('legend');
    if (legend) parts.push(legend.textContent ?? '');
  }
  const aria = el.getAttribute('aria-label');
  if (parts.length === 0 && aria) parts.push(aria);
  return parts.length === 0 ? null : parts.join(' ');
}

function nameOf(el: Element): string {
  const id = el.id ? `#${el.id}` : '';
  const name = el.getAttribute('name') ? `[name=${el.getAttribute('name')}]` : '';
  return `${el.tagName.toLowerCase()}${id}${name}`;
}

/** The server key a control answers to, the way a DTO would name it. */
function serverKeyOf(el: HTMLElement): string | null {
  const declared = (el.getAttribute('data-server-field') ?? '').split(/\s+/).find(Boolean);
  return (
    declared ?? el.getAttribute('formcontrolname') ?? el.getAttribute('name') ?? (el.id || null)
  );
}

function markedWithMessage(el: HTMLElement): boolean {
  if (el.getAttribute('aria-invalid') !== 'true') return false;
  const ids = (el.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
  return ids.some((id) => (el.ownerDocument.getElementById(id)?.textContent ?? '').trim() !== '');
}

/** Mounts `type` and drives it; returns the findings, or throws when it cannot be mounted. */
function driveComponent(file: string, type: Type<unknown>, rows: boolean, reach: Reach): string[] {
  const findings: string[] = [];
  const patient = file.startsWith('patient-portal/');
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [type],
    providers: [
      provideHttpClient(withInterceptors([serverFieldErrorsInterceptor])),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', component: ElsewhereComponent }]),
      provideTranslateService({ defaultLanguage: 'fr' }),
      {
        provide: RoleContextService,
        useValue: roleContextStub({
          superAdmin: false,
          hospitalId: 'hosp-1',
          roles: patient ? ['ROLE_PATIENT'] : STAFF_ROLES,
        }),
      },
    ],
  });
  const translate = TestBed.inject(TranslateService);
  translate.setTranslation('fr', fr);
  translate.use('fr');
  const ctrl = TestBed.inject(HttpTestingController);
  const fixture = TestBed.createComponent(type);
  document.body.appendChild(fixture.nativeElement);
  try {
    fixture.detectChanges();
    tick(50);
    settle(ctrl, fixture, rows);
    const page = document.body;

    const drive = (where: string) => {
      const submits = Array.from(page.querySelectorAll<HTMLElement>(SUBMITS))
        .filter(usable)
        // A primary button outside any form or dialog is an opener ("Enregistrer
        // une directive" opening its form), not that section's submit.
        .filter((b) => !b.matches(PRIMARY_ONLY) || b.closest(SUBMIT_SCOPE_SELECTOR) !== null);
      for (const submit of submits) {
        const scope = scopeOf(submit);
        if (!scope) continue;
        const blank = Array.from(scope.querySelectorAll<HTMLElement>(REQUIRED_CONTROLS))
          .filter(usable)
          .filter(isBlank);
        if (blank.length === 0) continue;
        settle(ctrl, fixture, rows);
        reach.presses++;
        submit.click();
        fixture.detectChanges();
        tick(10);
        fixture.detectChanges();
        const at = `${where} ${nameOf(submit)} "${(submit.textContent ?? '').trim().slice(0, 30)}"`;
        const writes = ctrl.match((r) => r.method !== 'GET');
        if (writes.length > 0) {
          const sent = writes.map((w) => `${w.request.method} ${w.request.url}`).join(', ');
          findings.push(`${at}: sent ${sent} with ${blank.map(nameOf).join(', ')} blank`);
        }
        for (const el of blank.filter((b) => b.isConnected && !markedWithMessage(b))) {
          findings.push(`${at}: ${nameOf(el)} blank and not marked`);
        }
        for (const el of Array.from(scope.querySelectorAll<HTMLElement>('[aria-invalid="true"]'))) {
          const label = labelText(el);
          if (label !== null && !label.includes('*')) {
            findings.push(`${at}: ${nameOf(el)} refused but "${label.trim()}" has no star`);
          }
        }
      }
      const scopes = new Set(
        submits.map((s) => scopeOf(s)).filter((s): s is HTMLElement => s !== null),
      );
      for (const scope of scopes) {
        reach.scopes++;
        const controls = Array.from(scope.querySelectorAll<HTMLElement>(FIELDS))
          .filter(usable)
          .filter((el) => scopeOf(el) === scope);
        for (const el of controls) {
          reach.controls++;
          const key = serverKeyOf(el);
          if (!key) {
            findings.push(
              `${where}: ${nameOf(el)} has no id, name or data-server-field, so no server fieldError can reach it`,
            );
            continue;
          }
          const found = fieldControlFor(scope, key);
          if (found !== el) {
            findings.push(
              `${where}: server key "${key}" reaches ${found ? nameOf(found) : 'nothing'}, not ${nameOf(el)}`,
            );
          }
        }
      }
    };

    drive('initial');
    const openers = Array.from(page.querySelectorAll<HTMLButtonElement>('button'))
      .filter(usable)
      .filter(
        (b) =>
          b.getAttribute('type') !== 'submit' &&
          !b.matches(SUBMITS) &&
          !NOT_PRESSED.test(b.textContent ?? ''),
      );
    for (const opener of openers) {
      if (!opener.isConnected) continue;
      const before = page.querySelectorAll(FIELDS).length;
      opener.click();
      fixture.detectChanges();
      tick(10);
      settle(ctrl, fixture, rows);
      if (page.querySelectorAll(FIELDS).length > before) {
        drive(`after "${(opener.textContent ?? '').trim().slice(0, 40)}"`);
      }
    }
  } finally {
    ctrl.match(() => true);
    discardPeriodicTasks();
    flush();
    fixture.destroy();
    (fixture.nativeElement as HTMLElement).remove();
  }
  return [...new Set(findings)];
}

describe('Form behaviour guard (QA round 29: empty submit, server fieldErrors)', () => {
  it('NOT_MOUNTED names only registered components', () => {
    const files = new Set(FORM_BEHAVIOUR_COMPONENTS.map((c) => c.file));
    expect(Object.keys(NOT_MOUNTED).filter((f) => !files.has(f))).toEqual([]);
  });

  for (const entry of FORM_BEHAVIOUR_COMPONENTS) {
    it(`${entry.file}: refused controls marked and starred, server fields reachable`, fakeAsync(() => {
      let findings: string[] | null = null;
      const errors: string[] = [];
      for (const rows of [true, false]) {
        const reach: Reach = { mode: rows ? 'rows' : 'empty', scopes: 0, presses: 0, controls: 0 };
        try {
          findings = driveComponent(entry.file, entry.type, rows, reach);
          reached.set(entry.file, reach);
          break;
        } catch (e) {
          errors.push(`${reach.mode}: ${(e as Error).message.split('\n')[0]}`);
        }
      }
      if (findings === null) {
        expect(NOT_MOUNTED[entry.file])
          .withContext(
            `${entry.file} does not mount (${errors.join(' / ')}); list it in NOT_MOUNTED with why`,
          )
          .toBeTruthy();
        return;
      }
      expect(NOT_MOUNTED[entry.file])
        .withContext(`${entry.file} mounts now: remove it from NOT_MOUNTED`)
        .toBeUndefined();
      expect(findings).withContext(entry.file).toEqual([]);
    }));
  }

  // After every component ran (the specs run in random order).
  afterAll(() => {
    const total = [...reached.values()].reduce((n, r) => n + r.scopes, 0);
    console.log(
      'FORM-BEHAVIOUR reached | ' +
        [...reached]
          .map(
            ([f, r]) =>
              `${f} (${r.mode}): scopes ${r.scopes}, presses ${r.presses}, controls ${r.controls}`,
          )
          .join(' | '),
    );
    expect(total)
      .withContext(`the harness reached ${total} forms, dialogs and sections`)
      .toBeGreaterThanOrEqual(MIN_SCOPES_REACHED);
  });
});
