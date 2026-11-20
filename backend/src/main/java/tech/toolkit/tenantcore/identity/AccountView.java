package tech.toolkit.tenantcore.identity;

/** Account 加上凭证上的强制改密标志。 */
public record AccountView(Account account, boolean mustChangePassword) {}
