package tech.toolkit.tenantcore.support;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import java.util.Base64;

/** 测试用 Ed25519 密钥对（每次 JVM 启动生成，不入库）。 */
public final class TestKeys {

  private static final OctetKeyPair KEY = generate();

  private TestKeys() {}

  private static OctetKeyPair generate() {
    try {
      return new OctetKeyPairGenerator(Curve.Ed25519).keyID("test-key").generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static OctetKeyPair keyPair() {
    return KEY;
  }

  /** 公钥以 JWK JSON 提供（后端配置支持 JWK JSON 或文件路径）。 */
  public static String publicKeyPem() {
    return KEY.toPublicJWK().toJSONString();
  }

  public static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
