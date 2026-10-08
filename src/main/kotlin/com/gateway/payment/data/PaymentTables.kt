package com.gateway.payment.data

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object Accounts : Table("accounts") {
    val id = uuid("id")
    val name = varchar("name", 200)
    val email = varchar("email", 320).nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Sites : Table("sites") {
    val id = uuid("id")
    val accountId = uuid("account_id").references(Accounts.id)
    // Database FK is declared in V6; omit the Exposed reference to avoid the Sites/Projects init cycle.
    val projectId = uuid("project_id").nullable()
    val hostname = varchar("hostname", 253)
    val upstreamUrl = varchar("upstream_url", 2048).nullable()
    val tlsRef = varchar("tls_ref", 253).nullable()
    val template = varchar("template", 32).default("proxy")
    val entitlementState = varchar("entitlement_state", 32).default("suspended")
    val stateReason = varchar("state_reason", 128).default("awaiting_payment")
    val billingAmount = decimal("billing_amount", 14, 2).default(java.math.BigDecimal.ZERO)
    val manualBlockReason = varchar("manual_block_reason", 256).nullable()
    val stateChangedAt = datetime("state_changed_at").nullable()
    val stateEffectiveAt = datetime("state_effective_at").nullable()
    val appliedHash = varchar("applied_hash", 64).nullable()
    val applyStatus = varchar("apply_status", 32).default("not_configured")
    val lastApplyError = text("last_apply_error").nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(accountId, hostname) }
}

object Projects : Table("projects") {
    val id = uuid("id")
    val accountId = uuid("account_id").references(Accounts.id)
    val siteId = uuid("site_id").references(Sites.id).nullable()
    val name = varchar("name", 200)
    val billingReference = varchar("billing_reference", 128).nullable()
    val status = varchar("status", 24).default("active")
    val statusReason = varchar("status_reason", 256).nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(accountId, name); index(false, accountId, createdAt) }
}

object Customers : Table("customers") {
    val id = uuid("id")
    val accountId = uuid("account_id").references(Accounts.id)
    val displayName = varchar("display_name", 200).nullable()
    val email = varchar("email", 320).nullable()
    val phoneNumber = varchar("phone_number", 24).nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
    init { index(false, accountId, createdAt) }
}

object MerchantApiKeys : Table("merchant_api_keys") {
    val id = uuid("id")
    val accountId = uuid("account_id").references(Accounts.id)
    val name = varchar("name", 100)
    val prefix = varchar("key_prefix", 16)
    val keyHash = varchar("key_hash", 64).uniqueIndex()
    val createdAt = datetime("created_at")
    val lastUsedAt = datetime("last_used_at").nullable()
    val revokedAt = datetime("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init { index(false, accountId, createdAt) }
}

object Payments : Table("payments") {
    val id = uuid("id")
    val accountId = uuid("account_id")
    val projectId = uuid("project_id").nullable()
    val customerId = uuid("customer_id").nullable()
    val siteId = uuid("site_id").nullable()
    val provider = varchar("provider", 32)
    val providerReference = varchar("provider_reference", 128)
    val gatewayReference = varchar("gateway_reference", 128)
    val amount = decimal("amount", 14, 2)
    val currency = varchar("currency", 3)
    val status = varchar("status", 24)
    val checkoutUrl = text("checkout_url").nullable()
    val idempotencyKey = varchar("idempotency_key", 128).nullable()
    val requestEmail = varchar("request_email", 320).nullable()
    val description = varchar("description", 256).nullable()
    val customerPhone = varchar("customer_phone", 24).nullable()
    val providerRequestId = varchar("provider_request_id", 128).nullable()
    val providerTransactionId = varchar("provider_transaction_id", 128).nullable()
    val reconciliationAttempts = integer("reconciliation_attempts").default(0)
    val nextReconciliationAt = datetime("next_reconciliation_at").nullable()
    val reconciliationClaimedAt = datetime("reconciliation_claimed_at").nullable()
    val lastProviderError = text("last_provider_error").nullable()
    val paidAt = datetime("paid_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(provider, providerReference) }
}

object PaymentEvents : Table("payment_events") {
    val id = uuid("id")
    val provider = varchar("provider", 32)
    val eventType = varchar("event_type", 128)
    val deduplicationKey = varchar("deduplication_key", 256)
    val providerReference = varchar("provider_reference", 128).nullable()
    val normalizedStatus = varchar("normalized_status", 24).nullable()
    val normalizedAmount = decimal("normalized_amount", 14, 2).nullable()
    val normalizedCurrency = varchar("normalized_currency", 3).nullable()
    val providerTransactionId = varchar("provider_transaction_id", 128).nullable()
    val rawPayload = text("raw_payload")
    val status = varchar("status", 24)
    val receivedAt = datetime("received_at")
    val processedAt = datetime("processed_at").nullable()
    val processingError = text("processing_error").nullable()
    val attemptCount = integer("attempt_count").default(0)
    val claimedAt = datetime("claimed_at").nullable()

    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(provider, deduplicationKey) }
}

object PaymentStatusHistory : Table("payment_status_history") {
    val id = uuid("id")
    val paymentId = uuid("payment_id").references(Payments.id)
    val previousStatus = varchar("previous_status", 24).nullable()
    val newStatus = varchar("new_status", 24)
    val eventSource = varchar("source", 64)
    val providerTransactionId = varchar("provider_transaction_id", 128).nullable()
    val occurredAt = datetime("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

object OperationsAudit : Table("operations_audit") {
    val id = uuid("id")
    val action = varchar("action", 100)
    val targetId = varchar("target_id", 128).nullable()
    val details = text("details").nullable()
    val occurredAt = datetime("occurred_at")
    override val primaryKey = PrimaryKey(id)
}

object GatewayUsers : Table("gateway_users") {
    val id = uuid("id")
    val accountId = uuid("account_id").references(Accounts.id)
    val email = varchar("email", 320).uniqueIndex()
    val displayName = varchar("display_name", 200)
    val role = varchar("role", 32)
    val passwordHash = varchar("password_hash", 256).nullable()
    val inviteTokenHash = varchar("invite_token_hash", 64).nullable().uniqueIndex()
    val inviteExpiresAt = datetime("invite_expires_at").nullable()
    val createdAt = datetime("created_at")
    val disabledAt = datetime("disabled_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init { index(false, accountId, createdAt) }
}

object GatewayUserSessions : Table("gateway_user_sessions") {
    val id = uuid("id")
    val userId = uuid("user_id").references(GatewayUsers.id)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")
    val revokedAt = datetime("revoked_at").nullable()
    val lastSeenAt = datetime("last_seen_at")
    override val primaryKey = PrimaryKey(id)
    init { index(false, userId, expiresAt) }
}
