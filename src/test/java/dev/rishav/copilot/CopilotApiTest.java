package dev.rishav.copilot;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class CopilotApiTest {
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private final HttpClient client=HttpClient.newHttpClient();
    private HttpResponse<String> post(String path,Object value) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
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
        assertThat(post("/api/executions",Map.of("planId",planId,"scenarioIndex",0,"expectedRevision",1,"approved",false)).statusCode()).isEqualTo(400);
        var positive=mapper.readTree(post("/api/executions",Map.of("planId",planId,"scenarioIndex",0,"expectedRevision",1,"approved",true)).body());
        assertThat(positive.path("passed").asBoolean()).isTrue();
        var negative=mapper.readTree(post("/api/executions",Map.of("planId",planId,"scenarioIndex",1,"expectedRevision",1,"approved",true)).body());
        assertThat(negative.path("actualStatus").asInt()).isEqualTo(400);
        assertThat(negative.path("passed").asBoolean()).isTrue();
        var edge=mapper.readTree(post("/api/executions",Map.of("planId",planId,"scenarioIndex",2,"expectedRevision",1,"approved",true)).body());
        assertThat(edge.path("passed").asBoolean()).isFalse();
        assertThat(edge.path("actualStatus").asInt()).isEqualTo(400);
        assertThat(edge.path("expectedStatus").asInt()).isEqualTo(201);
        var analysis=post("/api/executions/"+edge.path("id").asText()+"/analysis",Map.of());
        assertThat(analysis.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(analysis.body()).path("explanation").asText()).contains("minimum=0");
        assertThat(post("/api/executions",Map.of("planId",planId,"scenarioIndex",999,"expectedRevision",1,"approved",true)).statusCode()).isEqualTo(400);
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
    private JsonNode createPlan() throws Exception {
        var imported=post("/api/specifications",Map.of("name","Editable Catalog","content",OpenApiReaderTest.example()));
        String id=mapper.readTree(imported.body()).path("id").asText();
        return mapper.readTree(post("/api/plans",Map.of("specificationId",id,"endpointKey","POST /products")).body());
    }
    private HttpResponse<String> edit(String id, int index, Object value) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/plans/"+id+"/scenarios/"+index))
                .header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode latest(String id) throws Exception {
        return mapper.readTree(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/plans/"+id)).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    @Test void editsRequireCurrentRevisionAndPreserveExecutionEvidence() throws Exception {
        var plan=createPlan(); String id=plan.path("id").asText();
        var first=mapper.readTree(post("/api/executions",Map.of("planId",id,"scenarioIndex",0,"expectedRevision",1,"approved",true)).body());
        assertThat(first.path("scenarioRevision").asInt()).isEqualTo(1);
        assertThat(first.path("requestSnapshot")).isEqualTo(plan.path("scenarios").get(0));
        var edited=edit(id,0,Map.of("expectedRevision",1,"query",Map.of(),"body",Map.of("name","Free product","price",0),"expectedStatus",201));
        assertThat(edited.statusCode()).isEqualTo(200);
        var updated=mapper.readTree(edited.body());
        assertThat(updated.path("scenarioRevisions").toString()).isEqualTo("[2,1,1]");
        assertThat(updated.path("scenarios").get(0).path("body").path("price").asInt()).isZero();
        assertThat(post("/api/executions",Map.of("planId",id,"scenarioIndex",0,"expectedRevision",1,"approved",true)).statusCode()).isEqualTo(409);
        assertThat(post("/api/executions",Map.of("planId",id,"scenarioIndex",0,"expectedRevision",2,"approved",false)).statusCode()).isEqualTo(400);
        var run=post("/api/executions",Map.of("planId",id,"scenarioIndex",0,"expectedRevision",2,"approved",true));
        assertThat(run.statusCode()).isEqualTo(200);
        var observed=mapper.readTree(run.body());
        assertThat(observed.path("scenarioRevision").asInt()).isEqualTo(2);
        assertThat(observed.path("actualStatus").asInt()).isEqualTo(400);
        assertThat(observed.path("passed").asBoolean()).isFalse();
        assertThat(observed.path("requestSnapshot")).isEqualTo(updated.path("scenarios").get(0));
        var oldAnalysis=post("/api/executions/"+first.path("id").asText()+"/analysis",Map.of());
        assertThat(mapper.readTree(oldAnalysis.body()).path("explanation").asText()).contains("matches expected HTTP 201");
        assertThat(latest(id)).isEqualTo(updated);
    }
    @Test void invalidEditsLeaveSavedRequestAndRevisionUnchanged() throws Exception {
        var plan=createPlan(); String id=plan.path("id").asText();
        var changes=new LinkedHashMap<String,Object>();
        changes.put("expectedRevision",1); changes.put("query",Map.of()); changes.put("body",Map.of("price",2)); changes.put("expectedStatus",418);
        assertThat(edit(id,0,changes).statusCode()).isEqualTo(422);
        changes.put("expectedStatus",400); changes.put("query",Map.of("invented","value"));
        assertThat(edit(id,0,changes).statusCode()).isEqualTo(422);
        changes.put("query",Map.of()); changes.put("body",Map.of("name","x".repeat(16001)));
        assertThat(edit(id,0,changes).statusCode()).isEqualTo(422);
        assertThat(edit(id,-1,changes).statusCode()).isEqualTo(400);
        changes.remove("expectedRevision");
        assertThat(edit(id,0,changes).statusCode()).isEqualTo(400);
        assertThat(post("/api/executions",Map.of("planId",id,"scenarioIndex",0,"approved",true)).statusCode()).isEqualTo(400);
        assertThat(latest(id)).isEqualTo(plan);
        // Negative cases deliberately allow a missing/invalid business field; structural validation still applies.
        changes.put("expectedRevision",1); changes.put("body",null);
        var response=edit(id,0,changes);
        assertThat(response.statusCode()).isEqualTo(200);
        var updated=mapper.readTree(response.body()).path("scenarios").get(0);
        assertThat(updated.path("method")).isEqualTo(plan.path("scenarios").get(0).path("method"));
        assertThat(updated.path("path")).isEqualTo(plan.path("scenarios").get(0).path("path"));
        assertThat(updated.path("sourceIds")).isEqualTo(plan.path("scenarios").get(0).path("sourceIds"));
        assertThat(updated.path("body").isNull()).isTrue();
    }
    @Test void concurrentEditsCannotOverwriteEachOther() throws Exception {
        var plan=createPlan(); String id=plan.path("id").asText();
        var change=Map.of("expectedRevision",1,"query",Map.of(),"body",Map.of("name","Changed","price",2),"expectedStatus",201);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/plans/"+id+"/scenarios/0"))
                .header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(change))).build();
        var a=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        var b=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        assertThat(List.of(a.join().statusCode(),b.join().statusCode())).containsExactlyInAnyOrder(200,409);
        assertThat(latest(id).path("scenarioRevisions").get(0).asInt()).isEqualTo(2);
    }
    @Test void reportsSyntaxLocationWithoutEchoingContractContent() throws Exception {
        var response=post("/api/specifications",Map.of("name","Broken","content","openapi: 3.0.3\npaths: [\nSECRET_SENTINEL"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("line","column").doesNotContain("SECRET_SENTINEL");
    }

}
