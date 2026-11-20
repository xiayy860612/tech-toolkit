-- 测试用：模拟生产中由 DBA 预先创建的应用角色（非属主、非超级用户，因此受 RLS 约束）
CREATE ROLE tenantcore_app LOGIN PASSWORD 'tenantcore-test-only';
