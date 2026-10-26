package dev.rishav.copilot;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static dev.rishav.copilot.Models.*;

public interface Knowledge {
    void index(Specification specification, String documentation);
    List<Evidence> retrieve(UUID specificationId, Endpoint endpoint);

    static List<String> chunks(Endpoint endpoint, String documentation) {
        List<String> chunks = new ArrayList<>();
        chunks.add("CONTRACT " + endpoint.key()+"\n"+endpoint.contract());
        String docs = documentation == null ? "" : documentation;
        for (int start=0; start<docs.length(); start+=1400)
            chunks.add("DOCUMENTATION\n"+docs.substring(start, Math.min(start+1600, docs.length())));
        return chunks;
    }
}

/** Demo retrieval is exact endpoint lookup, deliberately not advertised as semantic search. */
@Component
@Profile("!ai")
class DemoKnowledge implements Knowledge {
    private final Map<String,List<Evidence>> entries = new ConcurrentHashMap<>();
    public void index(Specification specification, String docs) {
        specification.endpoints().forEach(e -> entries.put(specification.id()+e.key(),
            Knowledge.chunks(e, docs).stream().map(c -> new Evidence(UUID.randomUUID().toString(), c, 1.0)).toList()));
    }
    public List<Evidence> retrieve(UUID specificationId, Endpoint endpoint) {
        return entries.getOrDefault(specificationId+endpoint.key(), List.of()).stream().limit(4).toList();
    }
}
