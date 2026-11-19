import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { MyMedicationsComponent } from '../my-medications/my-medications.component';
import { MyBillingComponent } from '../my-billing/my-billing.component';
import { PatientPortalService } from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';
import fr from '../../../assets/i18n/fr.json';

/**
 * Wave 1 PR 9 (r63 D2353-D2355): the new prescription document, refill
 * actions and how-to-pay card hold inside the four audited widths. The host
 * box carries a transform, so the dialog's `position: fixed` is laid out in
 * it as it would be in a phone's viewport.
 */
describe('patient portal additions at 390 / 768 / 1024 / 1366 px', () => {
  // 366: the content column of a 390 px phone (16 px gutters on each side).
  const WIDTHS = [366, 390, 768, 1024, 1366];
  let box: HTMLDivElement;

  const rx = (id: string, extra: Record<string, unknown> = {}) => ({
    id,
    medicationName: 'Amoxicilline acide clavulanique 1 g / 125 mg comprimés pelliculés',
    dosage: '1 comprimé',
    frequency: 'Trois fois par jour pendant les repas',
    duration: '7 jours',
    notes: '',
    instructions: 'Avaler avec un grand verre d’eau, terminer la boîte même si la fièvre tombe.',
    route: 'Orale',
    quantity: 21,
    quantityUnit: 'comprimés',
    prescribedBy: 'Dr Aminata Ouédraogo-Kaboré',
    prescribedDate: '2026-11-14T09:00:00',
    signedAt: '2026-11-14T09:05:00',
    hospitalName: 'Centre hospitalier universitaire de démonstration',
    patientFullName: 'Seydou Traoré',
    encounterId: 'enc-1',
    status: 'SIGNED',
    ...extra,
  });

  function setUp(): void {
    TestBed.configureTestingModule({
      imports: [MyMedicationsComponent, MyBillingComponent, TranslateModule.forRoot()],
      providers: [
        {
          provide: PatientPortalService,
          useValue: {
            getAllMyMedications: () =>
              of([
                {
                  id: 'rx-1',
                  medicationName: 'Paracétamol 500 mg',
                  dosage: '1 g',
                  frequency: 'q6h',
                  prescribedBy: 'Dr A',
                  startDate: '2026-11-01',
                  status: 'ACTIVE',
                  refillable: true,
                  refillRequestStatus: 'REQUESTED',
                  refillRequestOpen: true,
                },
              ]),
            getMyPrescriptions: () => of([rx('rx-1'), rx('rx-2')]),
            getMyRefills: () =>
              of([
                {
                  id: 'rf-1',
                  prescriptionId: 'rx-1',
                  medicationName: 'Paracétamol 500 mg',
                  preferredPharmacy: '',
                  status: 'REQUESTED',
                  requestedAt: '2026-11-14T08:00:00',
                  updatedAt: null,
                  notes: '',
                },
              ]),
            getMyInvoices: () =>
              of([
                {
                  id: 'inv-1',
                  invoiceNumber: 'FAC-2026-000123456',
                  date: '2026-11-14',
                  dueDate: '2026-11-30',
                  amount: 1800,
                  paid: 0,
                  balance: 1800,
                  status: 'SENT',
                  facility: 'CHU démo',
                },
              ]),
          },
        },
        {
          provide: ToastService,
          useValue: { success: jasmine.createSpy(), error: jasmine.createSpy() },
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
  }

  // index.html's icon box (the Google icon font is not loaded under Karma).
  let iconBox: HTMLStyleElement;
  beforeAll(() => {
    iconBox = document.createElement('style');
    iconBox.textContent =
      '.material-symbols-outlined{display:inline-block;width:1em;overflow:hidden;white-space:nowrap}';
    document.head.appendChild(iconBox);
  });
  afterAll(() => iconBox.remove());

  beforeEach(() => {
    box = document.createElement('div');
    box.style.cssText = 'transform: translateZ(0); height: 844px; overflow: hidden;';
    document.body.appendChild(box);
    setUp();
  });
  afterEach(() => box.remove());

  /** Every element stays inside the box, and nothing scrolls sideways. */
  function overflowing(root: HTMLElement, width: number): string[] {
    const left = box.getBoundingClientRect().left;
    const out: string[] = [];
    root.querySelectorAll<HTMLElement>('*').forEach((el) => {
      const r = el.getBoundingClientRect();
      if (r.width === 0) return;
      if (r.right - left > width + 1)
        out.push(`${el.tagName}.${el.className} right=${r.right - left}`);
      if (el.scrollWidth > el.clientWidth + 1 && getComputedStyle(el).overflowX !== 'hidden') {
        if (el.clientWidth > 0 && !['SELECT', 'INPUT'].includes(el.tagName)) {
          out.push(`${el.tagName}.${el.className} scrolls sideways`);
        }
      }
    });
    return out;
  }

  for (const width of WIDTHS) {
    it(`prescription document and refill actions fit at ${width} px`, () => {
      box.style.width = `${width}px`;
      const fixture = TestBed.createComponent(MyMedicationsComponent);
      box.appendChild(fixture.nativeElement);
      fixture.detectChanges();
      fixture.componentInstance.toggleMed('rx-1');
      fixture.componentInstance.toggleRx('rx-1');
      fixture.detectChanges();
      expect(overflowing(fixture.nativeElement, width).join(' | ')).toBe('');

      fixture.componentInstance.openDocument(fixture.componentInstance.prescriptions()[0]);
      fixture.detectChanges();
      const doc = fixture.nativeElement.querySelector('[data-testid="rx-document"]');
      expect(doc).not.toBeNull();
      expect(overflowing(doc, width).join(' | ')).toBe('');
    });

    it(`how-to-pay card and declare button fit at ${width} px`, () => {
      box.style.width = `${width}px`;
      const fixture = TestBed.createComponent(MyBillingComponent);
      box.appendChild(fixture.nativeElement);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('[data-testid="pay-howto"]')).not.toBeNull();
      expect(overflowing(fixture.nativeElement, width).join(' | ')).toBe('');
    });
  }
});
