import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { IncompatibleReleasesComponent } from './incompatible-releases.component';
import {
  IncompatibleReleaseDecision,
  IncompatibleReleaseProtocol,
  TransfusionService,
} from '../../services/transfusion.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';

function decision(over: Partial<IncompatibleReleaseDecision> = {}): IncompatibleReleaseDecision {
  return {
    id: 'd1',
    releaseId: 'rel-1',
    requestId: 'req-1',
    bloodUnitId: 'u1',
    unitNumber: 'U-A-POS',
    patientId: 'p1',
    patientName: 'Awa Traoré',
    decisionBasis: 'AUTHORITY_PENDING',
    fallback: true,
    countersignDeadlineHours: 24,
    issuedByName: 'Tech Ouedraogo',
    authoriserName: null,
    declarerRole: 'ROLE_LAB_TECHNICIAN',
    unreachableReason: 'Dr de garde appelé à 01 h 40, sans réponse',
    authorityTiming: 'PENDING',
    antibodyStatus: 'ANTIBODY_ON_RECORD',
    antibodyDetail: 'anti-K',
    crossmatchFinding: 'Agglutination',
    recipientAbo: 'A',
    recipientRh: 'POSITIVE',
    unitAbo: 'A',
    unitRh: 'POSITIVE',
    rhdMatched: true,
    rhKellMatched: null,
    antigenNegative: null,
    leastIncompatible: true,
    candidatesConsidered: 1,
    unitChoiceReason: 'Seule poche compatible ABO testée',
    patientInformed: 'NOT_POSSIBLE',
    patientInformedAt: null,
    patientInformedByName: null,
    issuedAt: '2026-11-26T02:00:00',
    countersignDueAt: '2026-11-27T02:00:00',
    countersignedByName: null,
    countersignedAt: null,
    countersignOverdue: false,
    countersignEscalatedAt: null,
    monitoringChecklist: ['BEDSIDE_ABO_CHECK', 'SLOW_START', 'TUBING_KEPT', 'URINE_DIPSTICK'],
    monitoringChecksDone: [],
    monitoringOutcome: null,
    monitoringNote: null,
    monitoringRecordedByName: null,
    monitoringRecordedAt: null,
    ...over,
  };
}

const OFF: IncompatibleReleaseProtocol = {
  hospitalId: 'h1',
  fallbackEnabled: false,
  declarerRoles: [
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_TECHNICIAN',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_DIRECTOR',
  ],
  countersignDeadlineHours: 24,
  allowedDeclarerRoles: [
    'ROLE_LAB_SCIENTIST',
    'ROLE_LAB_TECHNICIAN',
    'ROLE_LAB_MANAGER',
    'ROLE_LAB_DIRECTOR',
  ],
};

describe('IncompatibleReleasesComponent (V381)', () => {
  let fixture: ComponentFixture<IncompatibleReleasesComponent>;
  let component: IncompatibleReleasesComponent;
  let tx: jasmine.SpyObj<TransfusionService>;
  let toast: jasmine.SpyObj<ToastService>;

  const el = (testId: string) =>
    fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as HTMLElement | null;

  const start = async (
    roles: string[],
    rows: IncompatibleReleaseDecision[] = [decision()],
    protocol: IncompatibleReleaseProtocol = OFF,
  ) => {
    tx = jasmine.createSpyObj('TransfusionService', [
      'getIncompatibleProtocol',
      'setIncompatibleProtocol',
      'listIncompatibleReleases',
      'countersignIncompatibleRelease',
      'recordIncompatibleMonitoring',
    ]);
    tx.getIncompatibleProtocol.and.returnValue(of(protocol));
    tx.listIncompatibleReleases.and.returnValue(of(rows));
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);
    await TestBed.configureTestingModule({
      imports: [IncompatibleReleasesComponent, TranslateModule.forRoot()],
      providers: [
        { provide: TransfusionService, useValue: tx },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles }),
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(IncompatibleReleasesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  };

  it('shows the setting off by default and a fallback release with what it owes; a technician is offered no action', async () => {
    await start(['ROLE_LAB_TECHNICIAN']);
    expect(tx.listIncompatibleReleases).toHaveBeenCalledWith(true);
    expect(el('incompat-setting-state')!.textContent).toContain('TRANSFUSION.INCOMPAT.SETTING_OFF');
    expect(el('incompat-setting-roles')).toBeNull();
    expect(el('incompat-basis')!.textContent).toContain(
      'TRANSFUSION.INCOMPAT.BASIS_AUTHORITY_PENDING',
    );
    expect(el('incompat-authority')!.textContent).toContain('TRANSFUSION.INCOMPAT.TIMING_PENDING');
    expect(el('incompat-unreachable-reason')!.textContent).toContain('01 h 40');
    expect(el('incompat-choice')!.textContent).toContain('Seule poche compatible ABO testée');
    expect(el('incompat-countersign-due')).not.toBeNull();
    expect(el('incompat-checklist')!.querySelectorAll('input').length).toBe(4);
    // The person who issues is offered neither the signature, nor the monitoring, nor the setting.
    expect(el('incompat-countersign')).toBeNull();
    expect(el('incompat-record-outcome')).toBeNull();
    expect(el('incompat-informed-action')).toBeNull();
    expect(el('incompat-edit-setting')).toBeNull();
  });

  it('only the laboratory director changes the setting, and it is never saved with no role', async () => {
    await start(['ROLE_LAB_DIRECTOR']);
    tx.setIncompatibleProtocol.and.returnValue(
      of({ ...OFF, fallbackEnabled: true, declarerRoles: ['ROLE_LAB_MANAGER'] }),
    );
    el('incompat-edit-setting')!.click();
    fixture.detectChanges();
    expect(component.draftEnabled).toBeFalse();
    expect(component.draftRoleList().length).toBe(4);

    component.draftEnabled = true;
    for (const role of Object.keys(component.draftRoles)) component.draftRoles[role] = false;
    fixture.detectChanges();
    expect((el('incompat-save-setting') as HTMLButtonElement).disabled).toBeTrue();
    component.saveProtocol();
    expect(tx.setIncompatibleProtocol).not.toHaveBeenCalled();

    component.draftRoles['ROLE_LAB_MANAGER'] = true;
    component.saveProtocol();
    expect(tx.setIncompatibleProtocol).toHaveBeenCalledWith({
      fallbackEnabled: true,
      declarerRoles: ['ROLE_LAB_MANAGER'],
    });
    fixture.detectChanges();
    expect(el('incompat-setting-state')!.textContent).toContain('TRANSFUSION.INCOMPAT.SETTING_ON');
    expect(el('incompat-setting-roles')!.textContent).toContain(
      'TRANSFUSION.INCOMPAT.ROLE_ROLE_LAB_MANAGER',
    );
    expect(toast.success).toHaveBeenCalledWith('TRANSFUSION.INCOMPAT.SETTING_SAVED');
  });

  it('a physician countersigns a release that went out with nobody reachable; the server reason shows on a refusal', async () => {
    await start(['ROLE_DOCTOR']);
    tx.countersignIncompatibleRelease.and.returnValue(
      throwError(() => ({ status: 403, error: { message: 'Refusé' } })),
    );
    el('incompat-countersign')!.click();
    expect(toast.error).toHaveBeenCalledWith('Refusé');

    tx.countersignIncompatibleRelease.and.returnValue(
      of(
        decision({
          authorityTiming: 'AFTER_ISSUE',
          countersignedAt: '2026-11-26T08:00:00',
          countersignedByName: 'Dr Sawadogo',
        }),
      ),
    );
    el('incompat-countersign')!.click();
    fixture.detectChanges();
    expect(tx.countersignIncompatibleRelease).toHaveBeenCalledWith('d1');
    expect(el('incompat-countersigned')!.textContent).toContain('TRANSFUSION.INCOMPAT.DONE_BY');
    expect(el('incompat-countersign')).toBeNull();
    expect(el('incompat-authority')!.textContent).toContain(
      'TRANSFUSION.INCOMPAT.TIMING_AFTER_ISSUE',
    );
  });

  it('a release a physician authorised owes no countersignature', async () => {
    await start(
      ['ROLE_DOCTOR'],
      [
        decision({
          decisionBasis: 'NAMED_PHYSICIAN',
          fallback: false,
          authoriserName: 'Dr Sawadogo',
          authorityTiming: 'BEFORE_ISSUE',
          declarerRole: null,
          unreachableReason: null,
          countersignDueAt: null,
        }),
      ],
    );
    expect(el('incompat-countersign')).toBeNull();
    expect(el('incompat-countersign-due')).toBeNull();
    expect(el('incompat-authority')!.textContent).toContain('Dr Sawadogo');
    expect(el('incompat-monitoring')).not.toBeNull();
  });

  it('a nurse ticks the checks, records that the patient was told, then the haemovigilance outcome', async () => {
    await start(['ROLE_NURSE']);
    expect(el('incompat-countersign')).toBeNull();
    const boxes = el('incompat-checklist')!.querySelectorAll('input');
    expect(component.hasNewChecks(decision())).toBeFalse();
    (boxes[0] as HTMLInputElement).click();
    fixture.detectChanges();

    const afterChecks = decision({ monitoringChecksDone: ['BEDSIDE_ABO_CHECK'] });
    tx.recordIncompatibleMonitoring.and.returnValue(of(afterChecks));
    el('incompat-save-checks')!.click();
    expect(tx.recordIncompatibleMonitoring).toHaveBeenCalledWith('d1', {
      checksDone: ['BEDSIDE_ABO_CHECK'],
    });
    fixture.detectChanges();
    // A check the server holds is ticked and no longer editable.
    const kept = el('incompat-checklist')!.querySelectorAll('input')[0] as HTMLInputElement;
    expect(kept.checked).toBeTrue();
    expect(kept.disabled).toBeTrue();

    const informed = decision({
      monitoringChecksDone: ['BEDSIDE_ABO_CHECK'],
      patientInformedAt: '2026-11-26T09:00:00',
      patientInformedByName: 'Inf. Kaboré',
    });
    tx.recordIncompatibleMonitoring.and.returnValue(of(informed));
    el('incompat-informed-action')!.click();
    expect(tx.recordIncompatibleMonitoring).toHaveBeenCalledWith('d1', { patientInformed: true });
    fixture.detectChanges();
    expect(el('incompat-informed-action')).toBeNull();

    // No outcome picked: nothing is sent.
    component.recordOutcome(informed);
    expect(tx.recordIncompatibleMonitoring).toHaveBeenCalledTimes(2);
    component.outcome['d1'] = 'NO_REACTION';
    component.notes['d1'] = '  RAS  ';
    tx.recordIncompatibleMonitoring.and.returnValue(
      of({
        ...informed,
        monitoringOutcome: 'NO_REACTION',
        monitoringRecordedByName: 'Inf. Kaboré',
      }),
    );
    component.recordOutcome(informed);
    expect(tx.recordIncompatibleMonitoring).toHaveBeenCalledWith('d1', {
      outcome: 'NO_REACTION',
      note: 'RAS',
    });
    fixture.detectChanges();
    expect(el('incompat-outcome-done')!.textContent).toContain(
      'TRANSFUSION.INCOMPAT.OUTCOME_NO_REACTION',
    );
    expect(el('incompat-record-outcome')).toBeNull();
  });

  it('shows « all » on demand, the empty state, and a failed load', async () => {
    await start(['ROLE_LAB_TECHNICIAN'], []);
    expect(el('incompat-empty')).not.toBeNull();
    component.setOpenOnly(false);
    expect(tx.listIncompatibleReleases).toHaveBeenCalledWith(false);

    tx.listIncompatibleReleases.and.returnValue(throwError(() => new Error('down')));
    component.loadReleases();
    fixture.detectChanges();
    expect(component.loadFailed()).toBeTrue();
    expect(el('incompat-empty')).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('TRANSFUSION.LOAD_ERROR');
  });
});
