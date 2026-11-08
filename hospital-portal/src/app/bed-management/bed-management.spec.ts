import { HospitalService } from '../services/hospital.service';
import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError, Subject } from 'rxjs';

import { BedManagementComponent } from './bed-management';
import { BedService, WardResponse, BedResponse } from '../services/bed.service';
import { ToastService } from '../core/toast.service';
import { DepartmentLookupService } from '../services/department-lookup.service';
import { RoleContextService } from '../core/role-context.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';

function ward(id: string, code: string, totalBeds = 0): WardResponse {
  return {
    id,
    hospitalId: 'h1',
    name: 'Ward ' + code,
    code,
    wardType: 'GENERAL',
    floor: 1,
    description: null,
    departmentId: null,
    departmentName: null,
    active: true,
    totalBeds,
    availableBeds: totalBeds,
    occupiedBeds: 0,
  };
}

function bed(
  id: string,
  bedNumber: string,
  status: BedResponse['status'] = 'AVAILABLE',
): BedResponse {
  return {
    id,
    wardId: 'w1',
    wardName: 'Ward MAT01',
    wardCode: 'MAT01',
    bedNumber,
    label: 'MAT01/' + bedNumber,
    status,
    bedType: null,
    floor: 1,
    roomNumber: null,
    notes: null,
    active: true,
  };
}

describe('BedManagementComponent', () => {
  let component: BedManagementComponent;
  let bedServiceSpy: jasmine.SpyObj<BedService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let deptSpy: jasmine.SpyObj<DepartmentLookupService>;

  beforeEach(async () => {
    bedServiceSpy = jasmine.createSpyObj('BedService', [
      'getWards',
      'createWard',
      'updateWard',
      'deleteWard',
      'getBeds',
      'getAvailableBeds',
      'createBed',
      'updateBed',
      'updateBedStatus',
      'deleteBed',
    ]);
    bedServiceSpy.getWards.and.returnValue(of([]));
    bedServiceSpy.getBeds.and.returnValue(of([]));
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error', 'info', 'warn']);
    deptSpy = jasmine.createSpyObj('DepartmentLookupService', ['getActiveDepartments']);
    deptSpy.getActiveDepartments.and.returnValue(of([{ id: 'd1', name: 'Médecine' }]));

    await TestBed.configureTestingModule({
      imports: [BedManagementComponent, TranslateModule.forRoot()],
      providers: [
        { provide: BedService, useValue: bedServiceSpy },
        { provide: HospitalService, useValue: { list: () => of([]) } },
        { provide: ToastService, useValue: toastSpy },
        { provide: DepartmentLookupService, useValue: deptSpy },
        {
          provide: RoleContextService,
          useValue: {
            activeHospitalId: 'h1',
            effectiveHospitalIdForRequest: () => 'h1',
            isSuperAdmin: () => false,
            globalView: () => false,
          },
        },
      ],
    }).compileComponents();

    component = TestBed.createComponent(BedManagementComponent).componentInstance;
  });

  function network(): void {
    const ctx = TestBed.inject(RoleContextService);
    spyOn(ctx, 'isSuperAdmin').and.returnValue(true);
    spyOn(ctx, 'globalView').and.returnValue(true);
    spyOn(ctx, 'effectiveHospitalIdForRequest').and.returnValue(null);
  }
  it('shows wards across hospitals and finds them by hospital name without changing scope', () => {
    network();
    const rows = [ward('w1', 'MED'), { ...ward('w2', 'MED'), hospitalId: 'h2' }];
    bedServiceSpy.getWards.and.returnValue(of(rows));
    component.ngOnInit();
    component.hospitals.set([
      { id: 'h1', name: 'Cdx North' },
      { id: 'h2', name: 'Cdx South' },
    ] as any);
    component.wardSearch.set('south');
    expect(component.filteredWards().map((w) => w.id)).toEqual(['w2']);
    expect(component.isNetworkView()).toBeTrue();
    expect(component.wards().length).toBe(2);
  });
  it('edits the row hospital and loads that hospitals departments, preserving global mode', () => {
    network();
    bedServiceSpy.updateWard.and.returnValue(of(ward('w2', 'MED')));
    component.openEditWard({ ...ward('w2', 'MED'), hospitalId: 'h2' });
    component.submitWard();
    expect(deptSpy.getActiveDepartments).toHaveBeenCalledWith('h2');
    expect(bedServiceSpy.updateWard).toHaveBeenCalledWith('w2', jasmine.any(Object), 'h2');
    expect(component.isNetworkView()).toBeTrue();
  });
  it('requires a target for global creation instead of silently using the accounts home hospital', () => {
    network();
    component.openCreateWard();
    component.wardForm = { name: 'Cdx New', code: 'NEW', wardType: 'GENERAL' };
    component.submitWard();
    expect(bedServiceSpy.createWard).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith('BEDS_NETWORK.TARGET_REQUIRED');
    bedServiceSpy.createWard.and.returnValue(of(ward('w3', 'NEW')));
    component.chooseWardHospital({ id: 'h2', name: 'Cdx South' } as any);
    component.submitWard();
    expect(bedServiceSpy.createWard).toHaveBeenCalledWith(jasmine.any(Object), 'h2');
  });
  it('keeps the target of a bed form fixed when another ward becomes selected', () => {
    network();
    const w1 = ward('w1', 'MED');
    const w2 = { ...ward('w2', 'MED'), hospitalId: 'h2' };
    component.wards.set([w1, w2]);
    component.selectWard(w1);
    component.openCreateBed();
    component.bedForm.bedNumber = 'CDX-1';
    component.selectWard(w2);
    bedServiceSpy.createBed.and.returnValue(of(bed('b1', 'CDX-1')));
    component.submitBed();
    expect(bedServiceSpy.createBed).toHaveBeenCalledWith('w1', jasmine.any(Object), 'h1');
  });
  it('refuses a bed status action from a different selected ward', () => {
    network();
    component.wards.set([{ ...ward('w2', 'MED'), hospitalId: 'h2' }]);
    component.selectedWardId.set('w2');
    component.setBedStatus(bed('b1', 'B1'), 'MAINTENANCE');
    expect(bedServiceSpy.updateBedStatus).not.toHaveBeenCalled();
  });
  it('does not let a late department list replace a newly chosen hospitals departments', () => {
    network();
    const late = new Subject<any>();
    deptSpy.getActiveDepartments.and.returnValues(late, of([{ id: 'south', name: 'Cdx South' }]));
    component.chooseWardHospital({ id: 'h1', name: 'North' } as any);
    component.chooseWardHospital({ id: 'h2', name: 'South' } as any);
    late.next([{ id: 'north', name: 'Cdx North' }]);
    expect(component.departments().map((d) => d.id)).toEqual(['south']);
  });

  it('should create and load wards on init', () => {
    bedServiceSpy.getWards.and.returnValue(of([ward('w1', 'MAT01', 4)]));
    component.ngOnInit();
    expect(component.wards().length).toBe(1);
    expect(bedServiceSpy.getWards).toHaveBeenCalledWith(false);
  });

  it('shows recoverable no-match states for ward and bed filters, preserving genuine empty states', () => {
    bedServiceSpy.getWards.and.returnValue(of([ward('w1', 'MED')]));
    bedServiceSpy.getBeds.and.returnValue(of([bed('b1', 'CDX-1')]));
    const fixture = TestBed.createComponent(BedManagementComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.wardSearch.set('missing');
    fixture.detectChanges();
    let empty = fixture.nativeElement.querySelector('.empty-state');
    expect(empty.textContent).toContain('BEDS.NO_MATCHES');
    expect(empty.textContent).not.toContain('BEDS.NO_WARDS');
    empty.querySelector('button').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(1);
    c.selectWard(c.wards()[0]);
    c.bedSearch.set('missing');
    c.bedFilter.set('OCCUPIED');
    fixture.detectChanges();
    empty = fixture.nativeElement.querySelector('.empty-state');
    expect(empty.textContent).toContain('BEDS.NO_MATCHES');
    empty.querySelector('button').click();
    fixture.detectChanges();
    expect(c.bedSearch()).toBe('');
    expect(c.bedFilter()).toBe('');
    expect(c.filteredBeds().length).toBe(1);
    c.beds.set([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty-state').textContent).toContain(
      'BEDS.NO_BEDS',
    );
    c.closeWard();
    c.wards.set([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.empty-state').textContent).toContain(
      'BEDS.NO_WARDS_HINT',
    );
  });

  it('keeps active-ward capacity totals independent of search and inactive units', () => {
    component.wards.set([ward('w1', 'MED', 10), { ...ward('w2', 'OLD', 100), active: false }]);
    component.wardSearch.set('OLD');
    expect(component.filteredWards().map((w) => w.id)).toEqual(['w2']);
    expect(component.totals()).toEqual({ wards: 1, beds: 10, available: 10, occupied: 0 });
    component.wardTypeFilter.set('ICU');
    expect(component.filteredWards()).toEqual([]);
    component.resetWardFilters();
    expect(component.filteredWards().length).toBe(2);
  });

  it('does not replace a selected unit with a late bed response or late error', () => {
    const old = new Subject<BedResponse[]>();
    const current = new Subject<BedResponse[]>();
    bedServiceSpy.getBeds.and.returnValues(old, current);
    component.selectWard(ward('w1', 'MED'));
    component.selectWard(ward('w2', 'ICU'));
    current.next([{ ...bed('new', 'ICU-1'), wardId: 'w2' }]);
    old.next([bed('old', 'MED-1')]);
    old.error(new Error('stale'));
    expect(component.beds().map((b) => b.id)).toEqual(['new']);
    expect(component.bedsError()).toBeFalse();
    component.closeWard();
    current.next([bed('late', 'ICU-2')]);
    expect(component.beds()).toEqual([]);
  });

  it('clears stale capacity on ward load failure and uses the selected hospital for departments', () => {
    component.wards.set([ward('w1', 'MED', 10)]);
    bedServiceSpy.getWards.and.returnValue(throwError(() => new Error('offline')));
    component.loadWards();
    expect(component.wards()).toEqual([]);
    expect(component.loadError()).toBeTrue();
    spyOn(TestBed.inject(RoleContextService), 'effectiveHospitalIdForRequest').and.returnValue(
      'h2',
    );
    component.openCreateWard();
    expect(deptSpy.getActiveDepartments).toHaveBeenCalledWith('h2');
  });

  it('combines bed search and status without changing the bed inventory', () => {
    component.beds.set([bed('b1', 'CDX-1', 'AVAILABLE'), bed('b2', 'CDX-2', 'OCCUPIED')]);
    component.bedSearch.set('cdx');
    component.bedFilter.set('AVAILABLE');
    expect(component.filteredBeds().map((b) => b.id)).toEqual(['b1']);
    expect(component.beds().length).toBe(2);
  });

  it('the ward form offers the hospital departments (D431)', () => {
    component.openCreateWard();
    expect(deptSpy.getActiveDepartments).toHaveBeenCalledWith('h1');
    expect(component.departments()).toEqual([{ id: 'd1', name: 'Médecine' }]);
  });

  it('selecting a ward loads its beds', () => {
    const w = ward('w1', 'MAT01', 2);
    bedServiceSpy.getBeds.and.returnValue(of([bed('b1', 'B01'), bed('b2', 'B02')]));

    component.wards.set([w]);
    component.selectWard(w);

    expect(component.selectedWardId()).toBe('w1');
    expect(component.beds().length).toBe(2);
    expect(bedServiceSpy.getBeds).toHaveBeenCalledWith('w1');
  });

  it('submitWard validates required fields before posting', () => {
    component.openCreateWard();
    component.wardForm = { name: '', code: '', wardType: 'GENERAL' };
    component.submitWard();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(bedServiceSpy.createWard).not.toHaveBeenCalled();
  });

  it('submitWard creates a ward, toasts, and reloads', () => {
    bedServiceSpy.createWard.and.returnValue(of(ward('w9', 'ICU1')));
    component.openCreateWard();
    component.wardForm = { name: 'ICU', code: 'ICU1', wardType: 'ICU' };
    component.submitWard();

    expect(bedServiceSpy.createWard).toHaveBeenCalledWith(
      jasmine.objectContaining({ code: 'ICU1', wardType: 'ICU' }),
    );
    expect(toastSpy.success).toHaveBeenCalled();
    expect(component.wardModalOpen()).toBeFalse();
    expect(bedServiceSpy.getWards).toHaveBeenCalled();
  });

  it('submitBed creates a bed in the selected ward', () => {
    bedServiceSpy.createBed.and.returnValue(of(bed('b9', 'B09')));
    component.selectedWardId.set('w1');
    component.openCreateBed();
    component.bedForm = { bedNumber: 'B09' };
    component.submitBed();

    expect(bedServiceSpy.createBed).toHaveBeenCalledWith(
      'w1',
      jasmine.objectContaining({ bedNumber: 'B09' }),
    );
    expect(toastSpy.success).toHaveBeenCalled();
    expect(component.bedModalOpen()).toBeFalse();
  });

  it('setBedStatus patches the new status and refreshes', () => {
    const b = bed('b1', 'B01');
    bedServiceSpy.updateBedStatus.and.returnValue(of({ ...b, status: 'MAINTENANCE' }));
    component.selectedWardId.set('w1');

    component.setBedStatus(b, 'MAINTENANCE');

    expect(bedServiceSpy.updateBedStatus).toHaveBeenCalledWith('b1', 'MAINTENANCE');
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('setBedStatus ignores a no-op selection', () => {
    const b = bed('b1', 'B01');
    component.setBedStatus(b, 'AVAILABLE');
    expect(bedServiceSpy.updateBedStatus).not.toHaveBeenCalled();
  });

  it('QA CX-010: deleting a ward or a bed asks first; a no sends nothing', async () => {
    const ask = spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(false);
    await component.deleteWard(ward('w1', 'MAT01', 3));
    await component.deleteBed(bed('b1', 'B01'));
    expect(ask).toHaveBeenCalledTimes(2);
    expect(bedServiceSpy.deleteWard).not.toHaveBeenCalled();
    expect(bedServiceSpy.deleteBed).not.toHaveBeenCalled();
  });

  it('deleteWard failure surfaces the delete-failed toast', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    bedServiceSpy.deleteWard.and.returnValue(throwError(() => new Error('has beds')));
    await component.deleteWard(ward('w1', 'MAT01', 3));
    expect(toastSpy.error).toHaveBeenCalled();
  });
});
