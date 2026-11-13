import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { UnsignedNotesPageComponent } from './unsigned-notes-page';
import { UnsignedNoteOverdue, UnsignedNotesService } from './unsigned-notes.service';

/** Provisional platform rule: the administrator's list of notes unsigned for more than 72 hours. */
describe('UnsignedNotesPageComponent', () => {
  let service: jasmine.SpyObj<UnsignedNotesService>;

  const row: UnsignedNoteOverdue = {
    noteId: 'n1',
    encounterId: 'e1',
    authorName: 'Dr Issa Ouédraogo',
    patientName: 'Awa Kaboré',
    visitDate: '2026-11-08T09:00:00',
    writtenAt: '2026-11-08T09:30:00',
    ageHours: 98,
  };

  beforeEach(() => {
    service = jasmine.createSpyObj<UnsignedNotesService>('UnsignedNotesService', ['getOverdue']);
    TestBed.configureTestingModule({
      imports: [UnsignedNotesPageComponent, TranslateModule.forRoot()],
      providers: [{ provide: UnsignedNotesService, useValue: service }],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      UNSIGNED_NOTES: {
        TITLE: 'Notes non signées depuis plus de 72 heures',
        EMPTY: 'Aucune note n’est restée non signée plus de 72 heures.',
        AGE_VALUE: '{{days}} j {{hours}} h',
      },
    });
    translate.use('fr');
  });

  function render() {
    const fixture = TestBed.createComponent(UnsignedNotesPageComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('lists the author, the patient and how long the note has waited', () => {
    service.getOverdue.and.returnValue(of([row]));
    const host = render();
    const cells = Array.from(host.querySelectorAll('tbody td')).map((td) => td.textContent?.trim());
    expect(cells[0]).toBe('Dr Issa Ouédraogo');
    expect(cells[1]).toBe('Awa Kaboré');
    expect(cells[3]).toBe('4 j 2 h');
  });

  it('says so when no note is overdue', () => {
    service.getOverdue.and.returnValue(of([]));
    const host = render();
    expect(host.querySelector('[data-testid="unsigned-notes-empty"]')?.textContent).toContain(
      'Aucune note',
    );
  });

  it('shows an error instead of an empty list when the read fails', () => {
    service.getOverdue.and.returnValue(throwError(() => new Error('403')));
    const host = render();
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="unsigned-notes-empty"]')).toBeNull();
  });
});
