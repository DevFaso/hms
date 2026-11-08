import { Component, ViewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule, NgModel } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';

import { PhoneFieldDirective } from './phone-field.directive';

@Component({
  standalone: true,
  imports: [FormsModule, PhoneFieldDirective],
  template: `<label for="ec">Contact</label>
    <input id="ec" type="tel" appPhoneField [(ngModel)]="phone" #model="ngModel" />`,
})
class HostComponent {
  phone = '';
  @ViewChild('model') model!: NgModel;
}

/**
 * QA D1211 (round 23, portal half): a non-number in a secondary or contact
 * phone was refused by the server only, in a banner after the save. The field
 * now says so itself, with the server's rule.
 */
describe('PhoneFieldDirective (QA D1211)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let input: HTMLInputElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HostComponent, TranslateModule.forRoot()],
    }).compileComponents();
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    input = (fixture.nativeElement as HTMLElement).querySelector('input')!;
  });

  function type(value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('marks "appeler le voisin" under the field once it is left, and the control is invalid', () => {
    type('appeler le voisin');
    expect(fixture.componentInstance.model.errors).toEqual({ phone: true });
    expect(input.getAttribute('aria-invalid'))
      .withContext('not before the field is left')
      .toBeNull();

    input.dispatchEvent(new Event('blur'));
    const msg = (fixture.nativeElement as HTMLElement).querySelector('#ec-invalid-msg');
    expect(msg?.textContent).toBe('USERS.PHONE_INVALID');
    expect(msg?.getAttribute('role')).toBe('alert');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe('ec-invalid-msg');
  });

  it('clears the mark as soon as the value is a number, and never refuses a blank field', () => {
    type('12ab');
    input.dispatchEvent(new Event('blur'));
    type('70 20 20 20');
    expect(fixture.componentInstance.model.errors).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('#ec-invalid-msg')).toBeNull();
    expect(input.hasAttribute('aria-invalid')).toBeFalse();
    expect(input.hasAttribute('aria-describedby')).toBeFalse();

    type('');
    input.dispatchEvent(new Event('blur'));
    expect(fixture.componentInstance.model.errors).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('#ec-invalid-msg')).toBeNull();
  });
});
