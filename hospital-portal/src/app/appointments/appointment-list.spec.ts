import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { AppointmentListComponent } from './appointment-list';
import { AppointmentResponse, AppointmentService } from '../services/appointment.service';
import { HospitalService } from '../services/hospital.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

function appt(id: string, patientName: string): AppointmentResponse {
  return {
    id,
    patientName,
    staffName: 'Dr Ouédraogo',
    reason: 'Contrôle',
    hospitalName: 'CHU',
    hospitalId: 'h1',
    appointmentDate: '2026-11-03',
    startTime: '09:00',
    status: 'SCHEDULED',
  } as AppointmentResponse;
}

describe('AppointmentListComponent — QA D1322 (round 29): the search asks the server', () => {
  let fixture: ComponentFixture<AppointmentListComponent>;
  let component: AppointmentListComponent;
  let list: jasmine.Spy;

  beforeEach(async () => {
    list = jasmine
      .createSpy('list')
      .and.callFake((filters?: { search?: string }) =>
        of(
          filters?.search
            ? [appt('a2', 'Awa Kaboré')]
            : [appt('a1', 'Boukaré Sawadogo'), appt('a2', 'Awa Kaboré')],
        ),
      );
    await TestBed.configureTestingModule({
      imports: [AppointmentListComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: AppointmentService, useValue: { list } },
        {
          provide: HospitalService,
          useValue: { list: () => of([]), getMyHospitalAsResponse: () => of({ id: 'h1' }) },
        },
        { provide: PermissionService, useValue: { hasPermission: () => true } },
        { provide: RoleContextService, useValue: { isSuperAdmin: () => false } },
        { provide: ToastService, useValue: jasmine.createSpyObj('ToastService', ['error']) },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AppointmentListComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('an MRN, phone, e-mail or birth date finds the row through the server search', fakeAsync(() => {
    for (const term of ['CHW1112', '70291801', '14/08/1979', 'awa.kabore@example.test']) {
      list.calls.reset();
      component.onSearchChange(term);
      tick(300);
      expect(list).toHaveBeenCalledWith({ search: term });
      expect(component.filtered().map((a) => a.id)).withContext(term).toEqual(['a2']);
      component.onSearchChange('');
      tick(300);
    }
  }));

  it('a name the page holds still matches, and clearing the term lists everything again', fakeAsync(() => {
    component.onSearchChange('Boukaré');
    tick(300);
    expect(component.filtered().map((a) => a.id)).toContain('a1');
    component.clearFilters();
    tick(300);
    expect(component.filtered().length).toBe(2);
  }));
});
