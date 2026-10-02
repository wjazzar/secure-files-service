package com.praxedo.securefiles.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * No credential has a default value in {@code application.yml} (audit S-02):
 * a default is a secret written in the repository, and a deployment that
 * forgets a variable would start with a password known to everyone.
 *
 * <p>Without the {@code local} profile and without the variable, resolving a
 * credential fails and names the variable expected — which is what makes the
 * service refuse to start. With the profile, the development values of
 * {@code docker-compose.yml} are found. Checked on the files themselves, with
 * Spring's own resolver, so the rule holds for any credential added later.
 */
@DisplayName("No credential has a default value: without the local profile, a missing one names its variable")
class NoSecretDefaultsTest {

    private static final Pattern CREDENTIAL = Pattern.compile("(?i).*(password|secret).*");
    private static final Pattern PLACEHOLDER_WITHOUT_DEFAULT = Pattern.compile("\\$\\{[A-Z0-9_]+}");

    @Test
    @DisplayName("⭐ every password and secret of application.yml is a bare ${VARIABLE}, without default")
    void no_credential_has_a_default() throws IOException {
        Map<String, Object> credentials = credentials(load("application.yml"));

        assertThat(credentials).hasSizeGreaterThanOrEqualTo(6);
        assertThat(credentials).allSatisfy((name, value) ->
                assertThat(value.toString()).as(name).matches(PLACEHOLDER_WITHOUT_DEFAULT));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "spring.datasource.password, DB_PASSWORD",
            "spring.flyway.password, DB_OWNER_PASSWORD",
            "praxedo.storage.ingest.secret-key, S3_INGEST_SECRET",
            "praxedo.storage.worker.secret-key, S3_WORKER_SECRET",
            "praxedo.storage.delivery.secret-key, S3_DELIVERY_SECRET",
            "praxedo.security.oidc.client.client-secret, OIDC_CLIENT_SECRET"
    })
    @DisplayName("without the local profile, a missing credential fails and names its variable")
    void a_missing_credential_names_its_variable(String property, String variable) throws IOException {
        PropertySourcesPropertyResolver withoutProfile = resolver(load("application.yml"));

        assertThatThrownBy(() -> withoutProfile.getProperty(property))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(variable);
    }

    @Test
    @DisplayName("with the local profile, every credential resolves to the development value of docker-compose.yml")
    void the_local_profile_provides_them_all() throws IOException {
        List<PropertySource<?>> application = load("application.yml");
        PropertySourcesPropertyResolver withLocal = resolver(load("application-local.yml"), application);

        assertThat(credentials(application)).allSatisfy((name, value) ->
                assertThat(withLocal.getProperty(name)).as(name).isNotBlank());
        assertThat(withLocal.getProperty("spring.datasource.username")).isEqualTo("praxedo_app");
        assertThat(withLocal.getProperty("spring.flyway.user")).isEqualTo("praxedo_owner");
    }

    private static Map<String, Object> credentials(List<PropertySource<?>> sources) {
        EnumerablePropertySource<?> main = (EnumerablePropertySource<?>) sources.getFirst();
        Map<String, Object> found = new LinkedHashMap<>();
        for (String name : main.getPropertyNames()) {
            if (CREDENTIAL.matcher(name).matches()) {
                found.put(name, main.getProperty(name));
            }
        }
        return found;
    }

    @SafeVarargs
    private static PropertySourcesPropertyResolver resolver(List<PropertySource<?>>... layers) {
        MutablePropertySources sources = new MutablePropertySources();
        for (List<PropertySource<?>> layer : layers) {
            layer.forEach(sources::addLast);
        }
        return new PropertySourcesPropertyResolver(sources);
    }

    private static List<PropertySource<?>> load(String file) throws IOException {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
    }
}
