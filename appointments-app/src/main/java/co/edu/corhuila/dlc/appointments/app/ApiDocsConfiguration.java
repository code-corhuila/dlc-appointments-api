package co.edu.corhuila.dlc.appointments.app;

import co.edu.corhuila.dlc.appointments.adapter.in.http.ApiDocs;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.webjars.WebJarVersionLocator;

/**
 * Opt-in Swagger UI: only when APPOINTMENTS_API_DOCS_DIR names a dlc-docs contracts folder (dev, qa). Without it
 * neither /docs nor its assets exist, so production exposes no documentation surface.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${appointments.api-docs.contracts-dir:}'.isBlank()")
class ApiDocsConfiguration implements WebMvcConfigurer {

    @Bean
    RouterFunction<ServerResponse> apiDocs(@Value("${appointments.api-docs.contracts-dir}") String contractsDir) {
        return new ApiDocs(Path.of(contractsDir)).routes();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String version = new WebJarVersionLocator().version("swagger-ui");
        if (version == null) throw new IllegalStateException("The swagger-ui webjar is missing");
        registry.addResourceHandler("/docs/assets/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/" + version + "/");
    }
}
