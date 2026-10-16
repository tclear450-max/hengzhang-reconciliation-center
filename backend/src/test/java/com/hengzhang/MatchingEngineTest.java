package com.hengzhang;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.hengzhang.Domain.*;

class MatchingEngineTest {
    private SourceRecord row(String id,String order,BusinessType type,long amount,TradeStatus status) {
        return new SourceRecord(id,order,type,amount,status,LocalDate.of(2026,10,1));
    }
    private SourceRecord payment(String id,String order,long amount) { return row(id,order,BusinessType.PAYMENT,amount,TradeStatus.SUCCESS); }
    @Test void moneyUsesExactCents() { assertThat(cents("0.10")+cents("0.20")).isEqualTo(30);assertThat(money(-103)).isEqualTo("-1.03"); }
    @ParameterizedTest @ValueSource(strings={"1.001","1e3","NaN"," 1.00","+1","9999999999999",""})
    void invalidMoneyCannotBeRoundedOrInterpreted(String value) { assertThatThrownBy(()->cents(value)).isInstanceOf(Problem.class); }
    @Test void identifiesBothOneSidedRecordsAndAmountDifference() {
        var results=MatchingEngine.transactions(List.of(payment("p1","1",100),payment("p2","2",200)),List.of(payment("c2","2",201),payment("c3","3",300)),MatchMode.ONE_TO_ONE,0);
        assertThat(results).extracting(Outcome::result).containsExactly("LEFT_ONLY","AMOUNT_MISMATCH","RIGHT_ONLY");
    }
    @Test void duplicateOrdersCannotBeSilentlyOverwritten() {
        var left=List.of(payment("a","1",40),payment("b","1",60));var right=List.of(payment("c","1",100));
        assertThat(MatchingEngine.transactions(left,right,MatchMode.ONE_TO_ONE,0).getFirst().result()).isEqualTo("DUPLICATE");
        assertThat(MatchingEngine.transactions(left,right,MatchMode.GROUPED,0).getFirst().result()).isEqualTo("MATCHED");
    }
    @Test void paymentAndRefundWithSameOrderRemainSeparate() {
        var rows=List.of(payment("p","1",100),row("r","1",BusinessType.REFUND,-100,TradeStatus.SUCCESS));
        assertThat(MatchingEngine.transactions(rows,rows,MatchMode.ONE_TO_ONE,0)).hasSize(2).allMatch(o->o.result().equals("MATCHED"));
    }
    @Test void statusMismatchIsDetectedEvenWhenMoneyMatches() {
        var left=List.of(payment("p","1",100));var right=List.of(row("c","1",BusinessType.PAYMENT,100,TradeStatus.PENDING));
        assertThat(MatchingEngine.transactions(left,right,MatchMode.ONE_TO_ONE,0).getFirst().result()).isEqualTo("STATUS_MISMATCH");
    }
    @Test void mixedGroupStatusesAreNotConsideredMatched() {
        var rows=List.of(payment("p","1",40),row("c","1",BusinessType.PAYMENT,60,TradeStatus.PENDING));
        assertThat(MatchingEngine.transactions(rows,rows,MatchMode.GROUPED,0).getFirst().result()).isEqualTo("STATUS_MISMATCH");
    }
    @Test void toleranceIsInclusiveAndDoesNotHideMissingSides() {
        var left=List.of(payment("p","1",100));var right=List.of(payment("c","1",101));
        assertThat(MatchingEngine.transactions(left,right,MatchMode.ONE_TO_ONE,1).getFirst().result()).isEqualTo("MATCHED");
        assertThat(MatchingEngine.transactions(left,List.of(),MatchMode.ONE_TO_ONE,100).getFirst().result()).isEqualTo("LEFT_ONLY");
    }
    @Test void fundsUseSignedSuccessfulMovementsAndKeepFeeCategoriesSeparate() {
        var left=List.of(payment("1","1",100),payment("2","2",200),row("3","3",BusinessType.FEE,-10,TradeStatus.SUCCESS),row("4","4",BusinessType.PAYMENT,999,TradeStatus.FAILED));
        var right=List.of(payment("5","5",300),row("6","6",BusinessType.FEE,-10,TradeStatus.SUCCESS));
        assertThat(MatchingEngine.funds(left,right,0)).hasSize(2).allMatch(o->o.result().equals("MATCHED"));
    }
}
