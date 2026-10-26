package dev.rishav.copilot;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;
import static dev.rishav.copilot.Models.*;

@Component
public class PlanEvaluator {
    public Evaluation evaluate(List<Scenario> scenarios) {
        Map<Category,Long> counts = new EnumMap<>(Category.class);
        counts.putAll(scenarios.stream().collect(Collectors.groupingBy(Scenario::category, Collectors.counting())));
        Arrays.stream(Category.values()).forEach(c -> counts.putIfAbsent(c,0L));
        List<String> warnings=new ArrayList<>();
        counts.forEach((c,n) -> { if (n==0) warnings.add("No "+c+" scenario; review whether the contract supports one."); });
        warnings.add("Citations show provenance, not proof that a scenario is correct. Review assumptions and response assertions.");
        double coverage=counts.values().stream().filter(n -> n>0).count()/3.0;
        double citations=scenarios.isEmpty()?0:scenarios.stream().filter(s -> !s.sourceIds().isEmpty()).count()/(double)scenarios.size();
        return new Evaluation(scenarios.size(),Map.copyOf(counts),coverage,citations,List.copyOf(warnings));
    }
}
