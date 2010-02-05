#!/usr/bin/env groovy

// You will want to have the following in your classpath

import groovy.sql.Sql

HOME_DIR = System.getProperty('user.home')
POSTGRES_JAR = System.getProperty("pg.jar") ?: "file:///${HOME_DIR}/.m2/repository/postgresql/postgresql/8.3-603.jdbc4/postgresql-8.3-603.jdbc4.jar"
JODA_JAR = System.getProperty("joda.jar") ?: "file:///${HOME_DIR}/.m2/repository/joda-time/joda-time/1.6/joda-time-1.6.jar"
JDBC_URL = System.getProperty("jdbc.url") ?: "jdbc:postgresql://localhost/blackbox"
JDBC_USER = System.getProperty("jdbc.user") ?: "blackbox"
JDBC_PASSWORD = System.getProperty("jdbc.password") ?: "blackbox"

def addToClassPath(path) {
  this.class.classLoader.rootLoader.addURL new URL(path)
}

addToClassPath POSTGRES_JAR
addToClassPath JODA_JAR

def isCompleted(task) {
  result = 0
  sql.eachRow("select count(*) as count from bb_db_meta_data where key = ${task.name()}") {row ->
    result = row.count
  }
  return result > 0
}

def markCompleted(task) {
  def now = new java.sql.Date(System.currentTimeMillis())
  try {
    sql.execute("insert into bb_db_meta_data (created, key) values (${now}, ${task.name()})")
  }
  catch (Throwable t) {
    //ignore
  }
}

// initial tasks
try {
  sql.execute('alter table bb_db_meta_data drop column guid');
  sql.execute('alter table bb_db_meta_data drop column version')
  sql.execute('alter table bb_db_meta_data drop column modified')
}
catch (Throwable t) {
  // ignore
}

interface Task {
  String name()

  void execute(sql)
}

tasks = []
tasks.metaClass.addTask = {task ->
  Task t = task as Task
  add t
}

tasks.addTask([
        name: {"fix-media-recipients"},
        execute: {sql ->
          sql.asTransaction {t ->
            t.execute 'alter table bb_media_recipient drop constraint fkcac3317f300cddac'
            t.execute 'alter table bb_media_recipient rename column messagemetadata_guid to media_guid'
            t.execute 'alter table bb_media_recipient add constraint recipient_media_guid foreign key (media_guid) references bb_media_meta_data (guid)'
          }
        }
])

// fix message recipients
tasks.addTask([
        name: {"fix-message-recipients"},
        execute: {sql ->
          sql.asTransaction {t ->

            // move the data from the bb_message_meta_data_bb_message_recipient content into the bb_message_recipient.messagemetadata_guid table
            t.eachRow('select bb_message_meta_data_guid as metaDataGuid, recipients_guid as recipientsGuid from bb_message_meta_data_bb_message_recipient') {row ->
              t.execute "update bb_message_recipient set messagemetadata_guid = ${row.metaDataGuid} where guid = ${row.recipientsGuid}"
            }
            t.execute 'drop table bb_message_meta_data_bb_message_recipient'
          }
        }

])

// fix birthdays
tasks.addTask([
        name: {"fix-birthdays"},
        execute: {sql ->

          sql.asTransaction {t ->

            t.execute 'create temporary table birthday_tmp (guid varchar(255), birthday timestamptz)'

            t.eachRow('select guid,birthday from bb_profile') {row ->
              birthday = row.birthday
              if (birthday != null) {
                instream = new ObjectInputStream(new ByteArrayInputStream(birthday))
                jodaDate = instream.readObject()
                sqlDate = new java.sql.Date(jodaDate.millis)
                t.execute "insert into birthday_tmp (guid, birthday) values (${row.guid}, ${sqlDate})"
              }
            }
            t.execute 'alter table bb_profile drop column birthday'
            t.execute 'alter table bb_profile add column birthday timestamptz'

            t.eachRow('select guid,birthday from birthday_tmp') {row ->
              t.execute "update bb_profile set birthday = ${row.birthday} where guid = ${row.guid}";
            }
          }
        }
])

tasks.addTask([
        name: {"fix-transaction-sequence"},
        execute: {sql ->
          sql.execute 'create sequence transaction_sequence start 1000'
          sql.execute 'drop sequence hibernate_sequence'
        }
])

tasks.addTask([
        name: {"billing-info-add-name"},
        execute: {sql ->
          sql.execute 'alter table bb_billing_info add column firstName varchar(100)'
          sql.execute 'alter table bb_billing_info add column lastName varchar(100)'
        }
])

// execute all defined tasks
sql = Sql.newInstance(JDBC_URL, JDBC_USER, JDBC_PASSWORD, "org.postgresql.Driver")

sql.metaClass.asTransaction = {c ->
  withTransaction {conn ->
    c.call new Sql(conn)
  }
}

try {
  tasks.each {task ->
    if (!isCompleted(task)) {
      try {
        task.execute sql
      }
      catch (Throwable e) {
        System.out.println(e.message)
      }
      finally {
        markCompleted task
      }
    }
  }
} finally {
  sql.close()
}


