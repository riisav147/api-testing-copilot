package dev.rishav.copilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import static dev.rishav.copilot.Models.*;

/** Exact origin allowlist, numeric loopback host only: no DNS, redirects, embedded credentials or arbitrary hosts. */
@Component
public class DevelopmentTargets {
    private final Set<String> origins;
    private final Function<String,String> secrets;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    public DevelopmentTargets(@Value("${copilot.allowed-origins:http://127.0.0.1:8081}") String origins,ObjectMapper mapper) { this(origins,mapper,System::getenv); }
    DevelopmentTargets(String origins,ObjectMapper mapper,Function<String,String> secrets) {
        this.origins=new HashSet<>(Arrays.stream(origins.split(",")).map(String::trim).toList());this.mapper=mapper;this.secrets=secrets;
    }
    public URI base(String url) {
        try {
            URI u=URI.create(url);
            String origin=u.getScheme()+"://"+u.getHost()+":"+u.getPort();
            if(!"http".equals(u.getScheme())||!"127.0.0.1".equals(u.getHost())||u.getPort()<1024||u.getPort()>65535
                    ||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!origins.contains(origin)
                    ||!u.getRawPath().matches("(?:/[A-Za-z0-9_-]+)*/*")) throw new IllegalArgumentException();
            return u;
        } catch(Exception e) { throw ApiException.badRequest("Choose an allowlisted http://127.0.0.1:port development origin and a plain path prefix. Configure copilot.allowed-origins on the server."); }
    }
    public void validate(EnvironmentRequest r) {
        base(r.baseUrl());
        if(!r.authorized())throw ApiException.badRequest("Confirm this development target is yours to test");
        if(r.headerRefs().size()>10)throw ApiException.badRequest("Use at most 10 header references");
        r.headerRefs().forEach((k,v)->{header(k);ref(v);});
        if(!r.authType().equals("NONE")) {ref(r.secretRef());if(r.authType().equals("API_KEY"))header(r.authHeader());}
    }
    private void header(String s) {
        if(s==null||!s.matches("[A-Za-z][A-Za-z0-9-]{0,79}")||Set.of("host","connection","content-length","transfer-encoding","upgrade","expect","proxy-authorization","proxy-connection").contains(s.toLowerCase(Locale.ROOT)))
            throw ApiException.badRequest("Unsupported header name");
    }
    private void ref(String s) { if(s==null||!s.matches("[A-Z][A-Z0-9_]{0,99}"))throw ApiException.badRequest("Use a server environment-variable name, not a credential value"); }
    public Map<String,String> headers(TargetEnvironment e) {
        base(e.baseUrl());var result=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);
        e.headerRefs().forEach((k,v)->result.put(k,secret(v)));
        if(e.authType().equals("BEARER"))result.put("Authorization","Bearer "+secret(e.secretRef()));
        if(e.authType().equals("API_KEY"))result.put(e.authHeader(),secret(e.secretRef()));
        return result;
    }
    private String secret(String ref) {
        String value=secrets.apply(ref);
        if(value==null||value.isBlank()||value.length()>4000||value.contains("\r")||value.contains("\n"))throw ApiException.badRequest("A referenced environment variable is missing or invalid. Configure it on the backend and restart.");
        return value;
    }
    public Execution redact(Execution e,TargetEnvironment target) {
        var values=new ArrayList<String>(); target.headerRefs().values().forEach(r->values.add(secret(r)));
        if(!target.authType().equals("NONE"))values.add(secret(target.secretRef()));
        values.sort(Comparator.comparingInt(String::length).reversed());
        JsonNode tree=mapper.valueToTree(e);
        // Structural metadata remains typed; user-controlled request/response content is scrubbed recursively.
        ObjectNode obj=(ObjectNode)tree;
        try { var parsed=mapper.readTree(e.responseBody());if(parsed!=null)obj.put("responseBody",mapper.writeValueAsString(scrub(parsed,values))); } catch(Exception ignored) { }
        for(String field:List.of("requestSnapshot","responseBody","responseHeaders","assertionResults")) obj.set(field,scrub(obj.get(field),values));
        try { return mapper.treeToValue(tree,Execution.class); } catch(Exception failure) { throw new IllegalStateException("Could not sanitize execution"); }
    }
    private JsonNode scrub(JsonNode node,List<String> values) {
        if(node==null)return NullNode.instance;
        if(node.isTextual()) {String s=node.asText();for(String v:values)s=s.replace(v,"[REDACTED]");return TextNode.valueOf(s);}
        if(node.isValueNode()&&values.contains(node.asText()))return TextNode.valueOf("[REDACTED]");
        if(node.isArray()) {var array=mapper.createArrayNode();node.forEach(v->array.add(scrub(v,values)));return array;}
        if(node.isObject()) {var object=mapper.createObjectNode();node.fields().forEachRemaining(f->{String key=f.getKey();for(String v:values)key=key.replace(v,"[REDACTED]");
            if(Set.of("authorization","proxy-authorization","set-cookie","cookie").contains(key.toLowerCase(Locale.ROOT)))object.set(key,f.getValue().isArray()?mapper.createArrayNode().add("[REDACTED]"):TextNode.valueOf("[REDACTED]"));else object.set(key,scrub(f.getValue(),values));});return object;}
        return node;
    }
}
