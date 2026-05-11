package me.apet97.breakcompliance.config;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS for cross-origin probes from Clockify hosts. Without this, Spring's
 * {@code DefaultCorsProcessor} returns 403 "Invalid CORS request" on any
 * preflight that carries an {@code Origin} header, which is what the
 * developer-portal validator does when verifying an add-on is responsive
 * before installing.
 *
 * <p>Allowed origin patterns come from
 * {@link BreakComplianceSecurityProperties#corsAllowedOriginPatterns()} so
 * deployers can extend the list for regions / subdomains via the
 * {@code CORS_ALLOWED_ORIGIN_PATTERNS} env var (comma-delimited) — per the
 * marketplace rule that hosts must not be hardcoded in business logic.
 */
@Configuration
@EnableConfigurationProperties(BreakComplianceSecurityProperties.class)
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(BreakComplianceSecurityProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(props.corsAllowedOriginPatterns());
        config.setAllowedMethods(List.of("GET", "POST", "HEAD", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Type", "Content-Length"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
