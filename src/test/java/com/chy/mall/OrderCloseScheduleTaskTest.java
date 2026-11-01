package com.chy.mall;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chy.mall.service.impl.OrderCloseScanService;
import com.chy.mall.task.OrderCloseScheduleTask;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 调度验证仅导入任务和模拟Service，不连接数据库或自动处理既有订单。 */
class OrderCloseScheduleTaskTest {
    @Test
    void failureIsSanitizedAndNextInvocationStillDelegates() {
        OrderCloseScanService service = mock(OrderCloseScanService.class);
        when(service.closeExpiredOrdersBatch()).thenThrow(new IllegalStateException("private-schedule-marker"))
                .thenReturn(1);
        OrderCloseScheduleTask task = new OrderCloseScheduleTask(service);
        Logger logger = (Logger) LoggerFactory.getLogger(OrderCloseScheduleTask.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(task::run);
            assertDoesNotThrow(task::run);
            verify(service, times(2)).closeExpiredOrdersBatch();
            assertEquals(1, appender.list.size());
            ILoggingEvent failure = appender.list.getFirst();
            assertEquals("event=order_close_schedule_failed", failure.getFormattedMessage());
            assertNotNull(failure.getThrowableProxy());
            assertFalse(failure.getThrowableProxy().getMessage().contains("private-schedule-marker"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void missingOrDisabledPropertyDoesNotRegisterScheduledTask() {
        OrderCloseScanService service = mock(OrderCloseScanService.class);
        ApplicationContextRunner runner = context(service);
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertNull(ctx.getEnvironment().getProperty("mall.order.close-schedule.enabled"));
            assertTrue(ctx.getBeansOfType(OrderCloseScheduleTask.class).isEmpty());
            assertTrue(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().isEmpty());
        });
        runner.withPropertyValues("mall.order.close-schedule.enabled=false").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertTrue(ctx.getBeansOfType(OrderCloseScheduleTask.class).isEmpty());
            assertTrue(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().isEmpty());
        });
        verifyNoInteractions(service);
    }

    /** 开启真实Spring调度，但业务使用Mock；通过信号等待第二次触发，不靠sleep。 */
    @Test
    void enabledSchedulerInvokesAgainAfterFailure() {
        OrderCloseScanService service = mock(OrderCloseScanService.class);
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        when(service.closeExpiredOrdersBatch()).thenAnswer(invocation -> {
            int current = invocations.incrementAndGet();
            twice.countDown();
            if (current == 1) { throw new IllegalStateException("controlled-schedule-failure"); }
            return 1;
        });
        context(service).withPropertyValues(
                "mall.order.close-schedule.enabled=true",
                "mall.order.close-schedule.delay-ms=20",
                "mall.order.close-schedule.initial-delay-ms=20").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(1, ctx.getBeansOfType(OrderCloseScheduleTask.class).size());
            assertEquals(1, ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
            try {
                assertTrue(twice.await(5, TimeUnit.SECONDS), "5秒内应自动触发两次，包括首次失败后的后续触发");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                fail("调度验证被中断", failure);
            }
            assertTrue(invocations.get() >= 2);
        });
    }

    private ApplicationContextRunner context(OrderCloseScanService service) {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> {
                    // 隔离外部启动参数，缺省开关场景必须真的没有该属性。
                    ctx.getEnvironment().getPropertySources().remove("systemProperties");
                    ctx.getEnvironment().getPropertySources().remove("systemEnvironment");
                }).withUserConfiguration(ScheduleConfig.class)
                .withBean(OrderCloseScanService.class, () -> service);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(OrderCloseScheduleTask.class)
    static class ScheduleConfig { }
}
