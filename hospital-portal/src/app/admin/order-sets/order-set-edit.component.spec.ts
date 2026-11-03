import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { OrderSetEditComponent } from './order-set-edit.component';
import { OrderSetService, OrderSetSummary } from '../../services/order-set.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { AuthService } from '../../auth/auth.service';

describe('OrderSetEditComponent', () => {
  let fixture: ComponentFixture<OrderSetEditComponent>;
  let svc: jasmine.SpyObj<OrderSetService>;
  let toast: jasmine.SpyObj<ToastService>;
  let auth: jasmine.SpyObj<AuthService>;

  const existing: OrderSetSummary = {
    id: 'os-9',
    name: 'Sepsis bundle',
    description: 'Hour-1 sepsis',
    admissionType: 'EMERGENCY',
    hospitalId: 'h1',
    orderItems: [
      {
        orderType: 'MEDICATION',
        medicationName: 'Acetaminophen',
        synonyms: ['paracetamol', 'Tylenol'],
        doseRangeMin: 500,
        doseRangeMax: 1000,
        doseUnit: 'mg',
        route: 'PO',
        frequency: 'Q6H',
      },
    ],
    clinicalGuidelines: 'Surviving Sepsis Campaign 2021',
    active: true,
    version: 1,
    orderCount: 1,
  };

  async function configure(idParam: string): Promise<void> {
    svc = jasmine.createSpyObj<OrderSetService>('OrderSetService', [
      'getById',
      'versions',
      'create',
      'update',
    ]);
    svc.getById.and.returnValue(of(existing));
    svc.versions.and.returnValue(of([existing]));
    svc.create.and.returnValue(of(existing));
    svc.update.and.returnValue(of({ ...existing, version: 2 }));

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['getUserProfile']);
    auth.getUserProfile.and.returnValue({
      id: 'u1',
      username: 'admin',
      email: 'admin@example.org',
      roles: ['ROLE_HOSPITAL_ADMIN'],
      staffId: 'staff-1',
      hospitalIds: ['h1'],
      active: true,
    });
    const role = jasmine.createSpyObj<RoleContextService>('RoleContextService', [], {
      activeHospitalId: 'h1',
    });

    await TestBed.configureTestingModule({
      imports: [OrderSetEditComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: OrderSetService, useValue: svc },
        { provide: ToastService, useValue: toast },
        { provide: AuthService, useValue: auth },
        { provide: RoleContextService, useValue: role },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: (k: string) => (k === 'id' ? idParam : null) } },
          },
        },
      ],
    }).compileComponents();
  }

  it('loads the existing template into the structured editor when id != "new"', async () => {
    await configure('os-9');
    fixture = TestBed.createComponent(OrderSetEditComponent);
    fixture.detectChanges();

    expect(svc.getById).toHaveBeenCalledOnceWith('os-9');
    expect(svc.versions).toHaveBeenCalledOnceWith('os-9');

    const items = fixture.componentInstance['items']();
    expect(items.length).toBe(1);
    expect(items[0].medicationName).toBe('Acetaminophen');
    expect(items[0]._synonymsText).toBe('paracetamol, Tylenol');
    expect(items[0].doseRangeMin).toBe(500);
    expect(items[0].doseRangeMax).toBe(1000);
  });

  it('persists synonyms (parsed from comma list) and dose-range fields on save', async () => {
    await configure('new');
    const navigateSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(OrderSetEditComponent);
    fixture.detectChanges();

    fixture.componentInstance['form'].name = 'New bundle';
    fixture.componentInstance['form'].admissionType = 'ELECTIVE';
    const items = fixture.componentInstance['items']();
    items[0].medicationName = 'Ibuprofen';
    items[0]._synonymsText = 'Advil, Motrin';
    items[0].doseRangeMin = 200;
    items[0].doseRangeMax = 400;
    items[0].doseUnit = 'mg';
    items[0].frequency = 'Q6H';
    items[0].route = 'PO';
    fixture.componentInstance['items'].set([...items]);

    (fixture.componentInstance as unknown as { save(): void }).save();

    expect(svc.create).toHaveBeenCalled();
    const sent = svc.create.calls.mostRecent().args[0];
    expect(sent.orderItems.length).toBe(1);
    expect(sent.orderItems[0].synonyms).toEqual(['Advil', 'Motrin']);
    expect(sent.orderItems[0].doseRangeMin).toBe(200);
    expect(sent.orderItems[0].doseRangeMax).toBe(400);
    expect(toast.success).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/admin/order-sets']);
  });

  // QA round 30 (user decision 2026-11-02): an as-needed (PRN) item states the
  // minimum interval between two doses; the order it creates requires it.
  it('QA round 30: a PRN item asks for its minimum interval, marks it, and saves it', async () => {
    await configure('new');
    spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(OrderSetEditComponent);
    fixture.detectChanges();
    const cmp = fixture.componentInstance as unknown as { save(): void };
    fixture.componentInstance['form'].name = 'Douleur';
    const items = fixture.componentInstance['items']();
    items[0].medicationName = 'Paracétamol';
    items[0].frequency = 'PRN';
    fixture.componentInstance['items'].set([...items]);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const field = () =>
      host.querySelector<HTMLInputElement>('[data-testid="order-set-item-prn-0"]');
    expect(field()).not.toBeNull();

    cmp.save();
    fixture.detectChanges();
    expect(svc.create).not.toHaveBeenCalled();
    expect(field()!.getAttribute('aria-invalid')).toBe('true');
    expect(toast.error.calls.mostRecent().args[0] as string).toContain(
      'PRESCRIPTIONS.PRN_MIN_INTERVAL_REQUIRED',
    );

    items[0].prnMinIntervalHours = 6;
    fixture.componentInstance['items'].set([...items]);
    cmp.save();
    expect(svc.create.calls.mostRecent().args[0].orderItems[0].prnMinIntervalHours).toBe(6);

    // A fixed schedule carries no interval.
    items[0].frequency = 'Q6H';
    fixture.componentInstance['items'].set([...items]);
    fixture.detectChanges();
    expect(field()).toBeNull();
    cmp.save();
    expect(
      'prnMinIntervalHours' in svc.create.calls.mostRecent().args[0].orderItems[0],
    ).toBeFalse();
  });

  it('errors when an item is missing a name', async () => {
    await configure('new');
    fixture = TestBed.createComponent(OrderSetEditComponent);
    fixture.detectChanges();

    fixture.componentInstance['form'].name = 'X';
    const items = fixture.componentInstance['items']();
    items[0].medicationName = '';
    fixture.componentInstance['items'].set([...items]);

    (fixture.componentInstance as unknown as { save(): void }).save();

    expect(svc.create).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
  });

  /** QA D859: HOSPITAL_ADMIN_A opened /admin/order-sets/new in FR and ES. */
  it('QA D859: an empty save marks the name and the unnamed item on the page', async () => {
    await configure('new');
    fixture = TestBed.createComponent(OrderSetEditComponent);
    fixture.detectChanges();

    (fixture.componentInstance as unknown as { save(): void }).save();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(svc.create).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="order-set-name-error"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="order-set-name"]')!.getAttribute('aria-invalid')).toBe(
      'true',
    );
    expect(host.querySelector('[data-testid="order-set-item-name-error-0"]')).not.toBeNull();
    // QA D1256 class (round 27): the name AND the unnamed item in one toast (it said the name only).
    const said = toast.error.calls.mostRecent().args[0] as string;
    expect(said).toContain('ORDER_SETS.NAME_REQUIRED');
    expect(said).toContain('ORDER_SETS.ITEM_NAME_MISSING');
  });

  it('QA D859: item types, units, routes and frequencies are read, not shown as codes', async () => {
    await configure('new');
    fixture = TestBed.createComponent(OrderSetEditComponent);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const labels = (testId: string) =>
      Array.from(host.querySelectorAll(`[data-testid="${testId}"] option`)).map((o) =>
        (o.textContent ?? '').trim(),
      );
    const values = (testId: string) =>
      Array.from(host.querySelectorAll(`[data-testid="${testId}"] option`)).map(
        (o) => (o as HTMLOptionElement).value,
      );

    // TranslateModule.forRoot() with no translations answers the key itself,
    // so a translated option shows its key here and a raw one its code.
    expect(labels('order-set-item-type-0')).toContain('ORDER_SETS.ITEM_TYPE.MEDICATION');
    expect(labels('order-set-item-type-0')).not.toContain('MEDICATION');
    expect(labels('order-set-item-dose-unit-0')).toContain('ORDER_SETS.DOSE_UNIT.TABLET');
    expect(labels('order-set-item-dose-unit-0')).toContain('mg');
    expect(labels('order-set-item-route-0')).toContain('ORDER_SETS.ROUTE.TOPICAL');
    expect(labels('order-set-item-frequency-0')).toContain('ORDER_SETS.FREQUENCY.QID');
    // The wire values are unchanged.
    expect(values('order-set-item-type-0')).toContain('MEDICATION');
    expect(values('order-set-item-dose-unit-0')).toContain('tablet');
    expect(values('order-set-item-route-0')).toContain('TOPICAL');
  });
});
