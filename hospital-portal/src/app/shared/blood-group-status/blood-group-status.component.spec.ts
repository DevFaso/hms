import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { BloodGroupStatusComponent } from './blood-group-status.component';
import { BloodGroupStatus, shownBloodGroup } from './blood-group-status';

/** QA D3901 (r77, High): the one way a screen shows the patient's blood group. */
describe('BloodGroupStatusComponent', () => {
  let fixture: ComponentFixture<BloodGroupStatusComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BloodGroupStatusComponent, TranslateModule.forRoot()],
    }).compileComponents();
    fixture = TestBed.createComponent(BloodGroupStatusComponent);
  });

  function show(
    status: BloodGroupStatus | null,
    declared: string | null = null,
    extra: { compact?: boolean; showAntibodies?: boolean } = {},
  ): HTMLElement {
    fixture.componentRef.setInput('status', status);
    fixture.componentRef.setInput('declared', declared);
    if (extra.compact !== undefined) fixture.componentRef.setInput('compact', extra.compact);
    if (extra.showAntibodies !== undefined) {
      fixture.componentRef.setInput('showAntibodies', extra.showAntibodies);
    }
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const byId = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => byId(el, id)?.textContent?.trim() ?? null;

  it('shows the laboratory group with its source, never as declared (P02: O-)', () => {
    const el = show({
      confirmed: 'O-',
      confirmedAt: '2026-11-25T09:30:00',
      confirmedHospitalName: 'Centre Médical Test',
    });
    expect(text(el, 'blood-group-confirmed')).toBe('O-');
    expect(text(el, 'blood-group-source')).toBe('BLOOD_GROUP.CONFIRMED_BY_LAB');
    expect(byId(el, 'blood-group-declared')).toBeNull();
    expect(byId(el, 'blood-group-discrepancy')).toBeNull();
  });

  it('shows a typed group alone as declared and not verified, never in the confirmed style', () => {
    const el = show({ declared: 'A+' });
    expect(text(el, 'blood-group-declared')).toBe('A+');
    expect(el.textContent).toContain('BLOOD_GROUP.DECLARED_UNVERIFIED');
    expect(byId(el, 'blood-group-confirmed')).toBeNull();
  });

  it('labels the typed group of a payload with no status as declared too', () => {
    const el = show(null, 'A+');
    expect(text(el, 'blood-group-declared')).toBe('A+');
    expect(el.textContent).toContain('BLOOD_GROUP.DECLARED_UNVERIFIED');
  });

  it('shows the laboratory group and a visible warning when the typed one differs (P03: B+ vs A+)', () => {
    const el = show({ declared: 'A+', confirmed: 'B+', discrepancy: true });
    expect(text(el, 'blood-group-confirmed')).toBe('B+');
    expect(text(el, 'blood-group-discrepancy')).toContain('BLOOD_GROUP.DIFFERS_FROM_DECLARED');
    expect(byId(el, 'blood-group-declared')).toBeNull();
  });

  it('keeps the warning in a list cell', () => {
    const el = show({ declared: 'A+', confirmed: 'B+', discrepancy: true }, null, {
      compact: true,
    });
    expect(text(el, 'blood-group-source')).toBe('BLOOD_GROUP.CONFIRMED_SHORT');
    expect(byId(el, 'blood-group-discrepancy')).not.toBeNull();
  });

  it('says « Non renseigné » when no group is recorded', () => {
    expect(text(show(null), 'blood-group-missing')).toBe('PATIENT_SAFETY.NOT_DOCUMENTED');
  });

  it('shows the allo-immunisation alert, with the antibody or as not identified', () => {
    let el = show({ confirmed: 'B+', alloImmunised: true, antibodySpecificities: 'anti-E' });
    // The text follows the aria-hidden icon's ligature.
    expect(text(el, 'blood-group-antibody')).toMatch(/BLOOD_GROUP\.ALLO_IMMUNISED$/);
    el = show({ confirmed: 'B+', alloImmunised: true });
    expect(text(el, 'blood-group-antibody')).toMatch(/BLOOD_GROUP\.ALLO_IMMUNISED_UNIDENTIFIED$/);
  });

  it('V380: shows the permanent RhD positive exposure flag, with or without a group on file', () => {
    let el = show({ rhdPositiveExposed: true, rhdPositiveExposureConfirmed: true });
    expect(text(el, 'blood-group-rhd-exposed')).toMatch(/BLOOD_GROUP\.RHD_EXPOSED$/);
    // Issued and never recorded as given: said to be unconfirmed, not stated as an exposure.
    el = show({ rhdPositiveExposed: true, rhdPositiveExposureConfirmed: false });
    expect(text(el, 'blood-group-rhd-exposed')).toMatch(/BLOOD_GROUP\.RHD_EXPOSED_UNCONFIRMED$/);
    el = show({ confirmed: 'O-', rhdPositiveExposed: false });
    expect(byId(el, 'blood-group-rhd-exposed')).toBeNull();
    el = show({ confirmed: 'O-', rhdPositiveExposed: true }, null, { showAntibodies: false });
    expect(byId(el, 'blood-group-rhd-exposed')).toBeNull();
  });

  it('leaves the antibody alert out where the host asks it to', () => {
    const el = show({ confirmed: 'B+', alloImmunised: true }, null, { showAntibodies: false });
    expect(byId(el, 'blood-group-antibody')).toBeNull();
  });

  it('shownBloodGroup prefers the laboratory group', () => {
    expect(shownBloodGroup({ declared: 'A+', confirmed: 'B+' }, 'A+')).toBe('B+');
    expect(shownBloodGroup({ declared: 'A+' })).toBe('A+');
    expect(shownBloodGroup(null, 'O-')).toBe('O-');
    expect(shownBloodGroup(undefined)).toBeNull();
  });
});
