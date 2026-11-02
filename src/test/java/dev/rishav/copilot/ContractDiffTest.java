package dev.rishav.copilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;

class ContractDiffTest {
    private final UUID project=UUID.randomUUID();
    private final ObjectMapper mapper=new ObjectMapper();
    private SavedSpecification saved(String text,int version,String docs) {
        return new SavedSpecification(project,version,new Specification(UUID.randomUUID(),"Comparison",new OpenApiReader().read(text),Instant.now()),new ImportRequest("Comparison",text,docs,project));
    }
    private Plan plan(SavedSpecification s,String operation) {
        var endpoint=s.specification().endpoints().stream().filter(e->e.key().equals(operation)).findFirst().orElseThrow();var evidence=List.of(new Evidence("old","old contract",1));var draft=new DemoGenerator().generate(endpoint,evidence);
        return new Plan(UUID.randomUUID(),s.specification().id(),operation,"demo",draft.scenarios(),Collections.nCopies(draft.scenarios().size(),1),evidence,new PlanEvaluator().evaluate(draft.scenarios()),Instant.now());
    }
    @Test void minimumChangeLinksOldBoundaryToNewInputAndOriginalComponentPointer() throws Exception {
        var a=saved(OpenApiReaderTest.example(),1,null);var b=saved(OpenApiReaderTest.example().replace("minimum: 0","minimum: 1"),2,null);var p=plan(a,"POST /products");
        var report=new ContractDiff().compare(a,b,p);
        assertThat(report.changes()).filteredOn(c->c.kind().equals("MINIMUM")).hasSize(2).allSatisfy(c->{assertThat(c.before().asInt()).isZero();assertThat(c.after().asInt()).isEqualTo(1);assertThat(c.certainty()).isEqualTo("KNOWN");});
        var impact=report.impacts().get(2);assertThat(impact.state()).isEqualTo("AFFECTED");assertThat(impact.ruleIds()).anyMatch(id->id.endsWith(":MINIMUM"));
        var suggestion=impact.suggestions().getFirst();assertThat(suggestion.request().body().path("price").asInt()).isEqualTo(1);assertThat(suggestion.request().expectedStatus()).isEqualTo(201);assertThat(suggestion.request().assertions()).isEqualTo(p.scenarios().get(2).assertions());
        assertThat(suggestion.sourcePointer()).isEqualTo("/components/schemas/ProductInput/properties/price/minimum");
        assertThat(report.changes()).anySatisfy(c->assertThat(c.beforeSource()).isEqualTo("/components/schemas/ProductInput/properties/price/minimum"));
        assertThat(report.impacts().getFirst().state()).isEqualTo("REVIEW");
    }
    @Test void unsupportedPatternAndDocumentationChangesStayUnknownWithoutProposals() throws Exception {
        String original=OpenApiReaderTest.example();var a=saved(original,1,"old documentation");
        var b=saved(original.replace("minLength: 1","minLength: 1\n          pattern: '[A-Z]+'"),2,"new documentation");
        var report=new ContractDiff().compare(a,b,plan(a,"POST /products"));
        assertThat(report.impacts()).allSatisfy(i->{assertThat(i.state()).isEqualTo("UNKNOWN");assertThat(i.suggestions()).isEmpty();});
        assertThat(report.changes()).anyMatch(c->c.kind().equals("DOCUMENTATION")).anyMatch(c->c.pointer().endsWith("/pattern"));
    }
    @Test void requiredEnumsResponsesAndOperationRemovalAreReported() throws Exception {
        String text=OpenApiReaderTest.example();var a=saved(text,1,null);
        var b=saved(text.replace("required: [name, price]","required: [price]").replace("minLength: 1","enum: [small, medium]\n          minLength: 1").replace("'201':","'202':"),2,null);
        var report=new ContractDiff().compare(a,b,plan(a,"POST /products"));
        assertThat(report.changes()).extracting(ContractDiff.Change::kind).contains("REQUIRED_FIELDS","ENUM","RESPONSE_STATUS");
        assertThat(report.impacts().get(1).ruleIds()).anyMatch(id->id.contains("/required/"));
        String only="{\"openapi\":\"3.0.3\",\"paths\":{\"/new\":{\"get\":{\"responses\":{\"200\":{}}}}}}";
        var removed=new ContractDiff().compare(a,saved(only,3,null),plan(a,"POST /products"));
        assertThat(removed.changes()).extracting(ContractDiff.Change::kind).contains("OPERATION_ADDED","OPERATION_REMOVED");assertThat(removed.impacts()).allMatch(i->i.state().equals("AFFECTED"));
    }
    @Test void requiredOrderAndNumericFormattingDoNotImplyNewSemantics() throws Exception {
        var a=saved(OpenApiReaderTest.example(),1,null);var b=saved(OpenApiReaderTest.example().replace("required: [name, price]","required: [price, name]").replace("minimum: 0","minimum: 0.0"),2,null);
        var report=new ContractDiff().compare(a,b,plan(a,"POST /products"));assertThat(report.changes()).isEmpty();assertThat(report.impacts()).allMatch(i->i.state().equals("NO_DETECTED_CHANGE"));
    }
    @Test void responseSchemaChangesRequireReviewButDoNotRewriteChecks() throws Exception {
        var a=saved(OpenApiReaderTest.example(),1,null);var b=saved(OpenApiReaderTest.example().replace("type: integer","type: string"),2,null);
        var report=new ContractDiff().compare(a,b,plan(a,"POST /products"));assertThat(report.changes()).anyMatch(c->c.pointer().contains("/responses/")&&c.kind().equals("TYPE"));assertThat(report.impacts()).allMatch(i->i.state().equals("REVIEW"));assertThat(report.impacts()).allSatisfy(i->assertThat(i.suggestions()).isEmpty());
    }
    @Test void invalidVersionPairsAndCrossProjectPlansAreRefused() throws Exception {
        var a=saved(OpenApiReaderTest.example(),1,null);var b=saved(OpenApiReaderTest.example(),2,null);
        assertThatThrownBy(()->new ContractDiff().compare(b,a,null)).hasMessageContaining("older");
        assertThatThrownBy(()->new ContractDiff().compare(a,b,plan(b,"POST /products"))).hasMessageContaining("older specification");
        var other=new SavedSpecification(UUID.randomUUID(),2,b.specification(),b.source());assertThatThrownBy(()->new ContractDiff().compare(a,other,null)).hasMessageContaining("same project");
    }
    @Test void comparisonBudgetMarksEveryCaseUnknown() throws Exception {
        var root=mapper.createObjectNode();root.put("openapi","3.0.3");var paths=root.putObject("paths");
        for(int i=0;i<30;i++){var op=paths.putObject("/op"+i).putObject("post");op.putObject("responses").putObject("200");var properties=op.putObject("requestBody").putObject("content").putObject("application/json").putObject("schema");properties.put("type","object");var fields=properties.putObject("properties");for(int j=0;j<10;j++)fields.putObject("f"+j).put("type","number").put("minimum",0);}
        var a=saved(root.toString(),1,null);String changed=root.toString().replace("\"minimum\":0","\"minimum\":1");var b=saved(changed,2,null);
        var report=new ContractDiff().compare(a,b,plan(a,"POST /op0"));assertThat(report.limited()).isTrue();assertThat(report.changes()).hasSize(256);assertThat(report.impacts()).allMatch(i->i.state().equals("UNKNOWN"));
    }
}
