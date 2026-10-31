package dev.rishav.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;

import java.util.*;
import java.util.stream.Collectors;
import static dev.rishav.copilot.Models.*;

@Component
public class SandboxRunner {
    private final ServletWebServerApplicationContext context;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper;
    private final AssertionEngine assertions=new AssertionEngine();
    public SandboxRunner(ServletWebServerApplicationContext context, ObjectMapper mapper) { this.context=context; this.mapper=mapper; }

    public Execution execute(Plan plan, int index) {
        return executeAt(plan,index,null,Map.of());
    }
    public Execution executeAt(Plan plan,int index,TargetEnvironment environment,Map<String,String> headers) {
        Scenario s=plan.scenarios().get(index);
        if (environment==null && !allowed(s)) throw new ApiException(HttpStatus.FORBIDDEN,"Execution is restricted to GET /products, GET /products/{id}, and POST /products in the bundled sandbox");
        try {
            String query=s.query().entrySet().stream().map(e -> encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));
            if(!s.path().matches("/[A-Za-z0-9_./~-]*")||s.path().contains("..")||s.path().startsWith("//"))throw ApiException.badRequest("Unsafe request path");
            String base=environment==null?"http://127.0.0.1:"+context.getWebServer().getPort()+"/sandbox":environment.baseUrl().replaceAll("/+$","");
            URI target=URI.create(base+s.path()+(query.isEmpty()?"":"?"+query));
            var builder=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json");
            headers.forEach(builder::setHeader);
            String body=s.body()==null || s.body().isNull()?"":mapper.writeValueAsString(s.body());
            builder.method(s.method(),body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
            long start=System.nanoTime();
            var pending=client.sendAsync(builder.build(),info->new BoundedBody(8193));
            HttpResponse<byte[]> response;
            try { response=pending.get(5,java.util.concurrent.TimeUnit.SECONDS); }
            catch(Exception failure) {pending.cancel(true);throw failure;}
            byte[] bytes=response.body();
            boolean truncated=bytes.length>8192;
            String responseBody=new String(bytes,0,Math.min(bytes.length,8192),StandardCharsets.UTF_8);
            var responseHeaders=response.headers().map();
            var checks=assertions.evaluate(s,response.statusCode(),responseHeaders,responseBody,truncated);
            return new Execution(UUID.randomUUID(),plan.id(),index,s.expectedStatus(),response.statusCode(),
                    checks.stream().allMatch(c -> c.outcome()==CheckOutcome.PASS),Duration.ofNanos(System.nanoTime()-start).toMillis(),responseBody,Instant.now(),
                    plan.scenarioRevisions().get(index),s,responseHeaders,truncated,checks,environment==null?"bundled localhost sandbox":environment.baseUrl());
        } catch(Exception e) { throw new ApiException(HttpStatus.BAD_GATEWAY,"Target request failed or timed out; its outcome may be unknown"); }
    }
    public static boolean allowed(Scenario s) {
        return (s.method().equals("GET") && s.path().matches("/products(?:/[A-Za-z0-9_-]+)?"))
                || (s.method().equals("POST") && s.path().equals("/products"));
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
}
