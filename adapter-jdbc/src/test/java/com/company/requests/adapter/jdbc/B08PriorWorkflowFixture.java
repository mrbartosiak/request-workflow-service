package com.company.requests.adapter.jdbc;

import javax.sql.DataSource;

public final class B08PriorWorkflowFixture {
    private B08PriorWorkflowFixture() { }

    public static void migrateV002(DataSource owner) {
        new JdbcWorkflowMigrations(owner).migrateThrough(2);
    }
}
