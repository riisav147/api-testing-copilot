package dev.rishav.copilot;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT, properties="server.port=18081")
@ActiveProfiles("demo")
class CopilotApiTest {
    @Autowired ObjectMapper mapper;
    private final HttpClient client=HttpClient.newHttpClient();
    private HttpResponse<String> post(String path,Object value) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18081"+path))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void importGenerateApproveExecuteAndDiagnoseBoundaryFailure() throws Exception {
        var imported=post("/api/specifications",Map.of("name","Catalog","content",OpenApiReaderTest.example()));
        assertThat(imported.statusCode()).isEqualTo(200);
        var id=mapper.readTree(imported.body()).path("id").asText();
        var generated=post("/api/plans",Map.of("specificationId",id,"endpointKey","POST /products"));
        assertThat(generated.statusCode()).isEqualTo(200);
        JsonNode plan=mapper.readTree(generated.body());
        assertThat(plan.path("scenarios")).hasSize(3);
        String planId=plan.path("id").asText();
        assertThat(post("/api/executions",Map.of("planId",planId,"scenarioIndex",0,"approved",false)).statusCode()).isEqualTo(400);
        var positive=mapper.readTree(post("/api/executions",Map.of("planId",planId,"scenarioIndex",0,"approved",true)).body());
        assertThat(positive.path("passed").asBoolean()).isTrue();
        var negative=mapper.readTree(post("/api/executions",Map.of("planId",planId,"scenarioIndex",1,"approved",true)).body());
        assertThat(negative.path("actualStatus").asInt()).isEqualTo(400);
        assertThat(negative.path("passed").asBoolean()).isTrue();
        var edge=mapper.readTree(post("/api/executions",Map.of("planId",planId,"scenarioIndex",2,"approved",true)).body());
        assertThat(edge.path("passed").asBoolean()).isFalse();
        assertThat(edge.path("actualStatus").asInt()).isEqualTo(400);
        assertThat(edge.path("expectedStatus").asInt()).isEqualTo(201);
        var analysis=post("/api/executions/"+edge.path("id").asText()+"/analysis",Map.of());
        assertThat(analysis.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(analysis.body()).path("explanation").asText()).contains("minimum=0");
        assertThat(post("/api/executions",Map.of("planId",planId,"scenarioIndex",999,"approved",true)).statusCode()).isEqualTo(400);
    }
    @Test void rejectsMalformedInputAndUnknownResources() throws Exception {
        assertThat(post("/api/specifications",Map.of("name","test","content","bad")).statusCode()).isEqualTo(400);
        assertThat(post("/api/plans",Map.of("specificationId",UUID.randomUUID(),"endpointKey","GET /products")).statusCode()).isEqualTo(404);
    }
    @Test void keepsRetrievalWithinSpecification() throws Exception {
        String a=mapper.readTree(post("/api/specifications",Map.of("name","A","content",OpenApiReaderTest.example(),"documentation","TENANT_A_ONLY")).body()).path("id").asText();
        String b=mapper.readTree(post("/api/specifications",Map.of("name","B","content",OpenApiReaderTest.example(),"documentation","TENANT_B_ONLY")).body()).path("id").asText();
        var plan=post("/api/plans",Map.of("specificationId",a,"endpointKey","POST /products"));
        assertThat(plan.body()).contains("TENANT_A_ONLY").doesNotContain("TENANT_B_ONLY");
    }
}
