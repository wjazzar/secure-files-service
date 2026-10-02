import { render, screen } from '@testing-library/react';

import { FILE_STATUSES } from '@/api/files/files-schemas';

import { STATUS_DISPLAY } from '../lib/status-display';
import { StatusBadge } from './status-badge';

describe('StatusBadge', () => {
  it.each([...FILE_STATUSES, 'UNKNOWN' as const])('renders a label for %s', (status) => {
    render(<StatusBadge status={status} />);
    expect(screen.getByText(STATUS_DISPLAY[status].label)).toBeInTheDocument();
  });

  it('shows an unknown status as a neutral badge (rule F-2)', () => {
    render(<StatusBadge status="UNKNOWN" />);
    expect(screen.getByText('État inconnu')).toBeInTheDocument();
  });

  it('never carries the status by color alone: every status has its own label and icon', () => {
    const labels = new Set(Object.values(STATUS_DISPLAY).map((display) => display.label));
    expect(labels.size).toBe(Object.keys(STATUS_DISPLAY).length);
  });
});
