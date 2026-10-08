package com.chy.mall;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;


public class MockitoVoidMethodTest {
    @Test
    void runThrowsConfiguredException() {
        Runnable task = mock(Runnable.class);
        IllegalStateException failure = new IllegalStateException("模拟执行失败");
        doThrow(failure).when(task).run();
        IllegalStateException actual = assertThrows(IllegalStateException.class, task::run);
        assertSame(failure, actual);
        verify(task, times(1)).run();
    }
    @Test
    void sendThrowsOnlyForSpecifiedUser(){
        MessageSender sender = mock(MessageSender.class);
        IllegalStateException failure = new IllegalStateException("模拟执行失败");
        doThrow(failure).when(sender).send(eq(7L), anyString());
        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> {
            sender.send(7L, "库存提醒");
        });
        assertSame(failure, actual);
        sender.send(8L, "库存提醒");
        System.out.println("继续执行");
        verify(sender).send(eq(7L), eq("库存提醒"));
        verify(sender).send(eq(8L), eq("库存提醒"));
    }
}


interface MessageSender {

    void send(Long userId, String message);
}
