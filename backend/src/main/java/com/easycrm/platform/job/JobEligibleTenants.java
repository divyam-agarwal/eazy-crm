package com.easycrm.platform.job;

import java.util.List;
import java.util.UUID;

/**
 * The tenants a scheduled job should act on. Declared here, in the consumer, and implemented in
 * {@code tenant} so that {@code platform} never imports {@code tenant} -- {@code tenant -> platform}
 * already exists (4 imports), so implementing a platform interface adds no edge and the graph stays
 * acyclic. Same inversion as {@code iam.AssignedWorkload} (challenge #66), and it works for the
 * same reason: only a {@code UUID} crosses the boundary. A port returning {@code List<Tenant>}
 * would leave the cycle exactly where it was.
 *
 * <p>Which statuses count is deliberately the implementation's decision, not this interface's:
 * {@code TenantStatus} is {@code tenant}'s vocabulary and naming it here would reintroduce the
 * import this port exists to remove.
 */
public interface JobEligibleTenants {

    /** Job-eligible tenant ids. Callable with no tenant context bound; {@code tenant} is global. */
    List<UUID> ids();
}
