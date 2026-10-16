package com.hengzhang;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengzhang.Domain.*;

@Service
public class ReconciliationService {
    private final Store db; private final LedgerService ledger; private final Clock clock; private final ObjectMapper mapper;
    public ReconciliationService(Store db,LedgerService ledger,Clock clock,ObjectMapper mapper) {
        this.db=db;this.ledger=ledger;this.clock=clock;this.mapper=mapper;
    }
    @Transactional public Map<String,Object> run(RunInput input,String actor) {
        long started=System.nanoTime();
        var first=db.one("SELECT * FROM projects WHERE id=?",input.projectId());
        var channel=db.one("SELECT * FROM channels WHERE id=? FOR UPDATE",first.get("channel_id"));
        var project=db.one("SELECT * FROM projects WHERE id=? FOR UPDATE",input.projectId());
        if(db.number(project,"channel_id")!=db.number(channel,"id")) throw new Problem("项目账户刚刚被修改，请重新发起任务");
        if(!Boolean.TRUE.equals(project.get("enabled"))) throw new Problem("项目已停用");
        LocalDate date=input.businessDate();
        if(date.isAfter(LocalDate.now(clock))) throw new Problem("不能执行未来日期的对账");
        var old=db.list("SELECT * FROM reconciliation_runs WHERE project_id=? AND business_date=? AND active=TRUE",input.projectId(),date);
        if(!old.isEmpty() && !input.rerun()) {
            if(Boolean.TRUE.equals(old.getFirst().get("stale"))) throw new Problem("账单新增了流水，原结果已过期，请勾选重新核对最后一个对账日");
            return Map.of("id",old.getFirst().get("id"),"duplicate",true);
        }
        if(old.isEmpty() && db.jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_runs WHERE project_id=? AND active=TRUE AND stale=TRUE",Long.class,input.projectId())>0)
            throw new Problem("前一日账单已更新，请先重对最后一个对账日，再执行后续日期");
        LocalDate next=project.get("last_date")==null?LocalDate.parse(project.get("start_date").toString()):LocalDate.parse(project.get("last_date").toString()).plusDays(1);
        if(old.isEmpty() && !date.equals(next)) throw new Problem("对账日期需连续，下一个可执行日期为 "+next);
        if(input.rerun() && old.isEmpty()) throw new Problem("该日期没有可重对的任务，请使用普通执行");
        if(!old.isEmpty() && !date.toString().equals(project.get("last_date"))) throw new Problem("仅允许重对最后一个对账日，避免影响后续余额");
        Kind kind=Kind.valueOf(project.get("kind").toString());
        Source left=Source.valueOf(project.get("left_source").toString()),right=Source.valueOf(project.get("right_source").toString());
        LocalDate leftDate=kind==Kind.FUND?date.minusDays(db.number(project,"settlement_days")):date;
        long channelId=db.number(project,"channel_id"), tolerance=db.number(project,"tolerance");
        LocalDate opening=LocalDate.parse(channel.get("opening_date").toString());
        if(kind==Kind.BALANCE) {
            if(java.time.temporal.ChronoUnit.DAYS.between(opening,date)>3660) throw new Problem("余额累计范围最多 3660 天，请建立新的期初账户");
            for(LocalDate day=opening;!day.isAfter(date);day=day.plusDays(1)) { requireBill(channelId,left,day);requireBill(channelId,right,day); }
        } else { requireBill(channelId,left,leftDate);requireBill(channelId,right,date); }
        long adjustmentLeft=cents(input.leftAdjustment()==null?"0":input.leftAdjustment());
        long adjustmentRight=cents(input.rightAdjustment()==null?"0":input.rightAdjustment());
        if(kind!=Kind.BALANCE && (adjustmentLeft!=0 || adjustmentRight!=0)) throw new Problem("仅余额调节对账支持在途调整");
        List<Outcome> outcomes;
        if(kind==Kind.BALANCE) {
            long book=Math.addExact(db.number(channel,"opening_balance"),movement(channelId,left,opening,date));
            long bank=Math.addExact(db.number(channel,"opening_balance"),movement(channelId,right,opening,date));
            long a=Math.addExact(book,adjustmentLeft),b=Math.addExact(bank,adjustmentRight);
            outcomes=List.of(new Outcome("BALANCE",Math.abs(Math.subtractExact(a,b))<=tolerance?"MATCHED":"AMOUNT_MISMATCH",a,b,1,1,
                "平台余额 "+money(book)+" + 在途调整 "+money(adjustmentLeft)+"；渠道余额 "+money(bank)+" + 在途调整 "+money(adjustmentRight)));
        } else {
            var a=records(channelId,left,leftDate);var b=records(channelId,right,date);
            outcomes=kind==Kind.FUND?MatchingEngine.funds(a,b,tolerance):MatchingEngine.transactions(a,b,MatchMode.valueOf(project.get("match_mode").toString()),tolerance);
        }
        int attempt=1;
        if(!old.isEmpty()) {
            long oldId=db.number(old.getFirst(),"id");
            var differences=db.list("SELECT * FROM differences WHERE run_id=? ORDER BY id FOR UPDATE",oldId);
            for(var difference:differences) {
                long processed=db.jdbc.queryForObject("SELECT COUNT(*) FROM resolution_requests WHERE difference_id=?",Long.class,difference.get("id"));
                if(!difference.get("status").equals("OPEN") || processed>0) throw new Problem("差异已进入处理流程，不能重对；请保留处理记录并在后续日期调整");
            }
            for(var difference:differences) { ledger.reverse(db.number(difference,"id"),actor); }
            db.jdbc.update("UPDATE differences SET status='SUPERSEDED' WHERE run_id=?",oldId);
            db.jdbc.update("UPDATE reconciliation_runs SET active=FALSE WHERE id=?",oldId);
            attempt=(int)db.number(old.getFirst(),"attempt")+1;
        }
        long totalLeft=0,totalRight=0;int matched=0;
        for(var outcome:outcomes) { totalLeft=Math.addExact(totalLeft,outcome.leftAmount());totalRight=Math.addExact(totalRight,outcome.rightAmount());if(outcome.result().equals("MATCHED")) matched++; }
        String snapshot;
        try { snapshot=mapper.writeValueAsString(Map.of("project",project,"leftAdjustment",money(adjustmentLeft),"rightAdjustment",money(adjustmentRight),"leftDate",leftDate.toString())); }
        catch(Exception e) { throw new IllegalStateException(e); }
        long runId=db.insert("INSERT INTO reconciliation_runs(project_id,business_date,attempt,total_count,matched_count,difference_count,left_amount,right_amount,duration_ms,rule_snapshot,actor) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            input.projectId(),date,attempt,outcomes.size(),matched,outcomes.size()-matched,totalLeft,totalRight,(System.nanoTime()-started)/1_000_000,snapshot,actor);
        for(var outcome:outcomes) {
            long itemId=db.insert("INSERT INTO reconciliation_items(run_id,match_key,result,left_amount,right_amount,left_count,right_count,detail) VALUES(?,?,?,?,?,?,?,?)",
                runId,outcome.key(),outcome.result(),outcome.leftAmount(),outcome.rightAmount(),outcome.leftCount(),outcome.rightCount(),outcome.detail());
            if(!outcome.result().equals("MATCHED")) {
                long delta=Math.subtractExact(outcome.leftAmount(),outcome.rightAmount());
                long differenceId=db.insert("INSERT INTO differences(run_id,item_id,delta) VALUES(?,?,?)",runId,itemId,delta);
                ledger.suspense(differenceId,input.projectId(),delta,actor);
            }
        }
        db.jdbc.update("UPDATE projects SET last_date=? WHERE id=?",date,input.projectId());
        db.audit(actor,input.rerun()?"RERUN":"RUN",runId,project.get("name")+" / "+date+"："+matched+" 对平，"+(outcomes.size()-matched)+" 差异");
        return Map.of("id",runId,"duplicate",false,"matched",matched,"differences",outcomes.size()-matched);
    }
    private void requireBill(long channel,Source source,LocalDate date) {
        long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM import_batches WHERE channel_id=? AND source=? AND business_date=?",Long.class,channel,source.name(),date);
        if(count==0) throw new Problem("账单未就绪："+source+" / "+date+"；零交易日也需上传仅含表头的空账单");
    }
    private long movement(long channel,Source source,LocalDate from,LocalDate to) {
        BigDecimal amount=db.jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM source_records WHERE channel_id=? AND source=? AND trade_date BETWEEN ? AND ? AND trade_status='SUCCESS'",BigDecimal.class,channel,source.name(),from,to);
        return Objects.requireNonNull(amount).longValueExact();
    }
    private List<SourceRecord> records(long channel,Source source,LocalDate date) {
        return db.list("SELECT * FROM source_records WHERE channel_id=? AND source=? AND trade_date=? ORDER BY id",channel,source.name(),date).stream()
            .map(r -> new SourceRecord(r.get("external_id").toString(),r.get("order_no").toString(),BusinessType.valueOf(r.get("business_type").toString()),db.number(r,"amount"),TradeStatus.valueOf(r.get("trade_status").toString()),LocalDate.parse(r.get("trade_date").toString()))).toList();
    }
}
