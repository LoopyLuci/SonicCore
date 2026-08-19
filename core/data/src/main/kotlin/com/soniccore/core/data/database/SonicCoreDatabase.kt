package com.soniccore.core.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class,
        DeviceEntity::class,
        EqPresetEntity::class,
        AutomationRuleEntity::class,
        ListeningSessionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SonicCoreDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun deviceDao(): DeviceDao
    abstract fun eqPresetDao(): EqPresetDao
    abstract fun automationRuleDao(): AutomationRuleDao
    abstract fun listeningHistoryDao(): ListeningHistoryDao

    companion object {
        const val NAME = "soniccore.db"

        fun build(context: Context): SonicCoreDatabase =
            Room.databaseBuilder(context, SonicCoreDatabase::class.java, NAME)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
