import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { RoleLabelPipe } from './role-label.pipe';

/**
 * The bug this pipe exists for: a French admin opening the user-registration
 * form saw the raw registry, twenty-six rows of it —
 *
 *     ROLE_LAB_SCIENTIST   ROLE_MIDWIFE   ROLE_PHARMACY_VERIFIER   …
 *
 * — even though every one of those labels had been translated. The vocabulary
 * was keyed under `PORTAL.ENUM.ROLE` in the BARE form (`MIDWIFE`), the wire
 * sends `ROLE_MIDWIFE`, and nothing bridged the two.
 */
describe('RoleLabelPipe', () => {
  let pipe: RoleLabelPipe;
  let translate: TranslateService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TranslateModule.forRoot()] });
    translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        ENUM: {
          ROLE: {
            MIDWIFE: 'Sage-femme',
            LAB_SCIENTIST: 'Biologiste de laboratoire',
            PHARMACY_VERIFIER: 'Vérificateur(trice) en pharmacie',
          },
        },
      },
    });
    pipe = TestBed.runInInjectionContext(() => new RoleLabelPipe());
  });

  afterEach(() => pipe.ngOnDestroy());

  it('translates the prefixed token the registry actually sends', () => {
    expect(pipe.transform('ROLE_MIDWIFE')).toBe('Sage-femme');
    expect(pipe.transform('ROLE_LAB_SCIENTIST')).toBe('Biologiste de laboratoire');
    expect(pipe.transform('ROLE_PHARMACY_VERIFIER')).toBe('Vérificateur(trice) en pharmacie');
  });

  it('translates the bare token too, which the write-audit path writes', () => {
    expect(pipe.transform('MIDWIFE')).toBe('Sage-femme');
  });

  it('renders nothing for a missing role, so a template fallback can take over', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform('   ')).toBe('');
    // The sentence AuditEventLogServiceImpl stamps when it cannot resolve one.
    expect(pipe.transform('Unknown Role')).toBe('');
  });

  it('Title-Cases a role an admin created at runtime rather than showing the token', () => {
    // The Roles screen can create any role; no key can exist for it in advance.
    expect(pipe.transform('ROLE_BLOOD_BANK_OFFICER')).toBe('Blood Bank Officer');
  });

  it('follows a language switch', () => {
    translate.setTranslation('en', { PORTAL: { ENUM: { ROLE: { MIDWIFE: 'Midwife' } } } }, true);
    expect(pipe.transform('ROLE_MIDWIFE')).toBe('Sage-femme');
    translate.use('en');
    expect(pipe.transform('ROLE_MIDWIFE')).toBe('Midwife');
  });
});

@Component({
  standalone: true,
  imports: [RoleLabelPipe],
  template: `
    <span id="filter">{{ role.name | roleLabel }}</span>
    <span id="checkbox">{{ role.name || role.code | roleLabel }}</span>
    <span id="codeOnly">{{ nameless.name || nameless.code | roleLabel }}</span>
    <span id="empty">{{ (missing | roleLabel) || '—' }}</span>
  `,
})
class RoleHostComponent {
  role = { name: 'ROLE_MIDWIFE', code: 'ROLE_MIDWIFE' };
  nameless = { name: '', code: 'ROLE_MIDWIFE' };
  missing: string | null = null;
}

describe('RoleLabelPipe — the expression shapes the templates use', () => {
  function text(id: string): string {
    const fixture = TestBed.createComponent(RoleHostComponent);
    fixture.detectChanges();
    return (
      (fixture.nativeElement as HTMLElement).querySelector(`#${id}`)?.textContent?.trim() ?? ''
    );
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [RoleHostComponent, TranslateModule.forRoot()] });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: { ENUM: { ROLE: { MIDWIFE: 'Sage-femme' } } },
    });
  });

  it('translates a plain binding', () => {
    expect(text('filter')).toBe('Sage-femme');
  });

  it('applies to the whole `a || b`, as the registration checkboxes write it', () => {
    // Worth pinning because it is easy to assume otherwise: the pipe operator
    // has the LOWEST precedence in an Angular template, so `a || b | roleLabel`
    // is `(a || b) | roleLabel` and not `a || (b | roleLabel)`. If it were the
    // latter, a truthy `role.name` would bypass the pipe and put the raw token
    // straight back on screen.
    expect(text('checkbox')).toBe('Sage-femme');
  });

  it('still translates when only the code is set', () => {
    expect(text('codeOnly')).toBe('Sage-femme');
  });

  it('leaves a template fallback usable for a role that resolves to nothing', () => {
    expect(text('empty')).toBe('—');
  });
});
