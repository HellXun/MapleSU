package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import android.content.SharedPreferences
import dev.maplesu.manager.modules.UltraRootKeyStore
import dev.maplesu.manager.ultrakernel.UltraKernelCredential
import dev.maplesu.manager.ultrakernel.UltraKernelFlow
import dev.maplesu.manager.ultrakernel.UltraKernelContextAssets
import dev.maplesu.manager.ultrakernel.UltraKernelOriginalRecovery
import dev.maplesu.manager.ultrakernel.UltraKernelSpec
import dev.maplesu.manager.runtime.MapleSuCredentialStore
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.DetectionRules
import java.security.SecureRandom

/** First Ultra installs prepare a durable identity; only a live SDK probe can mark it paired. */
internal object UltraInstallCredentials {
    const val IMAGE_EXPORT_OPERATION = "image-export"

    fun canProvision(): Boolean = false ||
        (ModeRuntime.active == null && (ModeRuntime.latestDetection?.state in
            setOf(ModeState.NOT_INSTALLED, ModeState.FOREIGN_SU) ||
            // A foreign KSU with our UAPI stays unresolved for runtime ownership, but its
            // kernel response still permits preparing a first Ultra installation identity.
            ModeRuntime.latestDetection?.rule == DetectionRules.Rule.R3_KSU_BRANCH_UNTRUSTED))

    fun fingerprint(context: Context, operationId: String? = null): String? {
        operationId?.let { id ->
            UltraPendingInstallCredentialStore(context).credential(id)?.let { credential ->
                val bytes = credential.copySecret()
                return try { UltraKernelSpec.sha256(bytes) } finally { bytes.fill(0) }
            }
        }
        val key = UltraRootKeyStore.read(context)?.takeIf(UltraKernelCredential::isValid) ?: return null
        val bytes = key.toByteArray(Charsets.US_ASCII)
        return try { UltraKernelSpec.sha256(bytes) } finally { bytes.fill(0) }
    }

    private fun policy(context: Context, imageOnly: Boolean = false, resumingRemoval: Boolean = false): UltraInstallCredentialPolicy {
        val app = context.applicationContext
        val store = MapleSuCredentialStore(app)
        return UltraInstallCredentialPolicy(
            currentKey = { if (resumingRemoval) null else UltraRootKeyStore.read(app) },
            suspendedKey = { app.ultraPreferences("maplesu-ultra", Context.MODE_PRIVATE)
                .getString("rootKeySuspended", null) },
            loadDevice = {
                imageSeed(app, create = false) ?: if (imageOnly) null else runCatching { store.load() }.getOrNull()
            },
            createDevice = { requireNotNull(imageSeed(app, create = true)) },
            knownNonUltra = { resumingRemoval || canProvision() },
        )
    }

    /** Both installation and export keep a durable seed separate from the running kernel. */
    @Synchronized
    private fun imageSeed(context: Context, create: Boolean): ByteArray? {
        val prefs = context.ultraPreferences("maplesu-ultra-image-exports", Context.MODE_PRIVATE)
        var key = prefs.getString("seed", null)?.takeIf(UltraKernelCredential::isValid)
        if (key == null && create) {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = SecureRandom()
            key = CharArray(UltraKernelCredential.LENGTH) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
            check(prefs.edit().putString("seed", key).commit()) { "ULTRA_KERNEL_IMAGE_CREDENTIAL_STORE_FAILED" }
        }
        return key?.toByteArray(Charsets.US_ASCII)
    }

    /** Read-back image identities are recovery candidates, never an installation receipt. */
    fun recoveryCandidates(context: Context): List<String> {
        val pending = UltraPendingInstallCredentialStore(context).recoveryCredentials().map { credential ->
            val bytes = credential.copySecret()
            try { bytes.toString(Charsets.US_ASCII) } finally { bytes.fill(0) }
        }
        return UltraIdentityResolution.installCandidates(pending,
            context.ultraPreferences("maplesu-ultra-image-exports", Context.MODE_PRIVATE).all)
    }

    fun existing(context: Context): UltraKernelCredential? = policy(context).existing()

    /** Explicit patching prepares an identity; detecting or pairing the running kernel is unrelated. */
    fun forCandidate(context: Context, imageOnly: Boolean = false): UltraKernelCredential =
        policy(context, imageOnly).forCandidate()

    /** A file export retains its own identity and preserves this device's existing pairing. */
    fun retain(context: Context, preparation: UltraKernelFlow.Preparation, operationId: String) {
        val candidate = requireNotNull(preparation.candidate) { "ULTRA_KERNEL_CANDIDATE_MISSING" }
        retainCandidate(context, candidate.image, candidate.bootSha256, preparation.credential, operationId)
    }

    fun retainCandidate(context: Context, image: java.io.File, sha256: String,
                        credential: UltraKernelCredential?, operationId: String) {
        check(image.isFile && UltraKernelSpec.sha256(image) == sha256) { "ULTRA_KERNEL_CANDIDATE_CHANGED" }
        requireNotNull(credential) { "ULTRA_KERNEL_CREDENTIAL_REQUIRED" }
        // The image may be exported or flashed later, even after cancellation or another
        // pairing. Archive its exact bound key before touching any replaceable task record.
        archiveCandidate(context.ultraPreferences("maplesu-ultra-image-exports", Context.MODE_PRIVATE),
            sha256, credential)
        if (operationId == IMAGE_EXPORT_OPERATION) {
            policy(context).retainForExport(credential, archive = {}, storeIfAbsent = { key ->
                UltraKeyPairedStore.storeInstallKeyIfAbsent(context, key, operationId, freshCandidate = canProvision())
            })
            return
        }
        val prefs = context.ultraPreferences("maplesu-ultra", Context.MODE_PRIVATE)
        val removal = prefs.getString("environmentRemoval", null)
        val expectedKey = if (removal != null) prefs.getString("rootKey", null) else UltraRootKeyStore.read(context)
        policy(context, resumingRemoval = removal != null).retain(credential, stageForReboot = {
            UltraPendingInstallCredentialStore(context).stage(expectedKey, credential, operationId, sha256)
        }) { key ->
            UltraKeyPairedStore.storeInstallKeyIfAbsent(context, key, operationId, expectedKey, removal,
                freshCandidate = canProvision())
        }
    }

    internal fun archiveCandidate(prefs: SharedPreferences, sha256: String, credential: UltraKernelCredential) {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "ULTRA_KERNEL_CANDIDATE_CHANGED" }
        val bytes = credential.copySecret()
        try {
            val key = bytes.toString(Charsets.US_ASCII)
            synchronized(UltraRootKeyStore) {
                check(prefs.edit().putString(sha256, key).putLong("at:$sha256", System.currentTimeMillis())
                    .commit()) { "ULTRA_KERNEL_IMAGE_CREDENTIAL_STORE_FAILED" }
            }
        } finally { bytes.fill(0) }
    }

    /** Signing changes only AVB bytes, not the embedded Ultra identity. Bind the final digest. */
    fun retainSignedIdentity(context: Context, previousImage: java.io.File, previousSha256: String,
                             signed: dev.maplesu.manager.installer.InstallCandidateImage, operationId: String) {
        check(UltraKernelSpec.sha256(previousImage) == previousSha256 &&
            UltraKernelSpec.sha256(signed.image) == signed.sha256) { "ULTRA_KERNEL_CANDIDATE_CHANGED" }
        check(UltraKernelOriginalRecovery(UltraKernelContextAssets(context)).retainSignedCandidate(
            previousImage, signed.image, dev.maplesu.manager.compat.UltraStateStorage.flash(context.filesDir))) {
            "ULTRA_KERNEL_SIGNED_RECEIPT_REQUIRED"
        }
        if (operationId == IMAGE_EXPORT_OPERATION) {
            val prefs = context.ultraPreferences("maplesu-ultra-image-exports", Context.MODE_PRIVATE)
            val key = requireNotNull(prefs.getString(previousSha256, null)) { "ULTRA_KERNEL_IMAGE_CREDENTIAL_REQUIRED" }
            check(UltraKernelCredential.isValid(key) && UltraKernelSpec.sha256(signed.image) == signed.sha256)
            archiveCandidate(prefs, signed.sha256, UltraKernelCredential.of(key))
        } else {
            val pending = UltraPendingInstallCredentialStore(context)
            val credential = pending.credential(operationId)?.also {
                check(pending.credential(operationId, previousSha256) == it) { "ULTRA_KERNEL_CANDIDATE_CHANGED" }
            } ?: context.ultraPreferences("maplesu-ultra-image-exports", Context.MODE_PRIVATE)
                .getString(previousSha256, null)?.let(UltraKernelCredential::of) ?: existing(context)
            retainCandidate(context, signed.image, signed.sha256, credential, operationId)
        }
    }
}
