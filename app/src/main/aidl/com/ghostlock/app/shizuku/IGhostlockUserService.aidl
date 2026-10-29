package com.ghostlock.app.shizuku;

import com.ghostlock.app.shizuku.IGhostlockCallback;
import com.ghostlock.app.shizuku.IGhostlockStatusCallback;

interface IGhostlockUserService {
    void destroy() = 16777114;
    void runExploit(int primaryCpu, int consumerCpu, boolean safeMode, boolean forceAttack, in byte[] profileBlob, @nullable String debugDir, IGhostlockCallback callback, IGhostlockStatusCallback statusCallback) = 2;
    void runExploitAsManager(int primaryCpu, int consumerCpu, boolean safeMode, boolean forceAttack, in byte[] profileBlob, @nullable String debugDir, @nullable String managerPackage, IGhostlockCallback callback, IGhostlockStatusCallback statusCallback) = 3;
    /**
     * Specialized H80GT chain (engine = "h80gt"). payloadId selects an entry in
     * the APK's assets/h80gt/payloads.conf; the service re-reads and re-verifies
     * every artifact before staging it. Stage tokens are preflight, stage,
     * launch, exploit, module, userspace, restore, handoff, plus the
     * outcome/reboot pseudo-steps.
     */
    void runH80gt(String payloadId, @nullable String managerPackage, IGhostlockCallback callback, IGhostlockStatusCallback statusCallback) = 4;
}
