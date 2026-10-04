import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { BirthPlanTabComponent } from './birth-plan-tab';
import { BirthPlanResponse, BirthPlanService } from '../services/birth-plan.service';
import { PageResponse } from '../services/maternity.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { RoleContextStub, roleContextStub } from '../testing/role-context.stub';

/**
 * The birth-plan worklist of the maternity page. Asserted through the rendered
 * DOM in French (the #661 shape): a translated header, an empty state and a
 * status badge only count once the page shows them.
 */
describe('BirthPlanTabComponent', () => {
  let fixture: ComponentFixture<BirthPlanTabComponent>;
  let service: jasmine.SpyObj<BirthPlanService>;
  let toast: jasmine.SpyObj<ToastService>;
  let roleCtx: RoleContextStub;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const texts = (selector: string): string[] =>
    Array.from(host().querySelectorAll(selector)).map((el) => el.textContent?.trim() ?? '');

  function page(content: BirthPlanResponse[]): PageResponse<BirthPlanResponse> {
    return { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 };
  }

  function plan(overrides: Partial<BirthPlanResponse> = {}): BirthPlanResponse {
    return {
      id: 'bp-1',
      patientId: 'p-1',
      introduction: {
        patientName: 'Awa Traoré',
        placeOfBirth: 'CHU Yalgado',
        healthcareProvider: 'Sage-femme Ouédraogo',
      },
      providerReviewed: false,
      ...overrides,
    } as BirthPlanResponse;
  }

  function create(roles: string[], plans: BirthPlanResponse[] = [plan()]): void {
    service.search.and.returnValue(of(page(plans)));
    roleCtx.set({ roles });
    fixture = TestBed.createComponent(BirthPlanTabComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<BirthPlanService>('BirthPlanService', [
      'search',
      'pendingReview',
    ]);
    service.pendingReview.and.returnValue(of(page([])));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    roleCtx = roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles: [] });
    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['getHospitalId']);
    auth.getHospitalId.and.returnValue('h-1');

    TestBed.configureTestingModule({
      imports: [BirthPlanTabComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: BirthPlanService, useValue: service },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
        { provide: RoleContextService, useValue: roleCtx },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      MATERNITY: { PATIENT: 'Patiente', WORKLIST_ALL: 'Tous' },
      BIRTH_PLAN: {
        NONE: 'Aucun projet de naissance',
        PENDING_REVIEW: 'À valider',
        REVIEWED: 'Validé',
        NEW: 'Nouveau projet',
        LOAD_ERROR: 'Chargement impossible',
      },
    });
  });

  it('lists the plans for the hospital with translated headers and status', () => {
    create(['ROLE_MIDWIFE']);
    expect(service.search).toHaveBeenCalledWith(jasmine.objectContaining({ hospitalId: 'h-1' }));
    expect(texts('th')[0]).toBe('Patiente');
    const row = texts('tbody tr td');
    expect(row[0]).toBe('Awa Traoré');
    expect(row[2]).toBe('CHU Yalgado');
    expect(texts('.status-badge')).toEqual(['À valider']);
  });

  it('shows the translated empty state when there is nothing to list', () => {
    create(['ROLE_MIDWIFE'], []);
    expect(texts('.empty-state h3')).toEqual(['Aucun projet de naissance']);
    expect(host().querySelector('table')).toBeNull();
  });

  it('gives a nurse edit but not review, delete or the pending-review worklist', () => {
    create(['ROLE_NURSE']);
    const tabs = texts('.tab-btn');
    expect(tabs).not.toContain('À valider');
    expect(host().querySelector('.filter-bar .btn-primary')).not.toBeNull();
    const titles = Array.from(host().querySelectorAll('.actions-cell button')).map((b) =>
      b.getAttribute('title'),
    );
    expect(titles).toEqual(['COMMON.VIEW', 'COMMON.EDIT']);
  });

  it('gives a midwife review and delete on an unreviewed plan', () => {
    create(['ROLE_MIDWIFE']);
    expect(texts('.tab-btn')).toContain('À valider');
    const titles = Array.from(host().querySelectorAll('.actions-cell button')).map((b) =>
      b.getAttribute('title'),
    );
    expect(titles).toEqual(['COMMON.VIEW', 'COMMON.EDIT', 'BIRTH_PLAN.REVIEW', 'COMMON.DELETE']);
  });

  it('offers no write control to a read-only role', () => {
    create(['ROLE_RECEPTIONIST']);
    expect(host().querySelector('.filter-bar .btn-primary')).toBeNull();
    const titles = Array.from(host().querySelectorAll('.actions-cell button')).map((b) =>
      b.getAttribute('title'),
    );
    expect(titles).toEqual(['COMMON.VIEW']);
  });

  it('reads the pending-review worklist from its own endpoint', () => {
    create(['ROLE_MIDWIFE']);
    const pending = Array.from(host().querySelectorAll('.tab-btn')).find(
      (b) => b.textContent?.trim() === 'À valider',
    ) as HTMLElement;
    pending.click();
    fixture.detectChanges();
    expect(service.pendingReview).toHaveBeenCalledWith('h-1', 0);
  });

  it('toasts the translated error when the list cannot load', () => {
    service.search.and.returnValue(throwError(() => new Error('500')));
    roleCtx.set({ roles: ['ROLE_MIDWIFE'] });
    fixture = TestBed.createComponent(BirthPlanTabComponent);
    fixture.detectChanges();
    expect(toast.error).toHaveBeenCalledWith('Chargement impossible');
    expect(fixture.componentInstance.loading()).toBeFalse();
  });
});
