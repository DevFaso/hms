import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-error-404',
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="error-page">
      <span class="material-symbols-outlined error-icon">search_off</span>
      <h1>404</h1>
      <h2>Page Not Found</h2>
      <p>The page you're looking for doesn't exist or has been moved.</p>
      <a routerLink="/dashboard" class="back-link">
        <span class="material-symbols-outlined">arrow_back</span>
        Back to Dashboard
      </a>
    </div>
  `,
  styles: `
    .error-page {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: 60vh;
      text-align: center;
      padding: 2rem;
    }
    .error-icon {
      font-size: 64px;
      color: #d97706;
      margin-bottom: 1rem;
    }
    h1 {
      font-size: 4rem;
      font-weight: 800;
      color: #1e293b;
      margin: 0;
    }
    h2 {
      font-size: 1.25rem;
      color: #475569;
      margin: 0.5rem 0;
    }
    p {
      color: #94a3b8;
      margin: 0 0 2rem;
    }
    .back-link {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.625rem 1.25rem;
      background: #2563eb;
      color: #fff;
      border-radius: 8px;
      text-decoration: none;
      font-weight: 500;
    }
    .back-link:hover {
      background: #1d4ed8;
    }
  `,
})
export class Error404Component {}
