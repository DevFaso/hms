import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WaitlistPanelComponent } from './waitlist-panel.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ReceptionService, WaitlistEntryResponse } from '../reception.service';
import { SlotInventoryService } from '../../services/slot-inventory.service';
import { PatientService } from '../../services/patient.service';
import { ReferralService } from '../../services/referral.service';
import { StaffService } from '../../services/staff.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { of, throwError } from 'rxjs';

function entry(overrides: Partial<WaitlistEntryResponse> = {}): WaitlistEntryResponse {
  return {
    id: 'w1',
    hospitalId: 'h1',
    patientId: 'p1',
    patientName: 'John Doe',
    mrn: null,
    departmentId: 'd1',
    departmentName: 'Cardiology',
    preferredProviderId: null,
    preferredProviderName: null,
    requestedDateFrom: null,
    requestedDateTo: null,
    priority: 'ROUTINE',
    reason: 'Back pain',
    status: 'WAITING',
    offeredAppointmentId: null,
    offeredSlotId: null,
    offeredSlotStartAt: null,
    offeredAt: null,
    offerExpiresAt: null,
    createdAt: '2026-08-20T09:00:00',
    createdBy: 'reception1',
    ...overrides,
  };
}

describe('WaitlistPanelComponent', () => {
  let component: WaitlistPanelComponent;
  let fixture: ComponentFixture<WaitlistPanelComponent>;
  let mockReceptionService: {
    getWaitlist: jasmine.Spy;
    addToWaitlist: jasmine.Spy;
    offerWaitlistSlot: jasmine.Spy;
    acceptWaitlistOffer: jasmine.Spy;
    declineWaitlistOffer: jasmine.Spy;
    closeWaitlistEntry: jasmine.Spy;
  };
  let mockSlotInventoryService: { searchOpen: jasmine.Spy };
  let mockToastService: { success: jasmine.Spy; error: jasmine.Spy };

  const mockPatientService = { list: () => of([]) };
  const mockReferralService = { getDepartmentsByHospital: () => of([]) };
  const mockStaffService = { list: () => of([]) };
  const mockRoleCtx = { activeHospitalId: 'h1' };

  beforeEach(async () => {
    mockReceptionService = {
      getWaitlist: jasmine.createSpy('getWaitlist').and.returnValue(of([])),
      addToWaitlist: jasmine.createSpy('addToWaitlist').and.returnValue(of({})),
      offerWaitlistSlot: jasmine.createSpy('offerWaitlistSlot').and.returnValue(of(entry())),
      acceptWaitlistOffer: jasmine.createSpy('acceptWaitlistOffer').and.returnValue(of(entry())),
      declineWaitlistOffer: jasmine.createSpy('declineWaitlistOffer').and.returnValue(of(entry())),
      closeWaitlistEntry: jasmine.createSpy('closeWaitlistEntry').and.returnValue(of(undefined)),
    };
    mockSlotInventoryService = {
      searchOpen: jasmine.createSpy('searchOpen').and.returnValue(of([])),
    };
    mockToastService = {
      success: jasmine.createSpy('success'),
      error: jasmine.createSpy('error'),
    };

    await TestBed.configureTestingModule({
      imports: [WaitlistPanelComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ReceptionService, useValue: mockReceptionService },
        { provide: SlotInventoryService, useValue: mockSlotInventoryService },
        { provide: PatientService, useValue: mockPatientService },
        { provide: ReferralService, useValue: mockReferralService },
        { provide: StaffService, useValue: mockStaffService },
        { provide: RoleContextService, useValue: mockRoleCtx },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(WaitlistPanelComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load entries on init', () => {
    expect(component.entries()).toEqual([]);
    expect(component.loading()).toBe(false);
  });

  it('should default status filter to WAITING', () => {
    expect(component.statusFilter()).toBe('WAITING');
  });

  it('should open add form', () => {
    component.openAddForm();
    expect(component.showAddForm()).toBe(true);
    expect(component.selectedPatient()).toBeNull();
  });

  it('should select patient', () => {
    const patient = { id: 'p1', firstName: 'John', lastName: 'Doe' } as any;
    component.selectPatient(patient);
    expect(component.selectedPatient()).toEqual(patient);
    expect(component.patientQuery()).toBe('John Doe');
  });

  describe('offer-a-slot modal (P3 #22)', () => {
    it('loads matching open slots when the modal opens', () => {
      const waiting = entry({ preferredProviderId: 's1', requestedDateFrom: '2026-09-01' });

      component.openOfferModal(waiting);

      expect(component.offerEntry()).toEqual(waiting);
      expect(mockSlotInventoryService.searchOpen).toHaveBeenCalledWith({
        departmentId: 'd1',
        staffId: 's1',
        from: '2026-09-01',
        to: undefined,
        limit: 50,
      });
    });

    it('refuses to submit without a selected slot', () => {
      component.openOfferModal(entry());
      component.submitOffer();

      expect(mockReceptionService.offerWaitlistSlot).not.toHaveBeenCalled();
      expect(mockToastService.error).toHaveBeenCalled();
    });

    it('submits the offer and closes the modal', () => {
      component.openOfferModal(entry());
      component.selectedSlotId.set('slot-1');
      component.offerHours.set(24);

      component.submitOffer();

      expect(mockReceptionService.offerWaitlistSlot).toHaveBeenCalledWith('w1', 'slot-1', 24);
      expect(component.offerEntry()).toBeNull();
      expect(mockToastService.success).toHaveBeenCalled();
    });

    it('surfaces the backend refusal verbatim when the offer fails', () => {
      mockReceptionService.offerWaitlistSlot.and.returnValue(
        throwError(() => ({ error: { message: 'That slot is no longer available.' } })),
      );
      component.openOfferModal(entry());
      component.selectedSlotId.set('slot-1');

      component.submitOffer();

      expect(mockToastService.error).toHaveBeenCalledWith('That slot is no longer available.');
      expect(component.offering()).toBe(false);
    });
  });

  describe('accept / decline offers', () => {
    it('accepts an offer and reloads the list', () => {
      const offered = entry({ status: 'OFFERED', offeredSlotId: 'slot-1' });

      component.acceptOffer(offered);

      expect(mockReceptionService.acceptWaitlistOffer).toHaveBeenCalledWith('w1');
      expect(mockToastService.success).toHaveBeenCalled();
      expect(component.actingOnEntryId()).toBeNull();
    });

    it('declines an offer', () => {
      component.declineOffer(entry({ status: 'OFFERED' }));

      expect(mockReceptionService.declineWaitlistOffer).toHaveBeenCalledWith('w1');
      expect(mockToastService.success).toHaveBeenCalled();
    });

    it('guards against double submission while a call is in flight', () => {
      component.actingOnEntryId.set('other');

      component.acceptOffer(entry({ status: 'OFFERED' }));

      expect(mockReceptionService.acceptWaitlistOffer).not.toHaveBeenCalled();
    });

    it('flags an expired offer', () => {
      expect(component.offerExpired(entry({ offerExpiresAt: '2000-01-01T00:00:00' }))).toBe(true);
      expect(component.offerExpired(entry({ offerExpiresAt: '2999-01-01T00:00:00' }))).toBe(false);
      expect(component.offerExpired(entry())).toBe(false);
    });
  });
});
