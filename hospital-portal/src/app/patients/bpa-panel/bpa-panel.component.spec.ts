import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of, throwError } from 'rxjs';

import { BpaPanelComponent } from './bpa-panel.component';
import { BpaService } from '../../services/bpa.service';
import { CdsCard } from '../../shared/cds-card/cds-card.model';

describe('BpaPanelComponent', () => {
  let fixture: ComponentFixture<BpaPanelComponent>;
  let bpaSpy: jasmine.SpyObj<BpaService>;

  beforeEach(async () => {
    bpaSpy = jasmine.createSpyObj<BpaService>('BpaService', ['evaluate']);

    await TestBed.configureTestingModule({
      imports: [BpaPanelComponent, TranslateModule.forRoot()],
      providers: [{ provide: BpaService, useValue: bpaSpy }],
    }).compileComponents();

    fixture = TestBed.createComponent(BpaPanelComponent);
  });

  function setPatient(id: string | null | undefined): void {
    fixture.componentRef.setInput('patientId', id);
    fixture.detectChanges();
  }

  it('renders the empty state when no patientId is provided', () => {
    setPatient(null);
    const root = fixture.nativeElement.querySelector('[data-testid="bpa-panel"]');
    // The whole panel is hidden when patientId is null.
    expect(root).toBeNull();
    expect(bpaSpy.evaluate).not.toHaveBeenCalled();
  });

  it('calls the BPA service with the patientId on init and renders the cards', () => {
    const cards: CdsCard[] = [sampleCard('warning', 'Sepsis — Hour-1 bundle (qSOFA ≥2)')];
    bpaSpy.evaluate.and.returnValue(of(cards));

    setPatient('p-123');

    expect(bpaSpy.evaluate).toHaveBeenCalledOnceWith('p-123', undefined);
    expect(
      fixture.nativeElement.querySelector('[data-testid="bpa-panel"]').getAttribute('data-state'),
    ).toBe('ready');
    expect(fixture.nativeElement.querySelector('[data-testid="bpa-panel-cards"]')).not.toBeNull();
  });

  it('renders the empty-state message when the service returns no cards', () => {
    bpaSpy.evaluate.and.returnValue(of([]));

    setPatient('p-empty');

    expect(fixture.nativeElement.querySelector('[data-testid="bpa-panel-empty"]')).not.toBeNull();
  });

  it('renders the error state when the service errors', () => {
    bpaSpy.evaluate.and.returnValue(
      throwError(() => new Error('network')) as Observable<CdsCard[]>,
    );

    setPatient('p-err');

    expect(fixture.nativeElement.querySelector('[data-testid="bpa-panel-error"]')).not.toBeNull();
  });

  it('forwards encounterId to the service when provided', () => {
    bpaSpy.evaluate.and.returnValue(of([]));
    fixture.componentRef.setInput('patientId', 'p-1');
    fixture.componentRef.setInput('encounterId', 'enc-9');
    fixture.detectChanges();

    expect(bpaSpy.evaluate).toHaveBeenCalledOnceWith('p-1', 'enc-9');
  });
});

function sampleCard(indicator: 'info' | 'warning' | 'critical', summary: string): CdsCard {
  return {
    summary,
    indicator,
    source: { label: 'HMS Best-Practice Advisory' },
    uuid: 'card-' + summary,
  };
}
