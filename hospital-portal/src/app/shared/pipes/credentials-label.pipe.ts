import { Pipe, PipeTransform, inject } from '@angular/core';

import { EnumLabelService } from '../../core/enum-label.service';

/**
 * QA D1136: an author's credentials in the reader's language. A nursing note
 * carries its author's job title as a code (`NURSE`, older rows "Nurse" are
 * mapped back to it by the server), shown in the reader's language;
 * credentials a clinician typed ("MD, FWACP", a
 * licence number) are shown exactly as written, never title-cased.
 *
 * Impure for the same reason as `enumLabel`: the label must follow the
 * translations once they have loaded.
 */
@Pipe({ name: 'credentialsLabel', standalone: true, pure: false })
export class CredentialsLabelPipe implements PipeTransform {
  private readonly labels = inject(EnumLabelService);

  transform(value: string | null | undefined): string {
    if (!value) return '';
    return this.labels.labelOrAsTyped(value.trim(), 'jobTitle');
  }
}
