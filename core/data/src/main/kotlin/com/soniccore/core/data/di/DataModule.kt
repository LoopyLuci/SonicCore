package com.soniccore.core.data.di

import android.content.Context
import com.soniccore.core.data.database.AutomationRuleDao
import com.soniccore.core.data.database.DeviceDao
import com.soniccore.core.data.database.EqPresetDao
import com.soniccore.core.data.database.ListeningHistoryDao
import com.soniccore.core.data.database.ProfileDao
import com.soniccore.core.data.database.SonicCoreDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SonicCoreDatabase =
        SonicCoreDatabase.build(context)

    @Provides
    fun provideProfileDao(db: SonicCoreDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideDeviceDao(db: SonicCoreDatabase): DeviceDao = db.deviceDao()

    @Provides
    fun provideEqPresetDao(db: SonicCoreDatabase): EqPresetDao = db.eqPresetDao()

    @Provides
    fun provideAutomationRuleDao(db: SonicCoreDatabase): AutomationRuleDao = db.automationRuleDao()

    @Provides
    fun provideListeningHistoryDao(db: SonicCoreDatabase): ListeningHistoryDao = db.listeningHistoryDao()
}
