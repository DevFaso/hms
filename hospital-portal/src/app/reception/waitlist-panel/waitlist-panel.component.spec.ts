import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { WaitlistPanelComponent } from './waitlist-panel.component';
import { SUBLINE_AWA, SUBLINE_FATIMATA } from '../../testing/patient-subline.fixtures';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
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
  const mockRoleCtx = { activeHospitalId: 'h1', effectiveHospitalIdForRequest: () => 'h1' };

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
        provideHttpClient(withXhr()),
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

  // QA D912: two patients with the same name were told apart by nothing but a
  // birth date; each search result now carries this hospital's MRN.
  it('names each search result by MRN (QA D912)', () => {
    component.openAddForm();
    component.patientResults.set([SUBLINE_AWA, SUBLINE_FATIMATA]);
    fixture.detectChanges();
    const hints = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.autocomplete-list .mrn-hint'),
    ).map((h) => h.textContent?.trim());
    expect(hints).toEqual(['· CHW2572', '· CHW1444']);
  });

  describe('QA D1251: the provider suggestions', () => {
    const aminata = { id: 's1', name: 'Aminata Ouédraogo' } as never;
    const amidou = { id: 's2', name: 'Amidou Sawadogo' } as never;
    const listbox = () => (fixture.nativeElement as HTMLElement).querySelector('#wl-staff-listbox');

    beforeEach(() => {
      component.allStaff.set([aminata, amidou]);
      component.openAddForm();
      component.onStaffQueryChange('Ami');
      fixture.detectChanges();
    });

    it('close once a provider is picked, and focus moves to the date range', fakeAsync(() => {
      const el = fixture.nativeElement as HTMLElement;
      document.body.appendChild(el);
      expect(listbox()).not.toBeNull();
      component.pickStaff(aminata);
      fixture.detectChanges();
      tick();
      expect(listbox()).toBeNull();
      expect(component.selectedStaff()).toBe(aminata);
      expect(document.activeElement?.id).toBe('wl-from');
      el.remove();
    }));

    it('answer the keyboard: ArrowDown activates, Enter picks, Escape closes', () => {
      const key = (k: string) => new KeyboardEvent('keydown', { key: k, cancelable: true });
      component.onStaffKeydown(key('ArrowDown'));
      expect(component.staffActive()).toBe(0);
      fixture.detectChanges();
      const input = (fixture.nativeElement as HTMLElement).querySelector('#wl-staff')!;
      expect(input.getAttribute('aria-expanded')).toBe('true');
      expect(input.getAttribute('aria-activedescendant')).toBe('wl-staff-option-0');
      component.onStaffKeydown(key('Enter'));
      expect(component.selectedStaff()).toBe(aminata);
      expect(component.staffListOpen()).toBeFalse();

      component.onStaffQueryChange('Ami');
      expect(component.selectedStaff()).toBeNull();
      expect(component.staffListOpen()).toBeTrue();
      component.onStaffKeydown(key('Escape'));
      expect(component.staffListOpen()).toBeFalse();
    });

    it('QA D1259: Escape over the open provider list never reaches the dialog', () => {
      const el = fixture.nativeElement as HTMLElement;
      const input = el.querySelector('#wl-staff')!;
      const reachedDialog = jasmine.createSpy('dialog keydown');
      input.parentElement!.addEventListener('keydown', reachedDialog);
      input.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
      expect(component.staffListOpen()).toBeFalse();
      expect(reachedDialog).not.toHaveBeenCalled();
      // With the list closed, the next Escape goes on (the dialog closes as before).
      input.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
      expect(reachedDialog).toHaveBeenCalledTimes(1);
    });
  });

  it('typing another name drops the patient picked before', () => {
    const patient = { id: 'p1', firstName: 'John', lastName: 'Doe' } as any;
    component.selectPatient(patient);
    component.onPatientQueryChange('Jane');
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

    it("QA D1270: after the offer, focus goes to the row's new first action", fakeAsync(() => {
      const el = fixture.nativeElement as HTMLElement;
      document.body.appendChild(el);
      mockReceptionService.getWaitlist.and.returnValue(
        of([entry({ status: 'OFFERED', offeredSlotId: 'slot-1' })]),
      );
      component.openOfferModal(entry());
      component.selectedSlotId.set('slot-1');
      component.submitOffer();
      fixture.detectChanges();
      tick();
      const row = el.querySelector('tr[data-entry-id="w1"]')!;
      expect(document.activeElement).toBe(row.querySelector('.actions-cell button'));
      el.remove();
    }));

    it('QA D1270: a row that left the filtered list hands focus to the active filter', fakeAsync(() => {
      const el = fixture.nativeElement as HTMLElement;
      document.body.appendChild(el);
      mockReceptionService.getWaitlist.and.returnValue(of([]));
      component.openOfferModal(entry());
      component.selectedSlotId.set('slot-1');
      component.submitOffer();
      fixture.detectChanges();
      tick();
      expect(document.activeElement?.classList.contains('filter-btn')).toBeTrue();
      el.remove();
    }));

    it('surfaces the backend refusal verbatim when the offer fails', () => {
      mockReceptionService.offerWaitlistSlot.and.returnValue(
        throwError(() => ({
          status: 400,
          error: { message: 'That slot is no longer available.' },
        })),
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

  it('QA D1157: "Fermer" asks for a reason in a dialog before closing the entry', fakeAsync(() => {
    mockReceptionService.getWaitlist.and.returnValue(of([entry({ id: 'w9', status: 'WAITING' })]));
    component.loadEntries();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
    try {
      const opener = Array.from(host.querySelectorAll<HTMLButtonElement>('td button')).find((b) =>
        b.textContent?.includes('COMMON.CLOSE'),
      )!;
      opener.focus();
      opener.click();
      fixture.detectChanges();
      tick();
      expect(mockReceptionService.closeWaitlistEntry).not.toHaveBeenCalled();
      const dialog = host.querySelector<HTMLElement>('[data-testid="reason-dialog"]')!;
      expect(dialog.contains(document.activeElement)).toBeTrue();

      host.querySelector<HTMLButtonElement>('[data-testid="reason-dialog-confirm"]')!.click();
      fixture.detectChanges();
      expect(mockReceptionService.closeWaitlistEntry).not.toHaveBeenCalled();

      const reason = host.querySelector<HTMLTextAreaElement>(
        '[data-testid="reason-dialog-reason"]',
      )!;
      reason.value = 'Patient vu en urgence';
      reason.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      host.querySelector<HTMLButtonElement>('[data-testid="reason-dialog-confirm"]')!.click();
      fixture.detectChanges();
      tick();
      expect(mockReceptionService.closeWaitlistEntry).toHaveBeenCalledWith(
        'w9',
        'Patient vu en urgence',
      );
      expect(host.querySelector('[data-testid="reason-dialog"]')).toBeNull();
      expect(document.activeElement?.classList.contains('filter-btn')).toBeTrue();
    } finally {
      host.remove();
    }
  }));

  /**
   * QA D1215 (round 23): an entry "from 20/11 to 10/11" was saved (201) and
   * could never be offered a slot. Each end of the range bounds the other in
   * the picker, and a typed reversed range is refused on the end field.
   */
  it('QA D1215: the range ends bound each other and a reversed range is refused on the field', () => {
    // The window is in the future of the pinned day (the past-end rule below).
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(2026, 10, 1, 9, 0));
    component.openAddForm();
    component.selectPatient({ id: 'p1', firstName: 'Hamed', lastName: 'Tiendrebeogo' } as any);
    component.selectedDeptId.set('d1');
    component.dateFrom.set('2026-11-20');
    component.dateTo.set('2026-11-10');
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    const from = host.querySelector<HTMLInputElement>('#wl-from')!;
    const to = host.querySelector<HTMLInputElement>('#wl-to')!;
    expect(from.getAttribute('max')).toBe('2026-11-10');
    expect(to.getAttribute('min')).toBe('2026-11-20');

    component.submitAdd();
    fixture.detectChanges();

    expect(mockReceptionService.addToWaitlist).not.toHaveBeenCalled();
    expect(mockToastService.error).toHaveBeenCalledWith('DATE_RULES.END_BEFORE_START');
    expect(to.getAttribute('aria-invalid')).toBe('true');
    expect(host.querySelector('#wl-to-invalid-msg')?.textContent).toBe(
      'DATE_RULES.END_BEFORE_START',
    );
    expect(document.activeElement).toBe(to);

    // A one-day window (start = end) is a valid range.
    component.dateTo.set('2026-11-20');
    component.submitAdd();
    fixture.detectChanges();
    expect(mockReceptionService.addToWaitlist).toHaveBeenCalledTimes(1);
    jasmine.clock().uninstall();
  });

  /**
   * QA D1215 (round 23, portal half of #1011): the server now also refuses a
   * window that ends before today (`waitlist.dateRange.past`). The end picker
   * starts at today, and a typed past end is refused on the field before any
   * request is sent.
   */
  it('QA D1215: a window that already ended is refused on the end field, before the server refuses it', () => {
    jasmine.clock().install();
    try {
      jasmine.clock().mockDate(new Date(2026, 10, 1, 9, 0));
      component.openAddForm();
      component.selectPatient({ id: 'p1', firstName: 'Hamed', lastName: 'Tiendrebeogo' } as any);
      component.selectedDeptId.set('d1');
      fixture.detectChanges();
      const host: HTMLElement = fixture.nativeElement;
      const to = host.querySelector<HTMLInputElement>('#wl-to')!;
      expect(to.getAttribute('min')).withContext('the end picker starts today').toBe('2026-11-01');

      component.dateTo.set('2026-10-20');
      component.submitAdd();
      fixture.detectChanges();
      expect(mockReceptionService.addToWaitlist).not.toHaveBeenCalled();
      expect(mockToastService.error).toHaveBeenCalledWith('DATE_RULES.NOT_IN_PAST');
      expect(to.getAttribute('aria-invalid')).toBe('true');

      component.dateTo.set('2026-11-01');
      component.submitAdd();
      expect(mockReceptionService.addToWaitlist).toHaveBeenCalledTimes(1);
    } finally {
      jasmine.clock().uninstall();
    }
  });

  describe('round 29 (QA D1323 / D1324 / D1325)', () => {
    const page = (): HTMLElement => fixture.nativeElement as HTMLElement;

    it('QA D1324: "Tous" lists every status, merged oldest first', () => {
      mockReceptionService.getWaitlist.and.callFake((opts?: { status?: string }) =>
        of(
          opts?.status === 'OFFERED'
            ? [entry({ id: 'o', status: 'OFFERED', createdAt: '2026-08-21T09:00:00' })]
            : opts?.status === 'CLOSED'
              ? [entry({ id: 'c', status: 'CLOSED', createdAt: '2026-08-19T09:00:00' })]
              : [entry({ id: 'w', createdAt: '2026-08-20T09:00:00' })],
        ),
      );
      component.statusFilter.set('ALL');
      component.loadEntries();
      const asked = mockReceptionService.getWaitlist.calls.allArgs().map((a) => a[0]?.status);
      expect(asked).toEqual(jasmine.arrayContaining(['WAITING', 'OFFERED', 'CLOSED']));
      expect(component.entries().map((e) => e.id)).toEqual(['c', 'w', 'o']);
    });

    it('QA D1323: a priority outside the dictionary shows as sent, a blank one "—", never the raw key', () => {
      mockReceptionService.getWaitlist.and.returnValue(
        of([entry({ id: 'a', priority: 'URGENTISSIME' }), entry({ id: 'b', priority: '' })]),
      );
      component.loadEntries();
      fixture.detectChanges();
      const text = page().textContent ?? '';
      expect(text).not.toContain('RECEPTION.URGENTISSIME');
      expect(text).toContain('URGENTISSIME');
      expect(text).not.toMatch(/RECEPTION\.(\s|$)/);
    });

    it('QA D1325: offering with no slot picked marks the slot group and moves focus to it', fakeAsync(() => {
      document.body.appendChild(page());
      try {
        mockSlotInventoryService.searchOpen.and.returnValue(
          of([{ id: 'slot-1', startAt: '2026-11-05T09:00:00', staffName: 'Dr X' }]),
        );
        component.openOfferModal(entry());
        fixture.detectChanges();
        tick();
        const offer = Array.from(page().querySelectorAll<HTMLButtonElement>('.modal-footer .btn-primary')).find(
          (b) => (b.textContent ?? '').includes('RECEPTION.OFFER_SLOT'),
        )!;
        offer.click();
        fixture.detectChanges();
        tick();
        const group = page().querySelector<HTMLElement>('#waitlist-offer-slots')!;
        expect(group.getAttribute('aria-invalid')).toBe('true');
        expect(group.getAttribute('aria-describedby')).toContain('waitlist-offer-slots-invalid-msg');
        expect(document.activeElement).toBe(group);
        expect(mockReceptionService.offerWaitlistSlot).not.toHaveBeenCalled();
      } finally {
        page().remove();
      }
    }));
  });
});
