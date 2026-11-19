import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { NoteAddendaComponent } from './note-addenda.component';
import { NoteAddendumView } from './note-addendum-view';

describe('NoteAddendaComponent (QA D2260)', () => {
  let fixture: ComponentFixture<NoteAddendaComponent>;

  function render(addenda: NoteAddendumView[] | null): HTMLElement {
    TestBed.configureTestingModule({
      imports: [NoteAddendaComponent, TranslateModule.forRoot()],
    });
    fixture = TestBed.createComponent(NoteAddendaComponent);
    fixture.componentRef.setInput('addenda', addenda);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows each addendum under the note, oldest first, dated and signed by its author', () => {
    const host = render([
      { content: 'Second', authorName: 'Dr B', signedAt: '2026-11-19T06:00:00' },
      { content: 'CRP à 85 mg/L', authorName: 'Dr A', signedAt: '2026-11-19T04:13:00' },
    ]);
    const items = Array.from(host.querySelectorAll('[data-testid="note-addendum"]'));
    expect(items.length).toBe(2);
    expect(items[0].querySelector('[data-testid="note-addendum-content"]')?.textContent).toContain(
      'CRP à 85 mg/L',
    );
    expect(items[0].querySelector('[data-testid="note-addendum-meta"]')?.textContent).toContain(
      'NOTE_ADDENDA.SIGNED_BY',
    );
    expect(items[0].querySelector('time')?.textContent?.trim()).not.toBe('');
    expect(items[1].textContent).toContain('Second');
  });

  it('says « not attested », never « signed by », on an addendum saved without attestation', () => {
    const host = render([
      { content: 'Draft', authorName: 'Dr A', documentedAt: '2026-11-19T04:13:00' },
    ]);
    const meta = host.querySelector('[data-testid="note-addendum-meta"]')?.textContent ?? '';
    expect(meta).toContain('NOTE_ADDENDA.NOT_ATTESTED');
    expect(meta).not.toContain('NOTE_ADDENDA.SIGNED_BY');
    expect(host.querySelector('time')?.textContent?.trim()).not.toBe('');
  });

  it('renders nothing when there is no addendum or only a blank one', () => {
    expect(render(null).querySelector('[data-testid="note-addenda"]')).toBeNull();
    TestBed.resetTestingModule();
    expect(render([{ content: '  ' }]).querySelector('[data-testid="note-addenda"]')).toBeNull();
  });
});
