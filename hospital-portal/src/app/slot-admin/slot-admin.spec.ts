import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { SlotAdminComponent } from './slot-admin';
import {
  AppointmentSlotResponse,
  SessionTemplateResponse,
  SlotInventoryService,
  VisitTypeResponse,
} from '../services/slot-inventory.service';
import { StaffService } from '../services/staff.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';

/**
 * Slot-inventory administration (P2 #11) — the populator without which
 * generate() answered slotsCreated=0 forever and search always returned [].
 */
describe('SlotAdminComponent', () => {
  let fixture: ComponentFixture<SlotAdminComponent>;
  let component: SlotAdminComponent;
  let slotService: jasmine.SpyObj<SlotInventoryService>;
  let toast: jasmine.SpyObj<ToastService>;

  function visitType(overrides: Partial<VisitTypeResponse>): VisitTypeResponse {
    return {
      id: 'vt-1',
      code: 'NEW_CONSULT',
      name: 'New consultation',
      durationMinutes: 30,
      patientBookable: false,
      active: true,
      ...overrides,
    };
  }

  function template(overrides: Partial<SessionTemplateResponse>): SessionTemplateResponse {
    return {
      id: 'tpl-1',
      staffId: 's-1',
      staffName: 'Dr. Awa Traoré',
      departmentId: 'd-1',
      departmentName: 'Cardiology',
      dayOfWeek: 1,
      startTime: '09:00',
      endTime: '12:00',
      slotMinutes: 20,
      effectiveFrom: '2026-09-01',
      active: true,
      ...overrides,
    };
  }

  function slot(overrides: Partial<AppointmentSlotResponse>): AppointmentSlotResponse {
    return {
      id: 'slot-1',
      staffName: 'Dr. Awa Traoré',
      slotDate: '2026-09-01',
      startAt: '2026-09-01T09:00:00',
      endAt: '2026-09-01T09:20:00',
      status: 'OPEN',
      ...overrides,
    };
  }

  function setup(visitTypes: VisitTypeResponse[] = []) {
    slotService = jasmine.createSpyObj<SlotInventoryService>('SlotInventoryService', [
      'listVisitTypes',
      'createVisitType',
      'updateVisitType',
      'deactivateVisitType',
      'reactivateVisitType',
      'listTemplates',
      'createTemplate',
      'updateTemplate',
      'deactivateTemplate',
      'reactivateTemplate',
      'generate',
      'searchOpen',
      'block',
      'release',
      'listDepartments',
    ]);
    slotService.listVisitTypes.and.returnValue(of(visitTypes));
    slotService.listTemplates.and.returnValue(of([]));
    slotService.searchOpen.and.returnValue(of([]));
    slotService.listDepartments.and.returnValue(of([]));

    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(of([]));

    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['getHospitalId']);
    auth.getHospitalId.and.returnValue('h-1');

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    TestBed.configureTestingModule({
      imports: [SlotAdminComponent, TranslateModule.forRoot()],
      providers: [
        { provide: SlotInventoryService, useValue: slotService },
        { provide: StaffService, useValue: staffService },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: { activeHospitalId: 'h-1', hasAnyActiveRole: () => true },
        },
      ],
    });

    fixture = TestBed.createComponent(SlotAdminComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('loads the visit-type catalog first — the root of the whole chain', () => {
    setup([visitType({ id: 'vt-1' })]);

    expect(slotService.listVisitTypes).toHaveBeenCalledWith(false);
    expect(fixture.nativeElement.querySelector('[data-testid="vt-row-vt-1"]')).not.toBeNull();
  });

  it('refuses a visit type without code or name before any request', () => {
    setup();
    component.vtForm = { code: '  ', name: '', durationMinutes: 30 };

    component.submitVisitType();

    expect(toast.error).toHaveBeenCalled();
    expect(slotService.createVisitType).not.toHaveBeenCalled();
  });

  it('surfaces the retired-code refusal verbatim so the admin knows to reactivate', () => {
    setup();
    const backendMessage =
      "A visit type with code 'NEW_CONSULT' exists but was retired; reactivate it instead of re-creating it.";
    slotService.createVisitType.and.returnValue(
      throwError(() => ({ error: { message: backendMessage } }) as unknown),
    );
    component.vtForm = { code: 'NEW_CONSULT', name: 'New consultation', durationMinutes: 30 };

    component.submitVisitType();

    expect(toast.error).toHaveBeenCalledWith(backendMessage);
  });

  it('shows a restore toggle instead of edit on retired visit types', () => {
    setup([visitType({ id: 'vt-ret', active: false })]);

    expect(fixture.nativeElement.querySelector('[data-testid="vt-edit-vt-ret"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="vt-toggle-vt-ret"]')).not.toBeNull();
  });

  it('refuses a session template whose window ends before it starts', () => {
    setup();
    component.tplForm = {
      staffId: 's-1',
      departmentId: 'd-1',
      dayOfWeek: 1,
      startTime: '12:00',
      endTime: '09:00',
      slotMinutes: 20,
      effectiveFrom: '2026-09-01',
    };

    component.submitTemplate();

    expect(toast.error).toHaveBeenCalled();
    expect(slotService.createTemplate).not.toHaveBeenCalled();
  });

  it('creates a template and reloads the rota', () => {
    setup();
    slotService.createTemplate.and.returnValue(of(template({})));
    component.section.set('templates');
    component.tplForm = {
      staffId: 's-1',
      departmentId: 'd-1',
      dayOfWeek: 1,
      startTime: '09:00',
      endTime: '12:00',
      slotMinutes: 20,
      effectiveFrom: '2026-09-01',
    };

    component.submitTemplate();

    expect(slotService.createTemplate).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalled();
    expect(slotService.listTemplates).toHaveBeenCalled();
  });

  it('runs generation and reports what it produced', () => {
    setup();
    slotService.generate.and.returnValue(
      of({
        from: '2026-09-01',
        to: '2026-09-28',
        templatesApplied: 2,
        slotsCreated: 54,
        skippedExisting: 6,
      }),
    );

    component.runGeneration();

    expect(slotService.generate).toHaveBeenCalled();
    expect(component.lastGeneration()?.slotsCreated).toBe(54);
    expect(toast.success).toHaveBeenCalled();
  });

  it('blocks an open slot only through the confirm modal, sending the reason', () => {
    const open = slot({ id: 'slot-1' });
    setup();
    slotService.searchOpen.and.returnValue(of([open]));
    slotService.block.and.returnValue(of({ ...open, status: 'BLOCKED' }));
    component.setSection('slots');
    fixture.detectChanges();

    component.confirmBlock(open);
    component.blockReason = 'Clinic cancelled';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="slot-block-modal"]')).not.toBeNull();

    component.executeBlock();

    expect(slotService.block).toHaveBeenCalledWith('slot-1', 'Clinic cancelled');
  });

  it('releases a blocked slot back to open', () => {
    const blocked = slot({ id: 'slot-2', status: 'BLOCKED' });
    setup();
    slotService.release.and.returnValue(of({ ...blocked, status: 'OPEN' }));

    component.release(blocked);

    expect(slotService.release).toHaveBeenCalledWith('slot-2');
  });
});
