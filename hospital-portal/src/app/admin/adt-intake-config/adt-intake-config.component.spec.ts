import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { AdtIntakeConfigComponent } from './adt-intake-config.component';
import { AdtIntakeConfig, AdtIntakeConfigService } from '../../services/adt-intake-config.service';
import { ToastService } from '../../core/toast.service';

describe('AdtIntakeConfigComponent', () => {
  let fixture: ComponentFixture<AdtIntakeConfigComponent>;
  let svc: jasmine.SpyObj<AdtIntakeConfigService>;
  let toast: jasmine.SpyObj<ToastService>;

  const sample: AdtIntakeConfig = {
    id: 'cfg-1',
    hospitalId: 'h1',
    hospitalName: 'Test Hospital',
    admittingProviderId: 'p1',
    departmentId: 'd1',
    defaultAssignmentId: null,
    defaultAdmissionType: 'EMERGENCY',
    defaultAcuityLevel: 'LEVEL_2_MODERATE',
    defaultEncounterType: 'INPATIENT',
    defaultChiefComplaint: 'Auto-created from ADT^A01',
    enabled: true,
    createdAt: '2026-05-17T09:00:00Z',
    updatedAt: '2026-05-17T09:00:00Z',
  };

  beforeEach(async () => {
    svc = jasmine.createSpyObj<AdtIntakeConfigService>('AdtIntakeConfigService', [
      'list',
      'findByHospital',
      'upsert',
      'remove',
    ]);
    svc.list.and.returnValue(of([sample]));
    svc.upsert.and.returnValue(of(sample));
    svc.remove.and.returnValue(of(void 0));

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [AdtIntakeConfigComponent, TranslateModule.forRoot()],
      providers: [
        { provide: AdtIntakeConfigService, useValue: svc },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdtIntakeConfigComponent);
  });

  it('lists existing intake configs on init', () => {
    fixture.detectChanges();
    expect(svc.list).toHaveBeenCalled();
    const rows = fixture.nativeElement.querySelectorAll('[data-config-id]');
    expect(rows.length).toBe(1);
    expect(rows[0].getAttribute('data-config-id')).toBe('cfg-1');
  });

  it('shows the empty state when no configs exist', () => {
    svc.list.and.returnValue(of([]));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="adt-intake-empty"]')).not.toBeNull();
  });

  it('shows the error state when list fails', () => {
    svc.list.and.returnValue(throwError(() => new Error('500')));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="adt-intake-error"]')).not.toBeNull();
  });

  it('rejects the save when hospital + provider are blank', () => {
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      save: (e: Event) => void;
    };
    component.save({ preventDefault: () => undefined } as unknown as Event);
    expect(svc.upsert).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
  });

  it('upserts and reloads on successful submit', () => {
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      form: Record<string, unknown>;
      save: (e: Event) => void;
    };
    component.form['hospitalId'] = 'h2';
    component.form['admittingProviderId'] = 'p2';
    component.save({ preventDefault: () => undefined } as unknown as Event);

    expect(svc.upsert).toHaveBeenCalledOnceWith(
      jasmine.objectContaining({
        hospitalId: 'h2',
        admittingProviderId: 'p2',
        enabled: false,
      }),
    );
    expect(toast.success).toHaveBeenCalled();
    // Reload triggered after save.
    expect(svc.list).toHaveBeenCalledTimes(2);
  });
});
