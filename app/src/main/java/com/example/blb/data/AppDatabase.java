package com.example.blb.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
        entities = {Account.class, Novel.class, Chapter.class, Purchase.class, CheckInLog.class},
        version = 2,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract AccountDao accountDao();

    public abstract SubscriptionDao subscriptionDao();

    public abstract CheckInDao checkInDao();
}
