import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { WitnessConfirmComponent } from './witness-confirm.component';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';
import {
  PharmacyService,
  StockWitnessCandidate,
  WitnessConfirmation,
} from '../../services/pharmacy.service';

/**
 * QA D2679: the witness of a controlled-drug movement confirms with their own password or authenticator code.
 * A name picked in a list confirmed nothing.
 */
describe('WitnessConfirmComponent (QA D2679)', () => {
  let fixture: ComponentFixture<WitnessConfirmComponent>;
  let svc: jasmine.SpyObj<PharmacyService>;

  const CANDIDATES: StockWitnessCandidate[] = [
    { userId: 'w-pass', displayName: 'Awa Sanou', confirmationMethod: 'PASSWORD' },
    { userId: 'w-totp', displayName: 'Issa Barry', confirmationMethod: 'TOTP' },
    { userId: 'w-none', displayName: 'Ali Sow', confirmationMethod: 'UNAVAILABLE' },
    { userId: 'w-admin', displayName: 'Admin A', confirmationMethod: 'PASSWORD', fallback: true },
  ];

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const el = (testId: string): HTMLElement | null =>
    host().querySelector(`[data-testid="${testId}"]`);
  const field = (): HTMLInputElement =>
    host().querySelector<HTMLInputElement>('#dispWitnessSecret')!;

  function confirmation(witnessedBy: string, minutes = 5): WitnessConfirmation {
    return {
      witnessedBy,
      displayName: 'Awa Sanou',
      method: 'PASSWORD',
      confirmedAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + minutes * 60_000).toISOString(),
    };
  }

  function pick(witnessId: string | undefined): void {
    fixture.componentRef.setInput('witnessId', witnessId);
    fixture.detectChanges();
  }

  function type(secret: string): void {
    field().value = secret;
    field().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    svc = jasmine.createSpyObj<PharmacyService>('PharmacyService', ['confirmWitness']);
    TestBed.configureTestingModule({
      imports: [WitnessConfirmComponent, TranslateModule.forRoot()],
      providers: [{ provide: PharmacyService, useValue: svc }],
    });
    fixture = TestBed.createComponent(WitnessConfirmComponent);
    fixture.componentRef.setInput('fieldId', 'dispWitnessSecret');
    fixture.componentRef.setInput('candidates', CANDIDATES);
    fixture.detectChanges();
  });

  it('asks for nothing until a witness is picked', () => {
    expect(el('witness-confirm')).toBeNull();
  });

  it('asks the witness for their password, in a field that hides it', () => {
    pick('w-pass');
    expect(field().type).toBe('password');
    expect(host().querySelector('label')!.textContent).toContain(
      'PHARMACY.WITNESS_CONFIRM_PASSWORD',
    );
    expect(fixture.componentInstance.isConfirmed()).toBeFalse();
  });

  it('asks a witness who has an authenticator for its code', () => {
    pick('w-totp');
    expect(field().type).toBe('text');
    expect(field().getAttribute('inputmode')).toBe('numeric');
    expect(host().querySelector('label')!.textContent).toContain('PHARMACY.WITNESS_CONFIRM_CODE');
  });

  it('sends the witness and what they typed, then says the witness is confirmed and drops the secret', () => {
    svc.confirmWitness.and.returnValue(of({ data: confirmation('w-pass') }) as never);
    const emitted: WitnessConfirmation[] = [];
    fixture.componentInstance.confirmed.subscribe((c) => emitted.push(c));
    pick('w-pass');
    type('s3cret');

    el('witness-confirm-submit')!.click();
    fixture.detectChanges();

    expect(svc.confirmWitness).toHaveBeenCalledOnceWith('w-pass', 's3cret');
    expect(el('witness-confirmed')!.textContent).toContain('PHARMACY.WITNESS_CONFIRM_CONFIRMED');
    expect(host().querySelector('#dispWitnessSecret')).toBeNull();
    expect(fixture.componentInstance.isConfirmed()).toBeTrue();
    expect(emitted.length).toBe(1);
  });

  it('a refused secret says why under the field, clears it and confirms nothing', () => {
    svc.confirmWitness.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'Mot de passe incorrect.' } })),
    );
    pick('w-pass');
    type('guess');

    el('witness-confirm-submit')!.click();
    fixture.detectChanges();

    expect(field().getAttribute('aria-invalid')).toBe('true');
    expect(host().querySelector('#dispWitnessSecret-invalid-msg')!.textContent).toBe(
      'Mot de passe incorrect.',
    );
    expect(field().value).toBe('');
    expect(el('witness-confirmed')).toBeNull();
    expect(fixture.componentInstance.isConfirmed()).toBeFalse();
  });

  it('refuses an empty field without calling the server', () => {
    pick('w-pass');
    document.body.appendChild(host());

    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="witness-confirm-submit"]',
      sent: svc.confirmWitness,
    });

    expect(marked).toEqual(['dispWitnessSecret']);
    host().remove();
  });

  it('marks the field with the reason the server names for the secret or for the choice of witness', () => {
    pick('w-pass');
    for (const refused of ['secret', 'witnessedBy']) {
      svc.confirmWitness.and.returnValue(
        throwError(() => fieldErrorsResponse({ [refused]: `Refusé : ${refused}` })),
      );
      type('guess');
      el('witness-confirm-submit')!.click();
      fixture.detectChanges();

      expectFieldsMarked(fixture, { dispWitnessSecret: `Refusé : ${refused}` });
      expect(fixture.componentInstance.isConfirmed()).toBeFalse();
    }
  });

  it('a confirmation is one witness’s: picking another colleague asks again', () => {
    svc.confirmWitness.and.returnValue(of({ data: confirmation('w-pass') }) as never);
    pick('w-pass');
    type('s3cret');
    el('witness-confirm-submit')!.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.isConfirmed()).toBeTrue();

    pick('w-totp');

    expect(el('witness-confirmed')).toBeNull();
    expect(fixture.componentInstance.isConfirmed()).toBeFalse();
    expect(field()).not.toBeNull();
  });

  it('a confirmation left on screen past its expiry is dropped and the field is asked again', () => {
    jasmine.clock().install();
    try {
      jasmine.clock().mockDate(new Date('2026-11-27T10:00:00Z'));
      svc.confirmWitness.and.returnValue(of({ data: confirmation('w-pass', 5) }) as never);
      pick('w-pass');
      type('s3cret');
      el('witness-confirm-submit')!.click();
      fixture.detectChanges();
      expect(fixture.componentInstance.isConfirmed()).toBeTrue();
      expect(host().querySelector('#dispWitnessSecret')).toBeNull();

      jasmine.clock().tick(5 * 60_000 + 1);
      fixture.detectChanges();

      expect(fixture.componentInstance.isConfirmed()).toBeFalse();
      expect(el('witness-confirmed')).toBeNull();
      expect(host().querySelector('#dispWitnessSecret')).not.toBeNull();
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('an expired confirmation no longer counts', () => {
    svc.confirmWitness.and.returnValue(of({ data: confirmation('w-pass', -1) }) as never);
    pick('w-pass');
    type('s3cret');
    el('witness-confirm-submit')!.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.isConfirmed()).toBeFalse();
    expect(el('witness-confirmed')).toBeNull();
  });

  it('says so when the witness has no way to confirm, and offers no field', () => {
    pick('w-none');
    expect(el('witness-confirm-unavailable')!.textContent).toContain(
      'PHARMACY.WITNESS_CONFIRM_UNAVAILABLE',
    );
    expect(host().querySelector('#dispWitnessSecret')).toBeNull();
  });

  it('explains why the administrator is offered when they are the fallback witness', () => {
    pick('w-admin');
    expect(el('witness-fallback-hint')!.textContent).toContain(
      'PHARMACY.WITNESS_CONFIRM_FALLBACK_HINT',
    );
    pick('w-pass');
    expect(el('witness-fallback-hint')).toBeNull();
  });
});
