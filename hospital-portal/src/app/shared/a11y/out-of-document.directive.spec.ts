import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';

import { OutOfDocumentDirective } from './out-of-document.directive';

@Component({
  standalone: true,
  imports: [OutOfDocumentDirective, FormsModule],
  template: `
    <div class="page" [appOutOfDocument]="locked()">
      <h1>Awa TestPortail — VIH</h1>
      <input id="note" [(ngModel)]="note" />
      @if (extra()) {
        <p class="late">added while locked</p>
      }
    </div>
    <div class="lock">locked</div>
  `,
})
class HostComponent {
  readonly locked = signal(false);
  readonly extra = signal(false);
  note = '';
}

/**
 * r81 D4262: what is behind a lock screen is not in the document while the
 * screen is locked, and comes back as it was when it is unlocked.
 */
describe('OutOfDocumentDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
    fixture.detectChanges();
  });

  afterEach(() => host.remove());

  it('leaves nothing of the page readable in the document while locked', () => {
    expect(document.body.innerText).toContain('Awa TestPortail');

    fixture.componentInstance.locked.set(true);
    fixture.detectChanges();

    expect(host.querySelector('.page')).toBeNull();
    expect(host.querySelector('#note')).toBeNull();
    expect(host.textContent).not.toContain('Awa TestPortail');
    expect(document.body.innerText).not.toContain('Awa TestPortail');
    // The lock screen itself, a sibling, stays.
    expect(host.querySelector('.lock')).not.toBeNull();
  });

  it('puts the same page back on unlock, with what was typed and what changed meanwhile', () => {
    const page = host.querySelector('.page')!;
    const input = host.querySelector<HTMLInputElement>('#note')!;
    input.value = 'half-written note';
    input.dispatchEvent(new Event('input'));

    fixture.componentInstance.locked.set(true);
    fixture.detectChanges();
    // The page keeps living while it is out: a change made meanwhile is not lost.
    fixture.componentInstance.extra.set(true);
    fixture.detectChanges();
    expect(host.querySelector('.late')).toBeNull();

    fixture.componentInstance.locked.set(false);
    fixture.detectChanges();

    expect(host.querySelector('.page')).toBe(page);
    expect(host.querySelector<HTMLInputElement>('#note')!.value).toBe('half-written note');
    expect(fixture.componentInstance.note).toBe('half-written note');
    expect(host.querySelector('.late')).not.toBeNull();
    // Before the lock screen, where it was.
    expect(page.nextElementSibling).toBe(host.querySelector('.lock'));
  });

  it('a page destroyed while locked is not put back', () => {
    fixture.componentInstance.locked.set(true);
    fixture.detectChanges();

    fixture.destroy();

    expect(document.body.innerText).not.toContain('Awa TestPortail');
  });
});
