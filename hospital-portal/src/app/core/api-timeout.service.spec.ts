import { ApiTimeoutService } from './api-timeout.service';

describe('API timeout notices belong to one session', () => {
  it('clears prior notices and rejects late results when the session changes', () => {
    const notice = new ApiTimeoutService();
    const oldEpoch = notice.sessionEpoch;
    notice.noteTimeout('POST', oldEpoch);
    expect(notice.failure()).toBe('write');
    notice.resetForSession();
    expect(notice.failure()).toBeNull();
    expect(notice.sessionEpoch).not.toBe(oldEpoch);
    notice.noteTimeout('POST', oldEpoch);
    expect(notice.failure()).toBeNull();
    notice.noteTimeout('GET');
    expect(notice.failure()).toBe('read');
  });

  it('retains an uncertain write over later reads in the same session', () => {
    const notice = new ApiTimeoutService();
    notice.noteTimeout('POST');
    notice.noteTimeout('GET');
    expect(notice.failure()).toBe('write');
  });

  it('reads a search whose terms travel in the body as a read, not an uncertain write', () => {
    const notice = new ApiTimeoutService();
    notice.noteTimeout('POST', notice.sessionEpoch, '/api/reception/patients/duplicate-candidates');
    expect(notice.failure()).toBe('read');
    notice.noteTimeout('POST', notice.sessionEpoch, '/api/patients/search?hospitalId=h1');
    expect(notice.failure()).toBe('read');
    notice.noteTimeout('POST', notice.sessionEpoch, '/api/patients');
    expect(notice.failure()).toBe('write');
  });
});
