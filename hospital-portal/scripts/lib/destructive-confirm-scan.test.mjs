// QA CX-010 guard: the scan behind `guard:destructive-confirm` follows a click
// to a destructive call, and a question anywhere on the path clears it.
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  classMethods,
  destructiveConfirmFindings,
  destructiveServiceMethods,
  eventBindings,
} from './destructive-confirm-scan.mjs';

const SERVICE = `
import { HttpClient } from '@angular/common/http';
@Injectable({ providedIn: 'root' })
export class PlanService {
  private readonly http = inject(HttpClient);
  list(): Observable<Plan[]> {
    return this.http.get<Plan[]>('/plans');
  }
  drop(id: string): Observable<void> {
    return this.http.delete<void>('/plans/' + id);
  }
  deactivate(id: string): Observable<void> {
    return this.http.post<void>('/plans/' + id + '/deactivate', {});
  }
  deactivatePreview(id: string): Observable<Plan> {
    return this.http.get<Plan>('/plans/' + id + '/deactivate-preview');
  }
}`;
const services = destructiveServiceMethods([{ file: 'x', text: SERVICE }]);
const component = (methods) => `
export class PlanComponent {
  private readonly svc = inject(PlanService);
  private readonly http = inject(HttpClient);
  private readonly confirm = inject(ConfirmService);
${methods}
}`;
const anchors = (html, ts) =>
  destructiveConfirmFindings(html, component(ts), services).map((f) => f.anchor);

test('the destructive methods: a DELETE whatever its name, a write named as ending something', () => {
  assert.deepEqual([...services.get('PlanService')].sort(), ['deactivate', 'drop']);
});

test('QA CX-010: one click to the service is reported, whatever the method is called', () => {
  const ts = `
  go(p: Plan): void {
    this.svc.deactivate(p.id).subscribe();
  }`;
  assert.deepEqual(anchors(`<button (click)="go(p)">x</button>`, ts), [
    'go->PlanService.deactivate',
  ]);
});

test('a direct http.delete in the component is reported', () => {
  const ts = `
  go(): void {
    this.http.delete('/x').subscribe();
  }`;
  assert.deepEqual(anchors(`<button (click)="go()">x</button>`, ts), ['go->http.delete']);
});

test('a helper in between does not hide the call', () => {
  const ts = `
  go(): void {
    this.send();
  }
  private send(): void {
    this.svc.drop('1').subscribe();
  }`;
  assert.deepEqual(anchors(`<button (click)="go()">x</button>`, ts), ['go->PlanService.drop']);
});

test('a question on the path clears it: asked in the handler or in the helper', () => {
  const inHandler = `
  async go(): Promise<void> {
    if (!(await this.confirm.ask({ titleKey: 'X' }))) return;
    this.send();
  }
  private send(): void {
    this.svc.drop('1').subscribe();
  }`;
  assert.deepEqual(anchors(`<button (click)="go()">x</button>`, inHandler), []);
  const inHelper = `
  go(): void {
    this.send();
  }
  private async send(): Promise<void> {
    if (!(await this.confirm.ask({ titleKey: 'X' }))) return;
    this.svc.drop('1').subscribe();
  }`;
  assert.deepEqual(anchors(`<button (click)="go()">x</button>`, inHelper), []);
});

test('the answer of a dialog, and a button inside a dialog the reader opened, are confirmed', () => {
  const ts = `
  send(): void {
    this.svc.drop('1').subscribe();
  }`;
  for (const html of [
    `<app-confirm-dialog titleKey="X" (confirmed)="send()" />`,
    `<app-reason-dialog (confirmed)="send()"></app-reason-dialog>`,
    `<div role="dialog"><button (click)="send()">Yes</button></div>`,
    `<div class="modal"><footer><button (click)="send()">Yes</button></footer></div>`,
    `<div appModalDialog><button (click)="send()">Yes</button></div>`,
  ]) {
    assert.deepEqual(anchors(html, ts), [], html);
  }
});

test('a button after a closed dialog is not inside it', () => {
  const ts = `
  send(): void {
    this.svc.drop('1').subscribe();
  }`;
  assert.deepEqual(anchors(`<div role="dialog"></div><button (click)="send()">x</button>`, ts), [
    'send->PlanService.drop',
  ]);
});

test('a read, or a method no template calls, is not reported', () => {
  const ts = `
  look(): void {
    this.svc.deactivatePreview('1').subscribe();
  }
  never(): void {
    this.svc.drop('1').subscribe();
  }`;
  assert.deepEqual(anchors(`<button (click)="look()">x</button>`, ts), []);
});

test('a comment or a string naming a call is not a call', () => {
  const ts = `
  go(): void {
    // this.svc.drop('1')
    const s = 'this.svc.drop(1)';
  }`;
  assert.deepEqual(anchors(`<button (click)="go()">x</button>`, ts), []);
});

test('the method table reads a class, a field arrow, and skips a call statement', () => {
  const methods = classMethods(`export class A {
  one(a: string): void {
    this.two();
  }
  two = (): void => {
    if (x) {
      y();
    }
  };
  readonly three = signal(0);
}`);
  assert.deepEqual([...methods.keys()], ['one', 'two']);
});

test('an event binding says whether a dialog encloses it', () => {
  const [open, inside] = eventBindings(
    `<button (click)="a()">x</button><section role="dialog"><button (click)="b()">y</button></section>`,
  );
  assert.equal(open.enclosed, false);
  assert.equal(inside.enclosed, true);
});

const UPDATE_SERVICE = `
import { HttpClient } from '@angular/common/http';
@Injectable({ providedIn: 'root' })
export class RegService {
  private readonly http = inject(HttpClient);
  update(id: string, body: unknown): Observable<void> {
    return this.http.put<void>('/regs/' + id, body);
  }
  read(id: string): Observable<void> {
    return this.http.get<void>('/regs/' + id);
  }
}`;

test('QA D1430: a plain update that ends the record is reported: by the flag it sends, or by the name of the handler', () => {
  const found = (ts) => {
    const updates = destructiveServiceMethods([{ file: 'x', text: UPDATE_SERVICE }]);
    const source = `
export class RegComponent {
  private readonly svc = inject(RegService);
${ts}
}`;
    return destructiveConfirmFindings(
      `<button (click)="go(r)">x</button><button (click)="deactivate(r)">y</button>`,
      source,
      updates,
    ).map((f) => f.anchor);
  };
  assert.deepEqual(
    found(`
  go(r: Reg): void {
    this.svc.update(r.id, { active: false }).subscribe();
  }`),
    ['go->RegService.update'],
  );
  assert.deepEqual(
    found(`
  deactivate(r: Reg): void {
    this.svc.update(r.id, {}).subscribe();
  }`),
    ['deactivate->RegService.update'],
  );
  // An ordinary save is no such thing, nor is a read under an ending name.
  assert.deepEqual(
    found(`
  go(r: Reg): void {
    this.svc.update(r.id, { active: true }).subscribe();
  }
  deactivate(r: Reg): void {
    this.svc.read(r.id).subscribe();
  }`),
    [],
  );
});
