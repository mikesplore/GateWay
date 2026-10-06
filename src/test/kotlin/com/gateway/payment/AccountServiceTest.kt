package com.gateway.payment

import com.gateway.payment.domain.*
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountServiceTest {
    @Test
    fun `creates named keys and authentication only resolves active key hash`() {
        val store = FakeAccountStore()
        val service = AccountService(store)
        val account = store.createAccount("merchant", null)
        val created = service.createKey(account.id, "production")

        assertTrue(created.secret.startsWith("gw_live_"))
        assertEquals("production", created.key.name)
        assertNotEquals(created.secret, store.savedHash)
        assertEquals(account.id, service.authenticate(created.secret)?.id)
        assertNull(service.authenticate("not-a-key"))
    }

    @Test
    fun `rotation returns new secret and atomically replaces active key`() {
        val store = FakeAccountStore()
        val service = AccountService(store)
        val account = store.createAccount("merchant", null)
        val first = service.createKey(account.id, "old")

        val rotated = service.rotateKey(account.id, first.key.id, "new")

        assertNotEquals(first.secret, rotated.secret)
        assertNull(service.authenticate(first.secret))
        assertEquals(account.id, service.authenticate(rotated.secret)?.id)
        assertTrue(service.listKeys(account.id).any { it.id == first.key.id && it.revokedAt != null })
    }

    private class FakeAccountStore : AccountStore {
        private val accounts = mutableMapOf<UUID, PaymentAccount>()
        private val keys = mutableMapOf<UUID, MerchantApiKey>()
        private val hashes = mutableMapOf<String, UUID>()
        private val hashesByKey = mutableMapOf<UUID, String>()
        var savedHash: String? = null

        override fun createAccount(name: String, email: String?): PaymentAccount = PaymentAccount(UUID.randomUUID(), name, email, Instant.now()).also { accounts[it.id] = it }
        override fun accountExists(accountId: UUID) = accountId in accounts
        override fun authenticateApiKey(apiKeyHash: String) = hashes[apiKeyHash]?.let(accounts::get)
        override fun listApiKeys(accountId: UUID) = keys.values.filter { it.accountId == accountId }
        override fun findApiKey(accountId: UUID, keyId: UUID) = keys[keyId]?.takeIf { it.accountId == accountId }
        override fun createApiKey(accountId: UUID, name: String, prefix: String, hash: String): MerchantApiKey {
            savedHash = hash
            val key = MerchantApiKey(UUID.randomUUID(), accountId, name, prefix, Instant.now(), null, null)
            keys[key.id] = key
            hashes[hash] = accountId
            hashesByKey[key.id] = hash
            return key
        }
        override fun revokeApiKey(accountId: UUID, keyId: UUID): Boolean {
            val old = findApiKey(accountId, keyId)?.takeIf { it.revokedAt == null } ?: return false
            keys[keyId] = old.copy(revokedAt = Instant.now())
            hashesByKey.remove(keyId)?.let(hashes::remove)
            return true
        }
        override fun rotateApiKey(accountId: UUID, oldKeyId: UUID, name: String, prefix: String, hash: String): MerchantApiKey {
            check(revokeApiKey(accountId, oldKeyId))
            return createApiKey(accountId, name, prefix, hash)
        }
    }
}
