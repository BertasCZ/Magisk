package com.topjohnwu.magisk.database;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.topjohnwu.magisk.MagiskManager;
import com.topjohnwu.magisk.superuser.Policy;
import com.topjohnwu.magisk.superuser.SuLogEntry;
import com.topjohnwu.magisk.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SuDatabaseHelper extends SQLiteOpenHelper {

    private static final int DATABASE_VER = 2;
    private static final String POLICY_TABLE = "policies";
    private static final String LOG_TABLE = "logs";
    private static final String SETTINGS_TABLE = "settings";

    private MagiskManager magiskManager;
    private PackageManager pm;

    public SuDatabaseHelper(Context context) {
        super(context, "su.db", null, DATABASE_VER);
        magiskManager = Utils.getMagiskManager(context);
        pm = context.getPackageManager();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        onUpgrade(db, 0, DATABASE_VER);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 0) {
            createTables(db);
            oldVersion = 2;
        }
        if (oldVersion == 1) {
            // We're dropping column app_name, rename and re-construct table
            db.execSQL("ALTER TABLE " + POLICY_TABLE + " RENAME TO " + POLICY_TABLE + "_old");

            // Create the new tables
            createTables(db);

            // Migrate old data to new tables
            db.execSQL(
                    "INSERT INTO " + POLICY_TABLE + " SELECT " +
                    "uid, package_name, policy, until, logging, notification " +
                    "FROM " + POLICY_TABLE + "_old");
            db.execSQL("DROP TABLE " + POLICY_TABLE + "_old");

            File oldDB = magiskManager.getDatabasePath("sulog.db");
            if (oldDB.exists()) {
                migrateLegacyLogList(oldDB, db);
                magiskManager.deleteDatabase("sulog.db");
            }
            ++oldVersion;
        }
    }

    private void createTables(SQLiteDatabase db) {
        // Policies
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + POLICY_TABLE + " " +
                "(uid INT, package_name TEXT, policy INT, " +
                "until INT, logging INT, notification INT, " +
                "PRIMARY KEY(uid))");

        // Logs
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + LOG_TABLE + " " +
                "(from_uid INT, package_name TEXT, app_name TEXT, from_pid INT, " +
                "to_uid INT, action INT, time INT, command TEXT)");

        // Settings
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + SETTINGS_TABLE + " " +
                "(key TEXT, value INT, PRIMARY KEY(key))");
    }

    public void deletePolicy(Policy policy) {
        deletePolicy(policy.packageName);
    }

    public void deletePolicy(String pkg) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(POLICY_TABLE, "package_name=?", new String[] { pkg });
        db.close();
    }

    public void deletePolicy(int uid) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(POLICY_TABLE, "uid=?", new String[]{String.valueOf(uid)});
        db.close();
    }

    public Policy getPolicy(int uid) {
        Policy policy = null;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(POLICY_TABLE, null, "uid=?", new String[] { String.valueOf(uid) }, null, null, null)) {
            if (c.moveToNext()) {
                policy = new Policy(c, pm);
            }
        } catch (PackageManager.NameNotFoundException e) {
            deletePolicy(uid);
            return null;
        }
        db.close();
        return policy;
    }

    public Policy getPolicy(String pkg) {
        Policy policy = null;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(POLICY_TABLE, null, "package_name=?", new String[] { pkg }, null, null, null)) {
            if (c.moveToNext()) {
                policy = new Policy(c, pm);
            }
        } catch (PackageManager.NameNotFoundException e) {
            deletePolicy(pkg);
            return null;
        }
        db.close();
        return policy;
    }

    public void addPolicy(Policy policy) {
        SQLiteDatabase db = getWritableDatabase();
        db.replace(POLICY_TABLE, null, policy.getContentValues());
        db.close();
    }

    public void updatePolicy(Policy policy) {
        SQLiteDatabase db = getWritableDatabase();
        db.update(POLICY_TABLE, policy.getContentValues(), "package_name=?",
                new String[] { policy.packageName });
        db.close();
    }

    public List<Policy> getPolicyList(PackageManager pm) {
        List<Policy> ret = new ArrayList<>();
        SQLiteDatabase db = getWritableDatabase();
        Policy policy;
        // Clear outdated policies
        db.delete(POLICY_TABLE, "until > 0 AND until < ?",
                new String[] { String.valueOf(System.currentTimeMillis()) });
        try (Cursor c = db.query(POLICY_TABLE, null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                policy = new Policy(c, pm);
                ret.add(policy);
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        db.close();
        Collections.sort(ret);
        return ret;
    }

    private List<SuLogEntry> getLogList(SQLiteDatabase db, String selection) {
        List<SuLogEntry> ret = new ArrayList<>();
        // Clear outdated logs
        db.delete(LOG_TABLE, "time < ?", new String[] { String.valueOf(
                System.currentTimeMillis() / 1000 - magiskManager.suLogTimeout * 86400) });
        try (Cursor c = db.query(LOG_TABLE, null, selection, null, null, null, "time DESC")) {
            while (c.moveToNext()) {
                ret.add(new SuLogEntry(c));
            }
        }
        db.close();
        return ret;
    }

    private void migrateLegacyLogList(File oldDB, SQLiteDatabase newDB) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(oldDB.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        List<SuLogEntry> logs = getLogList(db, null);
        for (SuLogEntry log : logs) {
            newDB.insert(LOG_TABLE, null, log.getContentValues());
        }
    }

    public List<SuLogEntry> getLogList() {
        return getLogList(null);
    }

    public List<SuLogEntry> getLogList(String selection) {
        return getLogList(getWritableDatabase(), selection);
    }

    public void addLog(SuLogEntry log) {
        SQLiteDatabase db = getWritableDatabase();
        db.insert(LOG_TABLE, null, log.getContentValues());
        db.close();
    }

    public void clearLogs() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(LOG_TABLE, null, null);
        db.close();
    }
}