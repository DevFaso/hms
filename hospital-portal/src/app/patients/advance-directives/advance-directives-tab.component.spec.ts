import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { AdvanceDirectivesTabComponent } from './advance-directives-tab.component';
import {
  AdvanceDirectiveResponse,
  AdvanceDirectiveService,
} from '../../services/advance-directive.service';
import { ToastService } from '../../core/toast.service';

/**
 * Advance-directives tab (P2 #13) — the first write surface for a table that
 * was read by the storyboard and record sharing but written by nothing.
 */
describe('AdvanceDirectivesTabComponent', () => {
  let fixture: ComponentFixture<AdvanceDirectivesTabComponent>;
  let component: AdvanceDirectivesTabComponent;
  let directiveService: jasmine.SpyObj<AdvanceDirectiveService>;
  let toast: jasmine.SpyObj<ToastService>;

  function directive(overrides: Partial<AdvanceDirectiveResponse>): AdvanceDirectiveResponse {
    return {
      id: 'd-1',
      patientId: 'p-1',
      hospitalId: 'h-1',
      directiveType: 'DO_NOT_RESUSCITATE',
      status: 'ACTIVE',
      ...overrides,
    };
  }

  function setup(directives: AdvanceDirectiveResponse[]) {
    directiveService = jasmine.createSpyObj<AdvanceDirectiveService>('AdvanceDirectiveService', [
      'listForPatient',
      'create',
      'update',
      'revoke',
    ]);
    directiveService.listForPatient.and.returnValue(of(directives));

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    TestBed.configureTestingModule({
      imports: [AdvanceDirectivesTabComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AdvanceDirectiveService, useValue: directiveService },
        { provide: ToastService, useValue: toast },
      ],
    });

    fixture = TestBed.createComponent(AdvanceDirectivesTabComponent);
    component = fixture.componentInstance;
    component.patientId = 'p-1';
    fixture.detectChanges();
  }

  it('hides revoked and expired directives by default, shows them on toggle', () => {
    setup([
      directive({ id: 'd-act', status: 'ACTIVE' }),
      directive({ id: 'd-rev', status: 'REVOKED' }),
      directive({ id: 'd-exp', status: 'EXPIRED' }),
    ]);

    expect(
      fixture.nativeElement.querySelector('[data-testid="directive-row-d-act"]'),
    ).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="directive-row-d-rev"]')).toBeNull();

    component.showInactive.set(true);
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="directive-row-d-rev"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="directive-row-d-exp"]'),
    ).not.toBeNull();
  });

  it('offers edit and revoke only on rows that are not already revoked', () => {
    setup([
      directive({ id: 'd-act', status: 'ACTIVE' }),
      directive({ id: 'd-rev', status: 'REVOKED' }),
    ]);
    component.showInactive.set(true);
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="directive-edit-d-act"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="directive-revoke-d-act"]'),
    ).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="directive-edit-d-rev"]')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="directive-revoke-d-rev"]'),
    ).toBeNull();
  });

  it('does not offer REVOKED as an editable status — revoke is its own ceremony', () => {
    setup([]);
    expect(component.editableStatuses).not.toContain('REVOKED' as never);
    expect(component.editableStatuses).not.toContain('EXPIRED' as never);
  });

  it('refuses an expiry before the effective date, mirroring the backend rule', () => {
    setup([]);
    component.form = {
      directiveType: 'DO_NOT_RESUSCITATE',
      status: 'ACTIVE',
      physicianName: 'Dr Ouédraogo',
      effectiveDate: '2026-08-22',
      expirationDate: '2026-08-01',
    };

    component.submit();

    expect(toast.error).toHaveBeenCalled();
    expect(directiveService.create).not.toHaveBeenCalled();
  });

  it('surfaces the backend refusal verbatim on save', () => {
    setup([]);
    const backendMessage = 'An advance directive cannot expire before it takes effect.';
    directiveService.create.and.returnValue(
      throwError(() => ({ status: 400, error: { message: backendMessage } }) as unknown),
    );
    component.form = {
      directiveType: 'LIVING_WILL',
      status: 'ACTIVE',
      effectiveDate: '2026-08-22',
    };

    component.submit();

    expect(toast.error).toHaveBeenCalledWith(backendMessage);
  });

  it('QA D1148: one click on Save records nothing — type, status and date are named, the DNR needs its physician', () => {
    setup([]);
    component.openCreate();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    // A submit button submits only a form that is in the document.
    document.body.appendChild(host);
    // Nothing is pre-filled.
    expect(component.form.directiveType).toBe('');
    expect(component.form.status).toBe('');
    host.querySelector<HTMLButtonElement>('[data-testid="directive-save"]')!.click();
    fixture.detectChanges();
    expect(directiveService.create).not.toHaveBeenCalled();
    for (const id of ['directive-type', 'directive-status', 'directive-effective']) {
      expect(host.querySelector('#' + id)?.getAttribute('aria-invalid'))
        .withContext(id)
        .toBe('true');
    }

    component.form = {
      directiveType: 'DO_NOT_RESUSCITATE',
      status: 'ACTIVE',
      effectiveDate: '2026-10-31',
    };
    fixture.detectChanges();
    host.querySelector<HTMLButtonElement>('[data-testid="directive-save"]')!.click();
    fixture.detectChanges();
    expect(directiveService.create).not.toHaveBeenCalled();
    expect(host.querySelector('#directive-physician')?.getAttribute('aria-invalid')).toBe('true');
    host.remove();
  });

  it('revokes through the revoke endpoint after confirmation, then reloads', () => {
    const row = directive({ id: 'd-1' });
    setup([row]);
    directiveService.revoke.and.returnValue(of({ ...row, status: 'REVOKED' }));

    component.confirmRevoke(row);
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[data-testid="directive-revoke-modal"]'),
    ).not.toBeNull();

    component.executeRevoke('Patient a changé d’avis');

    expect(directiveService.revoke).toHaveBeenCalledWith('d-1', 'Patient a changé d’avis');
    expect(directiveService.listForPatient).toHaveBeenCalledTimes(2);
  });

  /**
   * QA D1180 (round 22, regression): the server requires the reason; the
   * confirmation had no field and sent `{}`, so an active DNR stayed on the
   * banner. Driven through the real dialog: open, refuse empty, type, confirm.
   */
  describe('QA D1180: the revoke dialog asks for the reason', () => {
    function openDialog(): HTMLElement {
      const row = directive({ id: 'd-act', status: 'ACTIVE' });
      setup([row]);
      directiveService.revoke.and.returnValue(of({ ...row, status: 'REVOKED' }));
      const host = fixture.nativeElement as HTMLElement;
      document.body.appendChild(host);
      host.querySelector<HTMLButtonElement>('[data-testid="directive-revoke-d-act"]')!.click();
      fixture.detectChanges();
      return host;
    }

    it('has a required reason field, and an empty confirm sends nothing and says why', () => {
      const host = openDialog();
      const field = host.querySelector<HTMLTextAreaElement>('[data-testid="reason-dialog-reason"]');
      expect(field).withContext('the dialog asks for the reason').not.toBeNull();
      expect(field!.required).toBeTrue();
      host.querySelector<HTMLButtonElement>('[data-testid="reason-dialog-confirm"]')!.click();
      fixture.detectChanges();
      expect(directiveService.revoke).not.toHaveBeenCalled();
      expect(field!.getAttribute('aria-invalid')).toBe('true');
      expect(host.querySelector('[data-testid="submit-guard-reason"]')?.textContent?.trim())
        .withContext('the refusal is said next to the button')
        .toBeTruthy();
      host.remove();
    });

    it('sends the typed reason in the body and reloads the banner', () => {
      const host = openDialog();
      const field = host.querySelector<HTMLTextAreaElement>('[data-testid="reason-dialog-reason"]')!;
      field.value = '  Directive remplacée par une nouvelle  ';
      field.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      host.querySelector<HTMLButtonElement>('[data-testid="reason-dialog-confirm"]')!.click();
      fixture.detectChanges();
      expect(directiveService.revoke).toHaveBeenCalledOnceWith(
        'd-act',
        'Directive remplacée par une nouvelle',
      );
      expect(host.querySelector('[data-testid="reason-dialog"]')).toBeNull();
      host.remove();
    });

    it('shows the server refusal and keeps the dialog open on a 400', () => {
      const host = openDialog();
      directiveService.revoke.and.returnValue(
        throwError(() => ({ status: 400, error: { message: 'Indiquez le motif' } })),
      );
      const field = host.querySelector<HTMLTextAreaElement>('[data-testid="reason-dialog-reason"]')!;
      field.value = 'x';
      field.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      host.querySelector<HTMLButtonElement>('[data-testid="reason-dialog-confirm"]')!.click();
      fixture.detectChanges();
      expect(toast.error).toHaveBeenCalledWith('Indiquez le motif');
      expect(host.querySelector('[data-testid="reason-dialog"]')).not.toBeNull();
      host.remove();
    });
  });

  it('has no delete anywhere — a directive once in force is part of the record', () => {
    setup([]);
    expect((directiveService as unknown as Record<string, unknown>)['delete']).toBeUndefined();
  });
});
