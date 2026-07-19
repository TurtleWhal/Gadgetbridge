/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.database.schema;

import android.database.sqlite.SQLiteDatabase;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.database.DBUpdateScript;
import nodomain.freeyourgadget.gadgetbridge.entities.BatteryLevelDao;

public class GadgetbridgeUpdate_137 implements DBUpdateScript {
    @Override
    public void upgradeSchema(final SQLiteDatabase db) {
        if (!DBHelper.existsColumn(BatteryLevelDao.TABLENAME, BatteryLevelDao.Properties.Voltage.columnName, db)) {
            db.execSQL("ALTER TABLE " + BatteryLevelDao.TABLENAME + " ADD COLUMN \""
                    + BatteryLevelDao.Properties.Voltage.columnName + "\" REAL NOT NULL DEFAULT -1;");
        }
        if (!DBHelper.existsColumn(BatteryLevelDao.TABLENAME, BatteryLevelDao.Properties.BatteryState.columnName, db)) {
            db.execSQL("ALTER TABLE " + BatteryLevelDao.TABLENAME + " ADD COLUMN \""
                    + BatteryLevelDao.Properties.BatteryState.columnName + "\" INTEGER NOT NULL DEFAULT 0;");
        }
    }

    @Override
    public void downgradeSchema(final SQLiteDatabase db) {
    }
}
