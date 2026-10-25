package com.cardpricer.cloud.clubsync;

import com.cardpricer.cloud.trade.CompletedBuyinEvents;
import com.cardpricer.cloud.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClubTransactionEventsControllerTest {
    @Test
    void transportIsHeldWithoutTurningOffRecordingOrOtherSync() {
        ClubSyncAuth auth = mock(ClubSyncAuth.class);
        when(auth.enabled()).thenReturn(true);
        CompletedBuyinEvents events = mock(CompletedBuyinEvents.class);
        var held = new ClubTransactionEventsController(auth, events, false);
        var failure = assertThrows(ApiException.class, () -> held.pending(1, new MockHttpServletRequest()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
        verifyNoInteractions(events);
        verify(auth, never()).accepts(any());
    }
}
