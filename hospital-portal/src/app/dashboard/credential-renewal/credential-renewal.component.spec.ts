import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import {
  CredentialRenewalComponent,
  CredentialRenewalTarget,
} from './credential-renewal.component';
import { CredentialRenewal, CredentialingService } from '../../services/credentialing.service';
import { ToastService } from '../../core/toast.service';

/**
 * Credential renewal dialog (Tier 2 item 40).
 *
 * The dashboard has shown licence-expiry alerts since MVP 19 and there was
 * nowhere to act on one. These cover the acting: that the optional fields
 * really are optional on the wire, that the backend's refusals reach the
 * administrator verbatim, and that a failed history load is not dressed up
 * as an empty one.
 */
describe('CredentialRenewalComponent (Tier 2 item 40)', () => {
  let fixture: ComponentFixture<CredentialRenewalComponent>;
  let component: CredentialRenewalComponent;
  let service: jasmine.SpyObj<CredentialingService>;
  let toast: jasmine.SpyObj<ToastService>;

  const target: CredentialRenewalTarget = {
    staffId: 'staff-1',
    staffName: 'Dr Awa Traore',
    licenseNumber: 'MED-1234',
    licenseExpiryDate: '2026-09-30',
  };

  function renewal(overrides: Partial<CredentialRenewal> = {}): CredentialRenewal {
    return {
      id: 'ren-1',
      staffId: 'staff-1',
      previousLicenseNumber: 'MED-1234',
      previousExpiryDate: '2026-09-30',
      licenseNumber: 'MED-1234',
      expiryDate: '2027-09-30',
      issuingAuthority: 'Ordre des medecins',
      note: null,
      recordedByUserId: 'user-1',
      recordedByName: 'Admin One',
      recordedAt: '2026-08-26T09:00:00',
      ...overrides,
    };
  }

  beforeEach(async () => {
    service = jasmine.createSpyObj<CredentialingService>('CredentialingService', [
      'recordRenewal',
      'history',
    ]);
    service.history.and.returnValue(of([]));
    service.recordRenewal.and.returnValue(of(renewal()));

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [CredentialRenewalComponent, TranslateModule.forRoot()],
      providers: [
        { provide: CredentialingService, useValue: service },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CredentialRenewalComponent);
    component = fixture.componentInstance;
  });

  function open(t: CredentialRenewalTarget | null = target): void {
    fixture.componentRef.setInput('target', t);
    fixture.detectChanges();
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('stays closed until a target arrives', () => {
    open(null);
    expect(root().querySelector('[data-testid="credential-renewal-modal"]')).toBeNull();
    expect(service.history).not.toHaveBeenCalled();
  });

  it('opens on a target and loads that practitioner history straight away', () => {
    open();

    expect(root().querySelector('[data-testid="credential-renewal-modal"]')).not.toBeNull();
    // Loaded on open, not behind a second click: "has this lapsed before" is
    // the question the administrator has while deciding what to type.
    expect(service.history).toHaveBeenCalledOnceWith('staff-1');
  });

  it('refuses to save without a new expiry date', () => {
    open();
    component['save']();

    expect(service.recordRenewal).not.toHaveBeenCalled();
  });

  it('sends only the fields that were filled in', () => {
    open();
    component['expiryDate'].set('2027-09-30');

    component['save']();

    // Blank optional fields must be absent, not empty strings — the backend
    // treats null as "keep what is on file", and "" would blank the record.
    expect(service.recordRenewal).toHaveBeenCalledOnceWith('staff-1', {
      expiryDate: '2027-09-30',
      licenseNumber: undefined,
      issuingAuthority: undefined,
      note: undefined,
    });
  });

  it('sends the optional fields when they are filled in', () => {
    open();
    component['expiryDate'].set('2027-09-30');
    component['licenseNumber'].set(' MED-9999 ');
    component['issuingAuthority'].set(' Ordre des medecins ');
    component['note'].set(' Saw the original. ');

    component['save']();

    expect(service.recordRenewal).toHaveBeenCalledOnceWith('staff-1', {
      expiryDate: '2027-09-30',
      licenseNumber: 'MED-9999',
      issuingAuthority: 'Ordre des medecins',
      note: 'Saw the original.',
    });
  });

  it('emits renewed and closes on success', () => {
    const renewed = jasmine.createSpy('renewed');
    const closed = jasmine.createSpy('closed');
    component.renewed.subscribe(renewed);
    component.closed.subscribe(closed);

    open();
    component['expiryDate'].set('2027-09-30');
    component['save']();

    expect(renewed).toHaveBeenCalled();
    expect(closed).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalled();
    expect(component['saving']()).toBeFalse();
  });

  it('surfaces the backend refusal verbatim rather than a generic failure', () => {
    // "A practitioner cannot record their own credential renewal" tells an
    // administrator to fetch a colleague; "Could not record" does not.
    const message = 'A practitioner cannot record their own credential renewal.';
    service.recordRenewal.and.returnValue(throwError(() => ({ error: { message } }) as unknown));
    const closed = jasmine.createSpy('closed');
    component.closed.subscribe(closed);

    open();
    component['expiryDate'].set('2027-09-30');
    component['save']();

    expect(toast.error).toHaveBeenCalledWith(message);
    // Stays open so a typed note is not thrown away by a refusal the
    // administrator can resolve.
    expect(closed).not.toHaveBeenCalled();
    expect(component['saving']()).toBeFalse();
  });

  it('distinguishes a failed history load from an empty one', () => {
    service.history.and.returnValue(throwError(() => new Error('boom')));

    open();

    // "No renewals recorded" and "we could not load them" mean opposite
    // things to somebody deciding whether this licence has lapsed before.
    expect(root().querySelector('[data-testid="credential-history-error"]')).not.toBeNull();
    expect(root().querySelector('[data-testid="credential-history-empty"]')).toBeNull();
  });

  it('shows an empty history as empty', () => {
    open();
    expect(root().querySelector('[data-testid="credential-history-empty"]')).not.toBeNull();
    expect(root().querySelector('[data-testid="credential-history-error"]')).toBeNull();
  });

  it('renders history rows with the before and after expiry', () => {
    service.history.and.returnValue(of([renewal()]));

    open();

    const historyText = root().querySelector('[data-testid="credential-history"]')?.textContent;
    expect(historyText).toContain('2026-09-30');
    expect(historyText).toContain('2027-09-30');
    expect(historyText).toContain('Admin One');
  });

  it('clears typed values when a different practitioner is opened', () => {
    open();
    component['expiryDate'].set('2027-09-30');
    component['note'].set('For Dr Traore.');

    // Carrying these into the next dialog is how the wrong licence gets
    // renewed with the right-looking values.
    open({ ...target, staffId: 'staff-2', staffName: 'Dr Other' });

    expect(component['expiryDate']()).toBe('');
    expect(component['note']()).toBe('');
    expect(service.history).toHaveBeenCalledWith('staff-2');
  });
});
