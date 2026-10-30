package dev.rishav.copilot;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;

class AssertionEngineTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode node(String s) throws Exception { return json.readTree(s); }
    private List<AssertionResult> run(String body, boolean truncated, ResponseAssertion... checks) {
        var scenario=new Scenario("case",Category.POSITIVE,"GET","/products",Map.of(),null,200,"rule",List.of("source"),List.of(checks));
        return new AssertionEngine().evaluate(scenario,200,Map.of("Content-Type",List.of("application/json")),body,truncated);
    }
    @Test void statusSuccessDoesNotHideBodyMismatch() throws Exception {
        var result=run("{\"price\":5}",false,new ResponseAssertion(AssertionKind.JSON_EQUALS,"/price",node("10")));
        assertThat(result).extracting(AssertionResult::outcome).containsExactly(CheckOutcome.PASS,CheckOutcome.FAIL);
        assertThat(result.get(1).actual().intValue()).isEqualTo(5);
    }
    @Test void distinguishesMissingFieldsNullAndWrongTypes() throws Exception {
        var results=run("{\"price\":\"5\",\"nullable\":null}",false,
                new ResponseAssertion(AssertionKind.JSON_EXISTS,"/missing",null),
                new ResponseAssertion(AssertionKind.JSON_EXISTS,"/nullable",null),
                new ResponseAssertion(AssertionKind.JSON_TYPE,"/price",node("\"number\"")),
                new ResponseAssertion(AssertionKind.JSON_GTE,"/price",node("0")),
                new ResponseAssertion(AssertionKind.JSON_EQUALS,"/missing",node("null")));
        assertThat(results).extracting(AssertionResult::outcome).containsExactly(CheckOutcome.PASS,CheckOutcome.FAIL,CheckOutcome.PASS,CheckOutcome.FAIL,CheckOutcome.FAIL,CheckOutcome.FAIL);
    }
    @Test void comparesNumbersWithoutCoercingTextAndResolvesEscapedPointers() throws Exception {
        var results=run("{\"a/b\":{\"~x\":[1.0,5]}}",false,
                new ResponseAssertion(AssertionKind.JSON_EQUALS,"/a~1b/~0x/0",node("1")),
                new ResponseAssertion(AssertionKind.JSON_LTE,"/a~1b/~0x/1",node("5")),
                new ResponseAssertion(AssertionKind.JSON_GTE,"/a~1b/~0x/1",node("5")),
                new ResponseAssertion(AssertionKind.JSON_TYPE,"/a~1b/~0x/0",node("\"integer\"")));
        assertThat(results).allMatch(c -> c.outcome()==CheckOutcome.PASS);
    }
    @Test void invalidJsonAndTruncationCannotPassBodyChecksButHeadersStillRun() throws Exception {
        for(var body:List.of("", "broken", "{} {}")) {
            var results=run(body,false,new ResponseAssertion(AssertionKind.JSON_EXISTS,"",null));
            assertThat(results.get(1).outcome()).isEqualTo(CheckOutcome.UNEVALUABLE);
        }
        var results=run("{}",true,new ResponseAssertion(AssertionKind.JSON_EXISTS,"",null),
                new ResponseAssertion(AssertionKind.HEADER_EQUALS,"content-type",node("\"application/json\"")));
        assertThat(results).extracting(AssertionResult::outcome).containsExactly(CheckOutcome.PASS,CheckOutcome.UNEVALUABLE,CheckOutcome.PASS);
    }
    @Test void checksSchemaRulesAndReportsUnsupportedKeywords() throws Exception {
        var schema=node("""
                {"type":"object","required":["items"],"additionalProperties":false,"properties":{"items":{"type":"array","items":{"type":"object","required":["price"],"properties":{"price":{"type":"number","minimum":0,"maximum":10},"kind":{"enum":["a","b"]}}}}}}
                """);
        assertThat(run("{\"items\":[{\"price\":5,\"kind\":\"a\"}]}",false,new ResponseAssertion(AssertionKind.JSON_SCHEMA,"",schema))).allMatch(c -> c.outcome()==CheckOutcome.PASS);
        var failed=run("{\"items\":[{\"price\":-1,\"kind\":\"x\"},{}],\"extra\":true}",false,new ResponseAssertion(AssertionKind.JSON_SCHEMA,"",schema)).get(1);
        assertThat(failed.outcome()).isEqualTo(CheckOutcome.FAIL);
        assertThat(failed.detail()).contains("below minimum","outside enum","required field missing","additional property");
        for(String unsupported:List.of("{\"allOf\":[]}","{\"properties\":{\"a\":{\"format\":\"date\"}}}","{\"type\":[\"string\",\"null\"]}","{\"minimum\":\"0\"}")) {
            assertThat(run("{}",false,new ResponseAssertion(AssertionKind.JSON_SCHEMA,"",node(unsupported))).get(1).outcome()).isEqualTo(CheckOutcome.UNEVALUABLE);
        }
    }
    @Test void rejectsMalformedDefinitionsBeforeExecution() throws Exception {
        for(var check:List.of(new ResponseAssertion(AssertionKind.JSON_TYPE,"/price",node("\"invalid\"")),
                new ResponseAssertion(AssertionKind.JSON_GTE,"/price",node("\"5\"")),
                new ResponseAssertion(AssertionKind.JSON_EXISTS,"$.price",null),
                new ResponseAssertion(AssertionKind.JSON_EXISTS,"/~2",null),
                new ResponseAssertion(AssertionKind.HEADER_EQUALS,"bad\nheader",node("\"x\"")))) {
            assertThatThrownBy(() -> AssertionEngine.validate(List.of(check))).isInstanceOf(ApiException.class);
        }
    }
}
