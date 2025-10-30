package com.solace.spring.cloud.stream.binder.meter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SolaceMeterAccessorProcessingTimeTest {
    @Test
    public void testRecordMessageProcessingDuration(@Mock SolaceMessageMeterBinder messageMeterBinder) {
        SolaceMeterAccessor solaceMeterAccessor = new SolaceMeterAccessor(messageMeterBinder);
        String bindingName = "test-binding";
        Long messageDurationMilliseconds = 42L;

        solaceMeterAccessor.recordMessageProcessingTimeDuration(bindingName, messageDurationMilliseconds);
        Mockito.verify(messageMeterBinder).recordMessageProcessingTimeDuration(bindingName, messageDurationMilliseconds);
    }
}
