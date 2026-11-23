package tech.toolkit.auth.oauth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OidcLogoutEndpointConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.util.StringUtils;
import tech.toolkit.auth.account.PasswordChangeRequiredFilter;
import tech.toolkit.auth.account.UserRepository;
import tech.toolkit.auth.audit.AuditService;
import tech.toolkit.auth.client.ClientExtRepository;
import tech.toolkit.auth.config.AuthProperties;
import tech.toolkit.auth.session.SsoClientTracker;
import tech.toolkit.auth.session.SsoSessionActivity;

/**
 * The OAuth 2.0 / OIDC endpoints (Spring Authorization Server): authorization code with PKCE only,
 * exact redirect matching, no consent page, userinfo and OIDC discovery. Rules beyond the framework
 * defaults follow RFC 9700: {@code state} is required and only S256 is accepted.
 */
@Configuration
public class AuthorizationServerConfig {

  @Bean
  @Order(1)
  public SecurityFilterChain authorizationServerFilterChain(
      HttpSecurity http,
      UserInfoMapper userInfoMapper,
      JwtDecoder jwtDecoder,
      Customizer<OidcLogoutEndpointConfigurer> logoutEndpoint,
      Clock clock)
      throws Exception {
    http.oauth2AuthorizationServer(
            server -> {
              http.securityMatcher(server.getEndpointsMatcher());
              server
                  .authorizationEndpoint(
                      endpoint ->
                          endpoint.authenticationProviders(
                              providers ->
                                  providers.forEach(
                                      provider -> {
                                        if (provider
                                            instanceof
                                            OAuth2AuthorizationCodeRequestAuthenticationProvider
                                                codeProvider) {
                                          codeProvider.setAuthenticationValidator(
                                              authorizationRequestValidator());
                                        }
                                      })))
                  .authorizationServerMetadataEndpoint(
                      metadata ->
                          metadata.authorizationServerMetadataCustomizer(
                              doc -> doc.grantTypes(AuthorizationServerConfig::onlyOurGrantTypes)))
                  .oidc(
                      oidc ->
                          oidc.userInfoEndpoint(info -> info.userInfoMapper(userInfoMapper))
                              .logoutEndpoint(logoutEndpoint)
                              .providerConfigurationEndpoint(
                                  provider ->
                                      provider.providerConfigurationCustomizer(
                                          doc ->
                                              doc.grantTypes(
                                                      AuthorizationServerConfig::onlyOurGrantTypes)
                                                  .idTokenSigningAlgorithms(
                                                      algorithms -> {
                                                        algorithms.clear();
                                                        algorithms.add("ES256");
                                                      }))));
            })
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"), browserRequests()))
        .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(jwtDecoder)))
        // before the OAuth endpoints, right after the login has been read from the session
        .addFilterAfter(new ReauthenticationFilter(clock), SecurityContextHolderFilter.class)
        // a forced password change comes before any authorization
        .addFilterAfter(new PasswordChangeRequiredFilter(), SecurityContextHolderFilter.class);
    return http.build();
  }

  /** The framework lists every grant it could do; we only offer code (with PKCE) and refresh. */
  private static void onlyOurGrantTypes(java.util.List<String> grantTypes) {
    grantTypes.clear();
    grantTypes.add(AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
    grantTypes.add(AuthorizationGrantType.REFRESH_TOKEN.getValue());
  }

  /**
   * Only requests that really ask for HTML are sent to the login page. A request that accepts
   * anything (or says nothing) is an API call, such as a token revocation, and gets a 401.
   */
  private static MediaTypeRequestMatcher browserRequests() {
    MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
    matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
    return matcher;
  }

  @Bean
  public AuthorizationServerSettings authorizationServerSettings(AuthProperties properties) {
    return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
  }

  /** Registered clients, each with its own token lifetimes from {@code auth_client_ext}. */
  @Bean
  public RegisteredClientRepository registeredClientRepository(
      JdbcTemplate jdbc, ClientExtRepository clientExtensions) {
    return new ClientTokenSettingsCustomizer(
        new JdbcRegisteredClientRepository(jdbc), clientExtensions);
  }

  /**
   * Stored authorizations, wrapped so that every authorization is tied to its SSO login and a
   * reused authorization code deletes what it issued.
   */
  @Bean
  public OAuth2AuthorizationService authorizationService(
      JdbcTemplate jdbc,
      RegisteredClientRepository clients,
      AuditService audit,
      SsoClientTracker tracker,
      UserRepository users,
      ClientExtRepository clientExtensions,
      SsoSessionActivity sessions) {
    OAuth2AuthorizationService stored = new JdbcOAuth2AuthorizationService(jdbc, clients);
    OAuth2AuthorizationService linkedToLogin =
        new SessionLinkingAuthorizationService(stored, jdbc, tracker);
    OAuth2AuthorizationService refreshChecked =
        new SessionBoundRefreshValidator(linkedToLogin, users, clients, clientExtensions, sessions);
    OAuth2AuthorizationService reuseDetecting =
        new ReuseDetectingAuthorizationService(refreshChecked, jdbc, audit);
    return new AuthorizationCodeReuseHandler(reuseDetecting, audit);
  }

  /**
   * Verifies our own access tokens (userinfo, admin API): ES256 only and {@code typ} must be {@code
   * at+jwt}, so an ID token cannot be used as an access token (RFC 9068 section 4).
   */
  @Bean
  public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, AuthProperties properties) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSource(jwkSource)
            .jwsAlgorithm(SignatureAlgorithm.ES256)
            .jwtProcessorCustomizer(
                processor ->
                    processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt"))))
            .build();
    // the default validators insist on typ=JWT; RFC 9068 access tokens carry typ=at+jwt, and only
    // those
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTypeValidator("at+jwt"),
            new JwtTimestampValidator(),
            new JwtIssuerValidator(properties.issuer())));
    return decoder;
  }

  private static Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext>
      authorizationRequestValidator() {
    return ((Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext>)
            AuthorizationServerConfig::requireExactRedirectUri)
        .andThen(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR)
        .andThen(AuthorizationServerConfig::requireStateAndS256);
  }

  /**
   * The redirect URI must equal a registered one, character for character (FR-005). The framework
   * default would also accept another port on a loopback address (RFC 8252), which suits native
   * apps but not our server-side clients.
   */
  private static void requireExactRedirectUri(
      OAuth2AuthorizationCodeRequestAuthenticationContext context) {
    OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
    String uri = request.getRedirectUri();
    if (StringUtils.hasText(uri) && context.getRegisteredClient().getRedirectUris().contains(uri)) {
      return;
    }
    // no redirect address in the token: an error page is shown, never a redirect to an unverified
    // URI
    OAuth2AuthorizationCodeRequestAuthenticationToken withoutRedirect =
        new OAuth2AuthorizationCodeRequestAuthenticationToken(
            request.getAuthorizationUri(),
            request.getClientId(),
            (Authentication) request.getPrincipal(),
            null,
            request.getState(),
            request.getScopes(),
            request.getAdditionalParameters());
    OAuth2Error error =
        new OAuth2Error(
            OAuth2ErrorCodes.INVALID_REQUEST,
            "OAuth 2.0 Parameter: redirect_uri",
            "https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1");
    throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, withoutRedirect);
  }

  private static void requireStateAndS256(
      OAuth2AuthorizationCodeRequestAuthenticationContext context) {
    OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
    if (!StringUtils.hasText(request.getState())) {
      throw invalidRequest("state is required", request);
    }
    Object method = request.getAdditionalParameters().get(PkceParameterNames.CODE_CHALLENGE_METHOD);
    if (!"S256".equals(method)) {
      throw invalidRequest("code_challenge_method must be S256", request);
    }
  }

  private static OAuth2AuthorizationCodeRequestAuthenticationException invalidRequest(
      String description, OAuth2AuthorizationCodeRequestAuthenticationToken request) {
    OAuth2Error error =
        new OAuth2Error(
            OAuth2ErrorCodes.INVALID_REQUEST,
            description,
            "https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1");
    return new OAuth2AuthorizationCodeRequestAuthenticationException(error, request);
  }
}
