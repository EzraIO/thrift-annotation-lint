package io.github.thriftannotationlint.internal.planning;

import java.util.LinkedHashSet;
import java.util.Set;

/** Compilation-wide budget for fully resolved, referenced exact model identities. */
public final class ExactModelBudget {
    public enum Reservation {
        FREE_SOURCE,
        ALREADY_RESERVED,
        RESERVED,
        EXCEEDED_FIRST,
        EXCEEDED_REPEAT;

        public boolean accepted() {
            return this == FREE_SOURCE || this == ALREADY_RESERVED || this == RESERVED;
        }
    }

    private final int limit;
    private final Set<String> identities = new LinkedHashSet<String>();
    private boolean exceededReported;

    ExactModelBudget(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Exact-model limit must be positive");
        }
        this.limit = limit;
    }

    Reservation reserveResolved(String identity, boolean sourceRoot) {
        if (sourceRoot) {
            return Reservation.FREE_SOURCE;
        }
        if (identities.contains(identity)) {
            return Reservation.ALREADY_RESERVED;
        }
        if (identities.size() >= limit) {
            if (!exceededReported) {
                exceededReported = true;
                return Reservation.EXCEEDED_FIRST;
            }
            return Reservation.EXCEEDED_REPEAT;
        }
        identities.add(identity);
        return Reservation.RESERVED;
    }

    void release(String identity) {
        identities.remove(identity);
    }

    boolean contains(String identity) {
        return identities.contains(identity);
    }

    int size() {
        return identities.size();
    }

    int limit() {
        return limit;
    }
}
