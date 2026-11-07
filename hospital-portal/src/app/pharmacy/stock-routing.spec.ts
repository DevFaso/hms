import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LOCALE_ID, signal } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError, Subject } from 'rxjs';

import { ToastService } from '../core/toast.service';
import { PharmacyService } from '../services/pharmacy.service';
import { StockRoutingComponent } from './stock-routing';
import { RoleContextService } from '../core/role-context.service';
import { PrintLabelService } from '../services/print-label.service';
import { isSubmitBlocked } from '../shared/forms/submit-guard.testing';

describe('StockRoutingComponent', () => {
  let component: StockRoutingComponent;
  let fixture: ComponentFixture<StockRoutingComponent>;
  let pharmacySvc: jasmine.SpyObj<PharmacyService>;
  let toastSvc: jasmine.SpyObj<ToastService>;
  let printSvc: jasmine.SpyObj<PrintLabelService>;
  /** Only the scope gate is read here; a writable signal so a test can flip it. */
  const hasHospitalScope = signal(true);
  const roleContextStub = { hasHospitalScope } as unknown as RoleContextService;

  const stockCheckResponse = {
    data: {
      medicationName: 'Amoxicillin',
      pharmacyName: 'Main Dispensary',
      pharmacyId: 'ph-1',
      quantityOnHand: 1,
      sufficient: false,
      partnerPharmacies: [
        {
          pharmacyId: 'partner-1',
          pharmacyName: 'Partner Pharmacy',
          pharmacyType: 'PARTNER_PHARMACY',
          city: 'Ouagadougou',
          phoneNumber: '+22670000000',
          hasOnFormulary: true,
        },
      ],
    },
  };

  const decisionsResponse = {
    data: {
      content: [
        {
          id: 'dec-1',
          prescriptionId: 'rx-1',
          routingType: 'PARTNER',
          targetPharmacyId: 'partner-1',
          targetPharmacyName: 'Partner Pharmacy',
          reason: 'Nearest partner has stock',
          status: 'PENDING',
          decidedAt: '2025-06-01T10:00:00',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 10,
      number: 0,
    },
  };

  beforeEach(async () => {
    pharmacySvc = jasmine.createSpyObj('PharmacyService', [
      'checkStock',
      'listRoutingDecisionsByPrescription',
      'routeToPartner',
      'printForPatient',
      'backOrder',
      'partnerRespond',
      'confirmPartnerDispense',
      'getDispenseWorkQueue',
      'getPrintedPrescriptionCopy',
    ]);
    toastSvc = jasmine.createSpyObj('ToastService', ['success', 'error']);
    printSvc = jasmine.createSpyObj('PrintLabelService', ['openForPrint']);
    pharmacySvc.getPrintedPrescriptionCopy.and.returnValue(
      of(new Blob(['%PDF-1.4'], { type: 'application/pdf' })) as any,
    );

    pharmacySvc.checkStock.and.returnValue(of(stockCheckResponse as any));
    pharmacySvc.listRoutingDecisionsByPrescription.and.returnValue(of(decisionsResponse as any));
    pharmacySvc.routeToPartner.and.returnValue(of({ data: { id: 'dec-1' } } as any));
    pharmacySvc.printForPatient.and.returnValue(of({ data: { id: 'dec-2' } } as any));
    pharmacySvc.backOrder.and.returnValue(of({ data: { id: 'dec-3' } } as any));
    pharmacySvc.partnerRespond.and.returnValue(of({ data: { id: 'dec-1' } } as any));
    pharmacySvc.confirmPartnerDispense.and.returnValue(of({ data: { id: 'dec-1' } } as any));
    pharmacySvc.getDispenseWorkQueue.and.returnValue(
      of({
        data: {
          content: [
            {
              id: 'rx-amox',
              medicationName: 'Amoxicilline',
              dosage: '500 mg',
              status: 'SIGNED',
              patient: { id: 'p-1', firstName: 'Seydou', lastName: 'Traoré' },
            },
            {
              id: 'rx-para',
              medicationName: 'Paracétamol',
              status: 'SIGNED',
              patient: { id: 'p-2', firstName: 'Awa', lastName: 'Ouédraogo' },
            },
          ],
        },
      } as any),
    );

    await TestBed.configureTestingModule({
      imports: [StockRoutingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PharmacyService, useValue: pharmacySvc },
        { provide: ToastService, useValue: toastSvc },
        { provide: PrintLabelService, useValue: printSvc },
        { provide: RoleContextService, useValue: roleContextStub },
        // P-05: component now reads :prescriptionId from the route — provide an
        // empty paramMap by default so the existing tests don't auto-trigger
        // checkStock(). The deep-link path is exercised by its own test below.
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({}) } },
        },
      ],
    }).compileComponents();

    hasHospitalScope.set(true);
    fixture = TestBed.createComponent(StockRoutingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('P-05: should auto-check stock when navigated with :prescriptionId param', async () => {
    // Re-create the bed with a populated paramMap to verify the deep-link path.
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [StockRoutingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PharmacyService, useValue: pharmacySvc },
        { provide: ToastService, useValue: toastSvc },
        { provide: PrintLabelService, useValue: printSvc },
        { provide: RoleContextService, useValue: roleContextStub },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ prescriptionId: 'rx-deep-1' }) } },
        },
      ],
    }).compileComponents();

    const f = TestBed.createComponent(StockRoutingComponent);
    f.detectChanges();
    const c = f.componentInstance;

    expect(c.prescriptionId).toBe('rx-deep-1');
    expect(pharmacySvc.checkStock).toHaveBeenCalledWith('rx-deep-1');
    // QA D888: the deep link still works alongside the search.
    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalled();
  });

  it('QA D888: offers no raw prescription-id box, only a search over the queue', () => {
    expect(fixture.nativeElement.querySelector('#prescriptionIdInput')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="routing-rx-search"]')).not.toBeNull();
    // #963 review: newest first, or a busy dispensary's newest orders are out of reach.
    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalledWith(0, 100, 'ALL', [
      'createdAt,desc',
      'id,desc',
    ]);
  });

  it('#963 review: says the search covers only the newest orders, and searches older ones on request', () => {
    const rx = (id: string, firstName: string, medicationName: string) => ({
      id,
      medicationName,
      status: 'SIGNED',
      patient: { id: `p-${id}`, firstName, lastName: 'Kaboré' },
    });
    const first = Array.from({ length: 100 }, (_, i) => rx(`rx-new-${i}`, 'Issa', 'Ibuprofène'));
    pharmacySvc.getDispenseWorkQueue.and.returnValue(
      of({ data: { content: first, totalElements: 101, totalPages: 2 } } as any),
    );
    component.loadQueue();
    component.rxSearch.set('mariam');
    fixture.detectChanges();

    // The QA scenario: the order is the 101st, not on the page searched.
    expect(component.matches()).toEqual([]);
    expect(component.queuePartial()).toBeTrue();
    const noMatch = fixture.nativeElement.querySelector('[data-testid="routing-rx-no-match"]');
    expect(noMatch.textContent).toContain('PHARMACY.ROUTING_RX_NO_MATCH_PARTIAL');
    expect(
      fixture.nativeElement.querySelector('[data-testid="routing-rx-partial"]'),
    ).not.toBeNull();

    // "Load older" re-reads the newest 200 from the start in one request.
    pharmacySvc.getDispenseWorkQueue.and.returnValue(
      of({
        data: {
          content: [...first, rx('rx-old', 'Mariam', 'Fer')],
          totalElements: 101,
          totalPages: 1,
        },
      } as any),
    );
    (
      fixture.nativeElement.querySelector('[data-testid="routing-rx-load-older"]') as HTMLElement
    ).click();
    fixture.detectChanges();

    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalledWith(0, 200, 'ALL', [
      'createdAt,desc',
      'id,desc',
    ]);
    expect(component.queue().length).toBe(101);
    expect(component.matches().map((r) => r.id)).toEqual(['rx-old']);
    expect(component.queuePartial()).toBeFalse();
    expect(fixture.nativeElement.querySelector('[data-testid="routing-rx-partial"]')).toBeNull();
  });

  it('#963 review round 2: an order leaving the queue between reads cannot hide the 101st', () => {
    const rx = (id: string, firstName: string, medicationName: string) => ({
      id,
      medicationName,
      status: 'SIGNED',
      patient: { id: `p-${id}`, firstName, lastName: 'Sawadogo' },
    });
    // 150 orders waiting, newest first: R1..R100 on the first read.
    const all = Array.from({ length: 150 }, (_, i) =>
      i === 100 ? rx('rx-101', 'Mariam', 'Fer') : rx(`rx-${i + 1}`, 'Issa', 'Ibuprofène'),
    );
    pharmacySvc.getDispenseWorkQueue.and.returnValue(
      of({ data: { content: all.slice(0, 100), totalElements: 150, totalPages: 2 } } as any),
    );
    component.loadQueue();
    fixture.detectChanges();
    expect(component.queuePartial()).toBeTrue();

    // R50 is dispensed meanwhile: 149 remain and R101 is now the 100th row.
    const remaining = all.filter((r) => r.id !== 'rx-50');
    pharmacySvc.getDispenseWorkQueue.and.callFake(((page: number, size: number) =>
      of({
        data: {
          content: remaining.slice(page * size, page * size + size),
          totalElements: 149,
          totalPages: Math.ceil(149 / size),
        },
      } as any)) as any);
    component.loadOlderQueue();
    component.rxSearch.set('mariam');
    fixture.detectChanges();

    // An offset read of page 1 would start at R102 and never see R101.
    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalledWith(0, 200, 'ALL', [
      'createdAt,desc',
      'id,desc',
    ]);
    expect(component.queue().length).toBe(149);
    expect(component.queueTotal()).toBe(149);
    expect(component.queuePartial()).toBeFalse();
    expect(component.matches().map((r) => r.id)).toEqual(['rx-101']);
    expect(fixture.nativeElement.querySelector('[data-testid="routing-rx-no-match"]')).toBeNull();
  });

  it('#963 review round 2: at the server ceiling the note stays and the button goes', () => {
    const rows = Array.from({ length: 100 }, (_, i) => ({
      id: `rx-${i}`,
      medicationName: 'Ibuprofène',
      status: 'SIGNED',
      patient: { id: `p-${i}`, firstName: 'Issa', lastName: 'Zongo' },
    }));
    pharmacySvc.getDispenseWorkQueue.and.returnValue(
      of({ data: { content: rows, totalElements: 2500, totalPages: 25 } } as any),
    );
    pharmacySvc.getDispenseWorkQueue.calls.reset();
    component.loadQueue();
    for (let i = 0; i < 25; i++) {
      component.loadOlderQueue();
    }
    component.rxSearch.set('mariam');
    fixture.detectChanges();

    // 100 → 2000 in 19 steps; the 20th and later calls ask for nothing.
    expect(pharmacySvc.getDispenseWorkQueue).toHaveBeenCalledWith(0, 2000, 'ALL', [
      'createdAt,desc',
      'id,desc',
    ]);
    expect(pharmacySvc.getDispenseWorkQueue.calls.count()).toBe(20);
    expect(pharmacySvc.getDispenseWorkQueue.calls.mostRecent().args[1]).toBe(2000);
    expect(component.queueAtLimit()).toBeTrue();
    expect(component.queuePartial()).toBeTrue();
    expect(
      fixture.nativeElement.querySelector('[data-testid="routing-rx-partial"]'),
    ).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="routing-rx-load-older"]')).toBeNull();
  });

  it('#963 review: a whole queue says plainly that nothing matches, and a failed older page keeps the rows', () => {
    component.rxSearch.set('zzz');
    fixture.detectChanges();
    expect(component.queuePartial()).toBeFalse();
    const plain = fixture.nativeElement.querySelector('[data-testid="routing-rx-no-match"]');
    expect(plain.textContent.trim()).toBe('PHARMACY.ROUTING_RX_NO_MATCH');
    expect(fixture.nativeElement.querySelector('[data-testid="routing-rx-partial"]')).toBeNull();

    component.queueTotal.set(5);
    pharmacySvc.getDispenseWorkQueue.and.returnValue(throwError(() => ({ status: 500 })));
    component.loadOlderQueue();
    fixture.detectChanges();
    expect(component.queue().length).toBe(2);
    expect(component.queueMoreError()).toBeTrue();
    expect(
      fixture.nativeElement.querySelector('[data-testid="routing-rx-older-error"]'),
    ).not.toBeNull();
  });

  it('QA D888: finds an order by patient name and checks stock for the one picked', () => {
    component.rxSearch.set('traoré');
    fixture.detectChanges();
    expect(component.matches().map((rx) => rx.id)).toEqual(['rx-amox']);

    (
      fixture.nativeElement.querySelector('[data-testid="routing-rx-rx-amox"]') as HTMLElement
    ).click();
    fixture.detectChanges();

    expect(pharmacySvc.checkStock).toHaveBeenCalledWith('rx-amox');
    expect(component.prescriptionId).toBe('rx-amox');
    const chosen = fixture.nativeElement.querySelector(
      '[data-testid="routing-rx-selected"]',
    ) as HTMLElement;
    expect(chosen.textContent).toContain('Seydou Traoré');
    expect(chosen.textContent).toContain('Amoxicilline');
  });

  it('QA D888: finds an order by medication, and Enter takes the first match', () => {
    component.rxSearch.set('parac');
    component.pickFirstMatch();
    expect(pharmacySvc.checkStock).toHaveBeenCalledWith('rx-para');
  });

  it('QA D888: says so when nothing matches, and when the queue cannot be read', () => {
    component.rxSearch.set('zzz');
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[data-testid="routing-rx-no-match"]'),
    ).not.toBeNull();

    pharmacySvc.getDispenseWorkQueue.and.returnValue(throwError(() => ({ status: 403 })));
    component.loadQueue();
    fixture.detectChanges();
    expect(component.queueError()).toBeTrue();
    expect(
      fixture.nativeElement.querySelector('[data-testid="routing-rx-queue-error"]'),
    ).not.toBeNull();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load stock result and routing history', () => {
    component.prescriptionId = 'rx-1';

    component.checkStock();

    expect(pharmacySvc.checkStock).toHaveBeenCalledWith('rx-1');
    // A deterministic order with a unique tiebreaker, or page 2 is not a
    // continuation of page 1.
    expect(pharmacySvc.listRoutingDecisionsByPrescription).toHaveBeenCalledWith('rx-1', 0, 10, [
      'decidedAt,desc',
      'id,desc',
    ]);
    expect(component.stockResult()?.medicationName).toBe('Amoxicillin');
    expect(component.decisions().length).toBe(1);
  });

  it('should route to partner and refresh stock state', () => {
    component.prescriptionId = 'rx-1';
    component.openPartnerForm(stockCheckResponse.data.partnerPharmacies[0] as any);
    component.routingReason = 'Nearest partner has stock';
    const checkStockSpy = spyOn(component, 'checkStock');

    component.submitRouteToPartner();

    expect(pharmacySvc.routeToPartner).toHaveBeenCalledWith({
      prescriptionId: 'rx-1',
      routingType: 'PARTNER',
      targetPharmacyId: 'partner-1',
      reason: 'Nearest partner has stock',
    });
    expect(toastSvc.success).toHaveBeenCalledWith('PHARMACY.ROUTED_TO_PARTNER');
    expect(component.showPartnerForm()).toBeFalse();
    expect(checkStockSpy).toHaveBeenCalled();
  });

  it('should show backend error when stock check fails', () => {
    pharmacySvc.checkStock.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'Stock lookup failed' } })),
    );
    component.prescriptionId = 'rx-1';

    component.checkStock();

    expect(toastSvc.error).toHaveBeenCalledWith('Stock lookup failed');
    expect(component.checking()).toBeFalse();
  });

  it('renders a no-show from the flag, not from a stored English sentence', () => {
    pharmacySvc.listRoutingDecisionsByPrescription.and.returnValue(
      of({
        data: {
          content: [
            {
              id: 'dec-9',
              prescriptionId: 'rx-1',
              routingType: 'PARTNER',
              targetPharmacyName: 'Partner Pharmacy',
              reason: 'Nearest partner has stock',
              partnerNoShow: true,
              noShowReason: 'nobody at the counter',
              status: 'CANCELLED',
              decidedAt: '2025-06-01T10:00:00',
            },
          ],
          totalElements: 1,
          totalPages: 1,
          size: 10,
          number: 0,
        },
      } as any),
    );
    component.prescriptionId = 'rx-1';
    component.checkStock();
    fixture.detectChanges();

    const flag = fixture.nativeElement.querySelector('[data-testid="routing-no-show-dec-9"]');
    expect(flag).not.toBeNull();
    expect(flag.textContent).toContain('PHARMACY.PARTNER_NO_SHOW');
    const row = flag.closest('td');
    expect(row.textContent).toContain('nobody at the counter');
    expect(row.textContent).toContain('Nearest partner has stock');
  });

  it('says the routing history could not be loaded instead of rendering none', () => {
    // A swallowed failure read as "this order was never routed", which is
    // exactly what the next routing decision is taken from.
    pharmacySvc.listRoutingDecisionsByPrescription.and.returnValue(
      throwError(() => ({ status: 500 })),
    );
    component.prescriptionId = 'rx-1';
    component.checkStock();
    fixture.detectChanges();

    expect(component.decisionsError()).toBeTrue();
    expect(
      fixture.nativeElement.querySelector('[data-testid="routing-history-error"]'),
    ).not.toBeNull();

    pharmacySvc.listRoutingDecisionsByPrescription.and.returnValue(of(decisionsResponse as any));
    fixture.nativeElement.querySelector('[data-testid="routing-history-retry"]').click();
    fixture.detectChanges();

    expect(component.decisionsError()).toBeFalse();
    expect(fixture.nativeElement.querySelector('[data-testid="routing-history-error"]')).toBeNull();
  });

  it('clears a stale history failure when the prescription field is cleared', () => {
    pharmacySvc.listRoutingDecisionsByPrescription.and.returnValue(
      throwError(() => ({ status: 500 })),
    );
    component.prescriptionId = 'rx-1';
    component.loadDecisions();
    expect(component.decisionsError()).toBeTrue();

    // Otherwise the red panel — and a Retry that returns early and does
    // nothing — stay on screen for a prescription nobody is looking at.
    component.prescriptionId = '';
    component.loadDecisions();

    expect(component.decisionsError()).toBeFalse();
    expect(component.decisions()).toEqual([]);
  });

  it('a late failure from an older read cannot wipe a newer history', () => {
    // The error handler clears the table, so an unguarded race is destructive:
    // page forward while the first read is still out and the first failure
    // lands last, erasing rows the second read had already delivered.
    const first = new Subject<any>();
    const second = new Subject<any>();
    pharmacySvc.listRoutingDecisionsByPrescription.and.returnValues(first, second);

    component.prescriptionId = 'rx-1';
    component.loadDecisions();
    component.loadDecisions();

    second.next(decisionsResponse);
    second.complete();
    expect(component.decisions().length).toBe(1);

    // The superseded read answers last, and is ignored.
    first.error({ status: 500 });

    expect(component.decisions().length).toBe(1);
    expect(component.decisionsError()).toBeFalse();
  });

  it("does not attribute the previous order's history to a new prescription", () => {
    pharmacySvc.listRoutingDecisionsByPrescription.and.returnValue(
      throwError(() => ({ status: 500 })),
    );
    component.prescriptionId = 'rx-1';
    component.checkStock();
    fixture.detectChanges();
    expect(component.decisionsError()).toBeTrue();

    // A different prescription whose own lookup fails: the red panel and the
    // rows belong to rx-1 and must not be read as rx-2's.
    pharmacySvc.checkStock.and.returnValue(throwError(() => ({ status: 500 })));
    component.prescriptionId = 'rx-2';
    component.checkStock();
    fixture.detectChanges();

    expect(component.decisionsError()).toBeFalse();
    expect(component.decisions()).toEqual([]);
  });

  it("cancels the previous order's history read when a new prescription is checked", () => {
    // Clearing the signals is not enough: if rx-1's read is still out when
    // rx-2 is checked, its rows would land under rx-2's id.
    const slow = new Subject<any>();
    pharmacySvc.listRoutingDecisionsByPrescription.and.returnValue(slow);
    component.prescriptionId = 'rx-1';
    component.loadDecisions();

    // rx-2's own stock check fails, so it never starts a history read of its
    // own — the only thing that could clear rx-1's is the cancel.
    pharmacySvc.checkStock.and.returnValue(throwError(() => ({ status: 500 })));
    component.prescriptionId = 'rx-2';
    component.checkStock();
    fixture.detectChanges();

    // rx-1 answers late. It has been cancelled, so nothing of it is rendered.
    slow.next(decisionsResponse);
    slow.complete();

    expect(component.decisions()).toEqual([]);
    expect(component.decisionsError()).toBeFalse();
    expect(component.decisionsLoading()).toBeFalse();
  });

  it('says the history is loading rather than showing no history at all', () => {
    const slow = new Subject<any>();
    pharmacySvc.listRoutingDecisionsByPrescription.and.returnValue(slow);
    component.prescriptionId = 'rx-1';
    component.loadDecisions();
    fixture.detectChanges();

    // Rows are empty and the error is reset while the request is out; without
    // a loading branch the card vanishes and the order reads as never routed.
    expect(
      fixture.nativeElement.querySelector('[data-testid="routing-history-loading"]'),
    ).not.toBeNull();

    slow.next(decisionsResponse);
    slow.complete();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="routing-history-loading"]'),
    ).toBeNull();
  });

  it('does not offer writes the backend will refuse without a hospital', () => {
    // The reads answer in global view now, so the page renders where it used
    // to 500 — and every write behind it still requires a scope. Offering
    // them would turn one honest error into four dead controls.
    hasHospitalScope.set(false);
    component.prescriptionId = 'rx-1';
    component.checkStock();
    fixture.detectChanges();

    const backOrder = fixture.nativeElement.querySelector('[data-testid="open-back-order"]');
    const print = fixture.nativeElement.querySelector('[data-testid="print-for-patient"]');
    expect(isSubmitBlocked(backOrder)).toBeTrue();
    expect(isSubmitBlocked(print)).toBeTrue();
    // "Route here" is the control that starts a routing, and it carries its
    // own disabled condition — a second [disabled] binding silently replaced
    // the gate here once, so it is asserted rather than assumed.
    expect(
      isSubmitBlocked(fixture.nativeElement.querySelector('[data-testid="route-here-partner-1"]')),
    ).toBeTrue();

    hasHospitalScope.set(true);
    fixture.detectChanges();

    expect(
      isSubmitBlocked(fixture.nativeElement.querySelector('[data-testid="open-back-order"]')),
    ).toBeFalse();
    expect(
      isSubmitBlocked(fixture.nativeElement.querySelector('[data-testid="print-for-patient"]')),
    ).toBeFalse();
    expect(
      fixture.nativeElement.querySelector('[data-testid="route-here-partner-1"]').disabled,
    ).toBeFalse();
  });

  it('should return expected badge classes', () => {
    expect(component.statusBadgeClass('PENDING')).toBe('badge-warning');
    expect(component.statusBadgeClass('COMPLETED')).toBe('badge-success');
    expect(component.routingTypeBadgeClass('BACKORDER')).toBe('badge-warning');
    expect(component.routingTypeBadgeClass('PRINT')).toBe('badge-secondary');
  });

  describe('stock status (QA D913)', () => {
    /** Checks `data` for rx-1 and returns the rendered status badge. */
    function statusBadgeFor(data: Record<string, unknown>): HTMLElement {
      pharmacySvc.checkStock.and.returnValue(
        of({ data: { ...stockCheckResponse.data, ...data } } as any),
      );
      component.prescriptionId = 'rx-1';
      component.checkStock();
      fixture.detectChanges();
      return fixture.nativeElement.querySelector('[data-testid="stock-status"]');
    }

    beforeEach(() => {
      const translate = TestBed.inject(TranslateService);
      translate.setTranslation('fr', {
        PHARMACY: {
          IN_STOCK: 'En stock',
          OUT_OF_STOCK: 'En rupture de stock',
          INSUFFICIENT_STOCK: 'Stock insuffisant',
          INSUFFICIENT_STOCK_OF: 'Stock insuffisant ({{onHand}} / {{required}})',
        },
      });
      translate.use('fr');
    });

    it('says insufficient, with on hand and required, when some but not enough is on hand', () => {
      // The re-test: 50 Ocytocine on the shelf for an order of 60.
      const badge = statusBadgeFor({ quantityOnHand: 50, quantityRequired: 60, sufficient: false });

      expect(component.stockStatus()).toBe('INSUFFICIENT');
      expect(badge.textContent?.trim()).toBe('Stock insuffisant (50 / 60)');
      expect(badge.classList).toContain('badge-warning');
      expect(badge.classList).not.toContain('badge-danger');
    });

    it('says insufficient without numbers when the server does not send the required quantity', () => {
      const badge = statusBadgeFor({
        quantityOnHand: 50,
        quantityRequired: undefined,
        sufficient: false,
      });

      expect(badge.textContent?.trim()).toBe('Stock insuffisant');
    });

    it("formats the quantities in the reader's locale (12,5 in French)", async () => {
      registerLocaleData(localeFr, 'fr');
      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [StockRoutingComponent, TranslateModule.forRoot()],
        providers: [
          provideHttpClient(withXhr()),
          provideHttpClientTesting(),
          { provide: PharmacyService, useValue: pharmacySvc },
          { provide: ToastService, useValue: toastSvc },
          { provide: PrintLabelService, useValue: printSvc },
          { provide: RoleContextService, useValue: roleContextStub },
          {
            provide: ActivatedRoute,
            useValue: { snapshot: { paramMap: convertToParamMap({}) } },
          },
          { provide: LOCALE_ID, useValue: 'fr' },
        ],
      }).compileComponents();
      const translate = TestBed.inject(TranslateService);
      translate.setTranslation('fr', {
        PHARMACY: { INSUFFICIENT_STOCK_OF: 'Stock insuffisant ({{onHand}} / {{required}})' },
      });
      translate.use('fr');
      fixture = TestBed.createComponent(StockRoutingComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();

      const badge = statusBadgeFor({
        quantityOnHand: 12.5,
        quantityRequired: 60,
        sufficient: false,
      });

      expect(badge.textContent?.trim()).toBe('Stock insuffisant (12,5 / 60)');
    });

    it('keeps "out of stock" for an empty shelf only', () => {
      const badge = statusBadgeFor({ quantityOnHand: 0, quantityRequired: 60, sufficient: false });

      expect(component.stockStatus()).toBe('OUT_OF_STOCK');
      expect(badge.textContent?.trim()).toBe('En rupture de stock');
      expect(badge.classList).toContain('badge-danger');
    });

    it('says in stock when the shelf covers the order', () => {
      const badge = statusBadgeFor({ quantityOnHand: 300, quantityRequired: 60, sufficient: true });

      expect(component.stockStatus()).toBe('IN_STOCK');
      expect(badge.textContent?.trim()).toBe('En stock');
      expect(badge.classList).toContain('badge-success');
    });
  });

  describe('QA D966: print for the patient produces the copy', () => {
    it('records the decision, then opens the printed copy for printing', () => {
      component.prescriptionId = 'rx-1';
      component.printForPatient();

      expect(pharmacySvc.printForPatient).toHaveBeenCalledWith('rx-1');
      expect(pharmacySvc.getPrintedPrescriptionCopy).toHaveBeenCalledWith('rx-1');
      expect(printSvc.openForPrint).toHaveBeenCalled();
    });

    it('says so when the copy cannot be opened, instead of a silent "printed"', () => {
      pharmacySvc.getPrintedPrescriptionCopy.and.returnValue(throwError(() => new Error('x')));
      component.prescriptionId = 'rx-1';
      component.printForPatient();

      expect(toastSvc.error).toHaveBeenCalledWith('PHARMACY.PRINT_COPY_FAILED');
    });

    it('a printed order offers its copy again, not the routing buttons that would fail', () => {
      pharmacySvc.checkStock.and.returnValue(
        of({
          data: {
            ...stockCheckResponse.data,
            prescriptionStatus: 'PRINTED_FOR_PATIENT',
            routable: false,
          },
        } as any),
      );
      component.prescriptionId = 'rx-1';
      component.checkStock();
      fixture.detectChanges();
      const el: HTMLElement = fixture.nativeElement;

      expect(el.querySelector('[data-testid="print-for-patient"]')).toBeNull();
      expect(el.querySelector('[data-testid="open-back-order"]')).toBeNull();
      (el.querySelector('[data-testid="reprint-copy"]') as HTMLButtonElement).click();
      expect(printSvc.openForPrint).toHaveBeenCalled();
    });
  });
});
