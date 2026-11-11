import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of } from 'rxjs';

import { RoleListComponent } from './role-list';
import { RoleResponse, RoleService } from '../services/role.service';
import { ToastService } from '../core/toast.service';
import { isSubmitBlocked } from '../shared/forms/submit-guard.testing';

/**
 * QA D868: the role cards were titled 'ROLE_DOCTOR ROLE_DOCTOR' and every role
 * read '0 permissions', because /api/permissions has no catalogue: a system
 * role's access is built into the platform, not stored as permission rows.
 */
describe('RoleListComponent (QA D868)', () => {
  let fixture: ComponentFixture<RoleListComponent>;
  let roles: jasmine.SpyObj<RoleService>;

  const role = (over: Partial<RoleResponse>): RoleResponse => ({
    id: 'r1',
    name: 'ROLE_DOCTOR',
    authority: 'ROLE_DOCTOR',
    code: 'ROLE_DOCTOR',
    description: '',
    permissions: [],
    createdAt: '',
    updatedAt: '',
    ...over,
  });

  beforeEach(async () => {
    roles = jasmine.createSpyObj<RoleService>('RoleService', ['list', 'listPermissions', 'update']);
    roles.list.and.returnValue(
      of([
        role({}),
        role({
          id: 'r2',
          name: 'Chef de garde',
          authority: 'ROLE_CHEF_GARDE',
          code: 'ROLE_CHEF_GARDE',
        }),
      ]),
    );
    roles.listPermissions.and.returnValue(of([]));
    await TestBed.configureTestingModule({
      imports: [RoleListComponent, TranslateModule.forRoot()],
      providers: [
        { provide: RoleService, useValue: roles },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    }).compileComponents();
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      PORTAL: { ENUM: { ROLE: { DOCTOR: 'Médecin' } } },
      ROLES: {
        PERMISSIONS: 'permissions',
        BUILT_IN_ACCESS: 'Accès intégré au rôle',
        NO_PERMISSION_CATALOGUE: 'Aucun catalogue de permissions',
      },
    });
    translate.use('fr');
    fixture = TestBed.createComponent(RoleListComponent);
    fixture.detectChanges();
  });

  it('titles a system role in the reader language and keeps a custom role as typed', () => {
    const titles = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.role-card h4'),
    ).map((h) => h.textContent?.trim());
    expect(titles).toEqual(['Médecin', 'Chef de garde']);
  });

  it('titles a system role in English too, where the label equals the prettified code', () => {
    // en.json: PORTAL.ENUM.ROLE.DOCTOR is 'Doctor' — exactly the prettified
    // code. The label exists, so the card must not fall back to 'ROLE_DOCTOR'.
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('en', {
      PORTAL: { ENUM: { ROLE: { DOCTOR: 'Doctor' } } },
      ROLES: { PERMISSIONS: 'permissions', BUILT_IN_ACCESS: 'Built-in role access' },
    });
    translate.use('en');
    fixture.detectChanges();
    const titles = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.role-card h4'),
    ).map((h) => h.textContent?.trim());
    expect(titles).toEqual(['Doctor', 'Chef de garde']);
    expect(titles.join(' ')).not.toContain('ROLE_');
  });

  it('never shows a bare code as the title of a role with no label', () => {
    const component = fixture.componentInstance;
    expect(
      component.roleTitle(
        role({ name: 'ROLE_CHEF_GARDE', authority: 'ROLE_CHEF_GARDE', code: 'ROLE_CHEF_GARDE' }),
      ),
    ).toBe('Chef Garde');
  });

  it('says built-in access, not 0 permissions, for a role with no permission rows', () => {
    const counts = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.perm-count'),
    ).map((c) => c.textContent?.replace('lock', '').trim());
    expect(counts).toEqual(['Accès intégré au rôle', 'Accès intégré au rôle']);
    expect(counts.join(' ')).not.toContain('0 permissions');
  });

  it('explains the empty catalogue in the permissions dialog and offers no save', () => {
    fixture.componentInstance.openPermissions(role({}));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="no-permission-catalogue"]')).not.toBeNull();
    const save = Array.from(el.querySelectorAll('.modal-actions .btn-primary')).at(-1) as
      HTMLButtonElement | undefined;
    expect(isSubmitBlocked(save)).toBeTrue();
  });
  it('locks the code of a built-in role and always sends its stored code (QA D902, #968 review)', () => {
    const component = fixture.componentInstance;
    const superAdmin = role({
      id: 'sa',
      name: 'ROLE_SUPER_ADMIN',
      authority: 'ROLE_SUPER_ADMIN',
      code: 'ROLE_SUPER_ADMIN',
      builtIn: true,
    });
    roles.update.and.returnValue(of(superAdmin));
    component.openEdit(superAdmin);
    fixture.detectChanges();
    const code = (fixture.nativeElement as HTMLElement).querySelector(
      '#roleCode',
    ) as HTMLInputElement;
    expect(code.readOnly).toBeTrue();
    expect(code.getAttribute('aria-describedby')).toBe('roleCodeHint');
    expect((fixture.nativeElement as HTMLElement).querySelector('#roleCodeHint')).not.toBeNull();

    component.createForm.code = 'ROLE_PLATFORM_OWNER';
    component.submitCreate();
    expect(roles.update).toHaveBeenCalledWith(
      'sa',
      jasmine.objectContaining({ code: 'ROLE_SUPER_ADMIN' }),
    );
  });

  it('keeps the code of a custom role editable', () => {
    const component = fixture.componentInstance;
    component.openEdit(role({ id: 'r2', name: 'Chef de garde', code: 'ROLE_CHEF_GARDE' }));
    fixture.detectChanges();
    const code = (fixture.nativeElement as HTMLElement).querySelector(
      '#roleCode',
    ) as HTMLInputElement;
    expect(code.readOnly).toBeFalse();
    expect((fixture.nativeElement as HTMLElement).querySelector('#roleCodeHint')).toBeNull();
  });

  it('offers no trash icon on a built-in role and never opens its delete dialog (QA D902, #968 review)', () => {
    const component = fixture.componentInstance;
    const superAdmin = role({
      id: 'sa',
      name: 'ROLE_SUPER_ADMIN',
      authority: 'ROLE_SUPER_ADMIN',
      code: 'ROLE_SUPER_ADMIN',
      builtIn: true,
    });
    const custom = role({
      id: 'r2',
      name: 'Chef de garde',
      authority: 'ROLE_CHEF_GARDE',
      code: 'ROLE_CHEF_GARDE',
      builtIn: false,
    });
    roles.list.and.returnValue(of([superAdmin, custom]));
    component.loadRoles();
    fixture.detectChanges();
    const cards = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.role-card'));
    expect(cards.map((c) => c.querySelector('.btn-sm.delete') !== null)).toEqual([false, true]);

    component.confirmDelete(superAdmin);
    expect(component.showDeleteConfirm()).toBeFalse();
    expect(component.deletingRole()).toBeNull();
    component.confirmDelete(custom);
    expect(component.deletingRole()).toBe(custom);
  });
});
