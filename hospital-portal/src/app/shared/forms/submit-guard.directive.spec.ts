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

    // QA D1245: the listed field declares `required`, so it is filled first.
    (el.querySelector('#med') as HTMLInputElement).value = 'Amoxicilline';
    button().click();
    expect(host.clicked).toBe(1);
    expect(host.submitted).toBe(1);
  });

  it('QA D1245: a listed required field that is blank is refused even when the expression lets it through', () => {
    host.guard.set(false);
    fixture.detectChanges();
    button().click();
    expect(host.clicked).toBe(0);
    expect(host.submitted).toBe(0);
    expect(reason()?.textContent).toBe('Champs obligatoires manquants : Médicament');
    expect((el.querySelector('#med') as HTMLInputElement).getAttribute('aria-invalid')).toBe(
      'true',
    );
  });

  it('QA D1245: a listed field that is not required follows the expression', () => {
    host.guard.set(false);
    host.fields.set(['ack']);
    fixture.detectChanges();
    button().click();
    expect(host.clicked).toBe(1);
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

/**
 * QA D1245 (D1089 / D1124 NOT_FIXED in round 25): the prescription dialog is
 * not a form; its patient search input held the typed query but no pick, and
 * its "Créer" let `patientId: ''` through.
 */
@Component({
  standalone: true,
  imports: [FormsModule, SubmitGuardDirective],
  template: `
    <div class="modal">
      <label for="rx-patient">Nom du patient *</label>
      <input
        id="rx-patient"
        required
        [attr.data-submit-blank]="patientId() ? 'false' : 'true'"
        [value]="query"
      />
      <label for="rx-reason">Motif *</label>
      <textarea id="rx-reason" aria-required="true" [(ngModel)]="reason"></textarea>
      <button id="rx-create" [appSubmitGuard]="!med" submitGuardRequired (click)="sent = sent + 1">
        Créer
      </button>
    </div>
    <section data-submit-scope>
      <div>
        <div>
          <span><input id="far" required [(ngModel)]="far" /></span>
        </div>
      </div>
      <div class="actions">
        <span
          ><button id="far-save" appSubmitGuard submitGuardRequired (click)="farSent = farSent + 1">
            OK
          </button></span
        >
      </div>
    </section>
  `,
})
class DialogHostComponent {
  readonly patientId = signal('');
  query = 'Awa';
  reason = '';
  med = 'Paracétamol';
  sent = 0;
  far = '';
  farSent = 0;
}

describe('SubmitGuardDirective outside a form (QA D1245)', () => {
  let fixture: ComponentFixture<DialogHostComponent>;
  let el: HTMLElement;
  const msg = () => el.querySelector('#rx-create + [data-testid="submit-guard-reason"]');

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [DialogHostComponent, TranslateModule.forRoot()] });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      COMMON: {
        FIELD_REQUIRED: 'Ce champ est obligatoire.',
        MISSING_REQUIRED_FIELDS: 'Champs obligatoires manquants : {{fields}}',
      },
    });
    translate.use('fr');
    fixture = TestBed.createComponent(DialogHostComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  });

  it('a typed query with no patient picked is blank: the dialog names the patient and sends nothing', fakeAsync(() => {
    el.querySelector<HTMLButtonElement>('#rx-create')!.click();
    fixture.detectChanges();
    tick();
    expect(fixture.componentInstance.sent).toBe(0);
    expect(msg()?.textContent).toBe('Champs obligatoires manquants : Nom du patient, Motif');
    expect(el.querySelector('#rx-patient')?.getAttribute('aria-invalid')).toBe('true');
  }));

  it('aria-required counts as required, and a picked patient lets the click through', fakeAsync(() => {
    const app = fixture.componentInstance;
    app.patientId.set('p-1');
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('#rx-create')!.click();
    tick();
    expect(app.sent).toBe(0);
    expect(msg()?.textContent).toBe('Champs obligatoires manquants : Motif');
    const reason = el.querySelector<HTMLTextAreaElement>('#rx-reason')!;
    reason.value = 'Douleur';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('#rx-create')!.click();
    tick();
    expect(app.sent).toBe(1);
  }));

  it('a data-submit-scope section is where the fields are read, however deep the button sits', fakeAsync(() => {
    el.querySelector<HTMLButtonElement>('#far-save')!.click();
    fixture.detectChanges();
    tick();
    expect(fixture.componentInstance.farSent).toBe(0);
    expect(el.querySelector('#far')?.getAttribute('aria-invalid')).toBe('true');
  }));
});
