package com.alice.components.database.providers;

import com.alice.components.database.helpers.AliceNoSQLDatabaseHelper;

/**
 * Created by Sergey Nalivko.
 * email: snalivko93@gmail.com
 */
public abstract class AliceNoSQLProvider extends AliceContentProvider {

    private static final String TAG = AliceNoSQLProvider.class.getSimpleName();

    @Override
    protected abstract AliceNoSQLDatabaseHelper createDatabaseHelper();
}