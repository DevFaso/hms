import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { BedManagementComponent } from './bed-management';
import { BedService, WardResponse, BedResponse } from '../services/bed.service';
import { ToastService } from '../core/toast.service';

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

    await TestBed.configureTestingModule({
      imports: [BedManagementComponent, TranslateModule.forRoot()],
      providers: [
        { provide: BedService, useValue: bedServiceSpy },
        { provide: ToastService, useValue: toastSpy },
      ],
    }).compileComponents();

    component = TestBed.createComponent(BedManagementComponent).componentInstance;
  });

  it('should create and load wards on init', () => {
    bedServiceSpy.getWards.and.returnValue(of([ward('w1', 'MAT01', 4)]));
    component.ngOnInit();
    expect(component.wards().length).toBe(1);
    expect(bedServiceSpy.getWards).toHaveBeenCalledWith(false);
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

  it('deleteWard failure surfaces the delete-failed toast', () => {
    bedServiceSpy.deleteWard.and.returnValue(throwError(() => new Error('has beds')));
    component.deleteWard(ward('w1', 'MAT01', 3));
    expect(toastSpy.error).toHaveBeenCalled();
  });
});
