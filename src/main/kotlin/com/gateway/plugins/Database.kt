package com.gateway.plugins

import com.gateway.config.GatewayConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

object DatabaseFactory {
    private var dataSource: HikariDataSource? = null

    fun init() {
        if (dataSource != null) return
        val config = HikariConfig().apply {
            jdbcUrl = GatewayConfig.databaseUrl
            username = GatewayConfig.databaseUser
            password = GatewayConfig.databasePassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
        }
        val pool = HikariDataSource(config)
        try {
            Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate()
            Database.connect(pool)
            dataSource = pool
        } catch (error: Exception) {
            pool.close()
            throw error
        }
    }

    fun close() {
        dataSource?.close()
        dataSource = null
    }

    fun ready(): Boolean = dataSource?.connection?.use { connection -> connection.isValid(2) } == true
}

fun Application.configureDatabase() {
    DatabaseFactory.init()
    monitor.subscribe(ApplicationStopped) { DatabaseFactory.close() }
}
