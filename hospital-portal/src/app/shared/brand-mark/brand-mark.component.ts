import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * The e-Keneya mark: a Greek cross built from two interlaced strips, after the
 * strip-weave construction of Faso Dan Fani. The four centre cells are where
 * the two colours cross, so the weave — not the cross — is the focal point.
 *
 * Decorative by contract: every mounting point renders the name "e-Keneya" as
 * real text beside it, so the SVG is aria-hidden rather than carrying a
 * translated label that would be announced twice.
 *
 * Below ~24px the strip gaps stop resolving; the favicon is therefore a
 * separate simplified build (`public/favicon.svg`) with solid arms. Keep the
 * two in visual sync when either changes.
 */
@Component({
  selector: 'app-brand-mark',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.width]="size()"
      [attr.height]="size()"
      viewBox="0 0 64 64"
      aria-hidden="true"
      focusable="false"
    >
      <rect x="4" y="24" width="56" height="7" rx="1.5" [attr.fill]="weft" />
      <rect x="4" y="33" width="56" height="7" rx="1.5" [attr.fill]="weft" />
      <rect x="24" y="4" width="7" height="56" rx="1.5" [attr.fill]="warp()" />
      <rect x="33" y="4" width="7" height="56" rx="1.5" [attr.fill]="warp()" />
      <rect x="24" y="33" width="7" height="7" [attr.fill]="weft" />
      <rect x="33" y="24" width="7" height="7" [attr.fill]="weft" />
    </svg>
  `,
})
export class BrandMarkComponent {
  readonly size = input(32);

  /** `onDark` lifts the green so it holds contrast against the sidebar. */
  readonly tone = input<'onDark' | 'onLight'>('onLight');

  protected readonly weft = '#E39A2B';
  protected readonly warp = computed(() => (this.tone() === 'onDark' ? '#23B79C' : '#0E7C6B'));
}
