package dev.rishav.copilot;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ReportController {
    private final ResultReports reports;
    private final BatchService batches;
    public ReportController(ResultReports reports,BatchService batches) {this.reports=reports;this.batches=batches;}
    @GetMapping("/executions/{id}/report")
    public ResponseEntity<String> execution(@PathVariable UUID id,@RequestParam(defaultValue="json") String format) {
        var report=reports.execution(id);
        return switch(format) {
            case "json" -> download(reports.json(report),"application/json","execution-"+id+".json");
            case "markdown" -> download(report.markdown(),"text/markdown","execution-"+id+".md");
            case "curl" -> download(report.curl(),"text/plain","execution-"+id+".sh");
            case "junit" -> download(reports.junit(report),"application/xml","execution-"+id+".xml");
            default -> throw ApiException.badRequest("Choose json, markdown, curl or junit");
        };
    }
    @GetMapping("/batches/{id}/export")
    public ResponseEntity<String> batch(@PathVariable UUID id,@RequestParam(defaultValue="json") String format) {
        var report=reports.batch(batches.get(id));
        return switch(format) {
            case "json" -> download(reports.json(report),"application/json","batch-"+id+".json");
            case "junit" -> download(reports.junit(report),"application/xml","batch-"+id+".xml");
            default -> throw ApiException.badRequest("Choose json or junit");
        };
    }
    private ResponseEntity<String> download(String content,String type,String name) {
        return ResponseEntity.ok().header("Content-Type",type+";charset=UTF-8").header("Content-Disposition","attachment; filename=\""+name+"\"")
                .header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(content);
    }
}
