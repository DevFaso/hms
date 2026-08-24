import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { DiagnosisSlice } from '../morbidity.service';

/**
 * Ranked horizontal bars for a set of diagnoses.
 *
 * <p>Deliberately NOT an SVG chart. The sparkline next door is SVG
 * because it shows a shape with no labels; this shows LABELLED, ordered
 * magnitudes, which an ordered list renders better — the diagnosis names
 * wrap naturally at narrow widths, and the counts are real text a screen
 * reader reads in order without any aria plumbing.
 *
 * <p>The bars themselves are `aria-hidden` decoration over that list:
 * the number beside each bar is the accessible value, so nothing has to
 * be announced through a translated aria-label built by concatenation
 * (the trap PR #357 hit on the sparkline).
 *
 * <p>Widths are relative to the LARGEST count in the set, not to the
 * total, so the ranking stays legible when one diagnosis dominates —
 * which for a malaria-endemic deployment is the normal case, not the
 * exception.
 */
@Component({
  selector: 'app-diagnosis-bars',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './diagnosis-bars.component.html',
  styleUrl: './diagnosis-bars.component.scss',
})
export class DiagnosisBarsComponent {
  slices = input.required<DiagnosisSlice[]>();
  /** Bar fill colour, so the network chart and per-hospital cards can differ. */
  color = input<string>('var(--dx-bar, #2563eb)');
  /** Compact mode for the per-hospital cards. */
  compact = input<boolean>(false);

  /** Largest count in the set — the 100% reference for every bar. */
  private readonly max = computed<number>(() => {
    const counts = this.slices().map((s) => s.count);
    return counts.length > 0 ? Math.max(...counts) : 0;
  });

  /** Bar width as a percentage of the largest count. Zero-safe. */
  widthPercent(count: number): number {
    const max = this.max();
    if (max <= 0) return 0;
    // Floor at a visible sliver so a count of 1 beside a count of 400
    // still reads as a bar rather than as nothing at all.
    return Math.max((count / max) * 100, 1.5);
  }

  /** Stable track key — a null ICD code falls back to the display text. */
  trackKey(slice: DiagnosisSlice): string {
    return slice.code ?? slice.display;
  }
}
