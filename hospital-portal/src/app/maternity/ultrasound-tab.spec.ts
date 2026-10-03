import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { UltrasoundTabComponent } from './ultrasound-tab';
import { UltrasoundOrderResponse, UltrasoundService } from '../services/ultrasound.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { RoleContextStub, roleContextStub } from '../testing/role-context.stub';

/**
 * The ultrasound worklists of the maternity page. Asserted through the rendered
 * DOM in French (the #661 shape): scan type and status render through a key
 * built in the template, so only the DOM shows whether the key resolves.
 */
describe('UltrasoundTabComponent', () => {
  let fixture: ComponentFixture<UltrasoundTabComponent>;
  let service: jasmine.SpyObj<UltrasoundService>;
  let toast: jasmine.SpyObj<ToastService>;
  let roleCtx: RoleContextStub;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const texts = (selector: string): string[] =>
    Array.from(host().querySelectorAll(selector)).map((el) => el.textContent?.trim() ?? '');
  const actionTitles = (): (string | null)[] =>
    Array.from(host().querySelectorAll('.actions-cell button')).map((b) => b.getAttribute('title'));

  function order(overrides: Partial<UltrasoundOrderResponse> = {}): UltrasoundOrderResponse {
    return {
      id: 'us-1',
      patientId: 'p-1',
      patientDisplayName: 'Awa Traoré',
      hospitalId: 'h-1',
      scanType: 'ANATOMY_SCAN',
      status: 'ORDERED',
      gestationalAgeAtOrder: 20,
      ...overrides,
    };
  }

  function create(roles: string[], orders: UltrasoundOrderResponse[] = [order()]): void {
    service.ordersByHospital.and.returnValue(of(orders));
    roleCtx.set({ roles });
    fixture = TestBed.createComponent(UltrasoundTabComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<UltrasoundService>('UltrasoundService', [
      'ordersByHospital',
      'pendingOrders',
      'highRiskOrders',
      'followUpRequired',
      'anomalies',
    ]);
    service.pendingOrders.and.returnValue(of([]));
    service.anomalies.and.returnValue(of([]));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    roleCtx = roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles: [] });
    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['getHospitalId']);

    TestBed.configureTestingModule({
      imports: [UltrasoundTabComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: UltrasoundService, useValue: service },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
        { provide: RoleContextService, useValue: roleCtx },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      ULTRASOUND: {
        WORKLIST_ALL: 'Toutes',
        WORKLIST_PENDING: 'En attente',
        WORKLIST_ANOMALIES: 'Anomalies',
        TYPE_ANATOMY_SCAN: 'Échographie morphologique',
        STATUS_ORDERED: 'Prescrite',
        NO_ORDERS: 'Aucune demande',
        NO_REPORTS: 'Aucun compte rendu',
        LOAD_ERROR: 'Chargement impossible',
      },
    });
  });

  it('lists the hospital’s orders with a translated scan type and status', () => {
    create(['ROLE_MIDWIFE']);
    expect(service.ordersByHospital).toHaveBeenCalledWith('h-1');
    const cells = texts('tbody td');
    expect(cells[0]).toBe('Awa Traoré');
    expect(cells[1]).toBe('Échographie morphologique');
    expect(texts('tbody .status-badge')).toEqual(['Prescrite']);
  });

  it('renders the worklist tabs translated', () => {
    create(['ROLE_MIDWIFE']);
    const tabs = texts('.tab-btn');
    expect(tabs[0]).toBe('Toutes');
    expect(tabs[1]).toBe('En attente');
    expect(tabs[4]).toBe('Anomalies');
  });

  it('gives the ordering roles edit and report, and nobody writes a cancelled order', () => {
    create(['ROLE_MIDWIFE'], [order(), order({ id: 'us-2', status: 'CANCELLED' })]);
    expect(host().querySelector('.filter-bar .btn-primary')).not.toBeNull();
    expect(actionTitles()).toEqual([
      'COMMON.VIEW',
      'COMMON.EDIT',
      'ULTRASOUND.RECORD_REPORT',
      'COMMON.VIEW',
    ]);
  });

  it('offers a nurse the list but no write control', () => {
    create(['ROLE_NURSE']);
    expect(host().querySelector('.filter-bar .btn-primary')).toBeNull();
    expect(actionTitles()).toEqual(['COMMON.VIEW']);
  });

  it('switches to a report worklist and shows its translated empty state', () => {
    create(['ROLE_DOCTOR']);
    const anomalies = Array.from(host().querySelectorAll('.tab-btn'))[4] as HTMLElement;
    anomalies.click();
    fixture.detectChanges();
    expect(service.anomalies).toHaveBeenCalledWith('h-1');
    expect(texts('.empty-state h3')).toEqual(['Aucun compte rendu']);
  });

  it('shows the translated empty state for an empty order list', () => {
    create(['ROLE_DOCTOR'], []);
    expect(texts('.empty-state h3')).toEqual(['Aucune demande']);
  });

  it('toasts the translated error when the list cannot load', () => {
    service.ordersByHospital.and.returnValue(throwError(() => new Error('500')));
    fixture = TestBed.createComponent(UltrasoundTabComponent);
    fixture.detectChanges();
    expect(toast.error).toHaveBeenCalledWith('Chargement impossible');
    expect(host().querySelector('.loading-state')).toBeNull();
  });
});
