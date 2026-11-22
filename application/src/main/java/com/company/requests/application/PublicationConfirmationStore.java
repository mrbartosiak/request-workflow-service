package com.company.requests.application;

import com.company.requests.application.PublicationJournal.Entry;

/** Verified projection on the workflow transaction holding the organization guard. */
public interface PublicationConfirmationStore {
    boolean contains(PolicyGuard guard, Entry committed);

    void record(PolicyGuard guard, Entry committed);
}
