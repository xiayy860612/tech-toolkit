package tech.toolkit.tenantcore.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import java.nio.file.Path;
import org.springframework.test.web.servlet.ResultMatcher;

/** 对照 specs/001-tenant-admin-center/contracts/openapi.yaml 校验真实请求与响应（契约优先）。 */
public final class OpenApiContract {

  private static final OpenApiInteractionValidator VALIDATOR =
      OpenApiInteractionValidator.createForSpecificationUrl(
              Path.of("..", "specs", "001-tenant-admin-center", "contracts", "openapi.yaml")
                  .toAbsolutePath()
                  .normalize()
                  .toUri()
                  .toString())
          // 契约的 servers 是浏览器侧的 /api/v1；后端自身挂在根路径
          .withBasePathOverride("/")
          .build();

  private OpenApiContract() {}

  public static ResultMatcher valid() {
    return OpenApiValidationMatchers.openApi().isValid(VALIDATOR);
  }
}
