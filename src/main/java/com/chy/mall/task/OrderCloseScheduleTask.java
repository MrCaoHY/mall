package com.chy.mall.task;

import com.chy.mall.service.impl.OrderCloseScanService;
import com.chy.mall.util.LogSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty( name = "mall.order.close-schedule.enabled", havingValue = "true")
public class OrderCloseScheduleTask {
    private final OrderCloseScanService orderCloseScanService;
    @Scheduled(
            fixedDelayString = "${mall.order.close-schedule.delay-ms:10000}",
            initialDelayString = "${mall.order.close-schedule.initial-delay-ms:10000}"
    )
    public void run(){
        try {
            orderCloseScanService.closeExpiredOrdersBatch();
        } catch (RuntimeException e) {
            log.error("event=order_close_schedule_failed", LogSupport.safeStackTrace(e));
        }
    }
}
