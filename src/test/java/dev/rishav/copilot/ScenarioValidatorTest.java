package dev.rishav.copilot;

import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static dev.rishav.copilot.Models.*;

class ScenarioValidatorTest {
    private ScenarioValidator validator;
    private Endpoint endpoint;
    private final List<Evidence> evidence=List.of(new Evidence("source-1","contract",1));
    @BeforeEach void setup() throws Exception {
        validator=new ScenarioValidator(Validation.buildDefaultValidatorFactory().getValidator());
        endpoint=new OpenApiReader().read(OpenApiReaderTest.example()).get(1);
    }
    Scenario scenario(String method,String path,int status,String source) {
        return new Scenario("case",Category.POSITIVE,method,path,Map.of(),null,status,"reason",List.of(source));
    }
    @Test void acceptsGroundedCase() {
        assertThatCode(() -> validator.validate(new Draft(List.of(scenario("POST","/products",201,"source-1"))),endpoint,evidence)).doesNotThrowAnyException();
    }
    @Test void rejectsInventedStatus() {
        assertThatThrownBy(() -> validator.validate(new Draft(List.of(scenario("POST","/products",418,"source-1"))),endpoint,evidence)).hasMessageContaining("status");
    }
    @Test void rejectsInventedCitation() {
        assertThatThrownBy(() -> validator.validate(new Draft(List.of(scenario("POST","/products",201,"other-spec"))),endpoint,evidence)).hasMessageContaining("evidence");
    }
    @Test void rejectsAbsoluteUrlAndWrongMethod() {
        for(var s:List.of(scenario("POST","http://169.254.169.254/",201,"source-1"),scenario("DELETE","/products",201,"source-1")))
            assertThatThrownBy(() -> validator.validate(new Draft(List.of(s)),endpoint,evidence)).hasMessageContaining("method/path");
    }
    @Test void rejectsEmptyAndDuplicateCases() {
        assertThatThrownBy(() -> validator.validate(new Draft(List.of()),endpoint,evidence)).hasMessageContaining("malformed");
        var s=scenario("POST","/products",201,"source-1");
        assertThatThrownBy(() -> validator.validate(new Draft(List.of(s,s)),endpoint,evidence)).hasMessageContaining("Duplicate");
    }
    @Test void demoProducesThreeCategoriesForCreate() {
        var draft=new DemoGenerator().generate(endpoint,evidence);
        validator.validate(draft,endpoint,evidence);
        assertThat(draft.scenarios()).extracting(Scenario::category).containsExactly(Category.POSITIVE,Category.NEGATIVE,Category.EDGE);
        assertThat(new PlanEvaluator().evaluate(draft.scenarios()).categoryCoverage()).isEqualTo(1.0);
    }
    @Test void sandboxRejectsOtherRoutesAndEncodedTraversal() {
        for(String path:List.of("//evil.com","/products/../admin","/products/%2e%2e","/admin","/products?url=http://evil.com"))
            assertThat(SandboxRunner.allowed(scenario("GET",path,200,"source-1"))).isFalse();
    }
}
