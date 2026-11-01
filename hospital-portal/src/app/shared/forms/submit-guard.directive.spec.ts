import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { SubmitGuardDirective } from './submit-guard.directive';

@Component({
  standalone: true,
  imports: [FormsModule, SubmitGuardDirective],
  template: `
    <form (ngSubmit)="submitted = submitted + 1">
      <label for="med">Médicament *</label>
      <input id="med" name="med" required [(ngModel)]="med" />
      <label for="ack"
        ><input id="ack" name="ack" type="checkbox" [(ngModel)]="ack" /> J'accepte</label
      >
      <button
        id="save"
        type="submit"
        [disabled]="saving()"
        [appSubmitGuard]="guard()"
        [submitGuardFields]="fields()"
        (click)="clicked = clicked + 1"
      >
        Créer
      </button>
    </form>
  `,
})
class HostComponent {
  med = '';
  ack = false;
  clicked = 0;
  submitted = 0;
  readonly saving = signal(false);
  readonly guard = signal<unknown>(true);
  readonly fields = signal<string[] | null>(['med']);
}

describe('SubmitGuardDirective (QA D1124 / D1089)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent, TranslateModule.forRoot()] });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      COMMON: {
        FIELD_REQUIRED: 'Ce champ est obligatoire.',
        MISSING_REQUIRED_FIELDS: 'Champs obligatoires manquants : {{fields}}',
        SUBMIT_BLOCKED_REQUIRED: 'Complétez d’abord les champs obligatoires (marqués *).',
      },
    });
    translate.use('fr');
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    document.body.appendChild(el);
  });

  afterEach(() => el.remove());

  const button = () => el.querySelector('#save') as HTMLButtonElement;
  const reason = () => el.querySelector('[data-testid="submit-guard-reason"]');

  it('keeps the button clickable: a click is refused, the blank field is named and marked, focus goes to it', fakeAsync(() => {
    expect(button().disabled).toBeFalse();
    button().click();
    tick();
    fixture.detectChanges();

    expect(host.clicked).toBe(0);
    expect(host.submitted).toBe(0);
    expect(reason()?.textContent).toBe('Champs obligatoires manquants : Médicament');
    expect(reason()?.getAttribute('role')).toBe('alert');
    expect(button().getAttribute('aria-describedby')).toContain(reason()!.id);
    const med = el.querySelector('#med') as HTMLInputElement;
    expect(med.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(med);
  }));

  it('says a reason that is not a field as given', () => {
    host.guard.set('Choisissez d’abord un établissement.');
    fixture.detectChanges();
    button().click();
    expect(host.clicked).toBe(0);
    expect(reason()?.textContent).toBe('Choisissez d’abord un établissement.');
  });

  it('marks the listed fields only, an unticked box included', () => {
    host.fields.set(['ack']);
    fixture.detectChanges();
    button().click();
    expect((el.querySelector('#ack') as HTMLInputElement).getAttribute('aria-invalid')).toBe(
      'true',
    );
    expect((el.querySelector('#med') as HTMLInputElement).hasAttribute('aria-invalid')).toBeFalse();
    expect(reason()?.textContent).toBe("Champs obligatoires manquants : J'accepte");
  });

  it('without listed fields it marks nothing (a secondary action never flags the whole form)', () => {
    host.fields.set(null);
    fixture.detectChanges();
    button().click();
    expect(host.clicked).toBe(0);
    expect((el.querySelector('#med') as HTMLInputElement).hasAttribute('aria-invalid')).toBeFalse();
    expect(reason()?.textContent).toBe('Complétez d’abord les champs obligatoires (marqués *).');
  });

  it('falls back to the general sentence when no field is blank', () => {
    host.med = 'Amoxicilline';
    fixture.detectChanges();
    (el.querySelector('#med') as HTMLInputElement).value = 'Amoxicilline';
    button().click();
    expect(reason()?.textContent).toBe('Complétez d’abord les champs obligatoires (marqués *).');
  });

  it('lets the click through once the form may be sent, and clears the reason and the marks', () => {
    button().click();
    expect(reason()).not.toBeNull();

    host.guard.set(false);
    fixture.detectChanges();
    expect(reason()).toBeNull();
    expect(button().hasAttribute('aria-describedby')).toBeFalse();
    expect((el.querySelector('#med') as HTMLInputElement).hasAttribute('aria-invalid')).toBeFalse();

    button().click();
    expect(host.clicked).toBe(1);
    expect(host.submitted).toBe(1);
  });

  it('only a request in flight disables the button', () => {
    host.saving.set(true);
    fixture.detectChanges();
    expect(button().disabled).toBeTrue();
  });
});

/**
 * QA D1193 / D1089 (round 22): the lab instrument and inventory forms posted
 * empty — `required` on the fields, nothing on the button.
 */
@Component({
  standalone: true,
  imports: [FormsModule, SubmitGuardDirective],
  template: `
    <form (ngSubmit)="submitted = submitted + 1">
      <label
        >Nom *
        <input type="text" name="name" required [(ngModel)]="name" />
      </label>
      <label
        >N° de série *
        <input type="text" name="serial" required [(ngModel)]="serial" />
      </label>
      <label>Notes <textarea name="notes" [(ngModel)]="notes"></textarea></label>
      <button id="create" type="submit" appSubmitGuard submitGuardRequired>Créer</button>
    </form>
  `,
})
class RequiredFormHostComponent {
  name = '';
  serial = '';
  notes = '';
  submitted = 0;
}

describe('SubmitGuardDirective submitGuardRequired (QA D1193)', () => {
  let fixture: ComponentFixture<RequiredFormHostComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RequiredFormHostComponent, TranslateModule.forRoot()],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      COMMON: {
        FIELD_REQUIRED: 'Ce champ est obligatoire.',
        MISSING_REQUIRED_FIELDS: 'Champs obligatoires manquants : {{fields}}',
      },
    });
    translate.use('fr');
    fixture = TestBed.createComponent(RequiredFormHostComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  });

  it('an empty form is not sent; every blank required field is marked and named', fakeAsync(() => {
    el.querySelector<HTMLButtonElement>('#create')!.click();
    fixture.detectChanges();
    tick();
    expect(fixture.componentInstance.submitted).toBe(0);
    const fields = el.querySelectorAll('input[required]');
    fields.forEach((f) => expect(f.getAttribute('aria-invalid')).toBe('true'));
    expect(el.querySelector('[data-testid="submit-guard-reason"]')?.textContent?.trim()).toBe(
      'Champs obligatoires manquants : Nom, N° de série',
    );
    expect(el.querySelector('textarea')?.getAttribute('aria-invalid')).toBeNull();
  }));

  it('once every required field is filled the form is sent and the refusal goes away', fakeAsync(() => {
    el.querySelector<HTMLButtonElement>('#create')!.click();
    fixture.detectChanges();
    tick();
    const [name, serial] = Array.from(el.querySelectorAll<HTMLInputElement>('input[required]'));
    name.value = 'Automate Mindray';
    name.dispatchEvent(new Event('input'));
    serial.value = 'SN-001';
    serial.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('#create')!.click();
    fixture.detectChanges();
    tick();
    expect(fixture.componentInstance.submitted).toBe(1);
    expect(el.querySelector('[data-testid="submit-guard-reason"]')).toBeNull();
    expect(name.getAttribute('aria-invalid')).toBeNull();
  }));
});
