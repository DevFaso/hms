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
      throwError(() => ({ error: { message: backendMessage } }) as unknown),
    );
    component.form = { directiveType: 'LIVING_WILL' };

    component.submit();

    expect(toast.error).toHaveBeenCalledWith(backendMessage);
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

    component.executeRevoke();

    expect(directiveService.revoke).toHaveBeenCalledWith('d-1');
    expect(directiveService.listForPatient).toHaveBeenCalledTimes(2);
  });

  it('has no delete anywhere — a directive once in force is part of the record', () => {
    setup([]);
    expect((directiveService as unknown as Record<string, unknown>)['delete']).toBeUndefined();
  });
});
