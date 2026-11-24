import { Directive } from '@angular/core';

/** Design-system skin for a native control. No value accessor, validator or event handler. */
@Directive({
  selector: 'input[appFormControl], select[appFormControl], textarea[appFormControl]',
  standalone: true,
  host: { class: 'keneya-form-control' },
})
export class FormControlStyleDirective {}
