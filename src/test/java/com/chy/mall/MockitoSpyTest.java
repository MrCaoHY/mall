package com.chy.mall;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Mockito Spy练习：保留真实行为，并使用doReturn避免配置时调用真实方法。
 */
public class MockitoSpyTest {
    @Test
    void doReturnStubsSpyWithoutReadingEmptyList(){
        List<String> spyList = spy(new ArrayList<String>());
        doReturn("模拟值").when(spyList).get(0);
        assertEquals("模拟值", spyList.get(0));
        assertTrue(spyList.isEmpty());
        verify(spyList).get(0);
    }
}
