package me.rerere.rikkihub.data.management.runtime

/**
 * Evidence switch for the mutation base. Set by the app only after the
 * management startup barrier completed in this process: durable fences restored, stale
 * approvals revoked and every interrupted proposal reconciled from store receipts.
 * It is the input of the management write gate.
 */
object ManagementReadiness {
    @Volatile
    var mutationBaseAccepted: Boolean = false
        internal set
}
