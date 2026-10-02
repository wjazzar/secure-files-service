import { ERROR_CODES } from '@/api/problem-schema';
import { STATUS_REASON_CODES } from '@/api/files/files-schemas';

import { errorMessages, statusReasonMessages } from './messages';

describe('messages', () => {
  it.each(ERROR_CODES)('has a French message for error code %s', (code) => {
    expect(errorMessages[code]).toMatch(/\S/);
  });

  it.each(STATUS_REASON_CODES)('explains status reason %s', (code) => {
    expect(statusReasonMessages[code]).toMatch(/\S/);
  });
});
