const SLUG = /^[a-z][a-z0-9-]{2,31}$/;

/**
 * SQL that adds `count` synthetic Users to an existing Tenant (for performance tests only).
 * Run it as the database owner against a throw-away test database. The slug is validated because it
 * is embedded as a SQL literal.
 */
export function buildUserSeedSql({ tenantSlug, count }) {
  if (!SLUG.test(tenantSlug)) throw new Error(`invalid Tenant slug: ${tenantSlug}`);
  if (!Number.isInteger(count) || count < 1 || count > 10000) {
    throw new Error(`count must be an integer between 1 and 10000, got ${count}`);
  }
  return `INSERT INTO uam_user (id, tenant_user_id, tenant_id, display_name, status, created_at)
SELECT gen_random_uuid(),
       'tu_' || substr(md5(t.slug || ':' || n), 1, 12),
       t.id,
       'Perf User ' || n,
       'ACTIVE',
       now() - (n || ' seconds')::interval
FROM core_tenant t, generate_series(1, ${count}) AS n
WHERE t.slug = '${tenantSlug}';
`;
}
