import { Component, inject, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AuthService } from './auth/auth.service';
import { RoleContextService } from './core/role-context.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, TranslateModule],
  template: `<router-outlet />`,
  styles: [
    `
      :host {
        display: block;
        height: 100%;
      }
    `,
  ],
})
export class AppComponent implements OnInit {
  title = 'hospital-portal';

  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);

  ngOnInit(): void {
    // Re-hydrate role context from the stored JWT on every app bootstrap
    // (covers hard refresh, direct URL navigation, and tab re-open).
    const token = this.auth.getToken();
    if (token && !this.auth.isExpired(token)) {
      const roles = this.auth.getRoles();
      this.roleContext.setRoles(roles);

      const permittedIds = this.auth.getPermittedHospitalIds();
      this.roleContext.setPermittedHospitalIds(permittedIds);

      // Only pre-set activeHospitalId when the user belongs to exactly one
      // hospital. Multi-hospital users select their working hospital in-form;
      // the form calls roleContext.activeHospitalId = selectedId at that point.
      if (permittedIds.length === 1) {
        this.roleContext.activeHospitalId = permittedIds[0];
      }
    }
  }
}
