package com.gateway.payment.data

import com.gateway.payment.domain.NewPaymentEvent
import com.gateway.payment.domain.Payment
import com.gateway.payment.domain.PaymentAccount
import com.gateway.payment.domain.MerchantApiKey
import com.gateway.payment.domain.PaymentStatus
import com.gateway.payment.domain.PaymentTotals
import com.gateway.payment.domain.PaymentEvent
import com.gateway.payment.domain.PaymentProject
import com.gateway.payment.domain.PaymentCustomer
import com.gateway.payment.domain.PaymentSite
import com.gateway.payment.domain.PaymentStore
import com.gateway.payment.domain.ProviderPaymentEvent
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class ExposedPaymentStore : PaymentStore, com.gateway.payment.domain.PaymentEventRecorder, com.gateway.enforcement.adapter.SiteEnforcementStore, com.gateway.payment.domain.HumanAuthStore {
    override fun hasOwner(): Boolean = transaction {
        GatewayUsers.selectAll().where { (GatewayUsers.role eq "owner") and GatewayUsers.disabledAt.isNull() }.count() > 0
    }

    override fun provisionFirstOwner(email: String, displayName: String, passwordHash: String, accountName: String): Boolean = transaction {
        exec("SELECT pg_advisory_xact_lock(718273641)")
        if (GatewayUsers.selectAll().where { GatewayUsers.role eq "owner" }.count() > 0) return@transaction false
        val accountId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val now = LocalDateTime.now(ZoneOffset.UTC)
        Accounts.insert {
            it[Accounts.id] = accountId
            it[Accounts.name] = accountName
            it[Accounts.email] = email
            it[Accounts.createdAt] = now
        }
        GatewayUsers.insert {
            it[GatewayUsers.id] = userId
            it[GatewayUsers.accountId] = accountId
            it[GatewayUsers.email] = email
            it[GatewayUsers.displayName] = displayName
            it[GatewayUsers.role] = "owner"
            it[GatewayUsers.passwordHash] = passwordHash
            it[GatewayUsers.createdAt] = now
        }
        true
    }

    override fun findUserForLogin(email: String): Pair<com.gateway.payment.domain.GatewayUser, String?>? = transaction {
        GatewayUsers.selectAll().where { (GatewayUsers.email eq email) and GatewayUsers.disabledAt.isNull() }.singleOrNull()?.let {
            it.toGatewayUser() to it[GatewayUsers.passwordHash]
        }
    }

    override fun createSession(userId: UUID, tokenHash: String, expiresAt: Instant) = transaction {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        GatewayUserSessions.insert {
            it[GatewayUserSessions.id] = UUID.randomUUID()
            it[GatewayUserSessions.userId] = userId
            it[GatewayUserSessions.tokenHash] = tokenHash
            it[GatewayUserSessions.createdAt] = now
            it[GatewayUserSessions.expiresAt] = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
            it[GatewayUserSessions.revokedAt] = null
            it[GatewayUserSessions.lastSeenAt] = now
        }
        Unit
    }

    override fun findSession(tokenHash: String): Pair<com.gateway.payment.domain.GatewayUser, Instant>? = transaction {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        (GatewayUserSessions innerJoin GatewayUsers).selectAll().where {
            (GatewayUserSessions.tokenHash eq tokenHash) and GatewayUserSessions.revokedAt.isNull() and
                (GatewayUserSessions.expiresAt greater now) and GatewayUsers.disabledAt.isNull()
        }.singleOrNull()?.let { row ->
            GatewayUserSessions.update({ GatewayUserSessions.id eq row[GatewayUserSessions.id] }) { it[lastSeenAt] = now }
            row.toGatewayUser() to row[GatewayUserSessions.expiresAt].toInstant(ZoneOffset.UTC)
        }
    }

    override fun revokeSession(tokenHash: String) = transaction {
        GatewayUserSessions.update({ (GatewayUserSessions.tokenHash eq tokenHash) and GatewayUserSessions.revokedAt.isNull() }) {
            it[revokedAt] = LocalDateTime.now(ZoneOffset.UTC)
        }
        Unit
    }

    override fun createInvite(accountId: UUID, email: String, displayName: String, role: String, inviteHash: String, expiresAt: Instant): com.gateway.payment.domain.GatewayUser = transaction {
        require(GatewayUsers.selectAll().where { GatewayUsers.email eq email }.count() == 0L) { "A user with this email already exists" }
        val id = UUID.randomUUID()
        val now = LocalDateTime.now(ZoneOffset.UTC)
        GatewayUsers.insert {
            it[GatewayUsers.id] = id
            it[GatewayUsers.accountId] = accountId
            it[GatewayUsers.email] = email
            it[GatewayUsers.displayName] = displayName
            it[GatewayUsers.role] = role
            it[GatewayUsers.inviteTokenHash] = inviteHash
            it[GatewayUsers.inviteExpiresAt] = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
            it[GatewayUsers.createdAt] = now
        }
        com.gateway.payment.domain.GatewayUser(id, accountId, email, displayName, role)
    }

    override fun acceptInvite(inviteHash: String, passwordHash: String): com.gateway.payment.domain.GatewayUser? = transaction {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val row = GatewayUsers.selectAll().where {
            (GatewayUsers.inviteTokenHash eq inviteHash) and (GatewayUsers.inviteExpiresAt greater now) and GatewayUsers.disabledAt.isNull()
        }.singleOrNull() ?: return@transaction null
        GatewayUsers.update({ GatewayUsers.id eq row[GatewayUsers.id] }) {
            it[GatewayUsers.passwordHash] = passwordHash
            it[GatewayUsers.inviteTokenHash] = null
            it[GatewayUsers.inviteExpiresAt] = null
        }
        row.toGatewayUser()
    }

    private fun ResultRow.toGatewayUser() = com.gateway.payment.domain.GatewayUser(
        this[GatewayUsers.id], this[GatewayUsers.accountId], this[GatewayUsers.email], this[GatewayUsers.displayName], this[GatewayUsers.role]
    )

    override fun createAccount(name: String, email: String?): PaymentAccount = transaction {
        val id = UUID.randomUUID()
        val now = LocalDateTime.now(ZoneOffset.UTC)
        Accounts.insert {
            it[Accounts.id] = id
            it[Accounts.name] = name
            it[Accounts.email] = email
            it[Accounts.createdAt] = now
        }
        PaymentAccount(id, name, email, now.toInstant(ZoneOffset.UTC))
    }

    override fun findAccount(accountId: UUID): PaymentAccount? = transaction {
        Accounts.selectAll().where { Accounts.id eq accountId }.singleOrNull()?.let {
            PaymentAccount(it[Accounts.id], it[Accounts.name], it[Accounts.email], it[Accounts.createdAt].toInstant(ZoneOffset.UTC))
        }
    }

    override fun authenticateApiKey(apiKeyHash: String): PaymentAccount? = transaction {
        (MerchantApiKeys innerJoin Accounts).selectAll().where {
            (MerchantApiKeys.keyHash eq apiKeyHash) and MerchantApiKeys.revokedAt.isNull()
        }.singleOrNull()?.let { row ->
            val now = LocalDateTime.now(ZoneOffset.UTC)
            MerchantApiKeys.update({ MerchantApiKeys.id eq row[MerchantApiKeys.id] }) { it[lastUsedAt] = now }
            PaymentAccount(row[Accounts.id], row[Accounts.name], row[Accounts.email], row[Accounts.createdAt].toInstant(ZoneOffset.UTC))
        }
    }

    override fun listApiKeys(accountId: UUID): List<MerchantApiKey> = transaction {
        MerchantApiKeys.selectAll().where { MerchantApiKeys.accountId eq accountId }
            .orderBy(MerchantApiKeys.createdAt, SortOrder.DESC).map { it.toApiKey() }
    }

    override fun findApiKey(accountId: UUID, keyId: UUID): MerchantApiKey? = transaction {
        MerchantApiKeys.selectAll().where { (MerchantApiKeys.accountId eq accountId) and (MerchantApiKeys.id eq keyId) }
            .singleOrNull()?.toApiKey()
    }

    override fun createApiKey(accountId: UUID, name: String, prefix: String, hash: String): MerchantApiKey = transaction {
        val id = UUID.randomUUID()
        val now = LocalDateTime.now(ZoneOffset.UTC)
        MerchantApiKeys.insert {
            it[MerchantApiKeys.id] = id
            it[MerchantApiKeys.accountId] = accountId
            it[MerchantApiKeys.name] = name
            it[MerchantApiKeys.prefix] = prefix
            it[MerchantApiKeys.keyHash] = hash
            it[MerchantApiKeys.createdAt] = now
        }
        MerchantApiKey(id, accountId, name, prefix, now.toInstant(ZoneOffset.UTC), null, null)
    }

    override fun revokeApiKey(accountId: UUID, keyId: UUID): Boolean = transaction {
        MerchantApiKeys.update({
            (MerchantApiKeys.accountId eq accountId) and (MerchantApiKeys.id eq keyId) and MerchantApiKeys.revokedAt.isNull()
        }) { it[revokedAt] = LocalDateTime.now(ZoneOffset.UTC) } > 0
    }

    override fun rotateApiKey(accountId: UUID, oldKeyId: UUID, name: String, prefix: String, hash: String): MerchantApiKey = transaction {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val revoked = MerchantApiKeys.update({
            (MerchantApiKeys.accountId eq accountId) and (MerchantApiKeys.id eq oldKeyId) and MerchantApiKeys.revokedAt.isNull()
        }) { it[revokedAt] = now }
        require(revoked == 1) { "API key not found or already revoked" }
        val id = UUID.randomUUID()
        MerchantApiKeys.insert {
            it[MerchantApiKeys.id] = id
            it[MerchantApiKeys.accountId] = accountId
            it[MerchantApiKeys.name] = name
            it[MerchantApiKeys.prefix] = prefix
            it[MerchantApiKeys.keyHash] = hash
            it[MerchantApiKeys.createdAt] = now
        }
        MerchantApiKey(id, accountId, name, prefix, now.toInstant(ZoneOffset.UTC), null, null)
    }

    override fun accountExists(accountId: UUID): Boolean = transaction {
        Accounts.selectAll().where { Accounts.id eq accountId }.count() > 0
    }

    private fun ResultRow.toApiKey() = MerchantApiKey(
        this[MerchantApiKeys.id], this[MerchantApiKeys.accountId], this[MerchantApiKeys.name], this[MerchantApiKeys.prefix],
        this[MerchantApiKeys.createdAt].toInstant(ZoneOffset.UTC), this[MerchantApiKeys.lastUsedAt]?.toInstant(ZoneOffset.UTC),
        this[MerchantApiKeys.revokedAt]?.toInstant(ZoneOffset.UTC)
    )
    override fun findByProviderReference(provider: String, reference: String): Payment? = transaction {
        Payments.selectAll().where { (Payments.provider eq provider) and (Payments.providerReference eq reference) }.singleOrNull()?.toPayment()
    }

    override fun findByGatewayReference(reference: String): Payment? = transaction {
        Payments.selectAll().where { Payments.gatewayReference eq reference }.singleOrNull()?.toPayment()
    }

    override fun findByProviderRequestId(provider: String, requestId: String): Payment? = transaction {
        Payments.selectAll().where { (Payments.provider eq provider) and (Payments.providerRequestId eq requestId) }.singleOrNull()?.toPayment()
    }

    override fun reservePayment(accountId: UUID, amount: BigDecimal, currency: String, idempotencyKey: String, projectId: UUID?, requestEmail: String?, phoneNumber: String?, gatewayReference: String?, description: String?): com.gateway.payment.domain.PaymentReservation = transaction {
        val id = UUID.randomUUID()
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val email = requestEmail?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        val customer = if (email != null || phoneNumber != null) {
            val byEmail = email?.let { value -> Customers.selectAll().where { (Customers.accountId eq accountId) and (Customers.email eq value) }.orderBy(Customers.createdAt).firstOrNull() }
            val byPhone = if (byEmail == null) phoneNumber?.let { value -> Customers.selectAll().where { (Customers.accountId eq accountId) and (Customers.phoneNumber eq value) }.orderBy(Customers.createdAt).firstOrNull() } else null
            val existingCustomer = byEmail ?: byPhone
            if (existingCustomer != null) {
                val customerId = existingCustomer[Customers.id]
                Customers.update({ Customers.id eq customerId }) {
                    if (email != null && existingCustomer[Customers.email] == null) it[Customers.email] = email
                    if (phoneNumber != null && existingCustomer[Customers.phoneNumber] == null) it[Customers.phoneNumber] = phoneNumber
                    it[Customers.updatedAt] = now
                }
                customerId
            } else {
                val customerId = UUID.randomUUID()
                Customers.insert {
                    it[Customers.id] = customerId; it[Customers.accountId] = accountId
                    it[Customers.email] = email; it[Customers.phoneNumber] = phoneNumber
                    it[Customers.createdAt] = now; it[Customers.updatedAt] = now
                }
                customerId
            }
        } else null
        val inserted = Payments.insertIgnore {
            it[Payments.id] = id
            it[Payments.accountId] = accountId
            it[Payments.provider] = "initializing"
            it[Payments.providerReference] = "reservation:$id"
            it[Payments.gatewayReference] = gatewayReference ?: "reservation:$id"
            it[Payments.projectId] = projectId
            it[Payments.customerId] = customer
            it[Payments.amount] = amount
            it[Payments.currency] = currency
            it[Payments.status] = "initializing"
            it[Payments.idempotencyKey] = idempotencyKey
            it[Payments.requestEmail] = requestEmail
            it[Payments.customerPhone] = phoneNumber
            it[Payments.description] = description
            it[Payments.updatedAt] = now
            it[Payments.createdAt] = now
        }
        if (inserted.insertedCount == 0) {
            val existing = Payments.selectAll().where { (Payments.accountId eq accountId) and (Payments.idempotencyKey eq idempotencyKey) }.singleOrNull()
            if (existing != null) return@transaction com.gateway.payment.domain.PaymentReservation(null, existing.toPayment())
            error("Unable to reserve a unique Gateway payment reference")
        }
        com.gateway.payment.domain.PaymentReservation(id, null)
    }

    override fun completeReservation(reservation: com.gateway.payment.domain.PaymentReservation, provider: String, reference: String, checkoutUrl: String?, providerRequestId: String?, gatewayReference: String): Payment = transaction {
        val id = reservation.reservation ?: error("Missing payment reservation")
        Payments.update({ Payments.id eq id }) {
            it[Payments.provider] = provider
            it[Payments.providerReference] = reference
            it[Payments.gatewayReference] = gatewayReference
            it[Payments.checkoutUrl] = checkoutUrl
            it[Payments.providerRequestId] = providerRequestId
            it[Payments.status] = "pending"
            val now = LocalDateTime.now(ZoneOffset.UTC)
            it[Payments.updatedAt] = now
            it[Payments.nextReconciliationAt] = now.plusMinutes(com.gateway.config.GatewayConfig.reconciliationStaleMinutes)
        }
        Payments.selectAll().where { Payments.id eq id }.single().toPayment()
    }

    override fun listPayments(accountId: UUID, status: PaymentStatus?, provider: String?, currency: String?, projectId: UUID?, from: Instant?, to: Instant?, limit: Int, offset: Int): List<Payment> = transaction {
        val query = Payments.selectAll().where { Payments.accountId eq accountId }
        status?.let { query.andWhere { Payments.status eq it.name.lowercase() } }
        provider?.let { query.andWhere { Payments.provider eq it.lowercase() } }
        currency?.let { query.andWhere { Payments.currency eq it.uppercase() } }
        projectId?.let { query.andWhere { Payments.projectId eq it } }
        from?.let { query.andWhere { Payments.createdAt greaterEq LocalDateTime.ofInstant(it, ZoneOffset.UTC) } }
        to?.let { query.andWhere { Payments.createdAt lessEq LocalDateTime.ofInstant(it, ZoneOffset.UTC) } }
        query.orderBy(Payments.createdAt, SortOrder.DESC).limit(limit.coerceIn(1, 200), offset.coerceAtLeast(0).toLong()).map { it.toPayment() }
    }

    override fun paymentTotals(accountId: UUID): PaymentTotals = transaction {
            val rows = Payments.selectAll().where { Payments.accountId eq accountId }.toList()
        PaymentTotals(rows.size.toLong(), rows.fold(BigDecimal.ZERO) { sum, row -> sum + row[Payments.amount] },
            rows.count { it[Payments.status] == "succeeded" }.toLong(), rows.count { it[Payments.status] == "pending" }.toLong(),
            rows.count { it[Payments.status] == "failed" }.toLong(), rows.count { it[Payments.status] == "reversed" }.toLong(),
            rows.groupBy { it[Payments.currency] }.mapValues { (_, currencyRows) -> currencyRows.fold(BigDecimal.ZERO) { sum, row -> sum + row[Payments.amount] } },
            rows.filter { it[Payments.status] == "succeeded" }.groupBy { it[Payments.currency] }.mapValues { (_, currencyRows) -> currencyRows.fold(BigDecimal.ZERO) { sum, row -> sum + row[Payments.amount] } })
    }

    override fun paymentStatusHistory(paymentId: UUID): List<com.gateway.payment.domain.PaymentStatusChange> = transaction {
        PaymentStatusHistory.selectAll().where { PaymentStatusHistory.paymentId eq paymentId }.orderBy(PaymentStatusHistory.occurredAt, SortOrder.ASC).map { row ->
            com.gateway.payment.domain.PaymentStatusChange(row[PaymentStatusHistory.previousStatus]?.let { runCatching { PaymentStatus.valueOf(it.uppercase()) }.getOrNull() }, PaymentStatus.valueOf(row[PaymentStatusHistory.newStatus].uppercase()), row[PaymentStatusHistory.eventSource], row[PaymentStatusHistory.providerTransactionId], row[PaymentStatusHistory.occurredAt].toInstant(ZoneOffset.UTC))
        }
    }

    override fun claimStalePayments(before: Instant, limit: Int): List<Payment> = transaction {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val leaseExpired = now.minusMinutes(5)
        val candidates = Payments.selectAll().where {
            (Payments.status eq "pending") and
                (Payments.nextReconciliationAt lessEq LocalDateTime.ofInstant(before, ZoneOffset.UTC)) and
                (Payments.reconciliationClaimedAt.isNull() or (Payments.reconciliationClaimedAt lessEq leaseExpired))
        }.orderBy(Payments.nextReconciliationAt, SortOrder.ASC).limit(limit.coerceIn(1, 500)).map { it[Payments.id] }
        candidates.mapNotNull { id ->
            val claimed = Payments.update({ (Payments.id eq id) and (Payments.status eq "pending") and (Payments.reconciliationClaimedAt.isNull() or (Payments.reconciliationClaimedAt lessEq leaseExpired)) }) {
                it[Payments.reconciliationClaimedAt] = now
            }
            if (claimed == 1) Payments.selectAll().where { Payments.id eq id }.singleOrNull()?.toPayment() else null
        }
    }

    override fun recordReconciliationFailure(paymentId: UUID, error: String, nextAttemptAt: Instant) = transaction {
        val current = Payments.selectAll().where { Payments.id eq paymentId }.singleOrNull() ?: return@transaction
        Payments.update({ Payments.id eq paymentId }) {
            it[Payments.reconciliationAttempts] = current[Payments.reconciliationAttempts] + 1
            it[Payments.lastProviderError] = error.take(1000)
            it[Payments.nextReconciliationAt] = LocalDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC)
            it[Payments.reconciliationClaimedAt] = null
        }
        Unit
    }

    override fun releaseReconciliationClaim(paymentId: UUID) = transaction {
        Payments.update({ Payments.id eq paymentId }) { it[Payments.reconciliationClaimedAt] = null }
        Unit
    }

    override fun listEvents(status: String?, limit: Int, offset: Int): List<PaymentEvent> = transaction {
        val query = PaymentEvents.selectAll()
        status?.let { query.andWhere { PaymentEvents.status eq it } }
        query.orderBy(PaymentEvents.receivedAt, SortOrder.DESC).limit(limit.coerceIn(1, 200), offset.coerceAtLeast(0).toLong()).map { row ->
            row.toPaymentEvent()
        }
    }

    override fun claimFailedEvent(eventId: UUID): PaymentEvent? = transaction {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val leaseExpired = now.minusMinutes(5)
        val row = PaymentEvents.selectAll().where { PaymentEvents.id eq eventId }.singleOrNull() ?: return@transaction null
        val attempts = row[PaymentEvents.attemptCount]
        val claimable = (PaymentEvents.status eq "failed") or ((PaymentEvents.status eq "received") and (PaymentEvents.claimedAt lessEq leaseExpired))
        val updated = PaymentEvents.update({ (PaymentEvents.id eq eventId) and claimable }) {
            it[PaymentEvents.status] = "received"; it[PaymentEvents.claimedAt] = now; it[PaymentEvents.attemptCount] = attempts + 1
        }
        if (updated == 0) return@transaction null
        PaymentEvents.selectAll().where { PaymentEvents.id eq eventId }.single().toPaymentEvent()
    }

    override fun finishReplayedEvent(eventId: UUID, event: ProviderPaymentEvent?, error: String?): Boolean = transaction {
        val applied = if (error != null) false else if (event == null) true else applyNormalizedEvent(event, "event_replay")
        PaymentEvents.update({ PaymentEvents.id eq eventId }) {
            it[PaymentEvents.status] = if (applied) "processed" else "failed"
            it[PaymentEvents.processedAt] = LocalDateTime.now(ZoneOffset.UTC); it[PaymentEvents.claimedAt] = null
            it[PaymentEvents.processingError] = if (applied) null else error ?: "Payment not found, amount/currency mismatch, or disallowed transition"
        }
        applied
    }

    override fun createSite(accountId: UUID, hostname: String): PaymentSite = createConfiguredSite(accountId, hostname, null, null, "proxy", null)

    override fun createConfiguredSite(accountId: UUID, hostname: String, upstreamUrl: String?, tlsRef: String?, template: String, projectId: UUID?): PaymentSite = transaction {
        require(Accounts.selectAll().where { Accounts.id eq accountId }.count() > 0) { "Account not found" }
        if (projectId != null) require(Projects.selectAll().where { (Projects.id eq projectId) and (Projects.accountId eq accountId) and (Projects.status neq "archived") }.count() > 0) { "Project not found or archived for account" }
        val id = UUID.randomUUID(); val now = LocalDateTime.now(ZoneOffset.UTC)
        Sites.insert {
            it[Sites.id] = id; it[Sites.accountId] = accountId; it[Sites.projectId] = projectId; it[Sites.hostname] = hostname
            it[Sites.upstreamUrl] = upstreamUrl; it[Sites.tlsRef] = tlsRef; it[Sites.template] = template
            it[Sites.createdAt] = now; it[Sites.stateChangedAt] = now
            it[Sites.applyStatus] = nextApplyStatus()
        }
        PaymentSite(id, accountId, hostname, now.toInstant(ZoneOffset.UTC), projectId, upstreamUrl, tlsRef, template,
            stateChangedAt = now.toInstant(ZoneOffset.UTC), applyStatus = nextApplyStatus())
    }

    override fun listSites(accountId: UUID): List<PaymentSite> = transaction {
        Sites.selectAll().where { Sites.accountId eq accountId }.orderBy(Sites.createdAt, SortOrder.DESC).map { it.toPaymentSite() }
    }

    override fun findSite(siteId: UUID): PaymentSite? = transaction {
        Sites.selectAll().where { Sites.id eq siteId }.singleOrNull()?.toPaymentSite()
    }

    override fun allSites(): List<PaymentSite> = transaction { Sites.selectAll().map { it.toPaymentSite() } }

    override fun deleteSite(accountId: UUID, siteId: UUID): Boolean = transaction {
        if (Sites.selectAll().where { (Sites.id eq siteId) and (Sites.accountId eq accountId) }.count() == 0L) return@transaction false
        // Projects.site_id is a legacy FK with ON DELETE CASCADE. Clear it first
        // so deleting a site cannot delete its project or detach payment history.
        Projects.update({ (Projects.siteId eq siteId) and (Projects.accountId eq accountId) }) {
            it[Projects.siteId] = null
        }
        Sites.deleteWhere { (Sites.id eq siteId) and (Sites.accountId eq accountId) } > 0
    }

    override fun updateSite(accountId: UUID, siteId: UUID, hostname: String, upstreamUrl: String?, tlsRef: String?, template: String, projectId: UUID?): PaymentSite? = transaction {
        if (projectId != null) require(Projects.selectAll().where { (Projects.id eq projectId) and (Projects.accountId eq accountId) and (Projects.status neq "archived") }.count() > 0) { "Project not found or archived for account" }
        val changed = Sites.update({ (Sites.id eq siteId) and (Sites.accountId eq accountId) }) {
            it[Sites.projectId] = projectId; it[Sites.hostname] = hostname; it[Sites.upstreamUrl] = upstreamUrl; it[Sites.tlsRef] = tlsRef; it[Sites.template] = template
            it[Sites.applyStatus] = nextApplyStatus(); it[Sites.lastApplyError] = null
        }
        if (changed == 0) null else Sites.selectAll().where { Sites.id eq siteId }.single().toPaymentSite()
    }

    override fun setEntitlement(siteId: UUID, state: String, reason: String, effectiveAt: Instant?): Boolean = transaction {
        require(state in setOf("active", "grace", "suspended", "disabled_by_admin")) { "Unknown entitlement state" }
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val changed = Sites.update({ Sites.id eq siteId }) {
            it[Sites.entitlementState] = state; it[Sites.stateReason] = reason.take(128)
            it[Sites.stateChangedAt] = now
            it[Sites.stateEffectiveAt] = effectiveAt?.let { time -> LocalDateTime.ofInstant(time, ZoneOffset.UTC) }
            it[Sites.applyStatus] = nextApplyStatus(); it[Sites.lastApplyError] = null
        }
        changed > 0
    }

    override fun saveApplyResult(siteId: UUID, hash: String?, status: String, error: String?) = transaction {
        Sites.update({ Sites.id eq siteId }) {
            if (hash != null) it[Sites.appliedHash] = hash
            it[Sites.applyStatus] = status; it[Sites.lastApplyError] = error?.take(4000)
        }
        Unit
    }

    override fun expireGraceEntitlements(now: Instant): List<UUID> = transaction {
        val cutoff = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
        val ids = Sites.selectAll().where { (Sites.entitlementState eq "grace") and Sites.stateEffectiveAt.isNotNull() and (Sites.stateEffectiveAt lessEq cutoff) }.map { it[Sites.id] }
        ids.forEach { id ->
            Sites.update({ (Sites.id eq id) and (Sites.entitlementState eq "grace") and (Sites.stateEffectiveAt lessEq cutoff) }) {
                it[Sites.entitlementState] = "suspended"; it[Sites.stateReason] = "grace_expired"
                it[Sites.stateChangedAt] = cutoff; it[Sites.stateEffectiveAt] = cutoff
                it[Sites.applyStatus] = nextApplyStatus(); it[Sites.lastApplyError] = null
            }
        }
        ids
    }

    private fun ResultRow.toPaymentSite() = PaymentSite(
        id = this[Sites.id], accountId = this[Sites.accountId], hostname = this[Sites.hostname],
        createdAt = this[Sites.createdAt].toInstant(ZoneOffset.UTC), upstreamUrl = this[Sites.upstreamUrl], tlsRef = this[Sites.tlsRef],
        projectId = this[Sites.projectId], template = this[Sites.template], entitlementState = this[Sites.entitlementState], stateReason = this[Sites.stateReason],
        stateChangedAt = this[Sites.stateChangedAt]?.toInstant(ZoneOffset.UTC), stateEffectiveAt = this[Sites.stateEffectiveAt]?.toInstant(ZoneOffset.UTC),
        appliedHash = this[Sites.appliedHash], applyStatus = this[Sites.applyStatus], lastApplyError = this[Sites.lastApplyError]
    )

    override fun createProject(accountId: UUID, siteId: UUID, name: String, billingReference: String?): PaymentProject =
        createGroupedProject(accountId, siteId, name, billingReference)

    override fun createGroupedProject(accountId: UUID, siteId: UUID?, name: String, billingReference: String?): PaymentProject = transaction {
        if (siteId != null) require(Sites.selectAll().where { (Sites.id eq siteId) and (Sites.accountId eq accountId) }.count() > 0) { "Site not found for account" }
        val id = UUID.randomUUID(); val now = LocalDateTime.now(ZoneOffset.UTC)
        Projects.insert { it[Projects.id] = id; it[Projects.accountId] = accountId; it[Projects.siteId] = siteId; it[Projects.name] = name; it[Projects.billingReference] = billingReference; it[Projects.createdAt] = now }
        if (siteId != null) Sites.update({ (Sites.id eq siteId) and (Sites.accountId eq accountId) }) { it[Sites.projectId] = id }
        PaymentProject(id, accountId, siteId, name, billingReference, now.toInstant(ZoneOffset.UTC))
    }

    override fun listProjects(accountId: UUID): List<PaymentProject> = transaction {
        Projects.selectAll().where { Projects.accountId eq accountId }.orderBy(Projects.createdAt, SortOrder.DESC).map { it.toPaymentProject() }
    }

    override fun projectBelongsToAccount(projectId: UUID, accountId: UUID): Boolean = transaction {
        Projects.selectAll().where { (Projects.id eq projectId) and (Projects.accountId eq accountId) }.count() > 0
    }

    override fun projectAcceptsPayments(projectId: UUID): Boolean = transaction {
        Projects.selectAll().where { (Projects.id eq projectId) and (Projects.status neq "archived") }.count() > 0
    }

    fun findProject(accountId: UUID, projectId: UUID): PaymentProject? = transaction {
        Projects.selectAll().where { (Projects.id eq projectId) and (Projects.accountId eq accountId) }.singleOrNull()?.toPaymentProject()
    }

    fun setProjectStatus(accountId: UUID, projectId: UUID, status: String, reason: String?): PaymentProject? = transaction {
        require(status in setOf("active", "suspended", "archived")) { "Project status must be active, suspended, or archived" }
        val changed = Projects.update({ (Projects.id eq projectId) and (Projects.accountId eq accountId) }) {
            it[Projects.status] = status; it[Projects.statusReason] = reason?.trim()?.takeIf(String::isNotBlank)?.take(256)
        }
        if (changed == 0) return@transaction null
        val siteState = if (status == "active") "active" else "suspended"
        Sites.update({ (Sites.projectId eq projectId) and (Sites.entitlementState neq "disabled_by_admin") }) {
            it[Sites.entitlementState] = siteState
            it[Sites.stateReason] = if (status == "active") "project_reactivated" else "project_$status"
            it[Sites.stateChangedAt] = LocalDateTime.now(ZoneOffset.UTC)
            it[Sites.stateEffectiveAt] = if (status == "active") null else LocalDateTime.now(ZoneOffset.UTC)
            it[Sites.applyStatus] = nextApplyStatus(); it[Sites.lastApplyError] = null
        }
        Projects.selectAll().where { Projects.id eq projectId }.single().toPaymentProject()
    }

    fun listCustomers(accountId: UUID): List<PaymentCustomer> = transaction {
        Customers.selectAll().where { Customers.accountId eq accountId }.orderBy(Customers.createdAt, SortOrder.DESC).map { it.toPaymentCustomer() }
    }

    fun findCustomer(accountId: UUID, customerId: UUID): PaymentCustomer? = transaction {
        Customers.selectAll().where { (Customers.accountId eq accountId) and (Customers.id eq customerId) }.singleOrNull()?.toPaymentCustomer()
    }

    fun updateCustomer(accountId: UUID, customerId: UUID, displayName: String?): PaymentCustomer? = transaction {
        val changed = Customers.update({ (Customers.accountId eq accountId) and (Customers.id eq customerId) }) {
            it[Customers.displayName] = displayName?.trim()?.takeIf(String::isNotBlank)?.take(200)
            it[Customers.updatedAt] = LocalDateTime.now(ZoneOffset.UTC)
        }
        if (changed == 0) null else Customers.selectAll().where { Customers.id eq customerId }.single().toPaymentCustomer()
    }

    fun customerPayments(accountId: UUID, customerId: UUID, limit: Int = 100): List<Payment> = transaction {
        Payments.selectAll().where { (Payments.accountId eq accountId) and (Payments.customerId eq customerId) }
            .orderBy(Payments.createdAt, SortOrder.DESC).limit(limit.coerceIn(1, 200)).map { it.toPayment() }
    }

    fun projectsForCustomer(accountId: UUID, customerId: UUID): List<PaymentProject> = transaction {
        val projectIds = Payments.select(Payments.projectId).where {
            (Payments.accountId eq accountId) and (Payments.customerId eq customerId) and Payments.projectId.isNotNull()
        }.withDistinct().mapNotNull { it[Payments.projectId] }
        if (projectIds.isEmpty()) emptyList() else Projects.selectAll().where {
            (Projects.accountId eq accountId) and (Projects.id inList projectIds)
        }.orderBy(Projects.name).map { it.toPaymentProject() }
    }

    fun customersForProject(accountId: UUID, projectId: UUID): List<PaymentCustomer> = transaction {
        val customerIds = Payments.select(Payments.customerId).where {
            (Payments.accountId eq accountId) and (Payments.projectId eq projectId) and Payments.customerId.isNotNull()
        }.withDistinct().mapNotNull { it[Payments.customerId] }
        if (customerIds.isEmpty()) emptyList() else Customers.selectAll().where {
            (Customers.accountId eq accountId) and (Customers.id inList customerIds)
        }.orderBy(Customers.updatedAt, SortOrder.DESC).map { it.toPaymentCustomer() }
    }

    fun projectPaymentTotals(accountId: UUID, projectId: UUID): PaymentTotals = transaction {
        val rows = Payments.selectAll().where { (Payments.accountId eq accountId) and (Payments.projectId eq projectId) }.toList()
        PaymentTotals(rows.size.toLong(), rows.fold(BigDecimal.ZERO) { sum, row -> sum + row[Payments.amount] },
            rows.count { it[Payments.status] == "succeeded" }.toLong(), rows.count { it[Payments.status] == "pending" }.toLong(),
            rows.count { it[Payments.status] == "failed" }.toLong(), rows.count { it[Payments.status] == "reversed" }.toLong(),
            rows.groupBy { it[Payments.currency] }.mapValues { (_, currencyRows) -> currencyRows.fold(BigDecimal.ZERO) { sum, row -> sum + row[Payments.amount] } },
            rows.filter { it[Payments.status] == "succeeded" }.groupBy { it[Payments.currency] }.mapValues { (_, currencyRows) -> currencyRows.fold(BigDecimal.ZERO) { sum, row -> sum + row[Payments.amount] } })
    }

    private fun ResultRow.toPaymentProject() = PaymentProject(
        this[Projects.id], this[Projects.accountId], this[Projects.siteId], this[Projects.name], this[Projects.billingReference],
        this[Projects.createdAt].toInstant(ZoneOffset.UTC), this[Projects.status], this[Projects.statusReason]
    )

    private fun ResultRow.toPaymentCustomer() = PaymentCustomer(
        this[Customers.id], this[Customers.accountId], this[Customers.displayName], this[Customers.email], this[Customers.phoneNumber],
        this[Customers.createdAt].toInstant(ZoneOffset.UTC), this[Customers.updatedAt].toInstant(ZoneOffset.UTC)
    )

    override fun processProviderNotification(record: NewPaymentEvent, event: ProviderPaymentEvent?): String = transaction {
        val id = UUID.randomUUID()
        try {
            PaymentEvents.insert {
                it[PaymentEvents.id] = id
                it[PaymentEvents.provider] = record.provider
                it[PaymentEvents.eventType] = record.eventType
                it[PaymentEvents.deduplicationKey] = record.deduplicationKey
                it[PaymentEvents.providerReference] = record.reference
                it[PaymentEvents.rawPayload] = record.rawPayload
                it[PaymentEvents.normalizedStatus] = event?.status?.name?.lowercase()
                it[PaymentEvents.normalizedAmount] = event?.amount
                it[PaymentEvents.normalizedCurrency] = event?.currency
                it[PaymentEvents.providerTransactionId] = event?.providerTransactionId
                it[PaymentEvents.attemptCount] = 1
                it[PaymentEvents.status] = "received"
            }
        } catch (error: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            if ((error.cause as? org.postgresql.util.PSQLException)?.sqlState == "23505") return@transaction "duplicate"
            throw error
        }
        val applied = if (event == null) true else applyNormalizedEvent(event, record.eventType.take(64))
        PaymentEvents.update({ PaymentEvents.id eq id }) {
            it[PaymentEvents.status] = if (applied) "processed" else "failed"
            it[PaymentEvents.processedAt] = LocalDateTime.now(ZoneOffset.UTC)
            if (!applied) it[PaymentEvents.processingError] = "Payment not found or provider amount/currency did not match"
        }
        if (applied) "processed" else "rejected"
    }

    override fun listFailedEvents(limit: Int): List<PaymentEvent> = listEvents("failed", limit, 0)

    private fun applyNormalizedEvent(event: ProviderPaymentEvent, source: String): Boolean {
        val row = Payments.selectAll().where { (Payments.provider eq event.provider) and ((Payments.providerReference eq event.reference) or (Payments.gatewayReference eq event.reference) or (Payments.providerRequestId eq event.reference)) }.singleOrNull() ?: return false
        val previous = row[Payments.status]
        val current = runCatching { PaymentStatus.valueOf(previous.uppercase()) }.getOrDefault(PaymentStatus.PENDING)
        val next = event.status.name.lowercase()
        val validAmount = event.status != PaymentStatus.SUCCEEDED || (event.amount != null && event.currency != null && event.amount.compareTo(row[Payments.amount]) == 0 && event.currency.equals(row[Payments.currency], true))
        if (previous == next) {
            Payments.update({ Payments.id eq row[Payments.id] }) {
                if (event.providerTransactionId != null) it[Payments.providerTransactionId] = event.providerTransactionId
                if (event.paidAt != null) it[Payments.paidAt] = LocalDateTime.ofInstant(event.paidAt, ZoneOffset.UTC)
                it[Payments.nextReconciliationAt] = null
                it[Payments.reconciliationClaimedAt] = null
                it[Payments.lastProviderError] = null
            }
            when (event.status) {
                PaymentStatus.SUCCEEDED -> setProjectEntitlement(row[Payments.projectId], "active", "payment_succeeded:${row[Payments.gatewayReference]}")
                PaymentStatus.REVERSED -> setProjectEntitlement(row[Payments.projectId], "suspended", "payment_reversed:${row[Payments.gatewayReference]}")
                else -> Unit
            }
            return true
        }
        if (!validAmount || !com.gateway.payment.domain.PaymentStateTransitions.allows(current, event.status)) return false
        Payments.update({ Payments.id eq row[Payments.id] }) {
            it[Payments.status] = next
            event.paidAt?.let { paid -> it[Payments.paidAt] = LocalDateTime.ofInstant(paid, ZoneOffset.UTC) }
            event.providerTransactionId?.let { tx -> it[Payments.providerTransactionId] = tx }
            it[Payments.updatedAt] = LocalDateTime.now(ZoneOffset.UTC); it[Payments.nextReconciliationAt] = null; it[Payments.reconciliationClaimedAt] = null; it[Payments.lastProviderError] = null
        }
        PaymentStatusHistory.insert {
            it[PaymentStatusHistory.id] = UUID.randomUUID(); it[PaymentStatusHistory.paymentId] = row[Payments.id]
            it[PaymentStatusHistory.previousStatus] = previous; it[PaymentStatusHistory.newStatus] = next
            it[PaymentStatusHistory.eventSource] = source; it[PaymentStatusHistory.providerTransactionId] = event.providerTransactionId
            it[PaymentStatusHistory.occurredAt] = LocalDateTime.now(ZoneOffset.UTC)
        }
        when (event.status) {
            PaymentStatus.SUCCEEDED -> setProjectEntitlement(row[Payments.projectId], "active", "payment_succeeded:${row[Payments.gatewayReference]}")
            PaymentStatus.REVERSED -> setProjectEntitlement(row[Payments.projectId], "suspended", "payment_reversed:${row[Payments.gatewayReference]}")
            else -> Unit
        }
        return true
    }

    private fun setProjectEntitlement(projectId: UUID?, state: String, reason: String) {
        if (projectId == null) return
        val project = Projects.selectAll().where { Projects.id eq projectId }.singleOrNull() ?: return
        if (state == "active" && project[Projects.status] != "active") return
        Sites.update({ (Sites.projectId eq projectId) and (Sites.entitlementState neq "disabled_by_admin") and (Sites.entitlementState neq state) }) {
            it[Sites.entitlementState] = state; it[Sites.stateReason] = reason.take(128)
            it[Sites.stateChangedAt] = LocalDateTime.now(ZoneOffset.UTC)
            it[Sites.stateEffectiveAt] = if (state == "active") null else LocalDateTime.now(ZoneOffset.UTC)
            it[Sites.applyStatus] = nextApplyStatus(); it[Sites.lastApplyError] = null
        }
    }

    private fun nextApplyStatus(): String = if (com.gateway.config.GatewayConfig.nginxEnabled) "queued" else "disabled"

    private fun ResultRow.toPaymentEvent() = PaymentEvent(this[PaymentEvents.id], this[PaymentEvents.provider], this[PaymentEvents.eventType], this[PaymentEvents.deduplicationKey], this[PaymentEvents.providerReference], this[PaymentEvents.normalizedStatus]?.uppercase()?.let { runCatching { PaymentStatus.valueOf(it) }.getOrNull() }, this[PaymentEvents.normalizedAmount], this[PaymentEvents.normalizedCurrency], this[PaymentEvents.providerTransactionId], this[PaymentEvents.rawPayload], this[PaymentEvents.status], this[PaymentEvents.receivedAt].toInstant(ZoneOffset.UTC), this[PaymentEvents.processedAt]?.toInstant(ZoneOffset.UTC), this[PaymentEvents.processingError], this[PaymentEvents.attemptCount])

    fun getEvent(eventId: UUID): PaymentEvent? = transaction { PaymentEvents.selectAll().where { PaymentEvents.id eq eventId }.singleOrNull()?.toPaymentEvent() }

    fun audit(action: String, targetId: String?, details: String? = null) = transaction {
        OperationsAudit.insert {
            it[OperationsAudit.id] = UUID.randomUUID(); it[OperationsAudit.action] = action.take(100); it[OperationsAudit.targetId] = targetId?.take(128)
            it[OperationsAudit.details] = details?.take(1000); it[OperationsAudit.occurredAt] = LocalDateTime.now(ZoneOffset.UTC)
        }
    }

    private fun ResultRow.toPayment() = Payment(
        id = this[Payments.id], accountId = this[Payments.accountId], provider = this[Payments.provider],
        providerReference = this[Payments.providerReference], gatewayReference = this[Payments.gatewayReference] ?: this[Payments.providerReference], amount = this[Payments.amount], currency = this[Payments.currency],
        status = runCatching { PaymentStatus.valueOf(this[Payments.status].uppercase()) }.getOrDefault(PaymentStatus.PENDING),
        checkoutUrl = this[Payments.checkoutUrl], createdAt = this[Payments.createdAt].toInstant(ZoneOffset.UTC),
        paidAt = this[Payments.paidAt]?.toInstant(ZoneOffset.UTC), projectId = this[Payments.projectId], providerTransactionId = this[Payments.providerTransactionId], providerRequestId = this[Payments.providerRequestId], customerPhone = this[Payments.customerPhone], nextReconciliationAt = this[Payments.nextReconciliationAt]?.toInstant(ZoneOffset.UTC), reconciliationAttempts = this[Payments.reconciliationAttempts], lastProviderError = this[Payments.lastProviderError], requestEmail = this[Payments.requestEmail], description = this[Payments.description], customerId = this[Payments.customerId]
    )
}
