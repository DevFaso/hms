import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';

import { FacilityType, ProviderEvidence } from '../../../services/provider.model';

/** The text fields of the business identity layer (the address is edited on its own). */
export type BusinessField =
  | 'legalName'
  | 'tradeName'
  | 'legalStructure'
  | 'rccmNumber'
  | 'ifuNumber'
  | 'cnssNumber'
  | 'companyPhone'
  | 'managerName'
  | 'managerTitle'
  | 'startedOn';

export type AddressField = 'secteur' | 'section' | 'lot' | 'parcelle' | 'city' | 'region';

export type ProfessionalField =
  | 'licenceNumber'
  | 'licenceAuthority'
  | 'licenceIssuedOn'
  | 'licenceExpiresOn'
  | 'responsibleName'
  | 'responsibleOrdreNumber';

/**
 * The two evidence layers of a provider facility (provider plan §6.6, AC-1):
 * the **business identity** (legal name, RCCM, IFU, CNSS, registered address,
 * company phone, the gérant, the start date) and the **professional licence**
 * (the licence or ministry authorisation, the responsible pharmacist or
 * biologist and their Ordre number, optional issue and expiry dates). No
 * field is an expiry that must be filled.
 *
 * Shared by the create form, the resubmit form and the verify dialog's
 * corrections. `idPrefix` keeps the label/input ids unique when two instances
 * could share a page.
 */
@Component({
  selector: 'app-provider-evidence-form',
  standalone: true,
  imports: [FormsModule, TranslateModule],
  templateUrl: './provider-evidence-form.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './provider-evidence-form.scss',
})
export class ProviderEvidenceFormComponent {
  readonly evidence = model.required<ProviderEvidence>();
  readonly facilityType = input<FacilityType | null>(null);
  readonly idPrefix = input('evidence');

  /** A laboratory holds a ministry authorisation and a responsible biologist; a pharmacy a licence and a pharmacist. */
  readonly isLaboratory = computed(() => this.facilityType() === 'LABORATORY');

  id(field: string): string {
    return `${this.idPrefix()}-${field}`;
  }

  setBusiness(field: BusinessField, value: string): void {
    this.evidence.update((current) => ({
      ...current,
      business: { ...current.business, [field]: value },
    }));
  }

  setAddress(field: AddressField, value: string): void {
    this.evidence.update((current) => ({
      ...current,
      business: {
        ...current.business,
        address: { ...current.business.address, [field]: value },
      },
    }));
  }

  setProfessional(field: ProfessionalField, value: string): void {
    this.evidence.update((current) => ({
      ...current,
      professional: { ...current.professional, [field]: value },
    }));
  }
}
