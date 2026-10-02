import { lastReachablePage, parseFilesSearch, serializeFilesSearch, toListParams } from './files-search';

describe('files search (URL state)', () => {
  it('uses defaults for an empty URL', () => {
    expect(parseFilesSearch(new URLSearchParams())).toEqual({
      page: 1,
      size: 20,
      sort: 'uploadedAt,desc',
      status: [],
      q: undefined,
    });
  });

  it('corrects a hand-edited URL instead of failing', () => {
    const search = parseFilesSearch(new URLSearchParams('page=-4&size=7&sort=hack&status=BOGUS&q=%20%20'));
    expect(search).toEqual({ page: 1, size: 20, sort: 'uploadedAt,desc', status: [], q: undefined });
  });

  it('brings a page deeper than the API serves back within reach (contract: 10 000 files)', () => {
    expect(parseFilesSearch(new URLSearchParams('page=600&size=20')).page).toBe(501);
    expect(parseFilesSearch(new URLSearchParams('page=600&size=50')).page).toBe(201);
    expect(parseFilesSearch(new URLSearchParams('page=501&size=20')).page).toBe(501);
    expect(lastReachablePage(10)).toBe(1001);
  });

  it('round-trips through the URL and leaves defaults out', () => {
    const search = parseFilesSearch(
      new URLSearchParams('page=3&size=50&sort=filename,asc&status=PENDING&status=SCANNING&q=rapport'),
    );
    expect(serializeFilesSearch(search).toString()).toBe(
      'q=rapport&status=PENDING&status=SCANNING&sort=filename%2Casc&size=50&page=3',
    );
    expect(serializeFilesSearch(parseFilesSearch(new URLSearchParams())).toString()).toBe('');
  });

  it('converts the one-based page of the URL to the zero-based page of the API', () => {
    const search = parseFilesSearch(new URLSearchParams('page=3'));
    expect(toListParams(search).page).toBe(2);
  });
});
