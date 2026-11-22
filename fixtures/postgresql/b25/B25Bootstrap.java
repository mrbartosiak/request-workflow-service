package com.company.requests.bootstrap;

import com.company.requests.adapter.integration.jdbc.JdbcSyntheticConnector;
import com.company.requests.bootstrap.SyntheticDispatchConfiguration.Route;
import com.company.requests.bootstrap.SyntheticDispatchConfiguration.ScheduledWorker;
import com.company.requests.bootstrap.SyntheticEffectiveAccessConfiguration.Settings;
import com.company.requests.integration.OutboundEvidenceRecorder.Destination;
import com.company.requests.integration.SyntheticDelivery.Connector;
import com.company.requests.integration.SyntheticDelivery.Contract;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.DataSource;

/** Exposes the package-owned synthetic control only to the separately compiled launcher. */
public final class B25Bootstrap {
    private B25Bootstrap() { }

    public static ScheduledWorker start(Settings settings, ConnectionPoolDataSource source, DataSource proof,
                                        DataSource remote, List<Route> routes, Map<String, Contract> contracts,
                                        Function<JdbcSyntheticConnector, Connector> controls) {
        return SyntheticDispatchConfiguration.scheduledWorker(settings, source, proof, remote, routes,
                contracts, controls);
    }

    public static ScheduledWorker startProtected(Settings settings, ConnectionPoolDataSource source, DataSource proof,
                                                 DataSource remote, List<Route> routes, Map<String, Contract> contracts,
                                                 Function<JdbcSyntheticConnector, Connector> controls,
                                                 B42FixtureEvidence evidence, Map<String, Destination> aliases) {
        return SyntheticDispatchConfiguration.scheduledWorker(settings, source, proof, remote, routes,
                contracts, controls, evidence.spool(), aliases);
    }
}
