package com.xzwl.cloud.media.application.task;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.media.domain.task.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class DepthTasksTest {
  @Test void admissionBindsOwnerAndExactDurationAndRunsWithoutCommercialDependencies() {
    var f=new MediaWorkflowTest();
    var port=new Fake();
    var depths=new DepthTasks(f.service,port,true);
    assertThrows(IllegalArgumentException.class,()->depths.submit("owner","missing-resolution","source",null));
    var first=depths.submit("owner","key","source","720p");
    assertEquals(first.id(),depths.submit("owner","key","source","720p").id());
    assertThrows(TaskConflictException.class,()->depths.submit("owner","key","source","480p"));
    assertThrows(DefinitiveRejectionException.class,()->depths.submit("other","other","source","480p"));
    port.duration=30.001;
    assertThrows(IllegalArgumentException.class,()->depths.submit("owner","long","source","720p"));
    var longer=depths.submit("owner","long","source","480p");
    assertEquals(30.001,longer.data().request().references().getFirst().durationSeconds());
    assertEquals(TaskOperation.DEPTH,first.data().request().operation());
    for(int i=0;i<5;i++) {
      new MediaTaskWorker(f.store,f.peers,f.peers,f.peers,f.peers,f.clock,f.policy,null,depths).tick();
      f.clock.advance(java.time.Duration.ofSeconds(2));
    }
    assertEquals(TaskStatus.SUCCEEDED,f.service.get("owner",first.id()).status());
    assertEquals("output",f.service.results("owner",first.id()).getFirst().fileId());
    assertTrue(f.peers.events.isEmpty());
    assertTrue(f.peers.charges.isEmpty());
    assertThrows(TaskNotFoundException.class,()->f.service.get("other",first.id()));
  }
  @Test void disabledLocalProcessingCannotAdmit() {
    var f=new MediaWorkflowTest();
    assertThrows(DependencyUnavailableException.class,()->new DepthTasks(f.service,new Fake(),false).submit("owner","key","source","480p"));
  }
  static class Fake implements DepthTasks.Port {
    double duration=30;
    public DepthTasks.Source source(String owner,String file) {
      if(!owner.equals("owner")) throw new DefinitiveRejectionException("SOURCE_REJECTED");
      return new DepthTasks.Source("https://storage.example/source","video/mp4",12,duration,640,360,25.0);
    }
    public void submit(MediaTask task) { assertEquals("owner",task.ownerId()); }
    public String poll(MediaTask task) { return "COMPLETE"; }
    public String archive(MediaTask task) { return "output"; }
  }
}
