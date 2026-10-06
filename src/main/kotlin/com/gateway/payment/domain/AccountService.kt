package com.gateway.payment.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class AccountService(private val accounts: AccountStore) {
    fun create(name: String, email: String?): CreatedAccount {
        require(name.isNotBlank() && name.length <= 200) { "Account name is required (max 200 characters)" }
        require(email == null || email.length <= 320) { "Email is too long" }
        val account = accounts.createAccount(name.trim(), email?.trim()?.takeIf(String::isNotBlank))
        return CreatedAccount(account, createKey(account.id, "Default").secret)
    }

    fun authenticate(apiKey: String?): PaymentAccount? {
        if (apiKey.isNullOrBlank() || apiKey.length > 128) return null
        return accounts.authenticateApiKey(sha256(apiKey))
    }

    fun listKeys(accountId: java.util.UUID): List<MerchantApiKey> = accounts.listApiKeys(accountId)

    fun createKey(accountId: java.util.UUID, name: String): CreatedApiKey {
        require(name.isNotBlank() && name.length <= 100) { "Key name is required (max 100 characters)" }
        require(accounts.accountExists(accountId)) { "Account not found" }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val secret = "gw_live_${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}"
        val prefix = secret.take(16)
        val record = accounts.createApiKey(accountId, name.trim(), prefix, sha256(secret))
        return CreatedApiKey(record, secret)
    }

    fun revokeKey(accountId: java.util.UUID, keyId: java.util.UUID): Boolean = accounts.revokeApiKey(accountId, keyId)

    fun rotateKey(accountId: java.util.UUID, keyId: java.util.UUID, name: String): CreatedApiKey {
        require(accounts.findApiKey(accountId, keyId)?.revokedAt == null) { "API key not found or already revoked" }
        require(name.isNotBlank() && name.length <= 100) { "Key name is required (max 100 characters)" }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val secret = "gw_live_${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}"
        val key = accounts.rotateApiKey(accountId, keyId, name.trim(), secret.take(16), sha256(secret))
        return CreatedApiKey(key, secret)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

data class CreatedAccount(val account: PaymentAccount, val apiKey: String)

interface AccountStore {
    fun createAccount(name: String, email: String?): PaymentAccount
    fun accountExists(accountId: java.util.UUID): Boolean
    fun authenticateApiKey(apiKeyHash: String): PaymentAccount?
    fun listApiKeys(accountId: java.util.UUID): List<MerchantApiKey>
    fun findApiKey(accountId: java.util.UUID, keyId: java.util.UUID): MerchantApiKey?
    fun createApiKey(accountId: java.util.UUID, name: String, prefix: String, hash: String): MerchantApiKey
    fun revokeApiKey(accountId: java.util.UUID, keyId: java.util.UUID): Boolean
    fun rotateApiKey(accountId: java.util.UUID, oldKeyId: java.util.UUID, name: String, prefix: String, hash: String): MerchantApiKey
}
