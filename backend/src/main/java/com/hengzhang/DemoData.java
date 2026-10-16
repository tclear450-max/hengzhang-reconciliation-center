package com.hengzhang;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static com.hengzhang.Domain.*;

@Component
public class DemoData implements ApplicationRunner {
    private final Store db;private final CatalogService catalog;private final ImportService imports;
    private final ReconciliationService reconciliation;private final Clock clock;private final boolean enabled;
    public DemoData(Store db,CatalogService catalog,ImportService imports,ReconciliationService reconciliation,Clock clock,@Value("${app.demo-data}") boolean enabled) {
        this.db=db;this.catalog=catalog;this.imports=imports;this.reconciliation=reconciliation;this.clock=clock;this.enabled=enabled;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        if(!enabled || db.jdbc.queryForObject("SELECT COUNT(*) FROM channels",Long.class)>0) return;
        LocalDate end=LocalDate.now(clock).minusDays(1),start=end.minusDays(6);
        long account=catalog.channel(new ChannelInput("微信支付 · 电商主账户","WECHAT_MAIN","1900006821",start,"128000.00"),"demo");
        catalog.channel(new ChannelInput("支付宝 · 品牌直营网店","ALIPAY_STORE","2088102668123456",start,"86000.00"),"demo");
        long transaction=catalog.project(null,new ProjectInput("电商订单 · 微信交易核对",Kind.TRANSACTION,account,Source.PLATFORM,Source.CLEARING,MatchMode.ONE_TO_ONE,"0",0,start,true,false,"09:00"),"demo");
        long fund=catalog.project(null,new ProjectInput("微信主账户 · 清结算核对",Kind.FUND,account,Source.CLEARING,Source.SETTLEMENT,MatchMode.GROUPED,"0",0,start,true,false,"09:10"),"demo");
        long balance=catalog.project(null,new ProjectInput("微信主账户 · 余额调节",Kind.BALANCE,account,Source.PLATFORM,Source.SETTLEMENT,MatchMode.GROUPED,"0",0,start,true,false,"09:20"),"demo");
        for(LocalDate date=start;!date.isAfter(end);date=date.plusDays(1)) {
            boolean last=date.equals(end);String stamp=date.toString().replace("-","");
            var platform=new ArrayList<SourceRecord>();var clearing=new ArrayList<SourceRecord>();
            for(int n=1;n<=60;n++) {
                long amount=8900+(n*1379L+date.getDayOfMonth()*997L)%37000;
                String order="HZ"+stamp+String.format("%04d",n);
                if(!(last && n==17)) platform.add(new SourceRecord("P"+stamp+n,order,BusinessType.PAYMENT,amount,TradeStatus.SUCCESS,date));
                if(!(last && n==25)) clearing.add(new SourceRecord("C"+stamp+n,order,BusinessType.PAYMENT,amount+(last&&n==11?700:0),TradeStatus.SUCCESS,date));
            }
            for(int n=1;n<=3;n++) {
                String order="RF"+stamp+n;
                platform.add(new SourceRecord("PR"+stamp+n,order,BusinessType.REFUND,-3490L*n,TradeStatus.SUCCESS,date));
                clearing.add(new SourceRecord("CR"+stamp+n,order,BusinessType.REFUND,-3490L*n,TradeStatus.SUCCESS,date));
            }
            long fee=-MatchingEngine.sum(clearing.stream().filter(r -> r.type()==BusinessType.PAYMENT).toList())*6/1000;
            platform.add(new SourceRecord("PF"+stamp,"FEE"+stamp,BusinessType.FEE,fee,TradeStatus.SUCCESS,date));
            clearing.add(new SourceRecord("CF"+stamp,"FEE"+stamp,BusinessType.FEE,fee,TradeStatus.SUCCESS,date));
            var settlement=new ArrayList<SourceRecord>();
            for(var type:List.of(BusinessType.PAYMENT,BusinessType.REFUND,BusinessType.FEE)) {
                long amount=MatchingEngine.sum(clearing.stream().filter(r -> r.type()==type).toList());
                if(last && type==BusinessType.PAYMENT) amount-=900;
                settlement.add(new SourceRecord("S"+stamp+type,"SET"+stamp+type,type,amount,TradeStatus.SUCCESS,date));
            }
            imports.ingest(account,Source.PLATFORM,date,"platform-"+date+".csv",platform,"demo");
            imports.ingest(account,Source.CLEARING,date,"clearing-"+date+".csv",clearing,"demo");
            imports.ingest(account,Source.SETTLEMENT,date,"settlement-"+date+".csv",settlement,"demo");
            for(long id:List.of(transaction,fund,balance)) reconciliation.run(new RunInput(id,date,false,"0","0"),"demo");
        }
    }
}
