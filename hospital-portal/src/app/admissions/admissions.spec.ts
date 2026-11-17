import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { AdmissionsComponent } from './admissions';
import { AdmissionResponse } from '../services/admission.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { AuthService } from '../auth/auth.service';

describe('Admission overview filters', () => {
  let component: AdmissionsComponent;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AdmissionsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h1',
            roles: ['ROLE_HOSPITAL_ADMIN'],
          }),
        },
        { provide: AuthService, useValue: { getUserProfile: () => ({ staffId: 's1' }) } },
      ],
    });
    component = TestBed.createComponent(AdmissionsComponent).componentInstance;
  });
  it('accounts for every status and keeps cards, tabs and status filters consistent', () => {
    component.admissions.set(
      component.statuses.map((status, i) => ({ id: String(i), status }) as AdmissionResponse),
    );
    expect(
      component.statuses.reduce((sum, status) => sum + component.countByStatus(status), 0),
    ).toBe(component.admissions().length);
    component.setStatus('PENDING');
    expect(component.filtered().map((a) => a.status)).toEqual(['PENDING']);
    component.setStatus('ACTIVE');
    expect(component.activeTab()).toBe('admitted');
    component.setTab('discharged');
    expect(component.statusFilter()).toBe('DISCHARGED');
    expect(component.filtered().map((a) => a.status)).toEqual(['DISCHARGED']);
    component.resetFilters();
    expect(component.filtered().length).toBe(component.admissions().length);
  });
  it('counts missing beds as an active subset and respects structured and free-text assignments', () => {
    component.admissions.set([
      { id: 'missing', status: 'ACTIVE', roomBed: ' ' },
      { id: 'structured', status: 'ACTIVE', bedId: 'bed1' },
      { id: 'text', status: 'ACTIVE', roomBed: 'Room 1' },
      { id: 'pending', status: 'PENDING' },
    ] as AdmissionResponse[]);
    expect(component.missingBedCount()).toBe(1);
    component.filterMissingBeds();
    expect(component.filtered().map((a) => a.id)).toEqual(['missing']);
    expect(component.activeTab()).toBe('admitted');
    component.setTab('all');
    expect(component.missingBedOnly()).toBeFalse();
    expect(component.filtered().length).toBe(4);
  });
});
