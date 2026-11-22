package com.company.requests.application;

import com.company.requests.application.CurrentAccess.ActionTarget;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.Action;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Fixed-order, length-framed digest of the exact v1 observation tuple. */
final class ObservationBinding {
    private ObservationBinding() { }

    static byte[] digest(AuthenticatedActor actor, Admission admission, PolicyGuard guard, Action action,
                         ActionTarget scoped, String fact) {
        var request = scoped.request();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var data = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                text(data, "requests.action-context.binding.v1");
                text(data, actor.issuer());
                text(data, actor.principal());
                text(data, actor.kind().name());
                text(data, actor.person().map(person -> person.value()).orElse(""));
                text(data, guard.organization().value());
                text(data, admission.genesis().policyDigest());
                data.writeLong(guard.policyVersion());
                data.writeLong(admission.writer().writerId().getMostSignificantBits());
                data.writeLong(admission.writer().writerId().getLeastSignificantBits());
                data.writeLong(admission.writer().generation());
                text(data, request.type().name());
                text(data, request.ref().value());
                data.writeLong(request.creationOrder().epoch());
                data.writeLong(request.creationOrder().ordinal());
                text(data, action.name());
                text(data, request.state().name());
                data.writeLong(request.revision());
                text(data, request.originatingPersonId().value());
                text(data, scoped.assignment().person().value());
                data.writeLong(scoped.assignment().version());
                data.writeLong(scoped.visibility().version());
                // Body and immutable name are versioned by the request revision in this profile.
                text(data, scoped.materialField().name());
                data.writeLong(request.revision());
                text(data, fact);
            }
            return digest.digest();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new AuthorityUnavailableException();
        }
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
