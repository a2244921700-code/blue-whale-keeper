package com.moneydafeiyu.ledger.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.moneydafeiyu.ledger.data.dao.AccountDao
import com.moneydafeiyu.ledger.data.dao.AssetSnapshotDao
import com.moneydafeiyu.ledger.data.dao.CategoryDao
import com.moneydafeiyu.ledger.data.dao.FeedbackRuleDao
import com.moneydafeiyu.ledger.data.dao.RawEvidenceDao
import com.moneydafeiyu.ledger.data.dao.RuleProfileDao
import com.moneydafeiyu.ledger.data.dao.TransactionDao
import com.moneydafeiyu.ledger.data.dao.TransactionEvidenceDao
import com.moneydafeiyu.ledger.data.entity.AccountEntity
import com.moneydafeiyu.ledger.data.entity.AssetSnapshotEntity
import com.moneydafeiyu.ledger.data.entity.CategoryEntity
import com.moneydafeiyu.ledger.data.entity.CategoryKeywordEntity
import com.moneydafeiyu.ledger.data.entity.FeedbackRuleEntity
import com.moneydafeiyu.ledger.data.entity.RawEvidenceEntity
import com.moneydafeiyu.ledger.data.entity.RuleProfileEntity
import com.moneydafeiyu.ledger.data.entity.TransactionEntity
import com.moneydafeiyu.ledger.data.entity.TransactionEvidenceEntity
import java.io.File

@Database(
    entities = [
        RawEvidenceEntity::class,
        TransactionEntity::class,
        TransactionEvidenceEntity::class,
        CategoryEntity::class,
        CategoryKeywordEntity::class,
        AccountEntity::class,
        FeedbackRuleEntity::class,
        RuleProfileEntity::class,
        AssetSnapshotEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class MoneyDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun rawEvidenceDao(): RawEvidenceDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun feedbackRuleDao(): FeedbackRuleDao
    abstract fun ruleProfileDao(): RuleProfileDao
    abstract fun assetSnapshotDao(): AssetSnapshotDao
    abstract fun transactionEvidenceDao(): TransactionEvidenceDao

    companion object {
        private const val NAME = "moneydafeiyu.db"

        @Volatile
        private var instance: MoneyDatabase? = null

        /**
         * 1 → 2：新增「当前家当」快照表。
         * 老用户的账单数据必须原样保留，所以只加表、不动老表。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `asset_snapshots` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`amountCents` INTEGER NOT NULL, " +
                        "`recordedAt` INTEGER NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_asset_snapshots_recordedAt` " +
                        "ON `asset_snapshots` (`recordedAt`)"
                )
            }
        }

        /**
         * 2 → 3：给反馈规则加音效字段。
         * 老用户的规则已经在库里了，只加列不动数据。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `feedback_rules` ADD COLUMN `soundName` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): MoneyDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /**
         * 注意：WAL 必须通过 builder 的 setJournalMode 开启。
         * 早期版本在 onOpen 回调里用 execSQL("PRAGMA journal_mode = WAL")，
         * 那个位置处于 SQLiteOpenHelper 的打开流程中，会导致打开失败 —— 已移除。
         */
        private fun build(context: Context): MoneyDatabase =
            Room.databaseBuilder(context, MoneyDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** 调试用：原始文本导出，配合规则回放做离线分析 */
        fun rawTextDumpFile(context: Context): File =
            File(context.filesDir, "evidence_dump.jsonl")
    }
}
