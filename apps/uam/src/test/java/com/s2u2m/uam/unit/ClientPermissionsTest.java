package com.s2u2m.uam.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.s2u2m.uam.api.ClientPermissions;
import com.s2u2m.uam.domain.model.ClientIdentity;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T178：接口所需权限的唯一决策点——只读查询只需有效密钥，其余（登录、校验、退出、改密与全部写操作）需要断言权限；未知方法一律按需要处理（失败即关闭）。 */
class ClientPermissionsTest {

  private static final Optional<String> ASSERT =
      Optional.of(ClientIdentity.ASSERT_END_USER_CONTEXT);

  @Test
  void readOnlyMethodsNeedNoPermission() {
    assertThat(ClientPermissions.requiredFor("GET")).isEmpty();
    assertThat(ClientPermissions.requiredFor("HEAD")).isEmpty();
    assertThat(ClientPermissions.requiredFor("get")).isEmpty();
  }

  @Test
  void everythingElseNeedsToAssertEndUserContext() {
    for (String method : new String[] {"POST", "DELETE", "PUT", "PATCH", "OPTIONS", "TRACE", "X"}) {
      assertThat(ClientPermissions.requiredFor(method)).as(method).isEqualTo(ASSERT);
    }
  }

  @Test
  void aMissingMethodFailsClosed() {
    assertThat(ClientPermissions.requiredFor(null)).isEqualTo(ASSERT);
  }
}
