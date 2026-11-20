import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { ImagingReportingSettingsComponent } from './imaging-reporting-settings.component';
import { ImagingPacsService } from '../../services/imaging-pacs.service';
import { ToastService } from '../../core/toast.service';

/** Policy rule 3 (2026-11-20, provisional): « Échographie : compte rendu par un non-radiologue ». */
describe('ImagingReportingSettingsComponent (policy rule 3)', () => {
  let pacs: jasmine.SpyObj<ImagingPacsService>;
  let toast: jasmine.SpyObj<ToastService>;

  function render(): ComponentFixture<ImagingReportingSettingsComponent> {
    TestBed.configureTestingModule({
      imports: [ImagingReportingSettingsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: ImagingPacsService, useValue: pacs },
        { provide: ToastService, useValue: toast },
      ],
    });
    const fixture = TestBed.createComponent(ImagingReportingSettingsComponent);
    fixture.componentRef.setInput('hospitalId', 'h1');
    fixture.componentInstance.ngOnChanges();
    fixture.detectChanges();
    return fixture;
  }

  const el = (fixture: ComponentFixture<unknown>, testid: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testid}"]`);

  beforeEach(() => {
    pacs = jasmine.createSpyObj('ImagingPacsService', [
      'reportingSettings',
      'setReportingSettings',
    ]);
    toast = jasmine.createSpyObj('ToastService', ['success', 'error']);
    pacs.reportingSettings.and.returnValue(
      of({ hospitalId: 'h1', ultrasoundByNonPhysician: false }),
    );
  });

  it('opens on physicians only and says what the setting does; the save is never silently disabled', () => {
    pacs.setReportingSettings.and.returnValue(
      of({ hospitalId: 'h1', ultrasoundByNonPhysician: false }),
    );
    const fixture = render();
    expect(pacs.reportingSettings).toHaveBeenCalledWith('h1');
    expect(fixture.componentInstance.choice).toBe('PHYSICIANS_ONLY');
    expect(el(fixture, 'us-reporting-provisional')).not.toBeNull();
    expect((el(fixture, 'us-reporting-save') as HTMLButtonElement).disabled).toBeFalse();
    // An unchanged choice is sent as it is: the server writes nothing for it.
    el(fixture, 'us-reporting-save')?.click();
    expect(pacs.setReportingSettings).toHaveBeenCalledWith('h1', false);
  });

  it('saves the hospital’s decision and shows what the server holds', () => {
    pacs.setReportingSettings.and.returnValue(
      of({ hospitalId: 'h1', ultrasoundByNonPhysician: true }),
    );
    const fixture = render();
    fixture.componentInstance.choice = 'NON_PHYSICIAN';
    fixture.componentInstance.save();
    expect(pacs.setReportingSettings).toHaveBeenCalledWith('h1', true);
    expect(fixture.componentInstance.saved()).toBe('NON_PHYSICIAN');
    expect(toast.success).toHaveBeenCalled();
  });

  it('keeps the stored value when the save is refused', () => {
    pacs.setReportingSettings.and.returnValue(throwError(() => new Error('refused')));
    const fixture = render();
    fixture.componentInstance.choice = 'NON_PHYSICIAN';
    fixture.componentInstance.save();
    expect(fixture.componentInstance.saved()).toBe('PHYSICIANS_ONLY');
    expect(toast.error).toHaveBeenCalled();
  });

  it('says so when the setting cannot be read, and offers no choice', () => {
    pacs.reportingSettings.and.returnValue(throwError(() => new Error('boom')));
    const fixture = render();
    expect(el(fixture, 'us-reporting-save')).toBeNull();
    expect(fixture.componentInstance.loadFailed()).toBeTrue();
  });

  it('fits its box at 1366, 1024, 768 and 390 px wide', () => {
    const fixture = render();
    const host = fixture.nativeElement as HTMLElement;
    host.style.display = 'block';
    for (const width of [1366, 1024, 768, 390]) {
      host.style.width = `${width - 32}px`;
      fixture.detectChanges();
      const section = el(fixture, 'imaging-reporting-settings')!;
      expect(section.scrollWidth)
        .withContext(`reporting settings at ${width}px`)
        .toBeLessThanOrEqual(section.clientWidth + 1);
    }
  });
});
