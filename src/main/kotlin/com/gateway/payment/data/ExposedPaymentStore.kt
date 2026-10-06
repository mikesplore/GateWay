package com.gateway.payment.data

import com.gateway.payment.domain.NewPaymentEvent
import com.gateway.payment.domain.Payment
import com.gateway.payment.domain.PaymentAccount
import com.gateway.payment.domain.MerchantApiKey
import com.gateway.payment.domain.PaymentStatus
import com.gateway.payment.domain.PaymentTotals
import com.gateway.payment.domain.PaymentEvent
import com.gateway.payment.domain.PaymentProject
import com.gateway.payment.domain.PaymentSite
import com.gateway.payment.domain.PaymentStore
import com.gateway.payment.domain.ProviderPaymentEvent
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class ExposedPaymentStore : PaymentStore, com.gateway.payment.domain.PaymentEventRecorder {
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
        val inserted = Payments.insertIgnore {
            it[Payments.id] = id
            it[Payments.accountId] = accountId
            it[Payments.provider] = "initializing"
            it[Payments.providerReference] = "reservation:$id"
            it[Payments.gatewayReference] = gatewayReference ?: "reservation:$id"
            it[Payments.projectId] = projectId
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

    override fun createSite(accountId: UUID, hostname: String): PaymentSite = transaction {
        require(Accounts.selectAll().where { Accounts.id eq accountId }.count() > 0) { "Account not found" }
        val id = UUID.randomUUID(); val now = LocalDateTime.now(ZoneOffset.UTC)
        Sites.insert { it[Sites.id] = id; it[Sites.accountId] = accountId; it[Sites.hostname] = hostname; it[Sites.createdAt] = now }
        PaymentSite(id, accountId, hostname, now.toInstant(ZoneOffset.UTC))
    }

    override fun listSites(accountId: UUID): List<PaymentSite> = transaction {
        Sites.selectAll().where { Sites.accountId eq accountId }.orderBy(Sites.createdAt, SortOrder.DESC).map { PaymentSite(it[Sites.id], it[Sites.accountId], it[Sites.hostname], it[Sites.createdAt].toInstant(ZoneOffset.UTC)) }
    }

    override fun createProject(accountId: UUID, siteId: UUID, name: String, billingReference: String?): PaymentProject = transaction {
        require(Sites.selectAll().where { (Sites.id eq siteId) and (Sites.accountId eq accountId) }.count() > 0) { "Site not found for account" }
        val id = UUID.randomUUID(); val now = LocalDateTime.now(ZoneOffset.UTC)
        Projects.insert { it[Projects.id] = id; it[Projects.accountId] = accountId; it[Projects.siteId] = siteId; it[Projects.name] = name; it[Projects.billingReference] = billingReference; it[Projects.createdAt] = now }
        PaymentProject(id, accountId, siteId, name, billingReference, now.toInstant(ZoneOffset.UTC))
    }

    override fun listProjects(accountId: UUID): List<PaymentProject> = transaction {
        Projects.selectAll().where { Projects.accountId eq accountId }.orderBy(Projects.createdAt, SortOrder.DESC).map { PaymentProject(it[Projects.id], it[Projects.accountId], it[Projects.siteId], it[Projects.name], it[Projects.billingReference], it[Projects.createdAt].toInstant(ZoneOffset.UTC)) }
    }

    override fun projectBelongsToAccount(projectId: UUID, accountId: UUID): Boolean = transaction {
        Projects.selectAll().where { (Projects.id eq projectId) and (Projects.accountId eq accountId) }.count() > 0
    }

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
        return true
    }

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
        paidAt = this[Payments.paidAt]?.toInstant(ZoneOffset.UTC), projectId = this[Payments.projectId], providerTransactionId = this[Payments.providerTransactionId], providerRequestId = this[Payments.providerRequestId], customerPhone = this[Payments.customerPhone], nextReconciliationAt = this[Payments.nextReconciliationAt]?.toInstant(ZoneOffset.UTC), reconciliationAttempts = this[Payments.reconciliationAttempts], lastProviderError = this[Payments.lastProviderError], requestEmail = this[Payments.requestEmail], description = this[Payments.description]
    )
}
