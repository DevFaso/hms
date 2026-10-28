import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { NursingNotesPanelComponent } from './nursing-notes-panel.component';
import { NursingNote, NursingNoteService } from '../../services/nursing-note.service';

describe('NursingNotesPanelComponent (QA D927)', () => {
  let service: jasmine.SpyObj<NursingNoteService>;

  const dar: NursingNote = {
    id: 'n1',
    patientId: 'p1',
    template: 'DAR',
    dataSubjective: 'Douleur 7/10 post-morphine',
    actionSummary: 'Réévaluation douleur post-morphine',
    responseSummary: 'Douleur 3/10 à 30 min',
    authorName: 'Awa Traoré',
    documentedAt: '2026-10-27T10:30:00',
  };
  const older: NursingNote = {
    id: 'n0',
    patientId: 'p1',
    template: 'SOAPIE',
    narrative: 'Visite précédente',
    documentedAt: '2026-09-01T09:00:00',
  };

  function render(since: string | null) {
    TestBed.configureTestingModule({
      imports: [NursingNotesPanelComponent, TranslateModule.forRoot()],
      providers: [{ provide: NursingNoteService, useValue: service }],
    });
    const fixture = TestBed.createComponent(NursingNotesPanelComponent);
    fixture.componentInstance.patientId = 'p1';
    fixture.componentInstance.hospitalId = 'h1';
    fixture.componentInstance.since = since;
    fixture.componentInstance.ngOnChanges();
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj('NursingNoteService', ['listRecent']);
  });

  it('shows the care note the nurse wrote, with its DAR parts and author', () => {
    service.listRecent.and.returnValue(of([older, dar]));
    const fixture = render('2026-10-27T08:00:00');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(service.listRecent).toHaveBeenCalledWith('p1', 'h1');
    expect(text).toContain('Réévaluation douleur post-morphine');
    expect(text).toContain('Douleur 3/10 à 30 min');
    expect(text).toContain('Awa Traoré');
    // A note from an earlier visit is not this visit's.
    expect(text).not.toContain('Visite précédente');
  });

  it('lists every note newest first when no visit start is given', () => {
    service.listRecent.and.returnValue(of([older, dar]));
    const fixture = render(null);
    expect(fixture.componentInstance.notes().map((n) => n.id)).toEqual(['n1', 'n0']);
  });

  it('says so when the notes cannot be loaded', () => {
    service.listRecent.and.returnValue(throwError(() => new Error('boom')));
    const fixture = render(null);
    expect(fixture.componentInstance.failed()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'NURSING_NOTES.LOAD_FAILED',
    );
  });
});
