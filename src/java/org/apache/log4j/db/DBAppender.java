/*
 * Copyright 1999,2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.log4j.db;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.db.dialect.SQLDialect;
import org.apache.log4j.db.dialect.Util;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.spi.LocationInfo;
import org.apache.log4j.spi.LoggingEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.Iterator;
import java.util.Set;


/**
 *
 * @author Ceki G&uuml;lc&uuml;
 *
 */
public class DBAppender extends AppenderSkeleton {
  ConnectionSource connectionSource;
  SQLDialect sqlDialect;
  boolean locationInfo = false;

  public void activateOptions() {
    LogLog.debug("DBAppender.activateOptions called");

    if (connectionSource == null) {
      throw new IllegalStateException(
        "DBAppender cannot function without a connection source");
    }

    sqlDialect = Util.getDialectFromCode(connectionSource.getSQLDialectCode());

    if (sqlDialect == null) {
      throw new IllegalStateException(
        "DBAppender cannot function without a determined SQL dialect");
    }
  }

  /**
   * @return Returns the connectionSource.
   */
  public ConnectionSource getConnectionSource() {
    return connectionSource;
  }

  /**
   * @param connectionSource The connectionSource to set.
   */
  public void setConnectionSource(ConnectionSource connectionSource) {
    LogLog.debug("setConnectionSource called for DBAppender");
    this.connectionSource = connectionSource;
  }

  protected void append(LoggingEvent event) {
    try {
      Connection connection = connectionSource.getConnection();
      connection.setAutoCommit(false);


      //      sequence_number BIGINT NOT NULL,
      //      timestamp         BIGINT NOT NULL,
      //      rendered_message  TEXT NOT NULL,
      //      logger_name       VARCHAR(254) NOT NULL,
      //      level_string      VARCHAR(254) NOT NULL,
      //      ndc               TEXT,
      //      thread_name       VARCHAR(254),
      //      id                INT NOT NULL AUTO_INCREMENT PRIMARY KEY
      StringBuffer sql = new StringBuffer();
      sql.append("INSERT INTO logging_event (");
      sql.append("sequence_number, ");
      sql.append("timestamp, ");
      sql.append("rendered_message, ");
      sql.append("logger_name, ");
      sql.append("level_string, ");
      sql.append("ndc, ");
      sql.append("thread_name, ");
      sql.append("reference_flag, ");
      sql.append("caller_filename, ");
      sql.append("caller_class, ");
      sql.append("caller_method, ");
      sql.append("caller_line) ");

      sql.append(" VALUES (?, ?, ? ,?, ?, ?, ?, ?, ?, ?, ?, ?)");


      PreparedStatement insertStatement =
        connection.prepareStatement(sql.toString());
      insertStatement.setLong(1, event.getSequenceNumber());
      insertStatement.setLong(2, event.getTimeStamp());
      insertStatement.setString(3, event.getRenderedMessage());
      insertStatement.setString(4, event.getLoggerName());
      insertStatement.setString(5, event.getLevel().toString());
      insertStatement.setString(6, event.getNDC());
      insertStatement.setString(7, event.getThreadName());
      insertStatement.setShort(8, DBHelper.computeReferenceMask(event));

      LocationInfo li;

      if (event.locationInformationExists() || locationInfo) {
        li = event.getLocationInformation();
      } else {
        li = LocationInfo.NA_LOCATION_INFO;
      }

      insertStatement.setString(9, li.getFileName());
      insertStatement.setString(10, li.getClassName());
      insertStatement.setString(11, li.getMethodName());
      insertStatement.setString(12, li.getLineNumber());

      int updateCount = insertStatement.executeUpdate();

      if (updateCount != 1) {
        LogLog.warn("Failed to insert loggingEvent");
      }

      Statement idStatement = connection.createStatement();
      idStatement.setMaxRows(1);

      ResultSet rs = idStatement.executeQuery(sqlDialect.getSelectInsertId());
      rs.first();

      int eventId = rs.getInt(1);


      //      LogLog.info("inserted id is " + eventId);
      //      event_id        INT NOT NULL,
      //      mapped_key        VARCHAR(254) NOT NULL,
      //      mapped_value      VARCHAR(254),
      Set propertiesKeys = event.getPropertyKeySet();

      if (propertiesKeys.size() > 0) {
        String insertPropertiesSQL =
          "INSERT INTO  logging_event_property (event_id, mapped_key, mapped_value) VALUES (?, ?, ?)";
        PreparedStatement insertPropertiesStatement =
          connection.prepareStatement(insertPropertiesSQL);

        for (Iterator i = propertiesKeys.iterator(); i.hasNext();) {
          String key = (String) i.next();
          String value = (String) event.getProperty(key);
          LogLog.info("id " + eventId + ", key " + key + ", value " + value);
          insertPropertiesStatement.setInt(1, eventId);
          insertPropertiesStatement.setString(2, key);
          insertPropertiesStatement.setString(3, value);
          insertPropertiesStatement.addBatch();
        }

        insertPropertiesStatement.executeBatch();
      }

      String[] strRep = event.getThrowableStrRep();

      if (strRep != null) {
        LogLog.info("Logging an exception");


        //        CREATE TABLE logging_event_exception (
        //          event_id         INT NOT NULL,
        //          i                SMALLINT NOT NULL,
        //          trace_line       VARCHAR(254) NOT NULL)
        String insertExceptionSQL =
          "INSERT INTO  logging_event_exception (event_id, i, trace_line) VALUES (?, ?, ?)";
        PreparedStatement insertExceptionStatement =
          connection.prepareStatement(insertExceptionSQL);

        for (short i = 0; i < strRep.length; i++) {
          insertExceptionStatement.setInt(1, eventId);
          insertExceptionStatement.setShort(2, i);
          insertExceptionStatement.setString(3, strRep[i]);
          insertExceptionStatement.addBatch();
        }

        insertExceptionStatement.executeBatch();
      }

      connection.commit();
    } catch (SQLException sqle) {
      LogLog.error("problem appending event", sqle);
    }
  }

  public void close() {
    // TODO Auto-generated method st  
  }

  /*
   * The DBAppender does not require a layout.
   */
  public boolean requiresLayout() {
    return false;
  }

  /**
   * Returns value of the <b>LocationInfo</b> property which determines whether
   * caller's location info is written to the database.
   * */
  public boolean getLocationInfo() {
    return locationInfo;
  }

  /**
   * If true, the information written to the database will include
   * caller's location information. Due to performance concerns, by default no
   * location information is written to the database.
   * */
  public void setLocationInfo(boolean locationInfo) {
    this.locationInfo = locationInfo;
  }
}