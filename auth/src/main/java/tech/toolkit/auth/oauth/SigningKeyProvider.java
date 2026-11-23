package tech.toolkit.auth.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

/**
 * The ES256 signing keys of this auth instance: one current key that signs, plus verify-only keys
 * that stay published in the JWKS during a rotation (plan.md D5). Keys come from PKCS#8 PEM files;
 * ephemeral keys are only allowed when explicitly enabled (dev and test profiles).
 */
public final class SigningKeyProvider {

  private final ECKey current;
  private final List<ECKey> verifyOnly;

  public SigningKeyProvider(List<Path> keyFiles, boolean allowEphemeral) {
    List<ECKey> loaded = new ArrayList<>();
    for (Path file : keyFiles) {
      loaded.add(load(file));
    }
    if (loaded.isEmpty()) {
      if (!allowEphemeral) {
        throw new IllegalStateException(
            "no signing key configured: set AUTH_SIGNING_KEYS (ephemeral keys are only allowed in"
                + " the dev and test profiles)");
      }
      loaded.add(ephemeral());
    }
    Set<String> keyIds = new HashSet<>();
    for (ECKey key : loaded) {
      if (!keyIds.add(key.getKeyID())) {
        throw new IllegalArgumentException("duplicate signing key: " + key.getKeyID());
      }
    }
    this.current = loaded.get(0);
    this.verifyOnly = loaded.subList(1, loaded.size()).stream().map(ECKey::toPublicJWK).toList();
  }

  /** The key used to sign new tokens. */
  public ECKey current() {
    return current;
  }

  /** Public parts of the previous keys, kept so tokens signed before a rotation still verify. */
  public List<ECKey> verifyOnly() {
    return verifyOnly;
  }

  public List<ECKey> all() {
    List<ECKey> all = new ArrayList<>();
    all.add(current);
    all.addAll(verifyOnly);
    return List.copyOf(all);
  }

  @Override
  public String toString() {
    return "SigningKeyProvider[current="
        + current.getKeyID()
        + ", verifyOnly="
        + verifyOnly.size()
        + "]";
  }

  private static ECKey load(Path file) {
    try {
      String pem = Files.readString(file);
      String base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(base64);
      ECPrivateKey privateKey =
          (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
      if (!Curve.P_256.equals(Curve.forECParameterSpec(privateKey.getParams()))) {
        throw new IllegalArgumentException("not a P-256 key");
      }
      return toEcKey(privateKey, derivePublicKey(privateKey));
    } catch (IOException
        | GeneralSecurityException
        | IllegalArgumentException
        | ClassCastException e) {
      // never echo the file content or the underlying message: it may contain key material
      throw new IllegalArgumentException(
          "invalid signing key file "
              + file.getFileName()
              + ": expected a PKCS#8 PEM with an EC P-256 private key");
    }
  }

  private static ECKey ephemeral() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      KeyPair pair = generator.generateKeyPair();
      return toEcKey((ECPrivateKey) pair.getPrivate(), (ECPublicKey) pair.getPublic());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot generate an ephemeral signing key", e);
    }
  }

  private static ECKey toEcKey(ECPrivateKey privateKey, ECPublicKey publicKey) {
    try {
      return new ECKey.Builder(Curve.P_256, publicKey)
          .privateKey(privateKey)
          .keyUse(KeyUse.SIGNATURE)
          .algorithm(JWSAlgorithm.ES256)
          .keyIDFromThumbprint()
          .build();
    } catch (JOSEException e) {
      throw new IllegalStateException("cannot build the signing key", e);
    }
  }

  /**
   * A PKCS#8 file only holds the private scalar; the public point is d times the curve base point.
   */
  private static ECPublicKey derivePublicKey(ECPrivateKey privateKey)
      throws GeneralSecurityException {
    ECNamedCurveParameterSpec curve = ECNamedCurveTable.getParameterSpec("secp256r1");
    org.bouncycastle.math.ec.ECPoint q = curve.getG().multiply(privateKey.getS()).normalize();
    ECPoint point =
        new ECPoint(q.getAffineXCoord().toBigInteger(), q.getAffineYCoord().toBigInteger());
    return (ECPublicKey)
        KeyFactory.getInstance("EC")
            .generatePublic(new ECPublicKeySpec(point, privateKey.getParams()));
  }
}
