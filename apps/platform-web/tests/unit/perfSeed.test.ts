import { describe, expect, it } from 'vitest';
import { buildUserSeedSql } from '../../scripts/perf-seed.mjs';

describe('buildUserSeedSql', () => {
  it('inserts the requested number of synthetic Users into the Tenant found by slug', () => {
    const sql = buildUserSeedSql({ tenantSlug: 'perf-demo', count: 500 });
    expect(sql).toContain('INSERT INTO uam_user');
    expect(sql).toContain('generate_series(1, 500)');
    expect(sql).toContain("slug = 'perf-demo'");
    expect(sql).toContain('Perf User');
  });

  it('only touches uam_user and creates unique public ids', () => {
    const sql = buildUserSeedSql({ tenantSlug: 'perf-demo', count: 10 });
    expect(sql.match(/INSERT INTO/g)).toHaveLength(1);
    expect(sql).not.toMatch(/uam_account|uam_credential|DELETE|DROP|UPDATE/i);
    // public ids are globally unique, so they are derived from the Tenant slug and the sequence number
    expect(sql).toContain("'tu_' || substr(md5(t.slug || ':' || n), 1, 12)");
  });

  it('rejects a slug that could break out of the SQL literal', () => {
    expect(() =>
      buildUserSeedSql({ tenantSlug: "x'; DROP TABLE core_tenant;--", count: 5 }),
    ).toThrow(/slug/);
  });

  it('rejects a count outside 1..10000', () => {
    expect(() => buildUserSeedSql({ tenantSlug: 'perf-demo', count: 0 })).toThrow(/count/);
    expect(() => buildUserSeedSql({ tenantSlug: 'perf-demo', count: 10001 })).toThrow(/count/);
    expect(() => buildUserSeedSql({ tenantSlug: 'perf-demo', count: 1.5 })).toThrow(/count/);
  });
});
