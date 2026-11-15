package co.edu.corhuila.dlc.appointments.app;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * SDD contract check (Annex C): every response produced by an HTTP test under /api/v1 or /internal/v1 must match
 * the OpenAPI contracts of a dlc-docs checkout (DLC_DOCS_DIR, read from 07-api/contracts/openapi), checking status,
 * headers and body schema. The contracts are read in place and never copied into this repository. Without
 * DLC_DOCS_DIR the check is skipped, like the database tests without TEST_DATABASE_URL. Requests are not validated,
 * because tests send invalid requests on purpose. Other routes, such as /health, are not checked.
 */
@Configuration(proxyBeanMethods = false)
class ContractValidation {
    private static final Path CONTRACTS = contracts();
    static final boolean ENABLED = CONTRACTS != null;
    static final ResultMatcher MATCHER = ENABLED ? matcher() : result -> { };

    @Bean
    MockMvcBuilderCustomizer contractResponses() {
        return builder -> builder.alwaysExpect(MATCHER);
    }

    private static Path contracts() {
        String root = System.getenv("DLC_DOCS_DIR");
        if (root == null || root.isBlank()) return null;
        Path dir = Path.of(root, "07-api", "contracts", "openapi");
        if (!Files.isRegularFile(dir.resolve("appointments-service.yaml"))) {
            throw new IllegalStateException("DLC_DOCS_DIR has no 07-api/contracts/openapi/appointments-service.yaml");
        }
        return dir;
    }

    private static ResultMatcher matcher() {
        ResultMatcher publicApi = OpenApiValidationMatchers.openApi().isValid(validator("appointments-service.yaml"));
        ResultMatcher internalApi = OpenApiValidationMatchers.openApi()
                .isValid(validator("internal-coordination.yaml"));
        return result -> {
            String path = result.getRequest().getRequestURI();
            if (path.startsWith("/api/v1/")) publicApi.match(result);
            else if (path.startsWith("/internal/v1/")) internalApi.match(result);
        };
    }

    private static OpenApiInteractionValidator validator(String file) {
        return OpenApiInteractionValidator.createForSpecificationUrl(CONTRACTS.resolve(file).toUri().toString())
                .withLevelResolver(LevelResolver.create()
                        .withLevel("validation.request", ValidationReport.Level.IGNORE)
                        .build())
                .build();
    }
}
