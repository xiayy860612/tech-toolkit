package tech.toolkit.tenantcore.uam;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

/**
 * Holds the ES256 key of this UAM instance. The key id is the RFC 7638 thumbprint of the public
 * key, so every instance configured with the same private key publishes the same {@code kid}.
 */
public class SigningKeyProvider {

  private final ECKey key;

  public SigningKeyProvider(UamSettings settings) {
    this.key = load(settings);
  }

  public ECKey key() {
    return key;
  }

  public String keyId() {
    return key.getKeyID();
  }

  private static ECKey load(UamSettings settings) {
    String pem = settings.signingKeyPem();
    try {
      if (pem != null && !pem.isBlank()) {
        return fromPem(pem);
      }
      if (settings.allowEphemeralKey()) {
        return new ECKeyGenerator(Curve.P_256).keyIDFromThumbprint(true).generate();
      }
    } catch (Exception e) {
      throw new IllegalStateException("UAM_SIGNING_KEY_PEM is not a valid ES256 PKCS#8 key", e);
    }
    throw new IllegalStateException(
        "UAM_SIGNING_KEY_PEM is required (only the dev profile may use an ephemeral key)");
  }

  private static ECKey fromPem(String pem) throws Exception {
    String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(base64);
    KeyFactory factory = KeyFactory.getInstance("EC");
    ECPrivateKey privateKey = (ECPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    ECNamedCurveParameterSpec curve = ECNamedCurveTable.getParameterSpec("secp256r1");
    org.bouncycastle.math.ec.ECPoint q = curve.getG().multiply(privateKey.getS()).normalize();
    ECPublicKey publicKey =
        (ECPublicKey)
            factory.generatePublic(
                new ECPublicKeySpec(
                    new ECPoint(
                        q.getAffineXCoord().toBigInteger(), q.getAffineYCoord().toBigInteger()),
                    privateKey.getParams()));
    return new ECKey.Builder(Curve.P_256, publicKey)
        .privateKey(privateKey)
        .keyIDFromThumbprint()
        .build();
  }
}
