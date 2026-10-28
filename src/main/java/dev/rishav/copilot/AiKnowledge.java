package dev.rishav.copilot;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.*;
import static dev.rishav.copilot.Models.*;

@Component
@Profile("ai")
public class AiKnowledge implements Knowledge {
    private final VectorStore store;
    public AiKnowledge(VectorStore store) { this.store=store; }
    public void index(Specification specification,String docs) {
        List<Document> documents=new ArrayList<>();
        specification.endpoints().forEach(e -> Knowledge.chunks(e,docs).forEach(chunk -> {
            String id=UUID.randomUUID().toString();
            documents.add(new Document(id,chunk,Map.of("specId",specification.id().toString(),"endpointKey",e.key())));
        }));
        try {
            store.add(documents);
        } catch (Exception failure) {
            throw ProviderErrors.translate(failure, "Evidence indexing failed. The specification was not imported.");
        }
    }
    public List<Evidence> retrieve(UUID specificationId,Endpoint e) {
        var f=new FilterExpressionBuilder();
        var filter=f.and(f.eq("specId",specificationId.toString()),f.eq("endpointKey",e.key())).build();
        List<Document> found;
        try {
            found=store.similaritySearch(SearchRequest.builder().query(e.key()+" "+e.summary()+" validation boundaries required fields")
                    .topK(4).similarityThreshold(0.0).filterExpression(filter).build());
        } catch (Exception failure) {
            throw ProviderErrors.translate(failure, "Evidence retrieval failed. No test plan was generated.");
        }
        List<Evidence> result=new ArrayList<>();
        // Always include the selected contract as an anchor even when semantic ranking favours prose.
        result.add(new Evidence("contract:"+specificationId+":"+e.key(),"CONTRACT "+e.key()+"\n"+e.contract(),1.0));
        if (found!=null) found.forEach(d -> result.add(new Evidence(d.getId(),d.getText(),d.getScore()==null?0:d.getScore())));
        return List.copyOf(result);
    }
}
