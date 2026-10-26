package dev.rishav.copilot;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class OpenApiReaderTest {
    private final OpenApiReader reader=new OpenApiReader();
    static String example() throws Exception {
        try(var input=OpenApiReaderTest.class.getResourceAsStream("/static/examples/catalog.openapi.yaml")) {
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test void resolvesLocalSchemasAndInheritedParameters() throws Exception {
        var endpoints=reader.read(example());
        assertThat(endpoints).hasSize(3);
        var post=endpoints.stream().filter(e -> e.method().equals("POST")).findFirst().orElseThrow();
        assertThat(post.contract().at("/requestBody/content/application~1json/schema/properties/price/minimum").asInt(-1)).isZero();
        assertThat(endpoints.get(2).contract().path("parameters")).hasSize(1);
    }
    @Test void parsesJson() {
        assertThat(reader.read("""
                {"openapi":"3.1.0","paths":{"/ping":{"get":{"responses":{"200":{"description":"ok"}}}}}}
                """)).hasSize(1);
    }
    @Test void rejectsRemoteReferencesWithoutFetching() {
        assertThatThrownBy(() -> reader.read("""
                openapi: 3.0.3
                paths:
                  /products:
                    $ref: https://example.invalid/private.yaml
                """)).isInstanceOf(ApiException.class).hasMessageContaining("Only local");
    }
    @Test void rejectsRecursiveSchemas() throws Exception {
        assertThatThrownBy(() -> reader.read(example().replace("type: object","$ref: '#/components/schemas/ProductInput'")))
                .isInstanceOf(ApiException.class).hasMessageContaining("Recursive");
    }
    @Test void rejectsUnsupportedVersionAndInvalidYaml() {
        assertThatThrownBy(() -> reader.read("swagger: '2.0'" )).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> reader.read("paths: [" )).isInstanceOf(ApiException.class);
    }
    @Test void rejectsPathTraversal() {
        assertThatThrownBy(() -> reader.read("""
                {"openapi":"3.0.0","paths":{"/../admin":{"get":{"responses":{"200":{}}}}}}
                """)).isInstanceOf(ApiException.class).hasMessageContaining("path");
    }
    @Test void rejectsUnresolvedLocalReference() throws Exception {
        assertThatThrownBy(() -> reader.read(example().replace("#/components/schemas/ProductInput","#/missing")))
                .hasMessageContaining("Unresolved");
    }
}
