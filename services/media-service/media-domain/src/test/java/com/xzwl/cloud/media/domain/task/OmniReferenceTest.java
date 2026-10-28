package com.xzwl.cloud.media.domain.task;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OmniReferenceTest {
  final OmniReference v2=OmniReference.forModel("doubao-seedance-2-0-260128");
  final OmniReference v25=OmniReference.forModel("doubao-seedance-2-5-260628");
  MediaReference ref(MediaReference.Kind kind,double duration) {
    return new MediaReference("file-"+kind,null,kind,duration);
  }
  @Test void enforcesTypeCountsTotalsDurationSumsAndAudioOnlyPerExactVersion() {
    var image=new MediaReference("image",null);
    var video=ref(MediaReference.Kind.VIDEO,8);
    var audio=ref(MediaReference.Kind.AUDIO,8);
    assertFalse(v2.accepts(List.of(audio),true));
    assertTrue(v25.accepts(List.of(audio),true));
    assertTrue(v2.accepts(List.of(image,video,audio),true));
    assertFalse(v2.accepts(List.of(video,video),true));
    assertFalse(v2.accepts(List.of(image,audio,audio),true));
    assertTrue(v25.accepts(List.of(video,video,audio,audio),true));
    assertFalse(v2.accepts(Collections.nCopies(10,image),true));
    assertTrue(v25.accepts(Collections.nCopies(30,image),true));
    assertFalse(v25.accepts(Collections.nCopies(31,image),true));
    assertFalse(v25.accepts(List.of(ref(MediaReference.Kind.VIDEO,1)),true));
    assertFalse(v25.accepts(List.of(new MediaReference("video",null,MediaReference.Kind.VIDEO,null)),true));
    assertFalse(v25.accepts(List.of(),true));
    assertNull(OmniReference.forModel("future-model"));
  }
}
