// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.pgm.init;

import com.google.common.base.Joiner;
import com.google.gerrit.pgm.util.ConsoleUI;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gwtorm.jdbc.JdbcExecutor;
import com.google.gwtorm.jdbc.JdbcSchema;
import com.google.gwtorm.schema.ColumnModel;
import com.google.gwtorm.schema.RelationModel;
import com.google.gwtorm.schema.java.JavaSchemaModel;
import com.google.gwtorm.schema.sql.DialectPostgreSQL;
import com.google.gwtorm.schema.sql.SqlDialect;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.gwtorm.server.StatementExecutor;
import com.google.inject.Inject;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class UpdatePrimaryKeys implements InitStep {

  private static class PrimaryKey {
    String oldNameInDb;
    List<String> cols;
  }

  private final ConsoleUI ui;

  private SchemaFactory<ReviewDb> dbFactory;
  private ReviewDb db;
  private Connection conn;
  private SqlDialect dialect;

  @Inject
  UpdatePrimaryKeys(ConsoleUI ui) {
    this.ui = ui;
  }

  @Override
  public void run() throws Exception {
  }

  @Override
  public void postRun() throws Exception {
    db = dbFactory.open();
    try {
      conn = ((JdbcSchema) db).getConnection();
      dialect = ((JdbcSchema) db).getDialect();
      Map<String, PrimaryKey> corrections = findPKUpdates();
      if (corrections.isEmpty()) {
        return;
      }

      ui.header("Wrong Primary Key Column Order Detected");
      ui.message("The following tables are affected:\n");
      ui.message("%s\n", Joiner.on(", ").join(corrections.keySet()));
      if (ui.yesno(true, "Fix primary keys column order")) {
        ui.message("fixing primary keys...\n");
        JdbcExecutor executor = new JdbcExecutor(conn);
        try {
          for (Map.Entry<String, PrimaryKey> c : corrections.entrySet()) {
            ui.message("  table: %s ... ", c.getKey());
            recreatePK(executor, c.getKey(), c.getValue());
            ui.message("done\n");
          }
          ui.message("done\n");
        } finally {
          executor.close();
        }
      }
    } finally {
      db.close();
    }
  }

  @Inject(optional = true)
  void setSchemaFactory(SchemaFactory<ReviewDb> dbFactory) {
    this.dbFactory = dbFactory;
  }

  private Map<String, PrimaryKey> findPKUpdates()
      throws OrmException, SQLException {
    Map<String, PrimaryKey> corrections = new TreeMap<>();
    ReviewDb db = dbFactory.open();
    try {
      DatabaseMetaData meta = conn.getMetaData();
      JavaSchemaModel jsm = new JavaSchemaModel(ReviewDb.class);
      for (RelationModel rm : jsm.getRelations()) {
        String tableName = rm.getRelationName();
        List<String> expectedPKCols = relationPK(rm);
        PrimaryKey actualPK = dbTablePK(meta, tableName);
        if (!expectedPKCols.equals(actualPK.cols)) {
          actualPK.cols = expectedPKCols;
          corrections.put(tableName, actualPK);
        }
      }
      return corrections;
    } finally {
      db.close();
    }
  }

  private List<String> relationPK(RelationModel rm) {
    Collection<ColumnModel> cols = rm.getPrimaryKeyColumns();
    List<String> pk = new ArrayList<>(cols.size());
    for (ColumnModel cm : cols) {
      pk.add(cm.getColumnName().toLowerCase(Locale.US));
    }
    return pk;
  }

  private PrimaryKey dbTablePK(DatabaseMetaData meta, String tableName)
      throws SQLException {
    if (meta.storesUpperCaseIdentifiers()) {
      tableName = tableName.toUpperCase();
    } else if (meta.storesLowerCaseIdentifiers()) {
      tableName = tableName.toLowerCase();
    }

    ResultSet cols = meta.getPrimaryKeys(null, null, tableName);
    try {
      PrimaryKey pk = new PrimaryKey();
      Map<Short, String> seqToName = new TreeMap<>();
      while (cols.next()) {
        seqToName.put(cols.getShort("KEY_SEQ"), cols.getString("COLUMN_NAME"));
        if (pk.oldNameInDb == null) {
          pk.oldNameInDb = cols.getString("PK_NAME");
        }
      }

      pk.cols = new ArrayList<>(seqToName.size());
      for (String name : seqToName.values()) {
        pk.cols.add(name.toLowerCase(Locale.US));
      }
      return pk;
    } finally {
      cols.close();
    }
  }

  private void recreatePK(StatementExecutor executor, String tableName,
      PrimaryKey pk) throws OrmException {
    if (pk.oldNameInDb == null) {
      ui.message("WARN: primary key for table %s didn't exist ... ", tableName);
    } else {
      if (dialect instanceof DialectPostgreSQL) {
        // postgresql doesn't support the ALTER TABLE foo DROP PRIMARY KEY form
        executor.execute("ALTER TABLE " + tableName + " DROP CONSTRAINT "
            + pk.oldNameInDb);
      } else {
        executor.execute("ALTER TABLE " + tableName + " DROP PRIMARY KEY");
      }
    }
    executor.execute("ALTER TABLE " + tableName
        + " ADD PRIMARY KEY(" + Joiner.on(",").join(pk.cols) + ")");
  }
}
