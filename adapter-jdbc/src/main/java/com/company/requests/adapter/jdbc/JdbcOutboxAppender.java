package com.company.requests.adapter.jdbc;

import com.company.requests.application.OutboundObligation;
import com.company.requests.application.OutboxAppendPort;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists a pending obligation through the bound workflow command connection. */
public final class JdbcOutboxAppender implements OutboxAppendPort {
    private final DataSource command;
    private final JdbcTemplate jdbc;

    public JdbcOutboxAppender(DataSource command) {
        this.command = Objects.requireNonNull(command);
        jdbc = new JdbcTemplate(command);
    }

    @Override
    public void append(OutboundObligation obligation) {
        JdbcPublishGate.requireOutboxTarget(command, obligation);
        int appended = jdbc.update("""
                INSERT INTO requests.outbox (
                    destination, tenant, effect_id, trigger_slot, request_ref, request_revision, operation,
                    schema_version, projection_ref, scheduled_epoch_second, scheduled_nano
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, obligation.destination(), obligation.tenant().value(), obligation.effectId(), obligation.triggerSlot(),
                obligation.ref().value(), obligation.revision(), obligation.operation(), obligation.schemaVersion(),
                obligation.projectionRef(), obligation.scheduledAt().getEpochSecond(), obligation.scheduledAt().getNano());
        if (appended != 1) {
            throw new IllegalStateException("Exactly one outbound obligation must be appended.");
        }
    }
}
