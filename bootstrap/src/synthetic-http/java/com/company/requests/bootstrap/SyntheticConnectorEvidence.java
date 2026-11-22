package com.company.requests.bootstrap;

import java.io.IOException;

import com.company.requests.adapter.http.RequestEvidenceFilter;
import com.company.requests.application.evidence.RequestEvidenceRecorder;
import jakarta.servlet.ServletException;
import org.apache.catalina.AccessLog;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/** Tomcat access callback for requests rejected or completed before servlet dispatch. */
final class SyntheticConnectorEvidence extends ValveBase implements AccessLog {
    private final RequestEvidenceRecorder recorder;

    SyntheticConnectorEvidence(RequestEvidenceRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        getNext().invoke(request, response);
    }

    @Override
    public void log(Request request, Response response, long elapsedNanos) {
        if (!RequestEvidenceFilter.handled(request)) {
            recorder.recordUndispatchedIngress(elapsedNanos, response.getStatus());
        }
    }

    @Override
    public boolean getRequestAttributesEnabled() {
        return false;
    }

    @Override
    public void setRequestAttributesEnabled(boolean enabled) {
        // The evidence line uses no connector request attributes.
    }
}
