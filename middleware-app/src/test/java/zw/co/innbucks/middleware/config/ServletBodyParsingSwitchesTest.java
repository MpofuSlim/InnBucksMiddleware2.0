package zw.co.innbucks.middleware.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.UrlResource;
import org.springframework.web.filter.FormContentFilter;
import org.springframework.web.multipart.MultipartResolver;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing parses a request body before a controller (and so before Spring
 * Security, and before a KYC upload takes its place) — pinned against the
 * SHIPPED {@code application.yaml}.
 *
 * <p>Two Boot defaults do exactly that, and both are switched off there:
 * multipart (Tomcat spools every part to a temp file, a plaintext copy of a
 * KYC photo on disk) and {@link FormContentFilter} (the whole body of any
 * PUT, PATCH or DELETE sent as a form, read into the heap with no size limit,
 * unauthenticated). The test profile's own {@code application.yaml} shadows
 * the main one in this module, so no {@code @SpringBootTest} here ever sees
 * those two lines; this test reads the main file directly and proves Boot
 * honours what it says — and, as a control, that the same auto-configuration
 * without it DOES register both, so the assertions cannot pass vacuously.
 */
class ServletBodyParsingSwitchesTest {

    private static final AutoConfigurations WEB_MVC =
            AutoConfigurations.of(WebMvcAutoConfiguration.class, MultipartAutoConfiguration.class);

    @Test
    void theShippedConfigurationSwitchesBothOff() throws IOException {
        List<PropertySource<?>> shipped = shippedApplicationYaml();

        assertThat(property(shipped, "spring.servlet.multipart.enabled")).isEqualTo(false);
        assertThat(property(shipped, "spring.mvc.formcontent.filter.enabled")).isEqualTo(false);

        new WebApplicationContextRunner()
                .withInitializer(context -> {
                    // last, so nothing a test runner adds could be what decides
                    for (PropertySource<?> source : shipped) {
                        context.getEnvironment().getPropertySources().addLast(source);
                    }
                })
                .withConfiguration(WEB_MVC)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FormContentFilter.class);
                    assertThat(context).doesNotHaveBean(MultipartResolver.class);
                });
    }

    @Test
    void withoutIt_bootRegistersBoth() {
        new WebApplicationContextRunner()
                .withConfiguration(WEB_MVC)
                .run(context -> {
                    assertThat(context).hasSingleBean(FormContentFilter.class);
                    assertThat(context).hasSingleBean(MultipartResolver.class);
                });
    }

    /** The main module's application.yaml — not the test profile's, which comes first on the classpath. */
    private static List<PropertySource<?>> shippedApplicationYaml() throws IOException {
        List<URL> candidates = new ArrayList<>();
        for (URL url : Collections.list(ServletBodyParsingSwitchesTest.class.getClassLoader()
                .getResources("application.yaml"))) {
            if (!url.toString().contains("/test-classes/")) {
                candidates.add(url);
            }
        }
        assertThat(candidates).as("exactly one main application.yaml on the classpath").hasSize(1);
        return new YamlPropertySourceLoader().load("shipped application.yaml", new UrlResource(candidates.get(0)));
    }

    private static Object property(List<PropertySource<?>> sources, String name) {
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
