import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { EncounterNoteFormComponent } from './encounter-note-form.component';
import { SmartPhraseService } from '../../services/smart-phrase.service';

/**
 * QA D1151 (round 21): an encounter note with every section empty was saved
 * (200) and then offered for signature.
 */
describe('EncounterNoteFormComponent — an empty note is refused (QA D1151)', () => {
  let fixture: ComponentFixture<EncounterNoteFormComponent>;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    const phrases = jasmine.createSpyObj<SmartPhraseService>('SmartPhraseService', [
      'autocomplete',
    ]);
    phrases.autocomplete.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [EncounterNoteFormComponent, TranslateModule.forRoot()],
      providers: [{ provide: SmartPhraseService, useValue: phrases }],
    });
    fixture = TestBed.createComponent(EncounterNoteFormComponent);
    fixture.detectChanges();
    // A submit button submits only a form that is in the document.
    document.body.appendChild(host());
  });

  afterEach(() => host().remove());

  const save = () => {
    host().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
  };

  it('Save on an empty note emits nothing and marks the first section', () => {
    const emitted = jasmine.createSpy('noteSubmit');
    fixture.componentInstance.noteSubmit.subscribe(emitted);
    save();
    expect(emitted).not.toHaveBeenCalled();
    expect(host().querySelector('#note-subjective')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('a note with one written section is sent, and the mark clears', () => {
    const emitted = jasmine.createSpy('noteSubmit');
    fixture.componentInstance.noteSubmit.subscribe(emitted);
    save();
    fixture.componentInstance.patch({ assessment: 'Paludisme simple' });
    fixture.detectChanges();
    save();
    expect(emitted).toHaveBeenCalledWith(
      jasmine.objectContaining({ assessment: 'Paludisme simple' }),
    );
    expect(host().querySelector('#note-subjective')?.getAttribute('aria-invalid')).toBeNull();
  });
});
