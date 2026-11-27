import { convertToParamMap } from '@angular/router';

import { linkToken } from './link-token';

describe('linkToken: the token of an account link', () => {
  it('is read from the URL fragment', () => {
    expect(linkToken({ fragment: 'token=abc-123_x', queryParamMap: convertToParamMap({}) })).toBe(
      'abc-123_x',
    );
  });

  it('prefers the fragment over an older query string', () => {
    expect(
      linkToken({ fragment: 'token=new', queryParamMap: convertToParamMap({ token: 'old' }) }),
    ).toBe('new');
  });

  it('still reads a link sent before, with the token in the query', () => {
    expect(linkToken({ fragment: null, queryParamMap: convertToParamMap({ token: 'old' }) })).toBe(
      'old',
    );
  });

  it('is empty when the link carries none', () => {
    expect(linkToken({ fragment: 'other=1', queryParamMap: convertToParamMap({}) })).toBe('');
    expect(linkToken({ queryParamMap: convertToParamMap({}) })).toBe('');
  });
});
