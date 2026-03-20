import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PatientPortalService, EducationResourceDTO } from '../../services/patient-portal.service';

const CATEGORIES = [
  'GENERAL_HEALTH',
  'CHRONIC_DISEASE',
  'MEDICATION',
  'NUTRITION',
  'MENTAL_HEALTH',
  'PEDIATRICS',
  'WOMENS_HEALTH',
  'MENS_HEALTH',
  'SURGICAL',
  'PREVENTIVE_CARE',
  'REHABILITATION',
  'PALLIATIVE_CARE',
] as const;

@Component({
  selector: 'app-my-education-browse',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './my-education-browse.html',
  styleUrl: './my-education-browse.scss',
})
export class MyEducationBrowseComponent implements OnInit {
  private readonly svc = inject(PatientPortalService);

  readonly categories = CATEGORIES;
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly resources = signal<EducationResourceDTO[]>([]);
  readonly selectedCategory = signal('');
  searchQuery = '';

  readonly visible = computed(() => this.resources());

  ngOnInit(): void {
    this.loadAll();
  }

  private loadAll(): void {
    this.loading.set(true);
    this.error.set(null);
    this.svc.getMyEducationResources().subscribe({
      next: (data) => {
        this.resources.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load education resources.');
        this.loading.set(false);
      },
    });
  }

  reload(): void {
    this.loadAll();
  }

  onSearch(): void {
    const q = this.searchQuery.trim();
    if (!q) {
      const cat = this.selectedCategory();
      if (cat) {
        this.loadByCategory(cat);
      } else {
        this.loadAll();
      }
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.svc.searchMyEducationResources(q).subscribe({
      next: (data) => {
        this.resources.set(data);
        this.selectedCategory.set('');
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Search failed.');
        this.loading.set(false);
      },
    });
  }

  clearSearch(): void {
    this.searchQuery = '';
    const cat = this.selectedCategory();
    if (cat) {
      this.loadByCategory(cat);
    } else {
      this.loadAll();
    }
  }

  selectCategory(cat: string): void {
    if (this.searchQuery) {
      this.searchQuery = '';
    }
    this.selectedCategory.set(cat);
    if (!cat) {
      this.loadAll();
      return;
    }
    this.loadByCategory(cat);
  }

  private loadByCategory(cat: string): void {
    this.loading.set(true);
    this.error.set(null);
    this.svc.getMyEducationResourcesByCategory(cat).subscribe({
      next: (data) => {
        this.resources.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Failed to load resources for selected category.');
        this.loading.set(false);
      },
    });
  }

  formatCategory(cat: string): string {
    return cat
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }

  difficultyClass(difficulty: string): string {
    const d = difficulty.toLowerCase();
    if (d === 'easy' || d === 'beginner') return 'easy';
    if (d === 'medium' || d === 'intermediate') return 'medium';
    if (d === 'hard' || d === 'advanced') return 'hard advanced';
    return '';
  }
}
