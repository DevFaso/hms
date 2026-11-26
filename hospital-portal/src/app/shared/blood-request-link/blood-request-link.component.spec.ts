import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { BloodRequestLinkComponent } from './blood-request-link.component';
import { RoleContextService } from '../../core/role-context.service';
import { bloodRequestState, takeBloodRequestPatient } from '../blood-request-handoff';

/** QA D3220: « Demander du sang » beside the blood group, on the cards where bleeding is seen. */
describe('BloodRequestLinkComponent (QA D3220)', () => {
  let fixture: ComponentFixture<BloodRequestLinkComponent>;
  let asked: string[][];

  function create(canRequest: boolean, patientId: string | null): HTMLAnchorElement | null {
    asked = [];
    TestBed.configureTestingModule({
      imports: [BloodRequestLinkComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: (roles: string[]) => {
              asked.push(roles);
              return canRequest;
            },
          },
        },
      ],
    });
    fixture = TestBed.createComponent(BloodRequestLinkComponent);
    fixture.componentRef.setInput('patientId', patientId);
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>(
      '[data-testid="blood-request-link"]',
    );
  }

  it('offers the blood bank’s request form to a prescriber, with no patient in the URL', () => {
    const link = create(true, 'patient-1')!;

    expect(link.textContent).toContain('TRANSFUSION.NEW_REQUEST');
    expect(link.getAttribute('href')).toBe('/transfusions');
    expect(link.getAttribute('href')).not.toContain('patient-1');
    // The roles the request endpoint admits, from the map the backend mirrors.
    expect(asked[0]).toEqual(['ROLE_DOCTOR', 'ROLE_SURGEON', 'ROLE_MIDWIFE']);
  });

  it('is not offered to a role the request endpoint refuses', () => {
    expect(create(false, 'patient-1')).toBeNull();
  });

  it('is not offered before a patient is known', () => {
    expect(create(true, null)).toBeNull();
  });
});

describe('blood request hand-off (QA D3220)', () => {
  function history(state: unknown): History {
    const fake = {
      state,
      replaceState: (next: unknown) => {
        fake.state = next;
      },
    };
    return fake as unknown as History;
  }

  it('carries the patient in the navigation state and gives it once', () => {
    const h = history(bloodRequestState('patient-1'));

    expect(takeBloodRequestPatient(h)).toBe('patient-1');
    // Reloading the blood bank, or coming back to it, does not reopen the form.
    expect(takeBloodRequestPatient(h)).toBe('');
  });

  it('gives nothing when the navigation carried no patient', () => {
    expect(takeBloodRequestPatient(history(null))).toBe('');
    expect(takeBloodRequestPatient(history({ other: 1 }))).toBe('');
  });
});
