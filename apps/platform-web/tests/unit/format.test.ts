import { describe, expect, it } from 'vitest';
import {
  EMPTY_VALUE,
  PASSWORD_POLICY_MESSAGE,
  formatDateTime,
  formatLocalDateTime,
  formatUsernames,
  statusLabel,
} from '@/lib/format';

describe('statusLabel', () => {
  it.each([
    ['ACTIVE', '正常'],
    ['SUSPENDED', '已暂停'],
    ['DISABLED', '已停用'],
    ['SUBSCRIBED', '已订阅'],
  ])('maps %s to %s', (status, label) => {
    expect(statusLabel(status)).toBe(label);
  });

  it('falls back to the raw value for unknown statuses', () => {
    expect(statusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('formatDateTime (stable UTC text)', () => {
  it('formats ISO timestamps in UTC', () => {
    expect(formatDateTime('2030-01-02T03:04:05Z')).toBe('2030-01-02 03:04 UTC');
  });

  it('returns unparsable input unchanged', () => {
    expect(formatDateTime('not-a-date')).toBe('not-a-date');
  });
});

describe('formatLocalDateTime', () => {
  it('formats in the given time zone', () => {
    expect(formatLocalDateTime('2030-01-02T03:04:05Z', 'Asia/Shanghai')).toBe('2030-01-02 11:04');
    expect(formatLocalDateTime('2030-01-02T03:04:05Z', 'America/New_York')).toBe(
      '2030-01-01 22:04',
    );
  });

  it('uses midnight as 00, not 24', () => {
    expect(formatLocalDateTime('2030-01-01T16:00:00Z', 'Asia/Shanghai')).toBe('2030-01-02 00:00');
  });

  it('uses the runtime time zone when none is given', () => {
    expect(formatLocalDateTime('2030-01-02T03:04:05Z')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  });

  it('returns unparsable input unchanged', () => {
    expect(formatLocalDateTime('not-a-date', 'UTC')).toBe('not-a-date');
  });
});

describe('formatUsernames', () => {
  it('joins usernames', () => {
    expect(formatUsernames(['admin', 'zhangsan'])).toBe('admin, zhangsan');
  });

  it('shows a dash for a User without an Account', () => {
    expect(formatUsernames([])).toBe(EMPTY_VALUE);
    expect(EMPTY_VALUE).toBe('—');
  });
});

describe('PASSWORD_POLICY_MESSAGE', () => {
  it('is a fixed Chinese rule without backend text', () => {
    expect(PASSWORD_POLICY_MESSAGE).toBe('新密码至少 12 位，不超过 128 位，且不得与用户名相同');
  });
});
