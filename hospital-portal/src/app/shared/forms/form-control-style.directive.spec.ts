import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule, NgForm } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { FormControlStyleDirective } from './form-control-style.directive';

@Component({
  imports: [FormsModule, FormControlStyleDirective],
  template: `<form>
    <input
      appFormControl
      class="existing-class"
      name="name"
      [(ngModel)]="name"
      required
      maxlength="20"
      [disabled]="disabled"
    />
    <select appFormControl name="choice" [(ngModel)]="choice">
      <option value="one">One</option>
      <option value="two">Two</option>
    </select>
    <textarea appFormControl name="note" [(ngModel)]="note"></textarea>
  </form>`,
})
class NativeForm {
  name = '';
  choice = 'one';
  note = '';
  disabled = false;
}

@Component({
  template:
    '<input appFormControl><select appFormControl><option>One</option></select><textarea appFormControl></textarea>',
})
class AttributeOnlyForm {}

describe('design-system native form controls', () => {
  it('styles the native attribute even when the optional directive import is missing', () => {
    const fixture = TestBed.createComponent(AttributeOnlyForm);
    fixture.detectChanges();
    for (const control of fixture.nativeElement.querySelectorAll('input, select, textarea')) {
      expect(control.classList.contains('keneya-form-control')).toBeFalse();
      expect(parseFloat(getComputedStyle(control).minHeight)).toBeGreaterThanOrEqual(44);
      expect(getComputedStyle(control).boxSizing).toBe('border-box');
    }
    expect(
      parseFloat(getComputedStyle(fixture.nativeElement.querySelector('textarea')).minHeight),
    ).toBeGreaterThanOrEqual(80);
  });
  it('retains native elements, existing classes, validators and disabled state', async () => {
    const fixture = TestBed.createComponent(NativeForm);
    fixture.detectChanges();
    await fixture.whenStable();
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    const form = fixture.debugElement.query(By.directive(NgForm)).injector.get(NgForm);
    expect(input.classList.contains('existing-class')).toBeTrue();
    expect(input.classList.contains('keneya-form-control')).toBeTrue();
    expect(input.required).toBeTrue();
    expect(input.maxLength).toBe(20);
    expect(form.controls['name'].hasError('required')).toBeTrue();
    fixture.componentInstance.disabled = true;
    fixture.detectChanges();
    await fixture.whenStable();
    expect(input.disabled).toBeTrue();
    expect(form.controls['name'].disabled).toBeTrue();
    for (const control of fixture.nativeElement.querySelectorAll('input, select, textarea')) {
      expect(parseFloat(getComputedStyle(control).minHeight)).toBeGreaterThanOrEqual(44);
      expect(control.parentElement.tagName).toBe('FORM');
    }
  });

  it('uses the existing native Angular value accessors for typing and selection', async () => {
    const fixture = TestBed.createComponent(NativeForm);
    fixture.detectChanges();
    await fixture.whenStable();
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    input.value = 'cdx Name';
    input.dispatchEvent(new Event('input'));
    const select = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    select.value = 'two';
    select.dispatchEvent(new Event('change'));
    const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = 'cdx Note';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.name).toBe('cdx Name');
    expect(fixture.componentInstance.choice).toBe('two');
    expect(fixture.componentInstance.note).toBe('cdx Note');
  });
});
