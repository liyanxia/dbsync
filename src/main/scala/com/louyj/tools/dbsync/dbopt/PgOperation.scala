package com.louyj.tools.dbsync.dbopt

import java.nio.charset.StandardCharsets

import com.google.common.hash.Hashing
import com.louyj.tools.dbsync.config.{DatabaseConfig, SyncConfig}
import com.louyj.tools.dbsync.sync.{SyncData, SyncDataModel}
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/**
 *
 * Create at 2020/8/24 9:43<br/>
 *
 * @author Louyj<br/>
 */

class PgOperation extends DbOperation {

  val logger = LoggerFactory.getLogger(getClass)

  override def name(): String = "postgresql"

  override def pollBatch(jdbcTemplate: JdbcTemplate, dbConfig: DatabaseConfig, batch: Int, offset: Long): List[SyncDataModel] = {
    val sql =
      s"""
      select * from ${dbConfig.sysSchema}.sync_data t1
      left join ${dbConfig.sysSchema}.sync_data_status t1
      on t1.id=t2."dataId"
      where t2.status is null and t1.id > $offset
      order by t1.id
      limit $batch
    """
    jdbcTemplate.queryForList(sql, classOf[SyncDataModel]).asScala.toList
  }

  override def batchInsertSql(syncData: SyncData, sourceKeys: String, fieldBuffer: ListBuffer[String], valueBuffer: ListBuffer[AnyRef]): String = {
    s"""
            insert into \"${syncData.schema}\".\"${syncData.table}\"
            (${fieldBuffer.mkString(",")}})
            values
            (${(for (_ <- valueBuffer.indices) yield "?").mkString(",")}})
            ON CONFLICT ($sourceKeys}) DO NOTHING;
          """
  }

  override def batchUpdateSql(syncData: SyncData, fieldBuffer: ListBuffer[String], whereBuffer: ListBuffer[String]): String = {
    s"""
            update from "${syncData.schema}"."${syncData.table}"
            set ${fieldBuffer.mkString(",")}
            where ${whereBuffer.mkString(",")}
          """
  }

  override def batchDeleteSql(syncData: SyncData, whereBuffer: ListBuffer[String]): String = {
    s"""
            delete from "${syncData.schema}"."${syncData.table}"
            where ${whereBuffer.mkString(",")}
          """
  }

  override def batchAckSql(dbConfig: DatabaseConfig): String = {
    s"""
          insert into ${dbConfig.sysSchema}.sync_data_status ("dataId",status,message) values (?,?,?);
    """
  }

  override def buildInsertTrigger(dbName: String, sysSchema: String, jdbcTemplate: JdbcTemplate, syncConfig: SyncConfig): Unit = {
    val content =
      """
        DROP FUNCTION IF EXISTS {{sourceSchema}}.{{insertFunction}};
        CREATE OR REPLACE FUNCTION {{sourceSchema}}.{{insertFunction}}()
         RETURNS trigger
         LANGUAGE plpgsql
        AS $function$
        begin
          if {{insertCondition}} and 1=1 then
            insert into {{sysSchema}}.sync_data ("sourceDb","targetDb","schema","table","operation","data") values ('{{sourceDb}}','{{targetDb}}','{{sourceSchema}}','{{sourceTable}}','I',row_to_json(NEW));
          end if;
        return null;
        end;
        $function$;

        DROP TRIGGER IF EXISTS {{insertTrigger}} ON {{sourceSchema}}.{{sourceTable}};
        CREATE TRIGGER {{insertTrigger}}
        AFTER INSERT ON {{sourceSchema}}.{{sourceTable}}
        FOR EACH ROW
        EXECUTE PROCEDURE {{sourceSchema}}.{{insertFunction}}();
      """
    val insertTrigger = s"sync_insert_trigger"
    val insertFunction = s"sync_${syncConfig.sourceSchema}.${syncConfig.sourceTable}_insert"
    val insertCondition = if (syncConfig.insertCondition == null) "1=1" else syncConfig.insertCondition
    val sql = content.replace("{{sourceSchema}}", syncConfig.sourceSchema)
      .replace("{{sourceTable}}", syncConfig.sourceTable)
      .replace("{{insertCondition}}", insertCondition)
      .replace("{{sysSchema}}", sysSchema)
      .replace("{{sourceDb}}", syncConfig.sourceDb)
      .replace("{{targetDb}}", syncConfig.targetDb)
      .replace("{{sourceSchema}}", syncConfig.sourceSchema)
      .replace("{{sourceTable}}", syncConfig.sourceTable)
      .replace("{{insertTrigger}}", insertTrigger)
      .replace("{{insertFunction}}", insertFunction)
    val hash = Hashing.murmur3_32().newHasher.putString(sql, StandardCharsets.UTF_8).hash().toString
    if (triggerExists(jdbcTemplate, sysSchema, syncConfig.sourceSchema, syncConfig.sourceTable, insertTrigger, hash)) {
      logger.info("Insert trigger for table {}.{}[{}] already exists and matched", syncConfig.sourceSchema, syncConfig.sourceTable, dbName)
    } else {
      logger.info("Insert trigger for table {}.{}[{}] not matched, rebuild it", syncConfig.sourceSchema, syncConfig.sourceTable, dbName)
      jdbcTemplate.execute(sql)
      saveTriggerVersion(jdbcTemplate, sysSchema, syncConfig.sourceSchema, syncConfig.sourceTable, insertTrigger, hash)
      logger.info("Insert trigger for table {}.{}[{}] updated", syncConfig.sourceSchema, syncConfig.sourceTable, dbName)
    }
  }


  override def buildUpdateTrigger(dbName: String, sysSchema: String, jdbcTemplate: JdbcTemplate, syncConfig: SyncConfig): Unit = {
    val content =
      """
         DROP FUNCTION IF EXISTS {{sourceSchema}}.{{updateFunction}};
         CREATE OR REPLACE FUNCTION {{sourceSchema}}.{{updateFunction}}()
         RETURNS trigger
         LANGUAGE plpgsql
        AS $function$
        begin
          if {{updateCondition}} and 1=1 then
            insert into {{sysSchema}}.sync_data ("sourceDb","targetDb","schema","table","operation","data") values ('{{sourceDb}}','{{targetDb}}','{{sourceSchema}}','{{sourceTable}}','U',row_to_json(NEW));
          end if;
        return null;
        end;
        $function$;

        DROP TRIGGER IF EXISTS {{updateTrigger}} ON {{sourceSchema}}.{{sourceTable}};
        CREATE TRIGGER {{updateTrigger}}
        AFTER UPDATE ON {{sourceSchema}}.{{sourceTable}}
        FOR EACH ROW
        EXECUTE PROCEDURE {{sourceSchema}}.{{updateFunction}}();
      """
    val updateTrigger = s"sync_update_trigger"
    val updateFunction = s"sync_${syncConfig.sourceSchema}.${syncConfig.sourceTable}_update"
    val updateCondition = if (syncConfig.updateCondition == null) "1=1" else syncConfig.updateCondition
    val sql = content.replace("{{sourceSchema}}", syncConfig.sourceSchema)
      .replace("{{sourceTable}}", syncConfig.sourceTable)
      .replace("{{updateCondition}}", updateCondition)
      .replace("{{sysSchema}}", sysSchema)
      .replace("{{sourceDb}}", syncConfig.sourceDb)
      .replace("{{targetDb}}", syncConfig.targetDb)
      .replace("{{sourceSchema}}", syncConfig.sourceSchema)
      .replace("{{sourceTable}}", syncConfig.sourceTable)
      .replace("{{updateTrigger}}", updateTrigger)
      .replace("{{updateFunction}}", updateFunction)
    val hash = Hashing.murmur3_32().newHasher.putString(sql, StandardCharsets.UTF_8).hash().toString
    if (triggerExists(jdbcTemplate, sysSchema, syncConfig.sourceSchema, syncConfig.sourceTable, updateTrigger, hash)) {
      logger.info("Update trigger for table {}.{}[{}] already exists and matched", syncConfig.sourceSchema, syncConfig.sourceTable, dbName)
    } else {
      logger.info("Update trigger for table {}.{}[{}] not matched, rebuild it", syncConfig.sourceSchema, syncConfig.sourceTable, dbName)
      jdbcTemplate.execute(sql)
      saveTriggerVersion(jdbcTemplate, sysSchema, syncConfig.sourceSchema, syncConfig.sourceTable, updateTrigger, hash)
      logger.info("Update trigger for table {}.{}[{}] updated", syncConfig.sourceSchema, syncConfig.sourceTable, dbName)
    }
  }

  override def buildDeleteTrigger(dbName: String, sysSchema: String, jdbcTemplate: JdbcTemplate, syncConfig: SyncConfig): Unit = {
    val content =
      """
        DROP FUNCTION IF EXISTS {{sourceSchema}}.{{deleteFunction}};
        CREATE OR REPLACE FUNCTION {{sourceSchema}}.{{deleteFunction}}()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $function$
        begin
          if {{deleteCondition}} and 1=1 then
            insert into {{sysSchema}}.sync_data ("sourceDb","targetDb","schema","table","operation","data") values ('{{sourceDb}}','{{targetDb}}','{{sourceSchema}}','{{sourceTable}}','D',row_to_json(OLD));
          end if;
        return null;
        end;
        $function$;

        DROP TRIGGER IF EXISTS {{deleteTrigger}} ON {{sourceSchema}}.{{sourceTable}};
        CREATE TRIGGER {{deleteTrigger}}
        AFTER DELETE ON {{sourceSchema}}.{{sourceTable}}
        FOR EACH ROW
        EXECUTE PROCEDURE {{sourceSchema}}.{{deleteFunction}}();
      """
    val deleteTrigger = s"sync_delete_trigger"
    val deleteFunction = s"sync_${syncConfig.sourceSchema}.${syncConfig.sourceTable}_delete"
    val deleteCondition = if (syncConfig.deleteCondition == null) "1=1" else syncConfig.deleteCondition
    val sql = content.replace("{{sourceSchema}}", syncConfig.sourceSchema)
      .replace("{{sourceTable}}", syncConfig.sourceTable)
      .replace("{{deleteCondition}}", deleteCondition)
      .replace("{{sysSchema}}", sysSchema)
      .replace("{{sourceDb}}", syncConfig.sourceDb)
      .replace("{{targetDb}}", syncConfig.targetDb)
      .replace("{{sourceSchema}}", syncConfig.sourceSchema)
      .replace("{{sourceTable}}", syncConfig.sourceTable)
      .replace("{{deleteTrigger}}", deleteTrigger)
      .replace("{{deleteFunction}}", deleteFunction)
    val hash = Hashing.murmur3_32().newHasher.putString(sql, StandardCharsets.UTF_8).hash().toString
    if (triggerExists(jdbcTemplate, sysSchema, syncConfig.sourceSchema, syncConfig.sourceTable, deleteTrigger, hash)) {
      logger.info("Delete trigger for table {}.{}[{}] already exists and matched", syncConfig.sourceSchema, syncConfig.sourceTable, dbName)
    } else {
      logger.info("Delete trigger for table {}.{}[{}] not matched, rebuild it", syncConfig.sourceSchema, syncConfig.sourceTable, dbName)
      jdbcTemplate.execute(sql)
      saveTriggerVersion(jdbcTemplate, sysSchema, syncConfig.sourceSchema, syncConfig.sourceTable, deleteTrigger, hash)
      logger.info("Delete trigger for table {}.{}[{}] updated", syncConfig.sourceSchema, syncConfig.sourceTable, dbName)
    }
  }

  override def buildSysTable(dbName: String, jdbcTemplate: JdbcTemplate, sysSchema: String): Unit = {
    val schema = sysSchema
    var table = "sync_data"
    if (tableExists(jdbcTemplate, schema, table)) {
      logger.info("System table {}.{}[{}] already exists", schema, table, dbName)
    } else {
      logger.info("System table {}.{}[{}] not exists, rebuild it", schema, table, dbName)
      val sql =
        """
          drop table if exists dbsync.sync_data CASCADE;
          drop sequence if exists dbsync.seq_sync_data CASCADE;
          create sequence dbsync.seq_sync_data start 1;
          create table dbsync.sync_data
         (
            "id" bigint not null DEFAULT(nextval('dbsync.seq_sync_data')) PRIMARY KEY,
            "sourceDb" varchar(512),
            "targetDb" varchar(512),
            "schema" varchar(512),
            "table" varchar(512),
            "operation" varchar(10),
            "data" text,
            "createTime" TIMESTAMP not null default CURRENT_TIMESTAMP
         );
        """
      jdbcTemplate.execute(sql)
      logger.info("System table {}.{}[{}] updated", schema, table, dbName)
    }
    table = "sync_data_status"
    if (tableExists(jdbcTemplate, schema, table)) {
      logger.info("System table {}.{}[{}] already exists", schema, table, dbName)
    } else {
      logger.info("System table {}.{}[{}] not exists, rebuild it", schema, table, dbName)
      val sql =
        """
          drop table if exists dbsync.sync_data_status CASCADE;
          create table dbsync.sync_data_status
         (
            "dataId" bigint REFERENCES dbsync.sync_data(id) ON UPDATE CASCADE ON DELETE RESTRICT,
            "status" varchar(10),
            "message" text,
            "createTime" TIMESTAMP not null default CURRENT_TIMESTAMP
         );
         create index on dbsync.sync_data_status("dataId","status");
        """
      jdbcTemplate.execute(sql)
      logger.info("System table {}.{}[{}] updated", schema, table, dbName)
    }
    table = "sync_trigger_version"
    if (tableExists(jdbcTemplate, schema, table)) {
      logger.info("System table {}.{}[{}] already exists", schema, table, dbName)
    } else {
      logger.info("System table {}.{}[{}] not exists, rebuild it", schema, table, dbName)
      val sql =
        """
          drop table if exists dbsync.sync_trigger_version CASCADE ;
          create table dbsync.sync_trigger_version
         (
            "schema" varchar(512),
            "table" varchar(512),
            "trigger" varchar(512),
            "version" varchar(512),
            "createTime" TIMESTAMP not null default CURRENT_TIMESTAMP,
            PRIMARY KEY ("schema","table","trigger")
         );
        """
      jdbcTemplate.execute(sql)
      logger.info("System table {}.{}[{}] updated", schema, table, dbName)
    }
  }

  def triggerExists(jdbcTemplate: JdbcTemplate,
                    sysSchema: String,
                    schema: String, table: String, trigger: String, version: String) = {
    val sql =
      s"""
        select count(1) from pg_trigger tg
        left join pg_class cl on tg.tgrelid=cl.oid
        left join pg_namespace ns on ns.oid=cl.relnamespace
        left join $sysSchema.sync_trigger_version tv on tv."schema"=ns.nspname and tv."table"=cl.relname and tv."trigger"=tg.tgname
        where tv."schema"=? and tv."table"=? and tv."trigger"=? and tv."version"=?
    """
    val num = jdbcTemplate.queryForObject(sql, Array[AnyRef](schema, table, trigger, version), classOf[Long])
    num > 0
  }

  def saveTriggerVersion(jdbcTemplate: JdbcTemplate, sysSchema: String,
                         schema: String, table: String, trigger: String,
                         version: String) = {
    val sql =
      s"""
        insert into $sysSchema.sync_trigger_version
        ("schema","table","trigger","version")
        values
        (?,?,?,?)
        ON CONFLICT ("schema","table","trigger")
        DO UPDATE SET "version"=EXCLUDED."version"
    """
    jdbcTemplate.update(sql, Array[AnyRef](schema, table, trigger, version))
    ()
  }


  def tableExists(jdbcTemplate: JdbcTemplate,
                  schema: String, table: String) = {
    val sql =
      s"""
        select count(1) from pg_tables where schemaname =? and tablename =?
    """
    val num = jdbcTemplate.queryForObject(sql, Array[AnyRef](schema, table), classOf[Long])
    num > 0
  }

}
