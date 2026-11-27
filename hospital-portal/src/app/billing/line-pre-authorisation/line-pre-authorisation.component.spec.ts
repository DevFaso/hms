import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { LinePreAuthorisationComponent } from './line-pre-authorisation.component';
import { ImagingPreAuthEntryComponent } from './imaging-pre-auth-entry.component';
import { BillingService, InvoiceItemResponse } from '../../services/billing.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { expectEmptySubmitRefusal } from '../../testing/form-refusal-behaviour';

/** Insurer claims PR 1 (QA D3984): the desk and billing record an act's insurer pre-authorisation number. */
describe('Pre-authorisation number (claims PR 1, QA D3984)', () => {
  let billing: jasmine.SpyObj<BillingService>;

  const line = {
    id: 'line-1',
    billingInvoiceId: 'inv-1',
    itemDescription: 'Radio thorax',
    itemCategory: 'SERVICE',
    quantity: 1,
    unitPrice: 10000,
    totalPrice: 10000,
    preAuthorisationNumber: null,
  } as InvoiceItemResponse;

  function configure(roles: string[]): void {
    billing = jasmine.createSpyObj<BillingService>('BillingService', [
      'recordLinePreAuthorisation',
      'recordImagingPreAuthorisation',
    ]);
    billing.recordLinePreAuthorisation.and.returnValue(
      of({
        itemId: 'line-1',
        invoiceId: 'inv-1',
        preAuthorisationNumber: 'BON-117',
        orderUpdated: true,
      }),
    );
    billing.recordImagingPreAuthorisation.and.returnValue(
      of({
        orderId: 'ord-1',
        authorizationNumber: 'BON-117',
        authorizationPending: false,
        invoiceLinesUpdated: 1,
      }),
    );
    TestBed.configureTestingModule({
      imports: [
        LinePreAuthorisationComponent,
        ImagingPreAuthEntryComponent,
        TranslateModule.forRoot(),
      ],
      providers: [
        { provide: BillingService, useValue: billing },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'info']),
        },
      ],
    });
  }

  function lineFixture(status: string): ComponentFixture<LinePreAuthorisationComponent> {
    const fixture = TestBed.createComponent(LinePreAuthorisationComponent);
    fixture.componentRef.setInput('invoiceId', 'inv-1');
    fixture.componentRef.setInput('invoiceStatus', status);
    fixture.componentRef.setInput('line', line);
    fixture.detectChanges();
    return fixture;
  }

  it('reception records the number on a line; it is trimmed and sent once', () => {
    configure(['ROLE_RECEPTIONIST']);
    const fixture = lineFixture('SENT');
    const host = fixture.nativeElement as HTMLElement;
    let saved: string | undefined;
    fixture.componentInstance.saved.subscribe((n) => (saved = n));

    host.querySelector<HTMLButtonElement>('[data-testid="line-pre-auth-edit-line-1"]')!.click();
    fixture.detectChanges();
    fixture.componentInstance.draft.set('  BON   117 ');
    fixture.componentInstance.save();

    expect(billing.recordLinePreAuthorisation).toHaveBeenCalledOnceWith(
      'inv-1',
      'line-1',
      'BON 117',
    );
    expect(saved).toBe('BON-117');
  });

  it('the empty line form is refused on its field and nothing is sent', () => {
    configure(['ROLE_BILLING_SPECIALIST']);
    const fixture = lineFixture('SENT');
    document.body.appendChild(fixture.nativeElement);
    fixture.componentInstance.start();
    fixture.detectChanges();

    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="line-pre-auth-save"]',
      sent: billing.recordLinePreAuthorisation,
    });
    (fixture.nativeElement as HTMLElement).remove();
  });

  it('the empty follow-up form is refused on its field and nothing is sent', () => {
    configure(['ROLE_BILLING_SPECIALIST']);
    const fixture = TestBed.createComponent(ImagingPreAuthEntryComponent);
    fixture.componentRef.setInput('orderId', 'ord-1');
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
    fixture.componentInstance.editing.set(true);
    fixture.detectChanges();

    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="in-basket-pre-auth-save"]',
      sent: billing.recordImagingPreAuthorisation,
    });
    (fixture.nativeElement as HTMLElement).remove();
  });

  it('an empty or odd number is refused and nothing is sent', () => {
    configure(['ROLE_BILLING_SPECIALIST']);
    const fixture = lineFixture('SENT');
    fixture.componentInstance.start();
    fixture.detectChanges();

    fixture.componentInstance.draft.set('   ');
    fixture.componentInstance.save();
    fixture.componentInstance.draft.set('<b>1</b>');
    fixture.componentInstance.save();

    expect(billing.recordLinePreAuthorisation).not.toHaveBeenCalled();
  });

  it('no record button on a cancelled invoice, nor for a role that records no number', () => {
    configure(['ROLE_BILLING_SPECIALIST']);
    const cancelled = lineFixture('CANCELLED');
    expect(
      (cancelled.nativeElement as HTMLElement).querySelector(
        '[data-testid="line-pre-auth-edit-line-1"]',
      ),
    ).toBeNull();

    TestBed.resetTestingModule();
    configure(['ROLE_DOCTOR']);
    const doctor = lineFixture('SENT');
    expect(
      (doctor.nativeElement as HTMLElement).querySelector(
        '[data-testid="line-pre-auth-edit-line-1"]',
      ),
    ).toBeNull();
  });

  it('billing answers the imaging follow-up task with the number', () => {
    configure(['ROLE_BILLING_SPECIALIST']);
    const fixture = TestBed.createComponent(ImagingPreAuthEntryComponent);
    fixture.componentRef.setInput('orderId', 'ord-1');
    fixture.detectChanges();
    let done = false;
    fixture.componentInstance.saved.subscribe(() => (done = true));

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="in-basket-pre-auth"]')!
      .click();
    fixture.detectChanges();
    fixture.componentInstance.draft.set('BON-117');
    fixture.componentInstance.save();

    expect(billing.recordImagingPreAuthorisation).toHaveBeenCalledOnceWith('ord-1', 'BON-117');
    expect(done).toBeTrue();
  });
});
