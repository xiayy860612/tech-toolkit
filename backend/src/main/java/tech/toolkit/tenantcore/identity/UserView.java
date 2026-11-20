package tech.toolkit.tenantcore.identity;

import java.time.Instant;

/** User 加上列表展示所需的派生信息（Account 数、主用户名、最近登录）。 */
public record UserView(
    AppUser user, int accountCount, String primaryUsername, Instant lastLoginAt) {}
