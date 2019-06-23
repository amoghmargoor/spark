/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive

import java.net.URI

import scala.collection.mutable

import org.mockito.Mockito.{spy, when}
import org.scalatest.PrivateMethodTester
import org.scalatest.mockito.MockitoSugar

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.internal.config.UI.UI_ENABLED
import org.apache.spark.sql.{QueryTest, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.execution.command.DDLUtils
import org.apache.spark.sql.hive.test.TestHiveSingleton
import org.apache.spark.sql.internal.{HiveSerDe, SQLConf}
import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.sql.types.StructType

class SubstituteMvOSSuite extends QueryTest
  with PrivateMethodTester with MockitoSugar {

  val spark = {
    val sparkConf = new SparkConf(loadDefaults = true)
    // sparkConf.set(HiveUtils.HIVE_METASTORE_VERSION, "3.1.1")
    val builder = SparkSession.builder()
      .config(sparkConf)
      .config(UI_ENABLED.key, "false")
      .config(HiveUtils.HIVE_METASTORE_VERSION.key, "3.1.1")
      .master("local[1]")
      .appName("Materialized views")
      // The issue described in SPARK-16901 only appear when
      // spark.sql.hive.metastore.jars is not set to builtin.
      .config("spark.sql.hive.metastore.jars", "maven")
      .enableHiveSupport()
    builder.getOrCreate()
  }

  private val mvCatalog: MvCatalog = spark.sharedState.mvCatalog
  private val mockCatalog: MvCatalog = spy(mvCatalog)
  when(mockCatalog.getMaterializedViewForTable("db", "tbl"))
    .thenReturn(CatalogCreationData("db", "tbl", Seq(("db", "mv"))))

  // Private method accessors
  private val mvConfName = SQLConf.ENABLE_MV_OS_OPTIMIZATION.key
  private var catalog: SessionCatalog = spark.sessionState.catalog
  private var dataSourceTable: CatalogTable = _
  private var mvTable: CatalogTable = _
  private val tablesCreated: mutable.Seq[CatalogTable] = mutable.Seq.empty

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    catalog.createDatabase(newDb("db"), ignoreIfExists = false)
    var ident = TableIdentifier("tbl", Some("db"))
    catalog.dropTable(ident, ignoreIfNotExists = true, purge = true)
    val serde = HiveSerDe.sourceToSerDe("orc")
    dataSourceTable = CatalogTable(
      identifier = TableIdentifier("tbl", Some("db")),
      tableType = CatalogTableType.MANAGED,
      storage = CatalogStorageFormat.empty,
      schema = new StructType()
        .add("id", "int").add("col1", "string"),
      provider = Some("parquet"))
    catalog.createTable(dataSourceTable, ignoreIfExists = false)
    tablesCreated :+ dataSourceTable

    ident = TableIdentifier("mv", Some("db"))
    catalog.dropTable(ident, ignoreIfNotExists = true, purge = true)
    mvTable = CatalogTable(
      identifier = TableIdentifier("mv", Some("db")),
      tableType = CatalogTableType.MANAGED,
      storage = CatalogStorageFormat.empty,
      schema = new StructType()
        .add("id", "int").add("col1", "string"),
      provider = Some("parquet"),
      viewOriginalText = Some("SELECT * FROM tbl ORDER BY id"))
    catalog.createTable(mvTable, ignoreIfExists = false)
    tablesCreated :+ mvTable
  }

  def newDb(name: String): CatalogDatabase = {
    CatalogDatabase(name, "desc", new URI("loc"), Map())
  }

  override protected def afterAll(): Unit = {
    try {
      tablesCreated.foreach(table =>
        catalog.dropTable(table.identifier, ignoreIfNotExists = false, purge = false))
      spark.stop()
    } finally {
      super.afterAll()
    }
  }

  /** Fails the test if the two plans do not match */
  protected def comparePlans(plan1: LogicalPlan, plan2: LogicalPlan): Unit = {
    val normalizedPlan1 = invalidateStatsCache(plan1)
    val normalizedPlan2 = invalidateStatsCache(plan2)
    super.comparePlans(normalizedPlan1, normalizedPlan2)
  }

  test("Optimizer should substitute materialized view") {
    spark.sharedState.mvCatalog.init(spark) // we should move this inside session creation
    withSQLConf((mvConfName, "true"), (HiveUtils.HIVE_METASTORE_JARS.key, "3.1.1")) {
      val df1 = spark.sql("select * from db.tbl where id > 20")
      val df2 = spark.sql("select * from db.mv where id > 20")
      val optimized1 = Optimize.execute(df1.queryExecution.analyzed)
      val optimized2 = df2.queryExecution.optimizedPlan

      // comparePlans(optimized1, optimized2)
    }
  }

  private def invalidateStatsCache(plan: LogicalPlan): LogicalPlan = {
    plan transform {
      case rel: HiveTableRelation =>
        val table = rel.tableMeta
        if (table.stats.isDefined) {
          rel.copy(tableMeta = table.copy(stats = None))
        } else {
          rel
        }
    }
  }

  private def createMVTable(projection: String, exp: String,
      originalTable: String, mvTable: String, schema: StructType,
      catalog: SessionCatalog): CatalogTable = {
    val serde = HiveSerDe.sourceToSerDe("orc")
    val table = CatalogTable(
      identifier = TableIdentifier(mvTable, Some("mvdb")),
      tableType = CatalogTableType.MV,
      storage = getCatalogStorageFormat(serde),
      schema,
      viewText = Some(s"select $projection from $originalTable where $exp"),
      provider = Some(DDLUtils.HIVE_PROVIDER))
    catalog.createTable(table, ignoreIfExists = false)
    table
  }

  private def getCatalogStorageFormat(serde: Option[HiveSerDe]): CatalogStorageFormat = {
    CatalogStorageFormat.empty.copy(
      inputFormat = serde.get.inputFormat,
      outputFormat = serde.get.outputFormat,
      serde = serde.flatMap(_.serde)
        .orElse(Some("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe")))
  }

  private object Optimize extends RuleExecutor[LogicalPlan] {
    override protected def batches = {
      Seq(Batch("Substitute MV",
        Once,
        SubstituteMaterializedOSView(mockCatalog.asInstanceOf[HiveMvCatalog])))
    }
  }

}

