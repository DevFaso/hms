import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { PatientPortalActivationComponent } from './patient-portal-activation.component';
import { PatientService } from '../../services/patient.service';
import { ToastService } from '../../core/toast.service';
import { NotificationDeliveryStatus } from '../../shared/delivery-warnings';

/** QA r70 D3206: the desk hands over a patient's portal activation code. */
describe('PatientPortalActivationComponent', () => {
  let fixture: ComponentFixture<PatientPortalActivationComponent>;
  let patients: jasmine.SpyObj<PatientService>;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    patients = jasmine.createSpyObj<PatientService>('PatientService', [
      'revealPortalActivationCode',
      'resendPortalActivation',
    ]);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'warning', 'error']);
    await TestBed.configureTestingModule({
      imports: [PatientPortalActivationComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientService, useValue: patients },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PatientPortalActivationComponent);
    fixture.componentRef.setInput('patientId', 'p-1');
    fixture.detectChanges();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const click = (id: string) => {
    (el().querySelector(`[data-testid="${id}"]`) as HTMLButtonElement).click();
    fixture.detectChanges();
  };

  it('shows the code the server hands over', () => {
    patients.revealPortalActivationCode.and.returnValue(
      of({ assignmentId: 'a-1', confirmationCode: '123456', expiresAt: '2026-11-24T10:00:00' }),
    );
    click('portal-activation-show');
    expect(patients.revealPortalActivationCode).toHaveBeenCalledOnceWith('p-1');
    expect(el().querySelector('[data-testid="portal-activation-code"]')?.textContent).toContain(
      '123456',
    );
  });

  it('says why the server refused, never shows a code', () => {
    patients.revealPortalActivationCode.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 404, error: { message: 'Aucune invitation' } }),
      ),
    );
    click('portal-activation-show');
    expect(el().querySelector('[data-testid="portal-activation-code"]')).toBeNull();
    expect(el().querySelector('[data-testid="portal-activation-error"]')).not.toBeNull();
  });

  it('re-sends, drops a shown code (it may have been replaced) and reports the delivery', () => {
    patients.revealPortalActivationCode.and.returnValue(
      of({ assignmentId: 'a-1', confirmationCode: '123456' }),
    );
    click('portal-activation-show');
    patients.resendPortalActivation.and.returnValue(
      of([
        { channel: 'SMS', purpose: 'ACTIVATION', outcome: 'RECIPIENT_NOT_ALLOWED' },
      ] as NotificationDeliveryStatus[]),
    );
    click('portal-activation-resend');
    expect(el().querySelector('[data-testid="portal-activation-code"]')).toBeNull();
    expect(toast.success).toHaveBeenCalled();
    expect(toast.warning).toHaveBeenCalled();
  });
});
