import { initials } from './initials';

describe('initials', () => {
  it.each([
    ['Alice Martin', 'AM'],
    ['  claire   dubois ', 'CD'],
    ['Jean-Pierre de La Tour', 'JD'],
    ['Anonyme', 'A'],
  ])('%s → %s', (name, expected) => {
    expect(initials(name)).toBe(expected);
  });
});
