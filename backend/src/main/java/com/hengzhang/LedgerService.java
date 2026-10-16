package com.hengzhang;

import org.springframework.stereotype.Service;

@Service
public class LedgerService {
    private final Store db;
    public LedgerService(Store db) { this.db=db; }
    public void suspense(long differenceId,long projectId,long delta,String actor) {
        if(delta==0) return;
        String suspense="SUSPENSE:P"+projectId;
        db.jdbc.update("INSERT INTO vouchers(reference_type,reference_id,debit_account,credit_account,amount,note,actor) VALUES(?,?,?,?,?,?,?)",
            "SUSPENSE",differenceId,delta>0?suspense:"RECON_OFFSET",delta>0?"RECON_OFFSET":suspense,Math.abs(delta),"对账差异挂账 #"+differenceId,actor);
    }
    public void reverse(long differenceId,String actor) {
        var rows=db.list("SELECT * FROM vouchers WHERE reference_type='SUSPENSE' AND reference_id=?",differenceId);
        if(rows.isEmpty()) return;
        var voucher=rows.getFirst();
        db.jdbc.update("INSERT INTO vouchers(reference_type,reference_id,debit_account,credit_account,amount,note,reversal_of,actor) VALUES(?,?,?,?,?,?,?,?)",
            "REVERSAL",differenceId,voucher.get("credit_account"),voucher.get("debit_account"),voucher.get("amount"),"重对冲销旧挂账",voucher.get("id"),actor);
    }
    public void close(long requestId,long projectId,long delta,long amount,String method,String actor) {
        if(amount==0) return;
        String suspense="SUSPENSE:P"+projectId;
        String other=switch(method) {case "CONFIRM_LOSS" -> "CONFIRMED_LOSS";case "CONFIRM_INCOME" -> "CONFIRMED_INCOME";default -> "MANUAL_CLEARING";};
        db.jdbc.update("INSERT INTO vouchers(reference_type,reference_id,debit_account,credit_account,amount,note,actor) VALUES(?,?,?,?,?,?,?)",
            "RESOLUTION",requestId,delta>0?other:suspense,delta>0?suspense:other,amount,"核销复核通过 #"+requestId,actor);
    }
}
