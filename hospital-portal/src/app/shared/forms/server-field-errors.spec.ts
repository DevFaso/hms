import { TestBed, fakeAsync, flush } from '@angular/core/testing';
import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import {
  fieldControlFor,
  markServerFieldErrors,
  serverFieldErrors,
  serverFieldErrorsInterceptor,
} from './server-field-errors';

function refusal(fieldErrors: Record<string, unknown>, status = 400): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: { message: 'refused', fieldErrors } });
}

function host(html: string): HTMLElement {
  const div = document.createElement('div');
  div.innerHTML = html;
  document.body.appendChild(div);
  return div;
}

describe('server-field-errors (QA D1341 / D1320 / D1321)', () => {
  let root: HTMLElement | null = null;
  afterEach(() => {
    root?.remove();
    root = null;
  });

  it('reads only the non-blank fieldErrors of a 4xx', () => {
    expect([...serverFieldErrors(refusal({ amount: 'trop', reference: ' ', x: 3 }))]).toEqual([
      ['amount', 'trop'],
    ]);
    expect(serverFieldErrors(refusal({ amount: 'x' }, 500)).size).toBe(0);
    expect(serverFieldErrors(new Error('x')).size).toBe(0);
  });

  it('finds a control by data-server-field, then id / name / formcontrolname, then a unique id ending', () => {
    root = host(`
      <form>
        <input id="payRef" data-server-field="reference referenceNumber transactionReference">
        <input id="payment-amount">
        <input name="notes">
        <select formcontrolname="method"></select>
        <input id="a-code"><input id="b-code">
      </form>`);
    const form = root.querySelector('form')!;
    expect(fieldControlFor(form, 'transactionReference')?.id).toBe('payRef');
    expect(fieldControlFor(form, 'referenceNumber')?.id).toBe('payRef');
    expect(fieldControlFor(form, 'paymentAmount')?.id).toBe('payment-amount');
    expect(fieldControlFor(form, 'amount')?.id).toBe('payment-amount');
    expect(fieldControlFor(form, 'notes')?.getAttribute('name')).toBe('notes');
    expect(fieldControlFor(form, 'method')?.tagName).toBe('SELECT');
    expect(fieldControlFor(form, 'lines[0].amount')?.id).toBe('payment-amount');
    // Two controls end with "code": neither is guessed.
    expect(fieldControlFor(form, 'code')).toBeNull();
    expect(fieldControlFor(form, 'nothingHere')).toBeNull();
  });

  it('skips a disabled or hidden control', () => {
    root = host(`<form><input id="amount" disabled><div hidden><input id="x-reference"></div></form>`);
    const form = root.querySelector('form')!;
    expect(fieldControlFor(form, 'amount')).toBeNull();
    expect(fieldControlFor(form, 'reference')).toBeNull();
  });

  it('marks every named field with the server reason, focuses the first, and clears a mark on edit', () => {
    root = host(`<form><input id="amt"><input id="payment-reference"></form>`);
    const form = root.querySelector('form')!;
    const ids = markServerFieldErrors(
      form,
      refusal({ reference: 'Référence déjà utilisée', amount: 'Montant trop élevé' }),
      { amount: 'amt' },
    );
    expect(ids).toEqual(['amt', 'payment-reference']);
    const ref = form.querySelector<HTMLInputElement>('#payment-reference')!;
    const amt = form.querySelector<HTMLInputElement>('#amt')!;
    expect(ref.getAttribute('aria-invalid')).toBe('true');
    expect(amt.getAttribute('aria-invalid')).toBe('true');
    expect(form.querySelector('#payment-reference-invalid-msg')?.textContent).toBe(
      'Référence déjà utilisée',
    );
    expect(ref.getAttribute('aria-describedby')).toContain('payment-reference-invalid-msg');
    expect(document.activeElement).toBe(amt);
    ref.value = 'OM-2';
    ref.dispatchEvent(new Event('input'));
    expect(ref.hasAttribute('aria-invalid')).toBeFalse();
    expect(form.querySelector('#payment-reference-invalid-msg')).toBeNull();
    expect(amt.getAttribute('aria-invalid')).toBe('true');
  });

  it("leaves a field the page's handler already marked with its own words", () => {
    root = host(`<form><input id="amount" aria-invalid="true"></form>`);
    expect(markServerFieldErrors(root.querySelector('form'), refusal({ amount: 'x' }))).toEqual([]);
  });

  describe('interceptor', () => {
    let http: HttpClient;
    let ctrl: HttpTestingController;
    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([serverFieldErrorsInterceptor])),
          provideHttpClientTesting(),
        ],
      });
      http = TestBed.inject(HttpClient);
      ctrl = TestBed.inject(HttpTestingController);
    });

    it('marks the fields of the dialog the write was sent from, after the page handler', fakeAsync(() => {
      root = host(`
        <div role="dialog"><input id="payment-reference"><button type="button" id="save">OK</button></div>
        <form><input id="reference"></form>`);
      const save = root.querySelector<HTMLButtonElement>('#save')!;
      // Prime the tracker, then act in the dialog.
      http.post('/warmup', {}).subscribe({ error: () => undefined });
      ctrl.expectOne('/warmup').flush({});
      save.focus();
      save.click();
      let handled = false;
      http.post('/payments', {}).subscribe({ error: () => (handled = true) });
      ctrl
        .expectOne('/payments')
        .flush(
          { fieldErrors: { reference: 'Déjà utilisée' } },
          { status: 409, statusText: 'Conflict' },
        );
      expect(handled).toBeTrue();
      flush();
      const inDialog = root.querySelector('#payment-reference')!;
      expect(inDialog.getAttribute('aria-invalid')).toBe('true');
      // The other form's "reference" is not the place the reader acted in.
      expect(root.querySelector('#reference')!.hasAttribute('aria-invalid')).toBeFalse();
      // The next write from the same place clears the mark first.
      save.click();
      http.post('/payments', {}).subscribe();
      expect(inDialog.hasAttribute('aria-invalid')).toBeFalse();
      ctrl.expectOne('/payments').flush({});
    }));

    it('does nothing for a read, a 5xx or a refusal without fieldErrors', fakeAsync(() => {
      root = host(`<form><input id="amount"><button type="button" id="go">Go</button></form>`);
      root.querySelector<HTMLButtonElement>('#go')!.click();
      http.get('/x').subscribe({ error: () => undefined });
      ctrl.expectOne('/x').flush({ fieldErrors: { amount: 'x' } }, { status: 400, statusText: 'B' });
      http.post('/y', {}).subscribe({ error: () => undefined });
      ctrl.expectOne('/y').flush({ fieldErrors: { amount: 'x' } }, { status: 500, statusText: 'S' });
      http.post('/z', {}).subscribe({ error: () => undefined });
      ctrl.expectOne('/z').flush({ message: 'no' }, { status: 400, statusText: 'B' });
      flush();
      expect(root.querySelector('#amount')!.hasAttribute('aria-invalid')).toBeFalse();
    }));
  });
});
