package tech.toolkit.tenantcore.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tech.toolkit.tenantcore.platform.InternalJwtVerifier;

/**
 * 后端无状态、不面向浏览器：关闭 Servlet HttpSession、CSRF、表单登录与 Basic； 认证与授权全部由 {@link JwtAuthenticationFilter}
 * 完成（浏览器侧 CSRF 防护在 BFF）。
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http, InternalJwtVerifier verifier, PrincipalLoader principals)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .addFilterBefore(
            new JwtAuthenticationFilter(verifier, principals),
            UsernamePasswordAuthenticationFilter.class)
        .headers(Customizer.withDefaults());
    return http.build();
  }
}
