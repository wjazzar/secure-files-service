import { pollDelay } from './files-queries';

const NOW = Date.parse('2026-09-25T10:00:00Z');
const at = (secondsAgo: number) => new Date(NOW - secondsAgo * 1000).toISOString();

describe('pollDelay', () => {
  it('stops polling when every file is terminal', () => {
    expect(pollDelay([{ terminal: true, statusChangedAt: at(1) }], NOW)).toBe(false);
    expect(pollDelay([], NOW)).toBe(false);
  });

  it('polls every 2 s right after a status change', () => {
    expect(pollDelay([{ terminal: false, statusChangedAt: at(0) }], NOW)).toBe(2_000);
  });

  it('slows down for files waiting for a while, capped at 10 s', () => {
    expect(pollDelay([{ terminal: false, statusChangedAt: at(20) }], NOW)).toBe(6_000);
    expect(pollDelay([{ terminal: false, statusChangedAt: at(600) }], NOW)).toBe(10_000);
  });

  it('follows the most recent activity among the files in progress', () => {
    const files = [
      { terminal: false, statusChangedAt: at(600) },
      { terminal: false, statusChangedAt: at(0) },
      { terminal: true, statusChangedAt: at(0) },
    ];
    expect(pollDelay(files, NOW)).toBe(2_000);
  });
});
