package com.yesmadam.schedulerservice.jobs.spavailabilityautoon.service;

import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpAvailabilityAutoOnWindow;
import com.yesmadam.schedulerservice.jobs.spavailabilityautoon.model.SpKitCohortBatch;

/**
 * Business logic for the 20:00 kit-approved notification job. Imports nothing from {@code
 * io.temporal} (rule 12), so it is tested with plain JUnit and no test environment.
 *
 * <p>The window itself comes from {@link SpAvailabilityAutoOnService#resolveWindow} — both jobs
 * resolve the same one, which is what makes the 20:00 push and the 23:30 flip talk about the same
 * cohort on the same night.
 */
public interface SpKitApprovedNotifyService {

  /**
   * Claims the next page of SPs to notify, in one committed transaction.
   *
   * <p>Claiming only. The push itself is a separate activity, deliberately outside this transaction
   * (rule 14) — see {@link SpKitApprovedNotifyWriter} for why that ordering is the least-bad one.
   *
   * @param window the window resolved once for this run
   * @param afterSpId exclusive keyset cursor; {@code 0} for the first page
   * @return the newly claimed ids, the cursor for the next call, and whether the window is
   *     exhausted
   */
  SpKitCohortBatch claimNextBatch(SpAvailabilityAutoOnWindow window, int afterSpId);
}
