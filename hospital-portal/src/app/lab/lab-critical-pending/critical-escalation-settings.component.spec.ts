import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { CriticalEscalationSettingsComponent } from './critical-escalation-settings.component';
import { CriticalEscalationSettings, LabService } from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';

/** QA r70 (scorecard C5): the per-hospital steps of the critical-result escalation ladder. */
describe('CriticalEscalationSettingsComponent', () => {
  let fixture: ComponentFixture<CriticalEscalationSettingsComponent>;
  let component: CriticalEscalationSettingsComponent;
  let labSpy: jasmine.SpyObj<LabService>;

  const settings = (
    overrides: Partial<CriticalEscalationSettings> = {},
  ): CriticalEscalationSettings => ({
    hospitalId: 'h1',
    coverAfterMinutes: 15,
    leadAfterMinutes: 30,
    unresolvedAfterMinutes: 60,
    usesDefaults: true,
    medicalLeadUserId: null,
    doctors: [{ userId: 'u-chef', name: 'Dr Awa Chef' }],
    ...overrides,
  });

  beforeEach(async () => {
    labSpy = jasmine.createSpyObj('LabService', [
      'criticalEscalationSettings',
      'saveCriticalEscalationSettings',
    ]);
    labSpy.criticalEscalationSettings.and.returnValue(of(settings()));
    await TestBed.configureTestingModule({
      imports: [CriticalEscalationSettingsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: LabService, useValue: labSpy },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(CriticalEscalationSettingsComponent);
    fixture.componentRef.setInput('hospitalId', 'h1');
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  const el = () => fixture.nativeElement as HTMLElement;

  it('loads nothing until the administrator opens it, then shows the defaults 15, 30 and 60', () => {
    expect(labSpy.criticalEscalationSettings).not.toHaveBeenCalled();
    el().querySelector<HTMLButtonElement>('[data-testid="escalation-settings-toggle"]')!.click();
    fixture.detectChanges();
    expect(labSpy.criticalEscalationSettings).toHaveBeenCalledOnceWith('h1');
    expect([component.cover, component.lead, component.unresolved]).toEqual([15, 30, 60]);
    expect(el().querySelector('#esc-chief option[value="u-chef"]')).not.toBeNull();
  });

  it('refuses steps that do not increase or leave the 5 to 1440 minute range, and saves a valid set', () => {
    labSpy.saveCriticalEscalationSettings.and.returnValue(
      of(settings({ coverAfterMinutes: 10, leadAfterMinutes: 20, unresolvedAfterMinutes: 45 })),
    );
    component.toggle();
    fixture.detectChanges();

    for (const [cover, lead, unresolved] of [
      [30, 20, 60],
      [15, 15, 60],
      [4, 30, 60],
      [15, 30, 1441],
    ]) {
      component.cover = cover;
      component.lead = lead;
      component.unresolved = unresolved;
      expect(component.valid).withContext(`${cover}/${lead}/${unresolved}`).toBeFalse();
      component.save();
    }
    expect(labSpy.saveCriticalEscalationSettings).not.toHaveBeenCalled();

    component.cover = 10;
    component.lead = 20;
    component.unresolved = 45;
    component.medicalLead = 'u-chef';
    component.save();
    expect(labSpy.saveCriticalEscalationSettings).toHaveBeenCalledOnceWith('h1', {
      coverAfterMinutes: 10,
      leadAfterMinutes: 20,
      unresolvedAfterMinutes: 45,
      medicalLeadUserId: 'u-chef',
    });
    expect(component.settings()?.unresolvedAfterMinutes).toBe(45);
  });
});
