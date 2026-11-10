import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { MedicationHistoryComponent } from './medication-history';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { AuthService } from '../auth/auth.service';

/**
 * E9 #61 — provenance on the medication-timeline row. The backend has put
 * hospitalId/hospitalName on every entry since #605; the row must say which
 * hospital, and a foreign one must be marked by its id, never by its name.
 */
describe('MedicationHistoryComponent — row provenance', () => {
  let fixture: ComponentFixture<MedicationHistoryComponent>;
  let component: MedicationHistoryComponent;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MedicationHistoryComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h-1',
            roles: ['ROLE_DOCTOR'],
          }),
        },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => true,
            getRoles: () => ['ROLE_DOCTOR'],
            getHospitalId: () => 'h-1',
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
      ],
    });
    fixture = TestBed.createComponent(MedicationHistoryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('marks an entry from another hospital as foreign, by id', () => {
    expect(component.isForeignRow({ hospitalId: 'h-2' })).toBeTrue();
  });

  it('treats an entry from the active hospital as local', () => {
    expect(component.isForeignRow({ hospitalId: 'h-1' })).toBeFalse();
  });

  it('treats an entry with no hospital id as local rather than foreign', () => {
    expect(component.isForeignRow({})).toBeFalse();
  });

  // D1573: a foreign prescription row names its prescriber, signing date and read-only mark.
  it('shows prescriber, signing date and a read-only mark on a foreign prescription row', () => {
    component.patient.set({ id: 'p-1' } as never);
    component.timeline.set({
      timeline: [
        {
          entryId: 'RX-1',
          entryType: 'PRESCRIPTION',
          hospitalId: 'h-2',
          hospitalName: 'Hôpital A',
          prescriberName: 'Dr A',
          signedAt: '2026-11-01T10:00:00',
          status: 'SIGNED',
        },
        { entryId: 'RX-2', entryType: 'PRESCRIPTION', hospitalId: 'h-1', status: 'SIGNED' },
      ],
      totalMedications: 2,
      activeMedications: 0,
      controlledSubstances: 0,
      medicationsWithOverlaps: 0,
    } as never);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="mh-prescriber-RX-1"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="mh-signed-RX-1"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="mh-readonly-RX-1"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="mh-readonly-RX-2"]')).toBeNull();
  });
});
