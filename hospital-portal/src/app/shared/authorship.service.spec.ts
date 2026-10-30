import { TestBed } from '@angular/core/testing';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthorshipService } from './authorship.service';

describe('AuthorshipService (QA D1081)', () => {
  let superAdmin = false;
  let me: string | null = 'u-me';

  beforeEach(() => {
    superAdmin = false;
    me = 'u-me';
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: { getUserProfile: () => (me ? { id: me } : null), getUserId: () => me },
        },
        { provide: RoleContextService, useValue: { isSuperAdmin: () => superAdmin } },
      ],
    });
  });

  it('offers an author-only action to the author only', () => {
    const svc = TestBed.inject(AuthorshipService);
    expect(svc.isAuthor({ authorUserId: 'u-me' })).toBeTrue();
    expect(svc.isAuthor({ authorUserId: 'u-other' })).toBeFalse();
  });

  it('offers it to a super-admin whoever wrote the record', () => {
    superAdmin = true;
    expect(TestBed.inject(AuthorshipService).isAuthor({ authorUserId: 'u-other' })).toBeTrue();
  });

  it('keeps the old decision when the row names no author', () => {
    const svc = TestBed.inject(AuthorshipService);
    expect(svc.isAuthor({})).toBeTrue();
    expect(svc.isAuthor({ authorUserId: null }, false)).toBeFalse();
  });

  it('refuses when nobody is signed in', () => {
    me = null;
    expect(TestBed.inject(AuthorshipService).isAuthor({ authorUserId: 'u-me' })).toBeFalse();
  });
});
