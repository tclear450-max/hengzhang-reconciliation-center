package com.hengzhang;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengzhang.Domain.*;

@Service
public class DifferenceService {
    private final Store db;private final LedgerService ledger;
    public DifferenceService(Store db,LedgerService ledger) { this.db=db;this.ledger=ledger; }
    @Transactional public void investigate(long id,String actor) {
        var difference=lockDifference(id);
        if(!difference.get("status").equals("OPEN")) throw new Problem("仅待处理差异可开始排查");
        db.jdbc.update("UPDATE differences SET status='INVESTIGATING' WHERE id=?",id);
        db.audit(actor,"INVESTIGATE",id,"开始排查对账差异");
    }
    @Transactional public long submit(long id,ResolutionInput input,String actor) {
        var difference=lockDifference(id);
        if(!java.util.Set.of("OPEN","INVESTIGATING").contains(difference.get("status"))) throw new Problem("差异已关闭或正在等待复核");
        long amount=cents(input.amount());
        long remaining=Math.subtractExact(Math.abs(db.number(difference,"delta")),db.number(difference,"resolved_amount"));
        if(amount<0 || amount>remaining || (remaining>0 && amount==0)) throw new Problem("核销金额需大于 0 且不超过剩余金额 "+money(remaining));
        long requestId=db.insert("INSERT INTO resolution_requests(difference_id,amount,method,note,evidence,requester) VALUES(?,?,?,?,?,?)",
            id,amount,input.method().name(),input.note(),input.evidence(),actor);
        db.jdbc.update("UPDATE differences SET status='PENDING_REVIEW' WHERE id=?",id);
        db.audit(actor,"SUBMIT_RESOLUTION",id,"提交核销 "+money(amount)+"，申请 #"+requestId+"："+input.note());
        return requestId;
    }
    @Transactional public void review(long id,ReviewInput input,String actor) {
        var difference=lockDifference(id);
        if(!difference.get("status").equals("PENDING_REVIEW")) throw new Problem("该差异没有待复核申请，不能重复复核");
        var request=db.one("SELECT * FROM resolution_requests WHERE difference_id=? AND status='PENDING' FOR UPDATE",id);
        if(request.get("requester").equals(actor)) throw new Problem("提交人不能复核自己的核销申请");
        db.jdbc.update("UPDATE resolution_requests SET status=?,reviewer=?,review_note=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?",
            input.approve()?"APPROVED":"REJECTED",actor,input.note(),request.get("id"));
        if(input.approve()) {
            long amount=db.number(request,"amount"),resolved=Math.addExact(db.number(difference,"resolved_amount"),amount);
            String status=resolved==Math.abs(db.number(difference,"delta"))?"RESOLVED":"OPEN";
            db.jdbc.update("UPDATE differences SET status=?,resolved_amount=? WHERE id=?",status,resolved,id);
            var run=db.one("SELECT project_id FROM reconciliation_runs WHERE id=?",difference.get("run_id"));
            ledger.close(db.number(request,"id"),db.number(run,"project_id"),db.number(difference,"delta"),amount,request.get("method").toString(),actor);
        } else db.jdbc.update("UPDATE differences SET status='INVESTIGATING' WHERE id=?",id);
        db.audit(actor,input.approve()?"APPROVE_RESOLUTION":"REJECT_RESOLUTION",id,input.note());
    }
    private Map<String,Object> lockDifference(long id) {
        var owner=db.one("SELECT p.channel_id FROM differences d JOIN reconciliation_runs r ON r.id=d.run_id JOIN projects p ON p.id=r.project_id WHERE d.id=?",id);
        db.one("SELECT id FROM channels WHERE id=? FOR UPDATE",owner.get("channel_id"));
        var difference=db.one("SELECT * FROM differences WHERE id=? FOR UPDATE",id);
        var run=db.one("SELECT active,stale FROM reconciliation_runs WHERE id=?",difference.get("run_id"));
        if(!Boolean.TRUE.equals(run.get("active")) || Boolean.TRUE.equals(run.get("stale"))) throw new Problem("该任务结果已过期，请先重新对账");
        return difference;
    }
}
