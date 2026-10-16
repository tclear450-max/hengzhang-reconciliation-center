package com.hengzhang;

import com.fasterxml.jackson.dataformat.csv.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengzhang.Domain.*;

@Service
public class ImportService {
    private final Store db;
    public ImportService(Store db) { this.db=db; }
    public List<SourceRecord> parse(byte[] bytes) {
        if(bytes.length>5*1024*1024) throw new Problem("文件不能超过 5 MB");
        try {
            String csv=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(csv.startsWith("\ufeff")) csv=csv.substring(1);
            var schema=CsvSchema.emptySchema().withHeader();
            var reader=new CsvMapper().readerFor(Map.class).with(schema);
            var rows=new ArrayList<SourceRecord>();
            try(var iterator=reader.<Map<String,String>>readValues(csv)) {
                // Reading the header alone is also meaningful: an explicitly empty daily bill.
                Set<String> required=Set.of("external_id","order_no","business_type","amount","status","trade_date");
                while(iterator.hasNextValue()) {
                    var row=iterator.nextValue();
                    if(!row.keySet().equals(required)) throw new Problem("CSV 表头必须与下载的模板完全一致");
                    rows.add(new SourceRecord(text(row.get("external_id")),text(row.get("order_no")),
                        BusinessType.valueOf(row.get("business_type").strip()),cents(row.get("amount").strip()),
                        TradeStatus.valueOf(row.get("status").strip()),LocalDate.parse(row.get("trade_date").strip())));
                    if(rows.size()>10000) throw new Problem("单批最多 10000 行，请拆分文件");
                }
                Set<String> headers=new HashSet<>();
                var actual=(CsvSchema)iterator.getParser().getSchema();
                actual.iterator().forEachRemaining(c -> headers.add(c.getName()));
                if(!headers.equals(required) || actual.size()!=6) throw new Problem("CSV 表头必须与下载的模板完全一致");
            }
            return rows;
        } catch(Problem e) { throw e; }
        catch(Exception e) { throw new Problem("CSV 解析失败：请使用 UTF-8 编码、模板表头、有效日期和枚举值"); }
    }
    private String text(String text) {
        if(text==null || text.isBlank() || text.length()>100) throw new Problem("流水号和订单号为必填项，最多 100 字符");
        return text.strip();
    }
    @Transactional public Map<String,Object> ingest(long channelId,Source source,LocalDate date,String filename,
                                                    List<SourceRecord> records,String actor) {
        var channel=db.one("SELECT * FROM channels WHERE id=? FOR UPDATE",channelId);
        if(date.isBefore(LocalDate.parse(channel.get("opening_date").toString()))) throw new Problem("账单日期早于账户启用日");
        var seen=new HashSet<String>();
        for(var row:records) {
            if(!date.equals(row.date())) throw new Problem("每批只允许一个账单日期，文件日期需与所选日期一致");
            if(!seen.add(row.externalId())) throw new Problem("同一文件含重复流水号："+row.externalId());
            if((row.type()==BusinessType.PAYMENT && row.amount()<0)
                || ((row.type()==BusinessType.REFUND || row.type()==BusinessType.FEE) && row.amount()>0))
                throw new Problem("收款金额需非负；退款和手续费需非正；转账允许正负");
        }
        String hash=hash(records);
        var prior=db.list("SELECT * FROM import_batches WHERE channel_id=? AND source=? AND business_date=? AND content_hash=?",channelId,source.name(),date,hash);
        if(!prior.isEmpty()) return Map.of("id",prior.getFirst().get("id"),"inserted",0,"skipped",records.size(),"duplicate",true);
        var fresh=new ArrayList<SourceRecord>();
        for(var row:records) {
            var exists=db.list("SELECT * FROM source_records WHERE channel_id=? AND source=? AND external_id=?",channelId,source.name(),row.externalId());
            if(exists.isEmpty()) fresh.add(row);
            else {
                var old=exists.getFirst();
                if(!old.get("order_no").equals(row.orderNo()) || !old.get("business_type").equals(row.type().name())
                    || db.number(old,"amount")!=row.amount() || !old.get("trade_status").equals(row.status().name())
                    || !old.get("trade_date").equals(row.date().toString()))
                    throw new Problem("流水号 "+row.externalId()+" 已存在且内容冲突；原始数据不可覆盖，本批未导入");
            }
        }
        if(!fresh.isEmpty()) ensureMutableDate(channelId,source,date);
        long id=db.insert("INSERT INTO import_batches(channel_id,source,business_date,filename,content_hash,inserted_count,skipped_count,actor) VALUES(?,?,?,?,?,?,?,?)",
            channelId,source.name(),date,filename.substring(0,Math.min(200,filename.length())),hash,fresh.size(),records.size()-fresh.size(),actor);
        for(var row:fresh) db.jdbc.update("INSERT INTO source_records(batch_id,channel_id,source,external_id,order_no,business_type,amount,trade_status,trade_date) VALUES(?,?,?,?,?,?,?,?,?)",
            id,channelId,source.name(),row.externalId(),row.orderNo(),row.type().name(),row.amount(),row.status().name(),row.date());
        db.audit(actor,"IMPORT",id,source+" / "+date+"，新增 "+fresh.size()+" 行，跳过 "+(records.size()-fresh.size())+" 行");
        long stale=db.jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_runs r JOIN projects p ON p.id=r.project_id WHERE p.channel_id=? AND r.active=TRUE AND r.stale=TRUE",Long.class,channelId);
        return Map.of("id",id,"inserted",fresh.size(),"skipped",records.size()-fresh.size(),"duplicate",false,"requiresRerun",stale);
    }
    private void ensureMutableDate(long channelId,Source source,LocalDate date) {
        for(var project:db.list("SELECT * FROM projects WHERE channel_id=? AND last_date IS NOT NULL",channelId)) {
            boolean left=project.get("left_source").equals(source.name()),right=project.get("right_source").equals(source.name());
            if(!left && !right) continue;
            LocalDate affected=(left && project.get("kind").equals("FUND"))?date.plusDays(db.number(project,"settlement_days")):date;
            LocalDate last=LocalDate.parse(project.get("last_date").toString());
            if(last.isBefore(affected)) continue;
            if(last.isAfter(affected)) throw new Problem("该账单日期已被后续任务使用，不能补入新流水；请在后续日期通过调整数据处理");
            long handled=db.jdbc.queryForObject("SELECT COUNT(*) FROM differences d JOIN reconciliation_runs r ON r.id=d.run_id WHERE r.project_id=? AND r.business_date=? AND r.active=TRUE AND (d.status<>'OPEN' OR EXISTS(SELECT 1 FROM resolution_requests q WHERE q.difference_id=d.id))",Long.class,project.get("id"),affected);
            if(handled>0) throw new Problem("该日期差异已进入处理流程，不能再补入新流水");
            db.jdbc.update("UPDATE reconciliation_runs SET stale=TRUE WHERE project_id=? AND business_date=? AND active=TRUE",project.get("id"),affected);
        }
    }
    private String hash(List<SourceRecord> records) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(var row:records) for(String field:List.of(row.externalId(),row.orderNo(),row.type().name(),Long.toString(row.amount()),row.status().name(),row.date().toString())) {
                byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
