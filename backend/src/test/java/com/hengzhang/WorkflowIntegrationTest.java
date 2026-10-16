package com.hengzhang;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static com.hengzhang.Domain.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1","app.demo-data=false","app.scheduling-enabled=false"})
@AutoConfigureMockMvc
@Import(WorkflowIntegrationTest.FixedClock.class)
class WorkflowIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class FixedClock {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"),ZoneId.of("Asia/Hong_Kong"));
        }
    }
    @Autowired Store db;@Autowired CatalogService catalog;@Autowired ImportService imports;@Autowired ReconciliationService reconciliation;
    @Autowired DifferenceService differences;@Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired Clock clock;
    private static final AtomicInteger sequence=new AtomicInteger();
    private long channel;private LocalDate day;
    private static final String HEADER="external_id,order_no,business_type,amount,status,trade_date\r\n";
    @BeforeEach void setup() {
        day=LocalDate.now(clock).minusDays(3);
        channel=catalog.channel(new ChannelInput("测试账户","TEST_"+sequence.incrementAndGet(),"M001",day,"100.00"),"test");
    }
    private long project(Kind kind,int settlementDays) {
        return catalog.project(null,new ProjectInput("测试 "+kind,kind,channel,kind==Kind.FUND?Source.CLEARING:Source.PLATFORM,kind==Kind.TRANSACTION?Source.CLEARING:Source.SETTLEMENT,MatchMode.ONE_TO_ONE,"0",settlementDays,day.plusDays(settlementDays),true,false,"09:00"),"test");
    }
    private SourceRecord row(String id,String order,long amount,LocalDate date) { return new SourceRecord(id,order,BusinessType.PAYMENT,amount,TradeStatus.SUCCESS,date); }
    private Map<String,Object> ingest(Source source,LocalDate date,SourceRecord... rows) { return imports.ingest(channel,source,date,"test.csv",List.of(rows),"admin"); }
    private Map<String,Object> run(long project,LocalDate date,boolean rerun) { return reconciliation.run(new RunInput(project,date,rerun,"0","0"),"admin"); }
    private Map<String,Object> diff(long run) { return db.one("SELECT * FROM differences WHERE run_id=?",run); }
    @Test void csvSupportsBomQuotedFieldsAndExplicitEmptyBills() {
        var parsed=imports.parse(("\ufeff"+HEADER+"id1,\"order,one\",PAYMENT,0.10,SUCCESS,"+day+"\r\n").getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.getFirst().orderNo()).isEqualTo("order,one");assertThat(parsed.getFirst().amount()).isEqualTo(10);
        assertThat(imports.parse(HEADER.getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThatThrownBy(()->imports.parse("wrong,headers\n".getBytes(StandardCharsets.UTF_8))).isInstanceOf(Problem.class);
    }
    @Test void importsAreIdempotentAndConflictingBatchRollsBack() {
        var data=row("x","order",100,day);
        var first=ingest(Source.PLATFORM,day,data);var again=ingest(Source.PLATFORM,day,data);
        assertThat(again.get("id")).isEqualTo(first.get("id"));assertThat(again.get("duplicate")).isEqualTo(true);
        assertThatThrownBy(()->ingest(Source.PLATFORM,day,row("new","new",20,day),row("x","order",101,day))).isInstanceOf(Problem.class);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM source_records WHERE channel_id=?",Long.class,channel)).isEqualTo(1);
    }
    @Test void duplicateExternalIdsAndWrongDateAreRejectedAtomically() {
        assertThatThrownBy(()->ingest(Source.PLATFORM,day,row("a","1",100,day),row("a","2",100,day))).isInstanceOf(Problem.class);
        assertThatThrownBy(()->ingest(Source.PLATFORM,day,row("a","1",100,day.plusDays(1)))).isInstanceOf(Problem.class);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM import_batches WHERE channel_id=?",Long.class,channel)).isZero();
    }
    @Test void missingBillDoesNotBecomeFalseMatchAndDatesMustBeContinuous() {
        long project=project(Kind.TRANSACTION,0);
        ingest(Source.PLATFORM,day);
        assertThatThrownBy(()->run(project,day,false)).hasMessageContaining("账单未就绪");
        assertThat(db.one("SELECT * FROM projects WHERE id=?",project).get("last_date")).isNull();
        ingest(Source.CLEARING,day);
        var result=run(project,day,false);assertThat(run(project,day,false).get("id")).isEqualTo(result.get("id"));
        assertThatThrownBy(()->run(project,day.plusDays(2),false)).hasMessageContaining("需连续");
    }
    @Test void simultaneousRunsHaveOneEffectiveResultAndOneSuspenseVoucher() throws Exception {
        long project=project(Kind.TRANSACTION,0);ingest(Source.PLATFORM,day,row("p","1",100,day));ingest(Source.CLEARING,day);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return run(project,day,false);});
            var b=pool.submit(()->{start.await();return run(project,day,false);});start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS).get("id")).isEqualTo(b.get(10,TimeUnit.SECONDS).get("id"));
        }
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_runs WHERE project_id=?",Long.class,project)).isEqualTo(1);
    }
    @Test void rerunKeepsHistoryAndReversesOriginalSuspense() {
        long project=project(Kind.TRANSACTION,0);ingest(Source.PLATFORM,day,row("p","1",100,day));ingest(Source.CLEARING,day);
        long original=((Number)run(project,day,false).get("id")).longValue();long difference=db.number(diff(original),"id");
        ingest(Source.CLEARING,day,row("c","1",100,day));
        assertThat(db.one("SELECT * FROM reconciliation_runs WHERE id=?",original).get("stale")).isEqualTo(true);
        assertThatThrownBy(()->run(project,day,false)).hasMessageContaining("已过期");
        assertThatThrownBy(()->run(project,day.plusDays(1),false)).hasMessageContaining("先重对");
        assertThatThrownBy(()->differences.investigate(difference,"admin")).hasMessageContaining("已过期");
        var rerun=run(project,day,true);
        assertThat(rerun.get("differences")).isEqualTo(0);
        assertThat(db.one("SELECT * FROM reconciliation_runs WHERE id=?",original).get("active")).isEqualTo(false);
        assertThat(db.one("SELECT * FROM differences WHERE id=?",difference).get("status")).isEqualTo("SUPERSEDED");
        var vs=db.list("SELECT * FROM vouchers WHERE reference_id=? ORDER BY id",difference);
        assertThat(vs).hasSize(2);assertThat(vs.get(0).get("debit_account")).isEqualTo(vs.get(1).get("credit_account"));
    }
    @Test void importingLateDataAndSubmittingResolutionCannotBothCommit() throws Exception {
        long project=project(Kind.TRANSACTION,0);ingest(Source.PLATFORM,day,row("p","1",100,day));ingest(Source.CLEARING,day);
        long run=((Number)run(project,day,false).get("id")).longValue();long id=db.number(diff(run),"id");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();try{ingest(Source.CLEARING,day,row("c","1",100,day));return true;}catch(Problem e){return false;}});
            var b=pool.submit(()->{start.await();try{differences.submit(id,new ResolutionInput("1",ResolutionMethod.MANUAL_CONFIRM,"并发测试核销申请","回单001"),"admin");return true;}catch(Problem e){return false;}});
            start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)^b.get(10,TimeUnit.SECONDS)).isTrue();
        }
        var result=db.one("SELECT * FROM reconciliation_runs WHERE id=?",run);
        var difference=db.one("SELECT * FROM differences WHERE id=?",id);
        assertThat(Boolean.TRUE.equals(result.get("stale")) && difference.get("status").equals("PENDING_REVIEW")).isFalse();
    }
    @Test void sourceDataCannotBeBackfilledAfterLaterDayHasBeenClosed() {
        long project=project(Kind.TRANSACTION,0);
        for(var date:List.of(day,day.plusDays(1))) {ingest(Source.PLATFORM,date);ingest(Source.CLEARING,date);run(project,date,false);}
        assertThatThrownBy(()->ingest(Source.PLATFORM,day,row("late","late",100,day))).hasMessageContaining("后续任务");
        assertThatThrownBy(()->run(project,day,true)).hasMessageContaining("最后一个对账日");
    }
    @Test void partialWriteOffNeedsIndependentApprovalAndClearsSuspenseExactly() {
        long project=project(Kind.FUND,0);ingest(Source.CLEARING,day,row("c","1",10000,day));ingest(Source.SETTLEMENT,day,row("s","1",9000,day));
        long run=((Number)run(project,day,false).get("id")).longValue();long id=db.number(diff(run),"id");
        differences.submit(id,new ResolutionInput("4.00",ResolutionMethod.CONFIRM_LOSS,"测试第一次部分核销","回单 001"),"admin");
        assertThatThrownBy(()->differences.review(id,new ReviewInput(true,"自己复核"),"admin")).hasMessageContaining("不能复核");
        differences.review(id,new ReviewInput(true,"已核对原始回单"),"reviewer");
        var partial=db.one("SELECT * FROM differences WHERE id=?",id);assertThat(partial.get("status")).isEqualTo("OPEN");assertThat(db.number(partial,"resolved_amount")).isEqualTo(400);
        assertThatThrownBy(()->differences.submit(id,new ResolutionInput("7.00",ResolutionMethod.CONFIRM_LOSS,"超额不通过","回单"),"admin")).isInstanceOf(Problem.class);
        differences.submit(id,new ResolutionInput("6.00",ResolutionMethod.CONFIRM_LOSS,"余款确认损失核销","回单 002"),"admin");
        differences.review(id,new ReviewInput(true,"余款依据已核验"),"reviewer");
        assertThat(db.one("SELECT * FROM differences WHERE id=?",id).get("status")).isEqualTo("RESOLVED");
        assertThatThrownBy(()->differences.review(id,new ReviewInput(true,"重复复核"),"reviewer")).isInstanceOf(Problem.class);
        String suspense="SUSPENSE:P"+project;
        long net=db.jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN debit_account=? THEN amount ELSE -amount END),0) FROM vouchers WHERE debit_account=? OR credit_account=?",Long.class,suspense,suspense,suspense);
        assertThat(net).isZero();assertThatThrownBy(()->run(project,day,true)).hasMessageContaining("处理流程");
    }
    @Test void statusOnlyDifferenceCanCloseWithZeroAmountWithoutFakeVoucher() {
        long project=project(Kind.TRANSACTION,0);ingest(Source.PLATFORM,day,row("p","1",100,day));
        ingest(Source.CLEARING,day,new SourceRecord("c","1",BusinessType.PAYMENT,100,TradeStatus.PENDING,day));
        long run=((Number)run(project,day,false).get("id")).longValue();long id=db.number(diff(run),"id");
        differences.submit(id,new ResolutionInput("0",ResolutionMethod.CORRECTED_EXTERNALLY,"状态已在业务系统修复","工单 001"),"admin");
        differences.review(id,new ReviewInput(true,"状态核实一致"),"reviewer");
        assertThat(db.one("SELECT * FROM differences WHERE id=?",id).get("status")).isEqualTo("RESOLVED");
        assertThat(db.list("SELECT * FROM vouchers WHERE reference_type='SUSPENSE' AND reference_id=?",id)).isEmpty();
    }
    @Test void rejectedRequestReturnsToInvestigationAndDoesNotPostMoney() {
        long project=project(Kind.TRANSACTION,0);ingest(Source.PLATFORM,day,row("p","1",100,day));ingest(Source.CLEARING,day);
        long run=((Number)run(project,day,false).get("id")).longValue();long id=db.number(diff(run),"id");
        differences.submit(id,new ResolutionInput("1",ResolutionMethod.MANUAL_CONFIRM,"待核实人工回单","回单001"),"admin");
        differences.review(id,new ReviewInput(false,"回单依据不完整"),"reviewer");
        var result=db.one("SELECT * FROM differences WHERE id=?",id);assertThat(result.get("status")).isEqualTo("INVESTIGATING");assertThat(db.number(result,"resolved_amount")).isZero();
    }
    @Test void fundSettlementLagUsesClearingDateAndBalanceUsesOpeningAndAdjustments() {
        long fund=project(Kind.FUND,1);ingest(Source.CLEARING,day,row("c","1",100,day));ingest(Source.SETTLEMENT,day.plusDays(1),row("s1","1",100,day.plusDays(1)));
        assertThat(run(fund,day.plusDays(1),false).get("differences")).isEqualTo(0);
        long balance=project(Kind.BALANCE,0);ingest(Source.PLATFORM,day,row("p","1",100,day));ingest(Source.SETTLEMENT,day);
        var result=reconciliation.run(new RunInput(balance,day,false,"0","1.00"),"admin");
        assertThat(result.get("differences")).isEqualTo(0);
        var item=db.one("SELECT * FROM reconciliation_items WHERE run_id=?",result.get("id"));assertThat(db.number(item,"left_amount")).isEqualTo(10100);
    }
    @Test void securityEnforcesLoginCsrfAndRoles() throws Exception {
        mvc.perform(get("/api/channels")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/channels").with(user("admin").roles("OPERATOR")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        var bootstrap=mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn();
        var token=mapper.readTree(bootstrap.getResponse().getContentAsString()).get("csrfToken").asText();
        var result=mvc.perform(post("/api/login").cookie(bootstrap.getResponse().getCookie("XSRF-TOKEN")).header("X-XSRF-TOKEN",token).contentType("application/json").content("{\"username\":\"admin\",\"password\":\"demo123456\"}"))
            .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/channels").session((MockHttpSession)result.getRequest().getSession(false))).andExpect(status().isOk());
        mvc.perform(post("/api/imports/json").with(user("reviewer").roles("REVIEWER")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/differences/1/review").with(user("admin").roles("OPERATOR")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void deliveredSampleFilesProduceDocumentedTransactionFundAndBalanceResults() throws Exception {
        day=LocalDate.of(2026,10,15);
        channel=catalog.channel(new ChannelInput("交付样例验收","SAMPLE_"+sequence.incrementAndGet(),"M-SAMPLE",day,"1000.00"),"test");
        var directory=java.nio.file.Path.of(System.getProperty("basedir"),"..","samples","2026-10-15");
        for(var entry:Map.of(Source.PLATFORM,"platform.csv",Source.CLEARING,"clearing.csv",Source.SETTLEMENT,"settlement.csv").entrySet()) {
            var rows=imports.parse(java.nio.file.Files.readAllBytes(directory.resolve(entry.getValue())));
            imports.ingest(channel,entry.getKey(),day,entry.getValue(),rows,"test");
        }
        var transaction=run(project(Kind.TRANSACTION,0),day,false);
        assertThat(transaction.get("matched")).isEqualTo(4);assertThat(transaction.get("differences")).isEqualTo(3);
        var fund=run(project(Kind.FUND,0),day,false);
        assertThat(fund.get("matched")).isEqualTo(2);assertThat(fund.get("differences")).isEqualTo(1);
        var balance=run(project(Kind.BALANCE,0),day,false);
        var item=db.one("SELECT * FROM reconciliation_items WHERE run_id=?",balance.get("id"));
        assertThat(db.number(item,"left_amount")).isEqualTo(165640);assertThat(db.number(item,"right_amount")).isEqualTo(162240);
    }
}
