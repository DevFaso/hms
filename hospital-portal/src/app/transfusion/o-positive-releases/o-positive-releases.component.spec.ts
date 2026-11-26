import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { OPositiveReleasesComponent } from './o-positive-releases.component';
import {
  OPositiveProtocol,
  OPositiveReleaseDecision,
  TransfusionService,
} from '../../services/transfusion.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

function decision(over: Partial<OPositiveReleaseDecision> = {}): OPositiveReleaseDecision {
  return {
    id: 'd1',
    releaseId: 'rel-1',
    requestId: 'req-1',
    bloodUnitId: 'u1',
    unitNumber: 'U-OPOS',
    patientId: 'p1',
    patientName: 'Awa Traoré',
    riskTier: 'HIGH_RISK',
    tierReason: 'FEMALE_AT_OR_BELOW_CUTOFF',
    protocolActive: true,
    femaleAgeCutoff: 50,
    boysLowRisk: false,
    reviewDeadlineHours: 24,
    patientSex: 'FEMALE',
    patientAgeYears: 30,
    negativeStatus: 'OUT_OF_STOCK',
    decisionBasis: 'PHYSICIAN_ORDER',
    issuedByName: 'Tech',
    decidedByName: 'Dr Physician',
    biologistName: null,
    verbalOrder: true,
    issuedAt: '2026-11-25T22:00:00',
    countersignDueAt: '2026-11-26T22:00:00',
    countersignedByName: null,
    countersignedAt: null,
    countersignOverdue: false,
    countersignEscalatedAt: null,
    reviewPriority: 'URGENT',
    reviewDueAt: '2026-11-26T22:00:00',
    reviewedByName: null,
    reviewedAt: null,
    reviewNote: null,
    rhdExposure: true,
    followUpRequired: true,
    antibodyScreenDueFrom: '2027-02-25',
    antibodyScreenDueBy: '2027-05-25',
    antibodyScreenDoneAt: null,
    antiDDecision: null,
    antiDDecidedByName: null,
    antiDDecidedAt: null,
    patientInformedByName: null,
    patientInformedAt: null,
    followUpNote: null,
    ...over,
  };
}

const PROTOCOL: OPositiveProtocol = {
  hospitalId: 'h1',
  active: false,
  femaleAgeCutoff: 50,
  boysLowRisk: false,
  reviewDeadlineHours: 24,
};

describe('OPositiveReleasesComponent (V380)', () => {
  let fixture: ComponentFixture<OPositiveReleasesComponent>;
  let component: OPositiveReleasesComponent;
  let tx: jasmine.SpyObj<TransfusionService>;
  let toast: jasmine.SpyObj<ToastService>;

  const el = (testId: string) =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as HTMLElement | null;

  const start = async (roles: string[], rows: OPositiveReleaseDecision[] = [decision()]) => {
    tx = jasmine.createSpyObj('TransfusionService', [
      'getPositiveProtocol',
      'setPositiveProtocol',
      'listPositiveReleases',
      'countersignPositiveRelease',
      'reviewPositiveRelease',
      'recordPositiveFollowUp',
    ]);
    tx.getPositiveProtocol.and.returnValue(of(PROTOCOL));
    tx.listPositiveReleases.and.returnValue(of(rows));
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);
    await TestBed.configureTestingModule({
      imports: [OPositiveReleasesComponent, TranslateModule.forRoot()],
      providers: [
        { provide: TransfusionService, useValue: tx },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles }),
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(OPositiveReleasesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  };

  it('says the hospital has no protocol, and lists what is still owed on each release', async () => {
    await start(['ROLE_LAB_TECHNICIAN']);
    expect(tx.listPositiveReleases).toHaveBeenCalledWith(true);
    expect(el('opos-no-protocol')).not.toBeNull();
    expect(el('opos-tier')!.textContent).toContain('TRANSFUSION.OPOS.TIER_HIGH_RISK');
    expect(el('opos-basis')!.textContent).toContain('Dr Physician');
    expect(el('opos-countersign-due')).not.toBeNull();
    expect(el('opos-follow-up')).not.toBeNull();
    // A technician is offered none of the three actions, nor the protocol.
    expect(el('opos-countersign')).toBeNull();
    expect(el('opos-review')).toBeNull();
    expect(el('opos-anti-d-save')).toBeNull();
    expect(el('opos-informed-save')).toBeNull();
    expect(el('opos-edit-protocol')).toBeNull();
  });

  it('an empty queue says so', async () => {
    await start(['ROLE_LAB_DIRECTOR'], []);
    expect(el('opos-empty')).not.toBeNull();
  });

  it('a physician countersigns and records the follow-up; the row shows the server answer', async () => {
    await start(['ROLE_DOCTOR']);
    expect(el('opos-review')).toBeNull();
    tx.countersignPositiveRelease.and.returnValue(
      of(decision({ countersignedAt: '2026-11-26T08:00:00', countersignedByName: 'Dr Physician' })),
    );
    el('opos-countersign')!.click();
    fixture.detectChanges();
    expect(tx.countersignPositiveRelease).toHaveBeenCalledWith('d1');
    expect(el('opos-countersigned')).not.toBeNull();
    expect(el('opos-countersign')).toBeNull();
    expect(toast.success).toHaveBeenCalled();

    const row = component.releases()[0];
    component.recordAntiD(row);
    expect(tx.recordPositiveFollowUp).not.toHaveBeenCalled();

    component.followUpDecision['d1'] = 'ANTI_D_IMMUNOGLOBULIN';
    component.followUpNotes['d1'] = '  Vue avec son mari  ';
    const decided = decision({ antiDDecision: 'ANTI_D_IMMUNOGLOBULIN' });
    tx.recordPositiveFollowUp.and.returnValue(of(decided));
    component.recordAntiD(row);
    expect(tx.recordPositiveFollowUp).toHaveBeenCalledWith('d1', {
      antiDDecision: 'ANTI_D_IMMUNOGLOBULIN',
      note: 'Vue avec son mari',
    });
    fixture.detectChanges();
    expect(el('opos-anti-d-save')).toBeNull();
    expect(component.followUpOwed(component.releases()[0])).toBeTrue();

    tx.recordPositiveFollowUp.and.returnValue(
      of({ ...decided, patientInformedAt: '2026-11-26T09:00:00' }),
    );
    el('opos-informed-save')!.click();
    expect(tx.recordPositiveFollowUp).toHaveBeenCalledWith('d1', { patientInformed: true });
    expect(component.followUpOwed(component.releases()[0])).toBeFalse();
  });

  it('the anti-D decision is refused empty on its field, and a server refusal marks the field it names', async () => {
    await start(['ROLE_DOCTOR']);
    document.body.appendChild(fixture.nativeElement);
    try {
      const marked = expectEmptySubmitRefusal(fixture, {
        button: '[data-testid="opos-anti-d-save"]',
        sent: tx.recordPositiveFollowUp,
      });
      expect(marked).toEqual(['opos-antid-d1']);

      // The clinician picks a value: the guard lets the click through, and the server answers.
      component.followUpDecision['d1'] = 'NOT_INDICATED';
      fixture.detectChanges();
      tx.recordPositiveFollowUp.and.returnValue(
        throwError(() => fieldErrorsResponse({ antiDDecision: 'Décision déjà enregistrée' })),
      );
      component.recordAntiD(component.releases()[0]);
      fixture.detectChanges();
      expectFieldsMarked(fixture, { 'opos-antid-d1': 'Décision déjà enregistrée' });
      expect(toast.error).toHaveBeenCalled();
    } finally {
      fixture.nativeElement.remove();
    }
  });

  it('the biologist reviews with a note, and sets the protocol', async () => {
    await start(['ROLE_LAB_DIRECTOR']);
    component.reviewNotes['d1'] = '  Anti-D discuté  ';
    tx.reviewPositiveRelease.and.returnValue(
      of(
        decision({
          reviewedAt: '2026-11-26T08:00:00',
          reviewedByName: 'Dir',
          reviewNote: 'Anti-D discuté',
        }),
      ),
    );
    el('opos-review')!.click();
    fixture.detectChanges();
    expect(tx.reviewPositiveRelease).toHaveBeenCalledWith('d1', 'Anti-D discuté');
    expect(el('opos-reviewed')!.textContent).toContain('Anti-D discuté');

    el('opos-edit-protocol')!.click();
    fixture.detectChanges();
    component.draft.active = true;
    component.draft.femaleAgeCutoff = 45;
    tx.setPositiveProtocol.and.returnValue(of({ ...PROTOCOL, active: true, femaleAgeCutoff: 45 }));
    el('opos-save-protocol')!.click();
    fixture.detectChanges();
    expect(tx.setPositiveProtocol).toHaveBeenCalledWith({
      active: true,
      femaleAgeCutoff: 45,
      boysLowRisk: false,
      reviewDeadlineHours: 24,
    });
    expect(component.protocol()!.active).toBeTrue();
    expect(el('opos-no-protocol')).toBeNull();
  });

  it('a refusal shows the server reason and keeps the row', async () => {
    await start(['ROLE_DOCTOR']);
    tx.countersignPositiveRelease.and.returnValue(
      throwError(() => ({ status: 403, error: { message: 'Vous avez délivré cette poche' } })),
    );
    el('opos-countersign')!.click();
    expect(toast.error).toHaveBeenCalledWith('Vous avez délivré cette poche');
    expect(component.busyId()).toBeNull();
    expect(component.releases()[0].countersignedAt).toBeNull();
  });

  it('« Toutes » asks the server for every release', async () => {
    await start(['ROLE_LAB_DIRECTOR']);
    component.setOpenOnly(false);
    expect(tx.listPositiveReleases).toHaveBeenCalledWith(false);
  });

  it('a list that cannot be loaded says so and shows no false « nothing to review »', async () => {
    await start(['ROLE_LAB_DIRECTOR']);
    tx.listPositiveReleases.and.returnValue(throwError(() => ({ status: 500 })));
    component.loadReleases();
    fixture.detectChanges();
    expect(toast.error).toHaveBeenCalled();
    expect(component.loadFailed()).toBeTrue();
  });
});
