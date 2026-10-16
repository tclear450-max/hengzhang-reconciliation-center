package com.hengzhang;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengzhang.Domain.*;

@Service
public class CatalogService {
    private final Store db;
    private final Clock clock;
    public CatalogService(Store db, Clock clock) { this.db=db;this.clock=clock; }
    @Transactional public long channel(ChannelInput input,String actor) {
        if(input.openingDate().isAfter(LocalDate.now(clock))) throw new Problem("账户启用日期不能晚于今天");
        long id=db.insert("INSERT INTO channels(name,code,merchant_no,opening_date,opening_balance) VALUES(?,?,?,?,?)",
            input.name().strip(),input.code(),input.merchantNo().strip(),input.openingDate(),cents(input.openingBalance()));
        db.audit(actor,"CREATE_CHANNEL",id,"新建资金账户："+input.name()); return id;
    }
    @Transactional public long project(Long id,ProjectInput input,String actor) {
        var channel=db.one("SELECT * FROM channels WHERE id=? FOR UPDATE",input.channelId());
        if(input.startDate().isBefore(LocalDate.parse(channel.get("opening_date").toString()))) throw new Problem("对账起始日不能早于账户启用日");
        if(input.leftSource()==input.rightSource()) throw new Problem("核对双方必须是不同的数据源");
        if(cents(input.tolerance())<0) throw new Problem("容差不能为负数");
        if(input.kind()!=Kind.FUND && input.settlementDays()!=0) throw new Problem("仅资金对账支持划付延迟天数");
        if(input.kind()==Kind.FUND && input.startDate().minusDays(input.settlementDays()).isBefore(LocalDate.parse(channel.get("opening_date").toString())))
            throw new Problem("资金对账起始日减去划付延迟后不能早于账户启用日");
        if(input.kind()==Kind.BALANCE && input.autoRun()) throw new Problem("余额调节涉及在途确认，请手动执行");
        if(input.kind()==Kind.BALANCE && (input.leftSource()!=Source.PLATFORM || input.rightSource()!=Source.SETTLEMENT))
            throw new Problem("余额调节使用平台账与渠道结算账");
        if(id!=null) {
            var old=db.one("SELECT * FROM projects WHERE id=? FOR UPDATE",id);
            long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_runs WHERE project_id=?",Long.class,id);
            if(count>0 && (!old.get("kind").equals(input.kind().name()) || db.number(old,"channel_id")!=input.channelId()
                || !old.get("left_source").equals(input.leftSource().name()) || !old.get("right_source").equals(input.rightSource().name())
                || !old.get("match_mode").equals(input.matchMode().name()) || db.number(old,"tolerance")!=cents(input.tolerance())
                || db.number(old,"settlement_days")!=input.settlementDays() || !old.get("start_date").equals(input.startDate().toString())))
                throw new Problem("已有执行历史的项目仅可修改名称、启用状态和调度；新规则请新建项目");
            db.jdbc.update("UPDATE projects SET name=?,kind=?,channel_id=?,left_source=?,right_source=?,match_mode=?,tolerance=?,settlement_days=?,start_date=?,enabled=?,auto_run=?,schedule_time=? WHERE id=?",
                input.name(),input.kind().name(),input.channelId(),input.leftSource().name(),input.rightSource().name(),input.matchMode().name(),cents(input.tolerance()),input.settlementDays(),input.startDate(),input.enabled(),input.autoRun(),input.scheduleTime(),id);
        } else id=db.insert("INSERT INTO projects(name,kind,channel_id,left_source,right_source,match_mode,tolerance,settlement_days,start_date,enabled,auto_run,schedule_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                input.name(),input.kind().name(),input.channelId(),input.leftSource().name(),input.rightSource().name(),input.matchMode().name(),cents(input.tolerance()),input.settlementDays(),input.startDate(),input.enabled(),input.autoRun(),input.scheduleTime());
        db.audit(actor,"SAVE_PROJECT",id,"保存对账项目："+input.name()); return id;
    }
}
