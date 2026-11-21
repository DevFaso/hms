import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';

import { ToastService } from '../core/toast.service';
import { FeatureFlagsComponent } from './feature-flags';

/**
 * QA r65 D2768: at 390 px each flag row was 423 to 470 px wide inside a
 * 316 px card, so the state badge and the delete button were cut off outside
 * any scroll area.
 */
describe('FeatureFlagsComponent — the rows fit a phone (QA r65 D2768)', () => {
  it('no row is wider than its card at 1366, 1024, 768 and 390 px, with keys as long as the real ones', () => {
    TestBed.configureTestingModule({
      imports: [FeatureFlagsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']),
        },
      ],
    });
    const fixture = TestBed.createComponent(FeatureFlagsComponent);
    const http = TestBed.inject(HttpTestingController);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    http.expectOne('/feature-flags').flush({
      'imaging.dicom-proxy.enabled-for-every-hospital': true,
      'hl7.adt.visit-sync.auto-create.enabled': false,
      'pharmacy.partner-routing': true,
    });
    http.match('/feature-flags/overrides').forEach((r) => r.flush([]));
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    host.style.display = 'block';
    // The icon font is not loaded under test, so an icon would be measured as
    // its ligature text ("delete_outline", some 90 px) instead of a 24 px glyph.
    host
      .querySelectorAll('.material-icons, .material-symbols-outlined')
      .forEach((icon) => (icon.textContent = ''));
    const table = host.querySelector<HTMLElement>('.flags-table')!;
    const rows = Array.from(host.querySelectorAll<HTMLElement>('.flag-row'));
    expect(rows.length).toBe(3);

    // The page container's own padding at each width: what is left for the card.
    for (const width of [1366, 1024, 768, 390]) {
      host.style.width = `${width - 74}px`;
      fixture.detectChanges();
      const card = table.getBoundingClientRect();
      for (const row of rows) {
        expect(row.scrollWidth)
          .withContext(`row at ${width}px`)
          .toBeLessThanOrEqual(row.clientWidth + 1);
        // Every control of the row is inside the card: nothing is cut off.
        for (const control of Array.from(
          row.querySelectorAll<HTMLElement>('button, .flag-badge'),
        )) {
          const box = control.getBoundingClientRect();
          expect(box.right)
            .withContext(`${control.className} at ${width}px`)
            .toBeLessThanOrEqual(card.right + 1);
        }
      }
    }
    host.remove();
  });
});
