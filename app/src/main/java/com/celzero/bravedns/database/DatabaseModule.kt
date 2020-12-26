package com.celzero.bravedns.database

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

object DatabaseModule {
    private val databaseModule = module {
        single { AppDatabase.buildDatabase(androidContext()) }
        single {
            RefreshDatabase(
                androidContext(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get()
            )
        }
    }
    private val daoModule = module {
        single<AppInfoDAO> { get<AppDatabase>().appInfoDAO() }
        single<BlockedConnectionsDAO> { get<AppDatabase>().blockedConnectionsDAO() }
        single<CategoryInfoDAO> { get<AppDatabase>().categoryInfoDAO() }
        single<ConnectionTrackerDAO> { get<AppDatabase>().connectionTrackerDAO() }
        single<DNSCryptEndpointDAO> { get<AppDatabase>().dnsCryptEndpointDAO() }
        single<DNSCryptRelayEndpointDAO> { get<AppDatabase>().dnsCryptRelayEndpointDAO() }
        single<DNSLogDAO> { get<AppDatabase>().dnsLogDAO() }
        single<DNSProxyEndpointDAO> { get<AppDatabase>().dnsProxyEndpointDAO() }
        single<DoHEndpointDAO> { get<AppDatabase>().dohEndpointsDAO() }
        single<ProxyEndpointDAO> { get<AppDatabase>().proxyEndpointDAO() }
    }
    private val repositoryModule = module {
        single { AppInfoRepository(get()) }
        single { BlockedConnectionsRepository(get()) }
        single { CategoryInfoRepository(get()) }
        single { ConnectionTrackerRepository(get()) }
        single { DNSCryptEndpointRepository(get()) }
        single { DNSCryptRelayEndpointRepository(get()) }
        single { DNSProxyEndpointRepository(get()) }
        single { DNSLogRepository(get()) }
        single { DNSProxyEndpointRepository(get()) }
        single { DoHEndpointRepository(get()) }
        single { ProxyEndpointRepository(get()) }
    }


    val modules = listOf(databaseModule, daoModule, repositoryModule)
}