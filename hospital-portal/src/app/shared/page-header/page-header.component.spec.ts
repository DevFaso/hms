import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { PageHeaderComponent } from './page-header.component';

@Component({
  standalone: true,
  imports: [PageHeaderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header
      title="Préférences de notification"
      subtitle="Choisissez comment vous êtes prévenu."
      eyebrow="Mon compte"
      icon="tune"
      backLink="/notifications"
      backLabel="Retour aux notifications"
    >
      <button class="btn-primary">Enregistrer</button>
    </app-page-header>
    <app-page-header class="bare" title="Utilisateurs" />
  `,
})
class HostComponent {}

/** QA D2791 (r65): one page header for every page. */
describe('PageHeaderComponent (QA D2791)', () => {
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    el = fixture.nativeElement;
  });

  it('renders one h1 whose name is the title alone, the icon hidden from screen readers', () => {
    const header = el.querySelector('app-page-header')!;
    const titles = header.querySelectorAll('h1');
    expect(titles.length).toBe(1);
    const icon = titles[0].querySelector('.material-symbols-outlined')!;
    expect(icon.getAttribute('aria-hidden')).toBe('true');
    icon.remove();
    expect(titles[0].textContent?.trim()).toBe('Préférences de notification');
  });

  it('shows the eyebrow, the subtitle, the way back and the projected actions', () => {
    const header = el.querySelector('app-page-header')!;
    expect(header.querySelector('.aph-eyebrow')?.textContent?.trim()).toBe('Mon compte');
    expect(header.querySelector('.aph-subtitle')?.textContent?.trim()).toBe(
      'Choisissez comment vous êtes prévenu.',
    );
    const back = header.querySelector<HTMLAnchorElement>('a.aph-back')!;
    expect(back.getAttribute('href')).toBe('/notifications');
    expect(back.textContent).toContain('Retour aux notifications');
    expect(header.querySelector('.aph-actions .btn-primary')?.textContent).toBe('Enregistrer');
    // The actions are a toolbar the global layer wraps (styles.scss `.page-actions`).
    expect(header.querySelector('.aph-actions')!.classList).toContain('page-actions');
  });

  it('renders nothing it was not given', () => {
    const bare = el.querySelector('app-page-header.bare')!;
    expect(bare.querySelector('h1')?.textContent?.trim()).toBe('Utilisateurs');
    for (const part of [
      '.aph-eyebrow',
      '.aph-subtitle',
      '.aph-back',
      'h1 .material-symbols-outlined',
    ]) {
      expect(bare.querySelector(part)).withContext(part).toBeNull();
    }
  });

  it('takes the one title size from the global layer inside the content area', () => {
    const content = document.createElement('main');
    content.className = 'content';
    document.body.appendChild(content);
    content.appendChild(el);
    const style = getComputedStyle(el.querySelector('h1')!);
    expect(style.fontWeight).toBe('700');
    expect(style.fontSize).toBe(window.innerWidth < 600 ? '20px' : '24px');
    content.remove();
  });
});
