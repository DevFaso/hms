import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },
  // Future routes will be added here as modules are ported
  // { path: 'login', loadComponent: () => import('./auth/login/login.component').then(m => m.LoginComponent) },
  // { path: 'dashboard', loadComponent: () => import('./dashboard/dashboard.component').then(m => m.DashboardComponent) },
];
