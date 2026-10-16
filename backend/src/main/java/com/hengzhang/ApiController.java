package com.hengzhang;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.hengzhang.Domain.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final Store db;private final CatalogService catalog;private final ImportService imports;
    private final ReconciliationService reconciliation;private final DifferenceService differences;private final Clock clock;private final boolean schedulingEnabled;
    public ApiController(Store db,CatalogService catalog,ImportService imports,ReconciliationService reconciliation,DifferenceService differences,Clock clock,@Value("${app.scheduling-enabled}") boolean schedulingEnabled) {
        this.db=db;this.catalog=catalog;this.imports=imports;this.reconciliation=reconciliation;this.differences=differences;this.clock=clock;this.schedulingEnabled=schedulingEnabled;
    }
    @GetMapping("/channels") public Object channels() { return db.list("SELECT c.*, (SELECT COUNT(*) FROM projects p WHERE p.channel_id=c.id) AS project_count FROM channels c ORDER BY c.id"); }
    @PostMapping("/channels") public Object channel(@Valid @RequestBody ChannelInput input,Principal actor) { return Map.of("id",catalog.channel(input,actor.getName())); }
    @GetMapping("/projects") public Object projects() { return db.list("SELECT p.*, c.name AS channel_name, c.code AS channel_code, EXISTS(SELECT 1 FROM reconciliation_runs r WHERE r.project_id=p.id AND r.active=TRUE AND r.stale=TRUE) AS requires_rerun FROM projects p JOIN channels c ON p.channel_id=c.id ORDER BY p.id"); }
    @PostMapping("/projects") public Object project(@Valid @RequestBody ProjectInput input,Principal actor) { return Map.of("id",catalog.project(null,input,actor.getName())); }
    @PutMapping("/projects/{id}") public Object updateProject(@PathVariable long id,@Valid @RequestBody ProjectInput input,Principal actor) { return Map.of("id",catalog.project(id,input,actor.getName())); }
    @GetMapping("/imports") public Object batches() { return db.list("SELECT b.*, c.name AS channel_name FROM import_batches b JOIN channels c ON c.id=b.channel_id ORDER BY b.id DESC LIMIT 500"); }
    @PostMapping(value="/imports",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(@RequestParam long channelId,@RequestParam Source source,@RequestParam LocalDate businessDate,@RequestPart MultipartFile file,Principal actor) throws java.io.IOException {
        return imports.ingest(channelId,source,businessDate,Objects.requireNonNullElse(file.getOriginalFilename(),"upload.csv"),imports.parse(file.getBytes()),actor.getName());
    }
    @PostMapping("/imports/json") public Object jsonImport(@Valid @RequestBody JsonImport input,Principal actor) {
        var records=input.rows().stream().map(r -> new SourceRecord(r.externalId().strip(),r.orderNo().strip(),r.businessType(),cents(r.amount()),r.status(),r.tradeDate())).toList();
        return imports.ingest(input.channelId(),input.source(),input.businessDate(),"API-"+input.businessDate()+".json",records,actor.getName());
    }
    @GetMapping("/source-records") public Object sourceRecords(@RequestParam(required=false) Long channelId,
        @RequestParam(required=false) Source source,@RequestParam(required=false) LocalDate date) {
        var sql=new StringBuilder("SELECT r.*, c.name AS channel_name FROM source_records r JOIN channels c ON r.channel_id=c.id WHERE 1=1");var args=new ArrayList<Object>();
        if(channelId!=null) {sql.append(" AND r.channel_id=?");args.add(channelId);}
        if(source!=null) {sql.append(" AND r.source=?");args.add(source.name());}
        if(date!=null) {sql.append(" AND r.trade_date=?");args.add(date);}
        sql.append(" ORDER BY r.id DESC LIMIT 500");return db.list(sql.toString(),args.toArray());
    }
    @GetMapping("/template.csv") public ResponseEntity<byte[]> template() {
        String csv="external_id,order_no,business_type,amount,status,trade_date\r\n";
        return csvResponse(csv,"import-template.csv");
    }
    @GetMapping("/runs") public Object runs() {
        return db.list("SELECT r.*,p.name AS project_name,p.kind,c.name AS channel_name FROM reconciliation_runs r JOIN projects p ON p.id=r.project_id JOIN channels c ON c.id=p.channel_id ORDER BY r.id DESC LIMIT 500");
    }
    @PostMapping("/runs") public Object run(@Valid @RequestBody RunInput input,Principal actor) { return reconciliation.run(input,actor.getName()); }
    @GetMapping("/runs/{id}") public Object detail(@PathVariable long id) {
        var run=db.one("SELECT r.*,p.name AS project_name,p.kind FROM reconciliation_runs r JOIN projects p ON p.id=r.project_id WHERE r.id=?",id);
        return Map.of("run",run,"items",db.list("SELECT * FROM reconciliation_items WHERE run_id=? ORDER BY id LIMIT 10000",id));
    }
    @GetMapping("/differences") public Object differences(@RequestParam(required=false) String status) {
        var sql="SELECT d.*, i.match_key,i.result,i.left_amount,i.right_amount,i.detail, p.name AS project_name,p.kind,r.business_date,c.name AS channel_name FROM differences d JOIN reconciliation_items i ON i.id=d.item_id JOIN reconciliation_runs r ON r.id=d.run_id JOIN projects p ON p.id=r.project_id JOIN channels c ON c.id=p.channel_id WHERE r.active=TRUE AND r.stale=FALSE";
        if(status!=null && !status.isBlank()) return db.list(sql+" AND d.status=? ORDER BY d.id DESC LIMIT 500",status);
        return db.list(sql+" ORDER BY d.id DESC LIMIT 500");
    }
    @GetMapping("/differences/{id}") public Object difference(@PathVariable long id) {
        return Map.of("difference",db.one("SELECT d.*,i.match_key,i.result,i.detail,i.left_amount,i.right_amount FROM differences d JOIN reconciliation_items i ON i.id=d.item_id WHERE d.id=?",id),
            "requests",db.list("SELECT * FROM resolution_requests WHERE difference_id=? ORDER BY id DESC",id));
    }
    @PostMapping("/differences/{id}/investigate") public Object investigate(@PathVariable long id,Principal actor) { differences.investigate(id,actor.getName());return Map.of("ok",true); }
    @PostMapping("/differences/{id}/resolve") public Object resolve(@PathVariable long id,@Valid @RequestBody ResolutionInput input,Principal actor) { return Map.of("id",differences.submit(id,input,actor.getName())); }
    @PostMapping("/differences/{id}/review") public Object review(@PathVariable long id,@Valid @RequestBody ReviewInput input,Principal actor) { differences.review(id,input,actor.getName());return Map.of("ok",true); }
    @GetMapping("/vouchers") public Object vouchers() { return db.list("SELECT * FROM vouchers ORDER BY id DESC LIMIT 500"); }
    @GetMapping("/audit") public Object audit() { return db.list("SELECT * FROM audit_events ORDER BY id DESC LIMIT 500"); }
    @GetMapping("/dashboard") public Object dashboard() {
        var overview=db.one("SELECT (SELECT COUNT(*) FROM source_records) AS record_count,(SELECT COUNT(*) FROM projects WHERE enabled=TRUE) AS project_count,(SELECT COUNT(*) FROM differences d JOIN reconciliation_runs r ON r.id=d.run_id WHERE r.active=TRUE AND r.stale=FALSE AND d.status NOT IN ('RESOLVED','SUPERSEDED')) AS pending_count,(SELECT COALESCE(SUM(ABS(d.delta)-d.resolved_amount),0) FROM differences d JOIN reconciliation_runs r ON r.id=d.run_id WHERE r.active=TRUE AND r.stale=FALSE AND d.status NOT IN ('RESOLVED','SUPERSEDED')) AS pending_amount,(SELECT COUNT(*) FROM reconciliation_runs WHERE active=TRUE AND stale=TRUE) AS stale_count");
        LocalDate today=LocalDate.now(clock);
        var trend=db.list("SELECT r.business_date,SUM(r.total_count) AS total_count,SUM(r.matched_count) AS matched_count,SUM(r.difference_count) AS difference_count FROM reconciliation_runs r WHERE r.active=TRUE AND r.stale=FALSE AND r.business_date>=? GROUP BY r.business_date ORDER BY r.business_date",today.minusDays(7));
        var latest=db.list("SELECT r.*,p.name AS project_name,p.kind,c.name AS channel_name FROM reconciliation_runs r JOIN projects p ON p.id=r.project_id JOIN channels c ON c.id=p.channel_id WHERE r.active=TRUE ORDER BY r.business_date DESC,r.id DESC LIMIT 6");
        var distribution=db.list("SELECT i.result,COUNT(*) AS count FROM reconciliation_items i JOIN reconciliation_runs r ON r.id=i.run_id WHERE r.active=TRUE AND r.stale=FALSE GROUP BY i.result ORDER BY count DESC");
        return Map.of("overview",overview,"trend",trend,"latest",latest,"distribution",distribution,"today",today.toString(),"schedulingEnabled",schedulingEnabled);
    }
    @GetMapping("/export/differences.csv") public ResponseEntity<byte[]> export(Principal actor) {
        var rows=db.list("SELECT d.id,r.business_date,p.name,i.match_key,i.result,i.left_amount,i.right_amount,d.delta,d.resolved_amount,d.status FROM differences d JOIN reconciliation_items i ON i.id=d.item_id JOIN reconciliation_runs r ON r.id=d.run_id JOIN projects p ON p.id=r.project_id WHERE r.active=TRUE AND r.stale=FALSE ORDER BY d.id LIMIT 10000");
        StringBuilder csv=new StringBuilder("\ufeff差异编号,对账日期,项目名称,核对标识,差异类型,A方金额,B方金额,差额,已核销金额,处理状态\r\n");
        for(var row:rows) {
            var fields=new ArrayList<String>();
            for(var entry:row.entrySet()) fields.add(Set.of("left_amount","right_amount","delta","resolved_amount").contains(entry.getKey())?money(((Number)entry.getValue()).longValue()):safeCsv(Objects.toString(entry.getValue(),"")));
            csv.append(String.join(",",fields)).append("\r\n");
        }
        db.audit(actor.getName(),"EXPORT","differences","导出 "+rows.size()+" 条差异");
        return csvResponse(csv.toString(),"differences.csv");
    }
    private String safeCsv(String value) {
        if(value.matches("(?s)^[=+\\-@\\t\\r].*")) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    private ResponseEntity<byte[]> csvResponse(String value,String filename) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(value.getBytes(StandardCharsets.UTF_8));
    }
}
