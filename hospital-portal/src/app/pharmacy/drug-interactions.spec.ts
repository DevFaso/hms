import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { DrugInteractionsComponent } from './drug-interactions';
import { DrugInteractionEntry, DrugInteractionService } from '../services/drug-interaction.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

/**
 * Drug-interaction KB curation (P2 #14) — first caller of the admin API.
 */
describe('DrugInteractionsComponent', () => {
  let fixture: ComponentFixture<DrugInteractionsComponent>;
  let component: DrugInteractionsComponent;
  let interactionService: jasmine.SpyObj<DrugInteractionService>;
  let toast: jasmine.SpyObj<ToastService>;

  function entry(overrides: Partial<DrugInteractionEntry>): DrugInteractionEntry {
    return {
      id: 'i-1',
      drug1Code: '11289',
      drug1Name: 'warfarin',
      drug2Code: '1191',
      drug2Name: 'aspirin',
      severity: 'MAJOR',
      recommendation: 'Avoid unless a specific indication exists.',
      active: true,
      ...overrides,
    };
  }

  function setup(activeRoles: string[], rows: DrugInteractionEntry[]) {
    interactionService = jasmine.createSpyObj<DrugInteractionService>('DrugInteractionService', [
      'list',
      'create',
      'update',
      'deactivate',
      'reactivate',
    ]);
    interactionService.list.and.returnValue(of(rows));

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    TestBed.configureTestingModule({
      imports: [DrugInteractionsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: DrugInteractionService, useValue: interactionService },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (roles: string[]) => roles.some((r) => activeRoles.includes(r)),
          },
        },
      ],
    });

    fixture = TestBed.createComponent(DrugInteractionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('loads the KB and renders a row per pair', () => {
    setup(['ROLE_PHARMACIST'], [entry({ id: 'i-1' }), entry({ id: 'i-2', severity: 'MODERATE' })]);

    expect(interactionService.list).toHaveBeenCalledWith('', true);
    expect(fixture.nativeElement.querySelector('[data-testid="ddi-row-i-1"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="ddi-row-i-2"]')).not.toBeNull();
  });

  it('hides write controls from read-only roles the backend would refuse', () => {
    // READ spans PHARMACIST/DOCTOR/NURSE/MIDWIFE/admins; WRITE is
    // PHARMACIST/HOSPITAL_ADMIN/SUPER_ADMIN only.
    setup(['ROLE_DOCTOR'], [entry({})]);

    expect(fixture.nativeElement.querySelector('[data-testid="ddi-add"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="ddi-edit-i-1"]')).toBeNull();
  });

  it('refuses a non-RxCUI drug code before any request is made', () => {
    // A free-text code creates a row that fires at dispense but silently never
    // at CDS-Hooks (exact rxnormCode equality) — a KB row that lies.
    setup(['ROLE_PHARMACIST'], []);
    component.form = {
      drug1Code: 'amox-500',
      drug1Name: 'amoxicillin',
      drug2Code: '1191',
      drug2Name: 'aspirin',
      severity: 'MAJOR',
      recommendation: 'Something.',
    };

    component.submit();

    expect(toast.error).toHaveBeenCalled();
    expect(interactionService.create).not.toHaveBeenCalled();
  });

  it('surfaces the retired-pair refusal verbatim so the admin knows to reactivate', () => {
    setup(['ROLE_PHARMACIST'], []);
    const backendMessage =
      'An interaction between these two drugs exists but was retired; reactivate it instead of re-creating it.';
    interactionService.create.and.returnValue(
      throwError(() => ({ error: { message: backendMessage } }) as unknown),
    );
    component.form = {
      drug1Code: '11289',
      drug1Name: 'warfarin',
      drug2Code: '1191',
      drug2Name: 'aspirin',
      severity: 'MAJOR',
      recommendation: 'Avoid.',
    };

    component.submit();

    expect(toast.error).toHaveBeenCalledWith(backendMessage);
  });

  it('shows reactivate — not edit/deactivate — on retired rows, and calls it through', () => {
    const retired = entry({ id: 'i-ret', active: false });
    setup(['ROLE_PHARMACIST'], [retired]);
    interactionService.reactivate.and.returnValue(of({ ...retired, active: true }));

    expect(
      fixture.nativeElement.querySelector('[data-testid="ddi-reactivate-i-ret"]'),
    ).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="ddi-edit-i-ret"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="ddi-deactivate-i-ret"]')).toBeNull();

    component.reactivate(retired);

    expect(interactionService.reactivate).toHaveBeenCalledWith('i-ret');
    expect(interactionService.list).toHaveBeenCalledTimes(2);
  });

  it('reloads with activeOnly=false when the retired toggle is on', () => {
    setup(['ROLE_PHARMACIST'], [entry({})]);

    component.setShowInactive(true);

    expect(interactionService.list).toHaveBeenCalledWith('', false);
  });

  it('deactivates only through the confirm modal, whose copy owns the blast radius', () => {
    const row = entry({ id: 'i-1' });
    setup(['ROLE_PHARMACIST'], [row]);
    interactionService.deactivate.and.returnValue(of({ ...row, active: false }));

    component.confirmDeactivate(row);
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[data-testid="ddi-deactivate-modal"]'),
    ).not.toBeNull();

    component.executeDeactivate();

    expect(interactionService.deactivate).toHaveBeenCalledWith('i-1');
  });

  it('filters client-side by name or code', () => {
    setup(
      ['ROLE_PHARMACIST'],
      [
        entry({ id: 'i-1', drug1Name: 'warfarin' }),
        entry({ id: 'i-2', drug1Name: 'digoxin', drug1Code: '6835' }),
      ],
    );

    component.searchTerm.set('digo');
    fixture.detectChanges();

    expect(component.filtered().map((i) => i.id)).toEqual(['i-2']);
  });
});
