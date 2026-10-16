package com.hengzhang;

import java.util.*;
import static com.hengzhang.Domain.*;

/** Pure comparison logic. Inputs are immutable and monetary arithmetic uses integer cents. */
public final class MatchingEngine {
    private MatchingEngine() {}
    public static List<Outcome> transactions(List<SourceRecord> left,List<SourceRecord> right,MatchMode mode,long tolerance) {
        var a=group(left,false);var b=group(right,false);
        var keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());var results=new ArrayList<Outcome>();
        for(String key:keys) {
            var l=a.getOrDefault(key,List.of());var r=b.getOrDefault(key,List.of());
            long la=sum(l),ra=sum(r);String result="MATCHED";
            if(l.isEmpty()) result="RIGHT_ONLY";
            else if(r.isEmpty()) result="LEFT_ONLY";
            else if(mode==MatchMode.ONE_TO_ONE && (l.size()!=1 || r.size()!=1)) result="DUPLICATE";
            else if(!statuses(l).equals(statuses(r)) || statuses(l).size()!=1) result="STATUS_MISMATCH";
            else if(Math.abs(Math.subtractExact(la,ra))>tolerance) result="AMOUNT_MISMATCH";
            results.add(new Outcome(key,result,la,ra,l.size(),r.size(),
                "A "+l.size()+" 笔 / B "+r.size()+" 笔；状态 "+statuses(l)+" / "+statuses(r)));
        }
        return results;
    }
    public static List<Outcome> funds(List<SourceRecord> left,List<SourceRecord> right,long tolerance) {
        var a=group(left.stream().filter(r -> r.status()==TradeStatus.SUCCESS).toList(),true);
        var b=group(right.stream().filter(r -> r.status()==TradeStatus.SUCCESS).toList(),true);
        var keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());var result=new ArrayList<Outcome>();
        for(String key:keys) {
            var l=a.getOrDefault(key,List.of());var r=b.getOrDefault(key,List.of());long la=sum(l),ra=sum(r);
            String status=l.isEmpty()?"RIGHT_ONLY":r.isEmpty()?"LEFT_ONLY":Math.abs(Math.subtractExact(la,ra))<=tolerance?"MATCHED":"AMOUNT_MISMATCH";
            result.add(new Outcome(key,status,la,ra,l.size(),r.size(),"仅汇总 SUCCESS，按费用项核对，退款/手续费为负数"));
        }
        return result;
    }
    private static Map<String,List<SourceRecord>> group(List<SourceRecord> rows,boolean funds) {
        var map=new TreeMap<String,List<SourceRecord>>();
        for(var row:rows) map.computeIfAbsent(funds?row.type().name():row.orderNo()+" / "+row.type(),k -> new ArrayList<>()).add(row);
        return map;
    }
    private static Set<TradeStatus> statuses(List<SourceRecord> rows) {
        var result=new TreeSet<TradeStatus>();for(var row:rows) result.add(row.status());return result;
    }
    static long sum(List<SourceRecord> rows) { long sum=0;for(var row:rows) sum=Math.addExact(sum,row.amount());return sum; }
}
