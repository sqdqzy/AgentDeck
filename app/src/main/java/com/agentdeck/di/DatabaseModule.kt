package com.agentdeck.di

import android.content.Context
import androidx.room.Room
import com.agentdeck.data.db.AgentDeckDatabase
import com.agentdeck.data.db.dao.AuditLogDao
import com.agentdeck.data.db.dao.SiteAdapterDao
import com.agentdeck.data.db.dao.SiteCardDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AgentDeckDatabase =
        Room.databaseBuilder(context, AgentDeckDatabase::class.java, AgentDeckDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideSiteCardDao(database: AgentDeckDatabase): SiteCardDao = database.siteCardDao()

    @Provides
    fun provideSiteAdapterDao(database: AgentDeckDatabase): SiteAdapterDao = database.siteAdapterDao()

    @Provides
    fun provideAuditLogDao(database: AgentDeckDatabase): AuditLogDao = database.auditLogDao()
}
