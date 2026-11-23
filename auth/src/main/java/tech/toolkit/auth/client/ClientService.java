package tech.toolkit.auth.client;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.toolkit.auth.client.ClientException.Kind;
import tech.toolkit.auth.config.AuthProperties;

/**
 * Registers and changes clients online (FR-021 to FR-024). Reads and writes go straight to the
 * client table, not through the repository the OAuth endpoints use: that one hides a disabled
 * client, and the administrator must still see and re-enable it. The endpoints load a client on
 * every request, so whatever is saved here is in force at once, without a restart.
 */
@Service
public class ClientService {

  private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");
  private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]");
  private static final int MIN_TTL_SECONDS = 60;
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Public clients keep no secret and prove themselves with PKCE alone. */
  public enum Type {
    CONFIDENTIAL,
    PUBLIC
  }

  /** What a new client is registered with. */
  public record NewClient(
      String clientId,
      Type type,
      List<String> redirectUris,
      List<String> postLogoutRedirectUris,
      String backchannelLogoutUri,
      List<String> scopes,
      Integer accessTokenTtlSeconds,
      Integer refreshTokenTtlSeconds) {}

  /** What a change names; {@code null} leaves a setting as it is, an empty URI clears it. */
  public record ClientChanges(
      List<String> redirectUris,
      List<String> postLogoutRedirectUris,
      String backchannelLogoutUri,
      List<String> scopes,
      Integer accessTokenTtlSeconds,
      Integer refreshTokenTtlSeconds) {}

  /** A client as the API shows it; never carries the secret. */
  public record ClientView(
      String clientId,
      Type type,
      List<String> redirectUris,
      List<String> postLogoutRedirectUris,
      String backchannelLogoutUri,
      List<String> scopes,
      long accessTokenTtlSeconds,
      long refreshTokenTtlSeconds,
      boolean enabled) {}

  /** A new client, and its secret, which is shown this once only. */
  public record Registered(ClientView client, String clientSecret) {
    @Override
    public String toString() {
      return "Registered[client=" + client + "]";
    }
  }

  private final RegisteredClientRepository clients;
  private final ClientExtRepository extensions;
  private final PasswordEncoder encoder;
  private final AuthProperties properties;
  private final JdbcTemplate jdbc;

  public ClientService(
      JdbcTemplate jdbc,
      ClientExtRepository extensions,
      PasswordEncoder encoder,
      AuthProperties properties) {
    this.jdbc = jdbc;
    this.clients = new JdbcRegisteredClientRepository(jdbc);
    this.extensions = extensions;
    this.encoder = encoder;
    this.properties = properties;
  }

  @Transactional
  public Registered register(NewClient request) {
    if (request == null
        || request.clientId() == null
        || !CLIENT_ID.matcher(request.clientId()).matches()) {
      throw invalid("clientId 须为 1 到 100 位的字母、数字、点、下划线或连字符");
    }
    if (request.type() == null) {
      throw invalid("type 必填，取值 CONFIDENTIAL 或 PUBLIC");
    }
    List<String> redirects = redirectUris(request.redirectUris(), "redirectUris", true);
    List<String> postLogout =
        redirectUris(request.postLogoutRedirectUris(), "postLogoutRedirectUris", false);
    String backchannel = optionalUri(request.backchannelLogoutUri(), "backchannelLogoutUri");
    List<String> scopes = scopes(request.scopes());
    AuthProperties.Tokens defaults = properties.tokens();
    Duration access =
        ttl(request.accessTokenTtlSeconds(), "accessTokenTtlSeconds", defaults.accessTokenTtl());
    Duration refresh =
        ttl(request.refreshTokenTtlSeconds(), "refreshTokenTtlSeconds", defaults.refreshTokenTtl());
    requireValidLifetimes(access, refresh);
    if (clients.findByClientId(request.clientId()) != null) {
      throw conflict(request.clientId());
    }

    String secret = request.type() == Type.CONFIDENTIAL ? newSecret() : null;
    RegisteredClient.Builder builder =
        RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(request.clientId())
            .clientName(request.clientId())
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(false)
                    .build())
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenTimeToLive(defaults.accessTokenTtl())
                    .authorizationCodeTimeToLive(defaults.authorizationCodeTtl())
                    .refreshTokenTimeToLive(defaults.refreshTokenTtl())
                    .reuseRefreshTokens(false)
                    .idTokenSignatureAlgorithm(SignatureAlgorithm.ES256)
                    .build());
    if (secret == null) {
      builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
    } else {
      builder
          .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
          .clientSecret(encoder.encode(secret));
    }
    redirects.forEach(builder::redirectUri);
    postLogout.forEach(builder::postLogoutRedirectUri);
    scopes.forEach(builder::scope);
    try {
      clients.save(builder.build());
    } catch (DuplicateKeyException e) {
      throw conflict(request.clientId());
    }
    extensions.upsert(
        new ClientExt(
            request.clientId(),
            true,
            backchannel,
            null,
            request.accessTokenTtlSeconds(),
            request.refreshTokenTtlSeconds()));
    return new Registered(view(request.clientId()), secret);
  }

  public List<ClientView> list() {
    return jdbc
        .queryForList(
            "select client_id from oauth2_registered_client order by client_id", String.class)
        .stream()
        .map(this::view)
        .toList();
  }

  public ClientView get(String clientId) {
    return view(clientId);
  }

  @Transactional
  public ClientView update(String clientId, ClientChanges changes) {
    RegisteredClient current = find(clientId);
    ClientExt ext = extension(clientId);
    List<String> redirects =
        changes.redirectUris() == null
            ? null
            : redirectUris(changes.redirectUris(), "redirectUris", true);
    List<String> postLogout =
        changes.postLogoutRedirectUris() == null
            ? null
            : redirectUris(changes.postLogoutRedirectUris(), "postLogoutRedirectUris", false);
    List<String> scopes = changes.scopes() == null ? null : scopes(changes.scopes());
    String backchannel =
        changes.backchannelLogoutUri() == null
            ? ext.backchannelLogoutUri()
            : optionalUri(changes.backchannelLogoutUri(), "backchannelLogoutUri");
    Integer accessSeconds = pick(changes.accessTokenTtlSeconds(), ext.accessTokenTtlSeconds());
    Integer refreshSeconds = pick(changes.refreshTokenTtlSeconds(), ext.refreshTokenTtlSeconds());
    TokenSettings registered = current.getTokenSettings();
    requireValidLifetimes(
        ttl(accessSeconds, "accessTokenTtlSeconds", registered.getAccessTokenTimeToLive()),
        ttl(refreshSeconds, "refreshTokenTtlSeconds", registered.getRefreshTokenTimeToLive()));

    RegisteredClient.Builder builder = RegisteredClient.from(current);
    if (redirects != null) {
      builder.redirectUris(set -> replace(set, redirects));
    }
    if (postLogout != null) {
      builder.postLogoutRedirectUris(set -> replace(set, postLogout));
    }
    if (scopes != null) {
      builder.scopes(set -> replace(set, scopes));
    }
    clients.save(builder.build());
    extensions.upsert(
        new ClientExt(clientId, ext.enabled(), backchannel, null, accessSeconds, refreshSeconds));
    return view(clientId);
  }

  /** Switches the client on or off; the OAuth endpoints stop (or resume) serving it at once. */
  @Transactional
  public void setEnabled(String clientId, boolean enabled) {
    find(clientId);
    ClientExt ext = extension(clientId);
    extensions.upsert(
        new ClientExt(
            clientId,
            enabled,
            ext.backchannelLogoutUri(),
            ext.postLogoutRedirectUris(),
            ext.accessTokenTtlSeconds(),
            ext.refreshTokenTtlSeconds()));
  }

  /** Replaces the secret of a confidential client; the old one stops working at once. */
  @Transactional
  public String rotateSecret(String clientId) {
    RegisteredClient current = find(clientId);
    if (current.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
      throw invalid("公共 client 没有 secret，无需轮换");
    }
    String secret = newSecret();
    clients.save(RegisteredClient.from(current).clientSecret(encoder.encode(secret)).build());
    return secret;
  }

  // ---- helpers

  private RegisteredClient find(String clientId) {
    RegisteredClient client = clients.findByClientId(clientId);
    if (client == null) {
      throw new ClientException(Kind.NOT_FOUND, "没有 clientId 为 " + clientId + " 的 client");
    }
    return client;
  }

  private ClientExt extension(String clientId) {
    return extensions.find(clientId).orElse(new ClientExt(clientId, true, null, null, null, null));
  }

  private ClientView view(String clientId) {
    RegisteredClient client = find(clientId);
    ClientExt ext = extension(clientId);
    TokenSettings token = client.getTokenSettings();
    return new ClientView(
        clientId,
        client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
            ? Type.PUBLIC
            : Type.CONFIDENTIAL,
        sorted(client.getRedirectUris()),
        sorted(client.getPostLogoutRedirectUris()),
        ext.backchannelLogoutUri(),
        sorted(client.getScopes()),
        ext.accessTokenTtlSeconds() != null
            ? ext.accessTokenTtlSeconds()
            : token.getAccessTokenTimeToLive().toSeconds(),
        ext.refreshTokenTtlSeconds() != null
            ? ext.refreshTokenTtlSeconds()
            : token.getRefreshTokenTimeToLive().toSeconds(),
        ext.enabled());
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }

  private static <T> void replace(Set<T> target, List<T> values) {
    target.clear();
    target.addAll(new LinkedHashSet<>(values));
  }

  private static Integer pick(Integer requested, Integer current) {
    return requested != null ? requested : current;
  }

  private static String newSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static List<String> scopes(List<String> scopes) {
    if (scopes == null || scopes.isEmpty()) {
      throw invalid("scopes 至少要有一项");
    }
    for (String scope : scopes) {
      if (scope == null || scope.isBlank() || scope.chars().anyMatch(Character::isWhitespace)) {
        throw invalid("scope 不能为空，也不能含空白");
      }
    }
    return scopes;
  }

  private static List<String> redirectUris(List<String> uris, String field, boolean required) {
    if (uris == null || uris.isEmpty()) {
      if (required) {
        throw invalid(field + " 至少要有一项");
      }
      return List.of();
    }
    uris.forEach(uri -> requireAbsoluteUri(uri, field, true));
    return uris;
  }

  private static String optionalUri(String uri, String field) {
    if (uri == null || uri.isBlank()) {
      return null;
    }
    requireAbsoluteUri(uri, field, false);
    return uri;
  }

  /** Absolute http(s) URI without a fragment; a redirect also needs https unless it is loopback. */
  private static void requireAbsoluteUri(String uri, String field, boolean redirect) {
    URI parsed;
    try {
      parsed = URI.create(uri);
    } catch (RuntimeException e) {
      throw invalid(field + " 含无效的地址");
    }
    String scheme = parsed.getScheme();
    boolean web = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    if (!web || parsed.getHost() == null || parsed.getFragment() != null) {
      throw invalid(field + " 须为不带 fragment 的绝对 http(s) 地址");
    }
    if (redirect
        && "http".equalsIgnoreCase(scheme)
        && !LOOPBACK.contains(parsed.getHost().toLowerCase())) {
      throw invalid(field + " 只有回环地址可以使用 http，其余须为 https");
    }
  }

  private static Duration ttl(Integer seconds, String field, Duration fallback) {
    if (seconds == null) {
      return fallback;
    }
    if (seconds < MIN_TTL_SECONDS) {
      throw invalid(field + " 不能小于 " + MIN_TTL_SECONDS + " 秒");
    }
    return Duration.ofSeconds(seconds);
  }

  private static void requireValidLifetimes(Duration access, Duration refresh) {
    if (!AuthProperties.Tokens.lifetimesAreValid(access, refresh)) {
      throw invalid("访问令牌的有效期必须短于刷新令牌的有效期");
    }
  }

  private static ClientException invalid(String message) {
    return new ClientException(Kind.INVALID, message);
  }

  private static ClientException conflict(String clientId) {
    return new ClientException(Kind.CONFLICT, "clientId " + clientId + " 已存在");
  }
}
