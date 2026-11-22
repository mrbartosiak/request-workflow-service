package com.company.requests.bootstrap;

import java.nio.file.Path;

/** Port-free child that stops after a durable start without running close or finish. */
public final class AbandonedEvidenceProbe {
    private AbandonedEvidenceProbe() { }

    public static void main(String[] args) throws Exception {
        var spool = ProtectedEvidenceSpoolTest.open(Path.of(args[0]));
        ProtectedEvidenceSpoolTest.start(ProtectedEvidenceSpoolTest.recorder(spool));
        Runtime.getRuntime().halt(0);
    }
}
