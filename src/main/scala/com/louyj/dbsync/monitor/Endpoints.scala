package com.louyj.dbsync.monitor

import com.alibaba.druid.pool.DruidDataSource
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.louyj.dbsync.SystemContext
import com.louyj.dbsync.dbopt.DbOperationRegister.dbOpts
import com.louyj.dbsync.sync.{ComponentManager, StatisticsComponent}
import io.javalin.Javalin
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.collection.mutable

/**
 *
 * Create at 2020/9/18 11:04<br/>
 *
 * @author Louyj<br/>
 */

class Endpoints(val app: Javalin,
                componentManager: ComponentManager,
                sysctx: SystemContext) {

  val logger = LoggerFactory.getLogger(getClass)
  private val jackson = new ObjectMapper()
  jackson.registerModule(DefaultScalaModule)

  app.get("/status/sys", ctx => {
    val status = Map(
      "uptime" -> sysctx.uptime,
      "running" -> sysctx.running,
      "componentStatus" -> sysctx.componentStatus.toString,
      "syncStatus" -> sysctx.syncStatus
    )
    ctx.result(jackson.writeValueAsString(status))
  })

  app.get("/status/sync", ctx => {
    val result = sysctx.dbConfigs.map(dbConfig => {
      val jdbc = sysctx.dsPools.jdbcTemplate(dbConfig.name)
      val dbOpt = dbOpts(dbConfig.`type`)
      dbOpt.syncState(dbConfig, jdbc)
    }).sortBy(_.name)
    ctx.result(jackson.writeValueAsString(result))
  })

  app.get("/status/component", ctx => {
    val status = componentManager.components.map(e => {
      val component = e._2
      var props: mutable.Map[String, Any] = mutable.Map(
        "lastHeartbeat" -> new DateTime(component.lastHeartbeatTime()).toString("yyyy-MM-dd HH:mm:ss"),
        "status" -> component.componentStatus().toString
      )
      component match {
        case com: StatisticsComponent =>
          props += ("statistics" -> com.statistics, "total" -> com.totalCount)
        case _ =>
      }
      (
        e._1,
        props
      )
    })
    val sortedStatus = mutable.SortedMap(status.toSeq: _*)
    ctx.result(jackson.writeValueAsString(sortedStatus))
  })


  app.get("/status/datasource", ctx => {
    val status = sysctx.dsPools.jdbcTpls.map(e => {
      val name = e._1
      val ds = e._2.getDataSource.asInstanceOf[DruidDataSource]
      (
        name, Map(
        "name" -> ds.getName,
        "url" -> ds.getUrl,
        "user" -> ds.getUsername,
        "maxActive" -> ds.getMaxActive,
        "activeCount" -> ds.getActiveCount,
        "errorCount" -> ds.getErrorCount,
        "pollingCount" -> ds.getPoolingCount,
        "waitCount" -> ds.getWaitThreadCount
      )
      )
    })
    val sortedStatus = mutable.SortedMap(status.toSeq: _*)
    ctx.result(jackson.writeValueAsString(sortedStatus))
  })

  app.get("/control/restart", ctx => {
    val reason = ctx.queryParam("reason")
    sysctx.restart(reason)
    ctx.result("OK")
  })

  app.get("/config", ctx => {
    val status = Map(
      "sys" -> sysctx.sysConfig,
      "db" -> sysctx.dbConfigs.map(v => {
        val map: mutable.Map[String, AnyRef] = jackson.convertValue(v, classOf[mutable.Map[String, AnyRef]])
        map -= "password"
      }),
      "sync" -> sysctx.syncConfigs
    )
    ctx.result(jackson.writeValueAsString(status))
  })

}

case class SyncState(
                      name: String,
                      pending: Long,
                      blocked: Long,
                      error: Long,
                      success: Long,
                      others: Long
                    )

case class PauseSetting(sourceDb: String,
                        targetDb: String,
                        schema: String,
                        table: String)