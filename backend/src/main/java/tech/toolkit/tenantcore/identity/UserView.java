package tech.toolkit.tenantcore.identity;

/** 对外展示的 User（凭证字段不外泄：密码哈希不出现在任何视图里）。 */
public record UserView(AppUser user) {}
