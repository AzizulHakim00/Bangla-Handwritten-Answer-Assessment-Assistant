package com.assessment;

import com.assessment.model.Workspace;
import com.assessment.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceServiceTest {
    @Test void staleVersionCannotApplyMutation() {
        var mongo = mock(MongoTemplate.class); var w = new Workspace(); w.version=2L;
        when(mongo.findById("school",Workspace.class)).thenReturn(w);
        var service = new WorkspaceService(mongo);
        var error = assertThrows(ResponseStatusException.class, () -> service.change(1, x -> fail("Stale mutation ran")));
        assertEquals(409,error.getStatusCode().value()); verify(mongo,never()).save(any());
    }
    @Test void concurrentSaveIsReportedAsConflict() {
        var mongo = mock(MongoTemplate.class); var w = new Workspace(); w.version=2L;
        when(mongo.findById("school",Workspace.class)).thenReturn(w);
        when(mongo.save(w)).thenThrow(new OptimisticLockingFailureException("Concurrent update"));
        var error = assertThrows(ResponseStatusException.class, () -> new WorkspaceService(mongo).change(2, x -> {}));
        assertEquals(409,error.getStatusCode().value());
    }
}
