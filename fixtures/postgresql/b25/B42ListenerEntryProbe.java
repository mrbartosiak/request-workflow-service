import com.company.requests.application.evidence.RequestEvidenceRecorder;
import com.company.requests.bootstrap.SyntheticCreateConfiguration.Settings;
import com.company.requests.bootstrap.SyntheticHttpMain;
import java.io.BufferedReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;

/** Checks the actual packaged listener descriptors without starting either listener. */
public final class B42ListenerEntryProbe {
    private B42ListenerEntryProbe() { }

    public static void main(String[] args) throws ReflectiveOperationException {
        var b32 = SyntheticHttpMain.class.getDeclaredMethod("serve", int.class, Path.class,
                Settings.class, BufferedReader.class);
        verify(b32);
        b32.setAccessible(true);
        verify(SyntheticHttpMain.class.getDeclaredMethod("serve", int.class, Path.class,
                Settings.class, BufferedReader.class, RequestEvidenceRecorder.class));
        System.out.println("PASS B32 reflection descriptor and protected listener overload in packaged synthetic HTTP.");
    }

    private static void verify(Method method) {
        if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPrivate(method.getModifiers())
                || method.getReturnType() != void.class) {
            throw new AssertionError("Unexpected synthetic HTTP listener descriptor.");
        }
    }
}
