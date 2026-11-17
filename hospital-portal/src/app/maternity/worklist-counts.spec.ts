import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { worklistCounts } from './worklist-counts';

describe('worklistCounts', () => {
  it('keeps zero distinct from failed, missing and invalid counts', () => {
    const counts = TestBed.runInInjectionContext(() => worklistCounts<string>());
    counts.load({
      empty: () => of(0),
      populated: () => of(24),
      failed: () => throwError(() => new Error()),
      invalid: () => of(-1),
      missing: () => of(undefined as unknown as number),
    });
    expect(counts.values()).toEqual({
      empty: 0,
      populated: 24,
      failed: null,
      invalid: null,
      missing: null,
    });
  });

  it('clears old counts and cancels responses when patient scope changes or the owner is destroyed', () => {
    const counts = TestBed.runInInjectionContext(() => worklistCounts<'all'>());
    const previous = new Subject<number>();
    const current = new Subject<number>();
    counts.load({ all: () => previous });
    expect(counts.values().all).toBeUndefined();
    counts.load({ all: () => current });
    expect(previous.observed).toBeFalse();
    previous.next(99);
    expect(counts.values().all).toBeUndefined();
    current.next(3);
    expect(counts.values().all).toBe(3);
    TestBed.resetTestingModule();
    expect(current.observed).toBeFalse();
    const late = new Subject<number>();
    counts.load({ all: () => late });
    expect(late.observed).toBeFalse();
    current.next(40);
    expect(counts.values().all).toBe(3);
  });
});
