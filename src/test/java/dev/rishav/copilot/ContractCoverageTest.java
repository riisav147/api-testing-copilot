package dev.rishav.copilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;

class ContractCoverageTest {
    private final ContractCoverage coverage=new ContractCoverage();
    private SavedSpecification saved(String content,String docs) {
        return new SavedSpecification(UUID.randomUUID(),3,new Specification(UUID.randomUUID(),"Rules",new OpenApiReader().read(content),Instant.now()),new ImportRequest("Rules",content,docs,null));
    }
    @Test void linksReferencedSchemaRulesToOriginalVersionAndFindsEachMissingRequiredField() throws Exception {
        var saved=saved(OpenApiReaderTest.example(),null);var endpoint=saved.specification().endpoints().get(1);
        var rules=coverage.rules(saved,endpoint,List.of(new Evidence("c","contract",1)));
        assertThat(rules).anyMatch(r -> r.probe().equals("BELOW_MINIMUM")&&r.field().equals("price"));
        assertThat(rules.stream().filter(r -> r.probe().equals("MISSING"))).extracting(ContractCoverage.Rule::field).contains("name","price");
        assertThat(rules).filteredOn(r -> r.field().equals("price")&&r.probe().equals("MINIMUM")).allMatch(r -> r.sourcePointer().equals("/components/schemas/ProductInput/properties/price/minimum"));
        var rule=rules.stream().filter(r -> r.probe().equals("MINIMUM")).findFirst().orElseThrow();
        assertThat(coverage.matches(rule,rule.candidate())).isTrue();
        var c=rule.candidate();var body=(ObjectNode)c.body().deepCopy();body.put("price",7);
        assertThat(coverage.matches(rule,new Scenario(c.title(),c.category(),c.method(),c.path(),c.query(),body,c.expectedStatus(),c.rationale(),c.sourceIds()))).isFalse();
    }
    @Test void addsMaximumLengthAndEnumProbesWithoutInventingUndocumented400() throws Exception {
        String content=OpenApiReaderTest.example().replace("minimum: 0","minimum: 0\n          maximum: 100").replace("minLength: 1","minLength: 1\n          maxLength: 10");
        var saved=saved(content,null);var rules=coverage.rules(saved,saved.specification().endpoints().get(1),List.of(new Evidence("c","contract",1)));
        assertThat(rules).extracting(ContractCoverage.Rule::probe).contains("MAXIMUM","ABOVE_MAXIMUM","MAXLENGTH","TOO_LONG","TOO_SHORT");
        content=content.replace("minLength: 1","enum: [one, two]\n          minLength: 1");
        saved=saved(content,null);rules=coverage.rules(saved,saved.specification().endpoints().get(1),List.of(new Evidence("c","contract",1)));
        assertThat(rules).extracting(ContractCoverage.Rule::probe).contains("ENUM_VALID","ENUM_INVALID");
        var no400=saved(OpenApiReaderTest.example().replace("'400':","'422':"),null);
        assertThat(coverage.rules(no400,no400.specification().endpoints().get(1),List.of(new Evidence("c","contract",1)))).noneMatch(r -> r.expectedStatus()==400);
    }
    @Test void numericConflictsIncludeExactExcerptAndRecordedDecision() throws Exception {
        var saved=saved(OpenApiReaderTest.example(),"Business rule: price > 0. Unrelated price > -1.");var endpoint=saved.specification().endpoints().get(1);
        var rules=coverage.rules(saved,endpoint,List.of(new Evidence("c","contract",1)));
        var conflicts=coverage.conflicts(saved,rules,Map.of());assertThat(conflicts).hasSize(1);
        var conflict=conflicts.getFirst();assertThat(conflict.documentationStatement()).isEqualTo("price > 0");
        assertThat(conflict.contractStatement()).isEqualTo("price >= 0");
        assertThat(coverage.conflicts(saved,rules,Map.of(conflict.id(),new ConflictDecision(conflict.id(),"USE_CONTRACT","Confirmed contract"))).getFirst().decision()).isEqualTo("USE_CONTRACT");
    }
}
