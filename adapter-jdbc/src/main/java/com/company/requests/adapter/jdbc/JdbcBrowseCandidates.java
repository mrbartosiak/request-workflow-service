package com.company.requests.adapter.jdbc;

import com.company.requests.application.PolicyGuard;
import com.company.requests.application.RequestBrowseStore;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.RequestRef;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** A scoped, indexed seek on the connection holding the organization guard. */
public final class JdbcBrowseCandidates implements RequestBrowseStore {
    private static final String SCOPE = """
            SELECT ref, created_epoch, created_ordinal FROM requests.request
            WHERE organization = ?
            """;
    private static final String SEEK = " AND (created_epoch, created_ordinal, ref) < (?, ?, ?) ";
    // The existing ref column uses COLLATE "C" in the qualified UTF-8 database.
    private static final String ORDER = " ORDER BY created_epoch DESC, created_ordinal DESC, ref DESC LIMIT ?";
    private static final RowMapper<Candidate> CANDIDATE = (row, index) -> new Candidate(
            new CreationOrder(row.getLong("created_epoch"), row.getLong("created_ordinal")),
            new RequestRef(row.getString("ref")));

    private final DataSource source;
    private final JdbcTemplate jdbc;

    public JdbcBrowseCandidates(DataSource command) {
        source = command;
        jdbc = new JdbcTemplate(command);
    }

    @Override
    public List<Candidate> findCandidates(PolicyGuard guard, Optional<Candidate> below, int limit) {
        JdbcTransactionRunner.requireGuard(source, guard);
        String scope = guard.organization().value();
        return below.map(last -> jdbc.query(SCOPE + SEEK + ORDER, CANDIDATE, scope, last.order().epoch(),
                last.order().ordinal(), last.ref().value(), limit))
                .orElseGet(() -> jdbc.query(SCOPE + ORDER, CANDIDATE, scope, limit));
    }
}
