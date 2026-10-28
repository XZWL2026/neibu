package com.xzwl.cloud.media;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.interfaces.http.*;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.xzwl.cloud.media.domain.task.MediaReference.Kind;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PortraitControllerTest {
  @Test void realSessionFilterRequiresSessionAndCsrfAndKeepsOwnerScopedContract() throws Exception {
    var reviews=mock(PortraitReviews.class); var sessions=mock(MediaSessionPort.class);
    when(sessions.verify("XZWL_SESSION=valid",null,true)).thenThrow(new MediaSessionPort.Denied(403));
    when(sessions.verify("XZWL_SESSION=valid","csrf",true)).thenReturn("owner");
    when(sessions.verify("XZWL_SESSION=valid",null,false)).thenReturn("owner");
    var portrait=new PortraitReviews.Portrait("portrait-1","file-1","video","PROCESSING",null);
    when(reviews.submit("owner","key-1","file-1","video",Kind.IMAGE)).thenReturn(portrait);
    when(reviews.get("owner","portrait-1")).thenReturn(portrait);
    when(reviews.list("owner",20,null)).thenReturn(List.of(portrait));
    var mvc=MockMvcBuilders.standaloneSetup(new PortraitController(reviews)).setControllerAdvice(new MediaTaskErrors())
        .addFilters(new MediaSessionFilter(sessions)).build();
    String body="{\"fileId\":\"file-1\",\"model\":\"video\"}";
    mvc.perform(post("/api/v1/media/portraits").header("Idempotency-Key","key-1").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/media/portraits").cookie(new Cookie("XZWL_SESSION","valid")).header("Idempotency-Key","key-1")
        .contentType("application/json").content(body)).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/media/portraits").cookie(new Cookie("XZWL_SESSION","valid")).header("X-CSRF-TOKEN","csrf")
        .header("Idempotency-Key","key-1").header("X-User-Id","forged").contentType("application/json").content(body))
        .andExpect(status().isAccepted()).andExpect(jsonPath("id").value("portrait-1")).andExpect(jsonPath("status").value("PROCESSING"))
        .andExpect(jsonPath("kind").value("IMAGE")).andExpect(jsonPath("assetId").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
    mvc.perform(get("/api/v1/media/portraits").cookie(new Cookie("XZWL_SESSION","valid")))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].fileId").value("file-1"));
    mvc.perform(get("/api/v1/media/portraits/portrait-1").cookie(new Cookie("XZWL_SESSION","valid")))
        .andExpect(status().isOk()).andExpect(jsonPath("id").value("portrait-1"));
    verify(reviews,times(1)).submit("owner","key-1","file-1","video",Kind.IMAGE);
  }
  @ParameterizedTest @EnumSource(Kind.class)
  void submissionAndResponseRetainKindAndTrustedDuration(Kind kind) throws Exception {
    var reviews=mock(PortraitReviews.class);
    Double duration=kind==Kind.IMAGE?null:3.25;
    var portrait=new PortraitReviews.Portrait("portrait-1","file-1","video","PROCESSING",null,kind,duration);
    when(reviews.submit("owner","key-1","file-1","video",kind)).thenReturn(portrait);
    var mvc=MockMvcBuilders.standaloneSetup(new PortraitController(reviews)).build();
    mvc.perform(post("/api/v1/media/portraits").principal(() -> "owner").header("Idempotency-Key","key-1")
        .contentType("application/json").content("{\"fileId\":\"file-1\",\"model\":\"video\",\"kind\":\""+kind+"\"}"))
        .andExpect(status().isAccepted()).andExpect(jsonPath("kind").value(kind.name()))
        .andExpect(jsonPath("durationSeconds").value(duration));
    verify(reviews).submit("owner","key-1","file-1","video",kind);
  }
}
