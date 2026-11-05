package co.edu.corhuila.dlc.appointments.adapter.in.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Swagger UI at /docs over the OpenAPI contracts of a dlc-docs checkout, read in place on every request
 * (contract-first: nothing is generated from code or copied into this repository). Only the Appointments contracts
 * and the shared components they reference are served; the UI assets come from /docs/assets, never a CDN. "Try it
 * out" calls this service through the contracts' relative servers (/api/v1, /internal/v1) and still needs a token.
 */
public final class ApiDocs {
    private static final Set<String> CONTRACTS =
            Set.of("appointments-service.yaml", "internal-coordination.yaml", "_shared.yaml");
    private static final MediaType YAML = MediaType.parseMediaType("application/yaml;charset=UTF-8");
    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>dlc-appointments-api contracts</title>
              <link rel="stylesheet" href="{base}/docs/assets/swagger-ui.css">
            </head>
            <body>
              <div id="swagger-ui"></div>
              <script src="{base}/docs/assets/swagger-ui-bundle.js"></script>
              <script src="{base}/docs/assets/swagger-ui-standalone-preset.js"></script>
              <script>
                window.ui = SwaggerUIBundle({
                  urls: [
                    {name: "Public API (/api/v1)", url: "{base}/docs/contracts/appointments-service.yaml"},
                    {name: "Internal API (/internal/v1)", url: "{base}/docs/contracts/internal-coordination.yaml"}
                  ],
                  dom_id: "#swagger-ui",
                  presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
                  layout: "StandaloneLayout",
                  persistAuthorization: false
                });
              </script>
            </body>
            </html>
            """;

    private final Path contracts;

    public ApiDocs(Path contractsDir) {
        Objects.requireNonNull(contractsDir, "contractsDir");
        if (!Files.isDirectory(contractsDir)) {
            throw new IllegalArgumentException("API docs contracts folder not found: " + contractsDir);
        }
        this.contracts = contractsDir;
    }

    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
                .GET("/docs", this::page)
                .GET("/docs/contracts/{file}", this::contract)
                .build();
    }

    private ServerResponse page(ServerRequest request) {
        String base = request.requestPath().contextPath().value();
        return ServerResponse.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore())
                .body(PAGE.replace("{base}", base));
    }

    private ServerResponse contract(ServerRequest request) throws IOException {
        String file = request.pathVariable("file");
        Path path = contracts.resolve(CONTRACTS.contains(file) ? file : "");
        if (!CONTRACTS.contains(file) || !Files.isRegularFile(path)) return ServerResponse.notFound().build();
        return ServerResponse.ok().contentType(YAML).cacheControl(CacheControl.noStore())
                .body(Files.readString(path));
    }
}
