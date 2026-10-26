package dev.rishav.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import static dev.rishav.copilot.Models.*;

@Component
public class SandboxRunner {
    private final int port;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper;
    public SandboxRunner(@Value("${copilot.sandbox-port}") int port, ObjectMapper mapper) { this.port=port; this.mapper=mapper; }

    public Execution execute(Plan plan, int index) {
        Scenario s=plan.scenarios().get(index);
        if (!allowed(s)) throw new ApiException(HttpStatus.FORBIDDEN,"Execution is restricted to GET /products, GET /products/{id}, and POST /products in the bundled sandbox");
        try {
            String query=s.query().entrySet().stream().map(e -> encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));
            URI target=URI.create("http://127.0.0.1:"+port+"/sandbox"+s.path()+(query.isEmpty()?"":"?"+query));
            var builder=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json");
            String body=s.body()==null || s.body().isNull()?"":mapper.writeValueAsString(s.body());
            builder.method(s.method(),body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
            long start=System.nanoTime();
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            String responseBody;
            try(InputStream input=response.body()) { responseBody=new String(input.readNBytes(8192),StandardCharsets.UTF_8); }
            return new Execution(UUID.randomUUID(),plan.id(),index,s.expectedStatus(),response.statusCode(),
                    response.statusCode()==s.expectedStatus(),Duration.ofNanos(System.nanoTime()-start).toMillis(),responseBody,Instant.now());
        } catch(Exception e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"Sandbox request failed or timed out"); }
    }
    public static boolean allowed(Scenario s) {
        return (s.method().equals("GET") && s.path().matches("/products(?:/[A-Za-z0-9_-]+)?"))
                || (s.method().equals("POST") && s.path().equals("/products"));
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
}
