package tech.toolkit.tenantcore.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  @Test
  void definesTheCodesOfTheApiContract() {
    List<String> names = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();
    assertThat(names)
        .contains(
            "INVALID_CREDENTIALS",
            "MODULE_NOT_SUBSCRIBED",
            "UNAUTHORIZED",
            "PASSWORD_CHANGE_REQUIRED",
            "PASSWORD_POLICY",
            "VALIDATION",
            "NOT_FOUND",
            "CONFLICT");
  }

  @Test
  void domainExceptionCarriesCodeAndMessage() {
    DomainException e = new DomainException(ErrorCode.NOT_FOUND, "Tenant acme does not exist");
    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
    assertThat(e).hasMessage("Tenant acme does not exist");
  }
}
