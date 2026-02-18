import { Directive, inject, Input, TemplateRef, ViewContainerRef, OnInit } from '@angular/core';
import { PermissionService } from '../../core/permission.service';

@Directive({
  selector: '[appHasPermission]',
  standalone: true,
})
export class HasPermissionDirective implements OnInit {
  private readonly permissionService = inject(PermissionService);
  private readonly templateRef = inject(TemplateRef<unknown>);
  private readonly viewContainer = inject(ViewContainerRef);

  @Input('appHasPermission') permission = '';

  private rendered = false;

  ngOnInit(): void {
    if (this.permissionService.hasPermission(this.permission)) {
      if (!this.rendered) {
        this.viewContainer.createEmbeddedView(this.templateRef);
        this.rendered = true;
      }
    } else {
      this.viewContainer.clear();
      this.rendered = false;
    }
  }
}
