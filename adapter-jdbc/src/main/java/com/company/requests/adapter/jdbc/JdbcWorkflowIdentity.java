package com.company.requests.adapter.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.WriterLineage.Identity;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcWorkflowIdentity {
    private final DataSource source;

    public JdbcWorkflowIdentity(DataSource source) {
        this.source = source;
    }

    public Identity current() {
        JdbcTransactionRunner.requireWorkflowReadConnection(source);
        return inspect(source);
    }

    /** Explicit observation for the owner when provisioning an independent certificate. */
    public static Identity inspect(DataSource source) {
        try {
            var identity = new JdbcTemplate(source).queryForObject("""
                    SELECT host(inet_server_addr()), inet_server_port(), current_database(), pg_postmaster_start_time()
                    WHERE NOT pg_is_in_recovery() AND current_setting('transaction_read_only') = 'off'
                    """, (row, index) -> {
                String address = row.getString(1);
                if (address == null) {
                    throw new AuthorityUnavailableException();
                }
                return new Identity(address, row.getInt(2), row.getString(3),
                        row.getObject(4, OffsetDateTime.class).toInstant());
            });
            if (identity != null) {
                return identity;
            }
        } catch (DataAccessException failure) {
            throw new AuthorityUnavailableException();
        }
        throw new AuthorityUnavailableException();
    }
}
