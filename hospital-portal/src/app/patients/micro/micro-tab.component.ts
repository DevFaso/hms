import { Component, Input, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  MicroCultureResponse,
  MicroGrowthResult,
  MicroService,
  MicroSusceptibilityInterpretation,
  MicroSusceptibilityMethod,
} from '../../services/micro.service';

/**
 * Microbiology tab on the patient chart (P3 #19) — read-only culture
 * reports: organism isolates with their susceptibility panels. Resulting
 * happens on the /microbiology workbench; this surface only renders.
 */
@Component({
  selector: 'app-micro-tab',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './micro-tab.component.html',
  styleUrl: './micro-tab.component.scss',
})
export class MicroTabComponent implements OnInit {
  @Input({ required: true }) patientId!: string;

  private readonly microService = inject(MicroService);
  private readonly translate = inject(TranslateService);

  readonly loading = signal(true);
  readonly error = signal('');
  readonly cultures = signal<MicroCultureResponse[]>([]);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.microService.listForPatient(this.patientId).subscribe({
      next: (cultures) => {
        this.cultures.set(cultures);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? this.translate.instant('MICRO.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  statusLabel(status: MicroCultureResponse['status']): string {
    return this.translate.instant(`MICRO.STATUS_${status}`);
  }

  growthLabel(growth: MicroGrowthResult | null): string {
    return this.translate.instant(growth ? `MICRO.GROWTH_${growth}` : 'MICRO.GROWTH_PENDING');
  }

  methodLabel(method: MicroSusceptibilityMethod | null): string {
    return method ? this.translate.instant(`MICRO.METHOD_${method}`) : '—';
  }

  interpLabel(interpretation: MicroSusceptibilityInterpretation): string {
    return this.translate.instant(`MICRO.INTERP_${interpretation}`);
  }
}
