package com.hengzhang;

import java.time.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static com.hengzhang.Domain.*;

@Component("reconciliationTaskRunner")
public class TaskScheduler {
    private final Store db;private final ReconciliationService service;private final Clock clock;private final boolean enabled;
    private final ConcurrentHashMap<Long,String> lastFailures=new ConcurrentHashMap<>();
    public TaskScheduler(Store db,ReconciliationService service,Clock clock,@Value("${app.scheduling-enabled}") boolean enabled) {
        this.db=db;this.service=service;this.clock=clock;this.enabled=enabled;
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000) public void tick() {
        if(!enabled) return;
        var now=LocalDateTime.now(clock);
        for(var project:db.list("SELECT * FROM projects WHERE enabled=TRUE AND auto_run=TRUE ORDER BY id")) {
            long id=db.number(project,"id");
            LocalDate next=LocalDate.parse(project.get(project.get("last_date")==null?"start_date":"last_date").toString());
            if(project.get("last_date")!=null) next=next.plusDays(1);
            if(!next.isBefore(now.toLocalDate()) || now.toLocalTime().isBefore(LocalTime.parse(project.get("schedule_time").toString()))) continue;
            // Manual in-transit adjustments are deliberately left to an operator.
            if(project.get("kind").equals("BALANCE")) continue;
            try { service.run(new RunInput(id,next,false,"0","0"),"scheduler");lastFailures.remove(id); }
            catch(Exception e) {
                String failure=next+" "+(e instanceof Problem?e.getMessage():"执行异常，请检查服务日志");
                if(!failure.equals(lastFailures.put(id,failure))) db.audit("scheduler","SCHEDULE_FAILED",id,failure);
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Scheduled project {} failed: {}",id,e.getMessage());
            }
        }
    }
}
