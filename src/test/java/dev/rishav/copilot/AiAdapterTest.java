package dev.rishav.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.*;
import org.mockito.ArgumentCaptor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Protocol tests with synthetic model responses. They do not measure a real model's behaviour. */
class AiAdapterTest {
    private HttpServer server;
    private String responseMessage;
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<String> requests=new ArrayList<>();

    @BeforeEach void startProviderFixture() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String content="{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":"+responseMessage+",\"finish_reason\":\"stop\"}]}";
            byte[] body=content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
    }
    @AfterEach void stopProviderFixture() { server.stop(0); }
    private ChatClient client() {
        var api=OpenAiApi.builder().apiKey("local-test-key-not-a-secret")
                .baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build();
        return ChatClient.create(OpenAiChatModel.builder().openAiApi(api).build());
    }
    @Test void deserializesStructuredScenarioFromProviderProtocol() throws Exception {
        String draft="""
                {"scenarios":[{"title":"Create product","category":"POSITIVE","method":"POST","path":"/products","query":{},"body":{"name":"test","price":1},"expectedStatus":201,"rationale":"Documented success","sourceIds":["source-1"]}]}
                """;
        responseMessage=mapper.writeValueAsString(Map.of("role","assistant","content",draft));
        var endpoint=new OpenApiReader().read(OpenApiReaderTest.example()).get(1);
        var result=new AiGenerator(client()).generate(endpoint,List.of(new Evidence("source-1","contract",1)));
        assertThat(result.scenarios()).hasSize(1);
        assertThat(result.scenarios().getFirst().expectedStatus()).isEqualTo(201);
        assertThat(requests.getFirst()).contains("source-1","untrusted data","scenarios");
    }
    @Test void modelCanInvokeOnlyTheBoundApprovedExecution() {
        responseMessage="""
                {"role":"assistant","content":null,"tool_calls":[{"id":"call_fixture","type":"function","function":{"name":"executeApprovedScenario","arguments":"{}"}}]}
                """;
        AtomicInteger calls=new AtomicInteger();
        var observed=new Execution(UUID.randomUUID(),UUID.randomUUID(),0,201,201,true,2,"{}",Instant.now());
        var result=new AiWorkflow(client()).executeApproved(() -> { calls.incrementAndGet(); return observed; });
        assertThat(result).isSameAs(observed);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst()).contains("executeApprovedScenario").doesNotContain("apiKey","database-password");
    }
    @Test void refusesToInventAResultWhenModelDoesNotCallTool() throws Exception {
        responseMessage=mapper.writeValueAsString(Map.of("role","assistant","content","I ran it!"));
        assertThatThrownBy(() -> new AiWorkflow(client()).executeApproved(() -> { throw new AssertionError("Must not run"); }))
                .hasMessageContaining("did not call");
    }
    @Test void metadataFiltersIncludeSpecificationAndEndpoint() throws Exception {
        VectorStore store=mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        UUID id=UUID.randomUUID();
        var endpoint=new OpenApiReader().read(OpenApiReaderTest.example()).get(1);
        var evidence=new AiKnowledge(store).retrieve(id,endpoint);
        var request=ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(request.capture());
        String filter=request.getValue().getFilterExpression().toString();
        assertThat(filter).contains("specId",id.toString(),"endpointKey","POST /products");
        assertThat(evidence.getFirst().content()).contains("CONTRACT POST /products");
    }
}
