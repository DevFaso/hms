import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ToastService } from '../core/toast.service';
import { PharmacyService } from '../services/pharmacy.service';
import { StockRoutingComponent } from './stock-routing';

describe('StockRoutingComponent', () => {
  let component: StockRoutingComponent;
  let fixture: ComponentFixture<StockRoutingComponent>;
  let pharmacySvc: jasmine.SpyObj<PharmacyService>;
  let toastSvc: jasmine.SpyObj<ToastService>;

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
    ]);
    toastSvc = jasmine.createSpyObj('ToastService', ['success', 'error']);

    pharmacySvc.checkStock.and.returnValue(of(stockCheckResponse as any));
    pharmacySvc.listRoutingDecisionsByPrescription.and.returnValue(of(decisionsResponse as any));
    pharmacySvc.routeToPartner.and.returnValue(of({ data: { id: 'dec-1' } } as any));
    pharmacySvc.printForPatient.and.returnValue(of({ data: { id: 'dec-2' } } as any));
    pharmacySvc.backOrder.and.returnValue(of({ data: { id: 'dec-3' } } as any));
    pharmacySvc.partnerRespond.and.returnValue(of({ data: { id: 'dec-1' } } as any));
    pharmacySvc.confirmPartnerDispense.and.returnValue(of({ data: { id: 'dec-1' } } as any));

    await TestBed.configureTestingModule({
      imports: [StockRoutingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PharmacyService, useValue: pharmacySvc },
        { provide: ToastService, useValue: toastSvc },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StockRoutingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load stock result and routing history', () => {
    component.prescriptionId = 'rx-1';

    component.checkStock();

    expect(pharmacySvc.checkStock).toHaveBeenCalledWith('rx-1');
    expect(pharmacySvc.listRoutingDecisionsByPrescription).toHaveBeenCalledWith('rx-1', 0, 10);
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
      throwError(() => ({ error: { message: 'Stock lookup failed' } })),
    );
    component.prescriptionId = 'rx-1';

    component.checkStock();

    expect(toastSvc.error).toHaveBeenCalledWith('Stock lookup failed');
    expect(component.checking()).toBeFalse();
  });

  it('should return expected badge classes', () => {
    expect(component.statusBadgeClass('PENDING')).toBe('badge-warning');
    expect(component.statusBadgeClass('COMPLETED')).toBe('badge-success');
    expect(component.routingTypeBadgeClass('BACKORDER')).toBe('badge-warning');
    expect(component.routingTypeBadgeClass('PRINT')).toBe('badge-secondary');
  });
});
