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

package org.apache.spark.sql.sources

import scala.collection.JavaConverters._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.{JobContext, TaskAttemptContext}
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter
import org.apache.parquet.hadoop.ParquetOutputCommitter

import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql._
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.hive.test.TestHiveSingleton
import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.sql.types._


abstract class HadoopFsRelationTest extends QueryTest with SQLTestUtils with TestHiveSingleton {
  import sqlContext.implicits._

  val dataSourceName: String

  protected def supportsDataType(dataType: DataType): Boolean = true

  val dataSchema =
    StructType(
      Seq(
        StructField("a", IntegerType, nullable = false),
        StructField("b", StringType, nullable = false)))

  lazy val testDF = (1 to 3).map(i => (i, s"val_$i")).toDF("a", "b")

  lazy val partitionedTestDF1 = (for {
    i <- 1 to 3
    p2 <- Seq("foo", "bar")
  } yield (i, s"val_$i", 1, p2)).toDF("a", "b", "p1", "p2")

  lazy val partitionedTestDF2 = (for {
    i <- 1 to 3
    p2 <- Seq("foo", "bar")
  } yield (i, s"val_$i", 2, p2)).toDF("a", "b", "p1", "p2")

  lazy val partitionedTestDF = partitionedTestDF1.unionAll(partitionedTestDF2)

  def checkQueries(df: DataFrame): Unit = {
    // Selects everything
    checkAnswer(
      df,
      for (i <- 1 to 3; p1 <- 1 to 2; p2 <- Seq("foo", "bar")) yield Row(i, s"val_$i", p1, p2))

    // Simple filtering and partition pruning
    checkAnswer(
      df.filter('a > 1 && 'p1 === 2),
      for (i <- 2 to 3; p2 <- Seq("foo", "bar")) yield Row(i, s"val_$i", 2, p2))

    // Simple projection and filtering
    checkAnswer(
      df.filter('a > 1).select('b, 'a + 1),
      for (i <- 2 to 3; _ <- 1 to 2; _ <- Seq("foo", "bar")) yield Row(s"val_$i", i + 1))

    // Simple projection and partition pruning
    checkAnswer(
      df.filter('a > 1 && 'p1 < 2).select('b, 'p1),
      for (i <- 2 to 3; _ <- Seq("foo", "bar")) yield Row(s"val_$i", 1))

    // Project many copies of columns with different types (reproduction for SPARK-7858)
    checkAnswer(
      df.filter('a > 1 && 'p1 < 2).select('b, 'b, 'b, 'b, 'p1, 'p1, 'p1, 'p1),
      for (i <- 2 to 3; _ <- Seq("foo", "bar"))
        yield Row(s"val_$i", s"val_$i", s"val_$i", s"val_$i", 1, 1, 1, 1))

    // Self-join
    df.registerTempTable("t")
    withTempTable("t") {
      checkAnswer(
        sql(
          """SELECT l.a, r.b, l.p1, r.p2
            |FROM t l JOIN t r
            |ON l.a = r.a AND l.p1 = r.p1 AND l.p2 = r.p2
          """.stripMargin),
        for (i <- 1 to 3; p1 <- 1 to 2; p2 <- Seq("foo", "bar")) yield Row(i, s"val_$i", p1, p2))
    }
  }

  ignore("test all data types") {
    withTempPath { file =>
      // Create the schema.
      val struct =
        StructType(
          StructField("f1", FloatType, true) ::
            StructField("f2", ArrayType(BooleanType), true) :: Nil)
      // TODO: add CalendarIntervalType to here once we can save it out.
      val dataTypes =
        Seq(
          StringType, BinaryType, NullType, BooleanType,
          ByteType, ShortType, IntegerType, LongType,
          FloatType, DoubleType, DecimalType(25, 5), DecimalType(6, 5),
          DateType, TimestampType,
          ArrayType(IntegerType), MapType(StringType, LongType), struct,
          new MyDenseVectorUDT())
      val fields = dataTypes.zipWithIndex.map { case (dataType, index) =>
        StructField(s"col$index", dataType, nullable = true)
      }
      val schema = StructType(fields)

      // Generate data at the driver side. We need to materialize the data first and then
      // create RDD.
      val maybeDataGenerator =
        RandomDataGenerator.forType(
          dataType = schema,
          nullable = true,
          seed = Some(System.nanoTime()))
      val dataGenerator =
        maybeDataGenerator
          .getOrElse(fail(s"Failed to create data generator for schema $schema"))
      val data = (1 to 10).map { i =>
        dataGenerator.apply() match {
          case row: Row => row
          case null => Row.fromSeq(Seq.fill(schema.length)(null))
          case other =>
            fail(s"Row or null is expected to be generated, " +
              s"but a ${other.getClass.getCanonicalName} is generated.")
        }
      }

      // Create a DF for the schema with random data.
      val rdd = sqlContext.sparkContext.parallelize(data, 10)
      val df = sqlContext.createDataFrame(rdd, schema)

      // All columns that have supported data types of this source.
      val supportedColumns = schema.fields.collect {
        case StructField(name, dataType, _, _) if supportsDataType(dataType) => name
      }
      val selectedColumns = util.Random.shuffle(supportedColumns.toSeq)

      val dfToBeSaved = df.selectExpr(selectedColumns: _*)

      // Save the data out.
      dfToBeSaved
        .write
        .format(dataSourceName)
        .option("dataSchema", dfToBeSaved.schema.json) // This option is just used by tests.
        .save(file.getCanonicalPath)

      val loadedDF =
        sqlContext
          .read
          .format(dataSourceName)
          .schema(dfToBeSaved.schema)
          .option("dataSchema", dfToBeSaved.schema.json) // This option is just used by tests.
          .load(file.getCanonicalPath)
          .selectExpr(selectedColumns: _*)

      // Read the data back.
      checkAnswer(
        loadedDF,
        dfToBeSaved
      )
    }
  }

  test("save()/load() - non-partitioned table - Overwrite") {
    withTempPath { file =>
      testDF.write.mode(SaveMode.Overwrite).format(dataSourceName).save(file.getCanonicalPath)
      testDF.write.mode(SaveMode.Overwrite).format(dataSourceName).save(file.getCanonicalPath)

      checkAnswer(
        sqlContext.read.format(dataSourceName)
          .option("path", file.getCanonicalPath)
          .option("dataSchema", dataSchema.json)
          .load(),
        testDF.collect())
    }
  }

  test("save()/load() - non-partitioned table - Append") {
    withTempPath { file =>
      testDF.write.mode(SaveMode.Overwrite).format(dataSourceName).save(file.getCanonicalPath)
      testDF.write.mode(SaveMode.Append).format(dataSourceName).save(file.getCanonicalPath)

      checkAnswer(
        sqlContext.read.format(dataSourceName)
          .option("dataSchema", dataSchema.json)
          .load(file.getCanonicalPath).orderBy("a"),
        testDF.unionAll(testDF).orderBy("a").collect())
    }
  }

  test("save()/load() - non-partitioned table - ErrorIfExists") {
    withTempDir { file =>
      intercept[AnalysisException] {
        testDF.write.format(dataSourceName).mode(SaveMode.ErrorIfExists).save(file.getCanonicalPath)
      }
    }
  }

  test("save()/load() - non-partitioned table - Ignore") {
    withTempDir { file =>
      testDF.write.mode(SaveMode.Ignore).format(dataSourceName).save(file.getCanonicalPath)

      val path = new Path(file.getCanonicalPath)
      val fs = path.getFileSystem(sqlContext.sparkContext.hadoopConfiguration)
      assert(fs.listStatus(path).isEmpty)
    }
  }

  test("save()/load() - partitioned table - simple queries") {
    withTempPath { file =>
      partitionedTestDF.write
        .format(dataSourceName)
        .mode(SaveMode.ErrorIfExists)
        .partitionBy("p1", "p2")
        .save(file.getCanonicalPath)

      checkQueries(
        sqlContext.read.format(dataSourceName)
          .option("dataSchema", dataSchema.json)
          .load(file.getCanonicalPath))
    }
  }

  test("save()/load() - partitioned table - Overwrite") {
    withTempPath { file =>
      partitionedTestDF.write
        .format(dataSourceName)
        .mode(SaveMode.Overwrite)
        .partitionBy("p1", "p2")
        .save(file.getCanonicalPath)

      partitionedTestDF.write
        .format(dataSourceName)
        .mode(SaveMode.Overwrite)
        .partitionBy("p1", "p2")
        .save(file.getCanonicalPath)

      checkAnswer(
        sqlContext.read.format(dataSourceName)
          .option("dataSchema", dataSchema.json)
          .load(file.getCanonicalPath),
        partitionedTestDF.collect())
    }
  }

  test("save()/load() - partitioned table - Append") {
    withTempPath { file =>
      partitionedTestDF.write
        .format(dataSourceName)
        .mode(SaveMode.Overwrite)
        .partitionBy("p1", "p2")
        .save(file.getCanonicalPath)

      partitionedTestDF.write
        .format(dataSourceName)
        .mode(SaveMode.Append)
        .partitionBy("p1", "p2")
        .save(file.getCanonicalPath)

      checkAnswer(
        sqlContext.read.format(dataSourceName)
          .option("dataSchema", dataSchema.json)
          .load(file.getCanonicalPath),
        partitionedTestDF.unionAll(partitionedTestDF).collect())
    }
  }

  test("save()/load() - partitioned table - Append - new partition values") {
    withTempPath { file =>
      partitionedTestDF1.write
        .format(dataSourceName)
        .mode(SaveMode.Overwrite)
        .partitionBy("p1", "p2")
        .save(file.getCanonicalPath)

      partitionedTestDF2.write
        .format(dataSourceName)
        .mode(SaveMode.Append)
        .partitionBy("p1", "p2")
        .save(file.getCanonicalPath)

      checkAnswer(
        sqlContext.read.format(dataSourceName)
          .option("dataSchema", dataSchema.json)
          .load(file.getCanonicalPath),
        partitionedTestDF.collect())
    }
  }

  test("save()/load() - partitioned table - ErrorIfExists") {
    withTempDir { file =>
      intercept[AnalysisException] {
        partitionedTestDF.write
          .format(dataSourceName)
          .mode(SaveMode.ErrorIfExists)
          .partitionBy("p1", "p2")
          .save(file.getCanonicalPath)
      }
    }
  }

  test("save()/load() - partitioned table - Ignore") {
    withTempDir { file =>
      partitionedTestDF.write
        .format(dataSourceName).mode(SaveMode.Ignore).save(file.getCanonicalPath)

      val path = new Path(file.getCanonicalPath)
      val fs = path.getFileSystem(SparkHadoopUtil.get.conf)
      assert(fs.listStatus(path).isEmpty)
    }
  }

  test("saveAsTable()/load() - non-partitioned table - Overwrite") {
    testDF.write.format(dataSourceName).mode(SaveMode.Overwrite)
      .option("dataSchema", dataSchema.json)
      .saveAsTable("t")

    withTable("t") {
      checkAnswer(sqlContext.table("t"), testDF.collect())
    }
  }

  test("saveAsTable()/load() - non-partitioned table - Append") {
    testDF.write.format(dataSourceName).mode(SaveMode.Overwrite).saveAsTable("t")
    testDF.write.format(dataSourceName).mode(SaveMode.Append).saveAsTable("t")

    withTable("t") {
      checkAnswer(sqlContext.table("t"), testDF.unionAll(testDF).orderBy("a").collect())
    }
  }

  test("saveAsTable()/load() - non-partitioned table - ErrorIfExists") {
    Seq.empty[(Int, String)].toDF().registerTempTable("t")

    withTempTable("t") {
      intercept[AnalysisException] {
        testDF.write.format(dataSourceName).mode(SaveMode.ErrorIfExists).saveAsTable("t")
      }
    }
  }

  test("saveAsTable()/load() - non-partitioned table - Ignore") {
    Seq.empty[(Int, String)].toDF().registerTempTable("t")

    withTempTable("t") {
      testDF.write.format(dataSourceName).mode(SaveMode.Ignore).saveAsTable("t")
      assert(sqlContext.table("t").collect().isEmpty)
    }
  }

  test("saveAsTable()/load() - partitioned table - simple queries") {
    partitionedTestDF.write.format(dataSourceName)
      .mode(SaveMode.Overwrite)
      .option("dataSchema", dataSchema.json)
      .saveAsTable("t")

    withTable("t") {
      checkQueries(sqlContext.table("t"))
    }
  }

  test("saveAsTable()/load() - partitioned table - boolean type") {
    sqlContext.range(2)
      .select('id, ('id % 2 === 0).as("b"))
      .write.partitionBy("b").saveAsTable("t")

    withTable("t") {
      checkAnswer(
        sqlContext.table("t").sort('id),
        Row(0, true) :: Row(1, false) :: Nil
      )
    }
  }

  test("saveAsTable()/load() - partitioned table - Overwrite") {
    partitionedTestDF.write
      .format(dataSourceName)
      .mode(SaveMode.Overwrite)
      .option("dataSchema", dataSchema.json)
      .partitionBy("p1", "p2")
      .saveAsTable("t")

    partitionedTestDF.write
      .format(dataSourceName)
      .mode(SaveMode.Overwrite)
      .option("dataSchema", dataSchema.json)
      .partitionBy("p1", "p2")
      .saveAsTable("t")

    withTable("t") {
      checkAnswer(sqlContext.table("t"), partitionedTestDF.collect())
    }
  }

  test("saveAsTable()/load() - partitioned table - Append") {
    partitionedTestDF.write
      .format(dataSourceName)
      .mode(SaveMode.Overwrite)
      .option("dataSchema", dataSchema.json)
      .partitionBy("p1", "p2")
      .saveAsTable("t")

    partitionedTestDF.write
      .format(dataSourceName)
      .mode(SaveMode.Append)
      .option("dataSchema", dataSchema.json)
      .partitionBy("p1", "p2")
      .saveAsTable("t")

    withTable("t") {
      checkAnswer(sqlContext.table("t"), partitionedTestDF.unionAll(partitionedTestDF).collect())
    }
  }

  test("saveAsTable()/load() - partitioned table - Append - new partition values") {
    partitionedTestDF1.write
      .format(dataSourceName)
      .mode(SaveMode.Overwrite)
      .option("dataSchema", dataSchema.json)
      .partitionBy("p1", "p2")
      .saveAsTable("t")

    partitionedTestDF2.write
      .format(dataSourceName)
      .mode(SaveMode.Append)
      .option("dataSchema", dataSchema.json)
      .partitionBy("p1", "p2")
      .saveAsTable("t")

    withTable("t") {
      checkAnswer(sqlContext.table("t"), partitionedTestDF.collect())
    }
  }

  test("saveAsTable()/load() - partitioned table - Append - mismatched partition columns") {
    partitionedTestDF1.write
      .format(dataSourceName)
      .mode(SaveMode.Overwrite)
      .option("dataSchema", dataSchema.json)
      .partitionBy("p1", "p2")
      .saveAsTable("t")

    // Using only a subset of all partition columns
    intercept[Throwable] {
      partitionedTestDF2.write
        .format(dataSourceName)
        .mode(SaveMode.Append)
        .option("dataSchema", dataSchema.json)
        .partitionBy("p1")
        .saveAsTable("t")
    }
  }

  test("saveAsTable()/load() - partitioned table - ErrorIfExists") {
    Seq.empty[(Int, String)].toDF().registerTempTable("t")

    withTempTable("t") {
      intercept[AnalysisException] {
        partitionedTestDF.write
          .format(dataSourceName)
          .mode(SaveMode.ErrorIfExists)
          .option("dataSchema", dataSchema.json)
          .partitionBy("p1", "p2")
          .saveAsTable("t")
      }
    }
  }

  test("saveAsTable()/load() - partitioned table - Ignore") {
    Seq.empty[(Int, String)].toDF().registerTempTable("t")

    withTempTable("t") {
      partitionedTestDF.write
        .format(dataSourceName)
        .mode(SaveMode.Ignore)
        .option("dataSchema", dataSchema.json)
        .partitionBy("p1", "p2")
        .saveAsTable("t")

      assert(sqlContext.table("t").collect().isEmpty)
    }
  }

  test("Hadoop style globbing") {
    withTempPath { file =>
      partitionedTestDF.write
        .format(dataSourceName)
        .mode(SaveMode.Overwrite)
        .partitionBy("p1", "p2")
        .save(file.getCanonicalPath)

      val df = sqlContext.read
        .format(dataSourceName)
        .option("dataSchema", dataSchema.json)
        .load(s"${file.getCanonicalPath}/p1=*/p2=???")

      val expectedPaths = Set(
        s"${file.getCanonicalFile}/p1=1/p2=foo",
        s"${file.getCanonicalFile}/p1=2/p2=foo",
        s"${file.getCanonicalFile}/p1=1/p2=bar",
        s"${file.getCanonicalFile}/p1=2/p2=bar"
      ).map { p =>
        val path = new Path(p)
        val fs = path.getFileSystem(sqlContext.sparkContext.hadoopConfiguration)
        path.makeQualified(fs.getUri, fs.getWorkingDirectory).toString
      }

      val actualPaths = df.queryExecution.analyzed.collectFirst {
        case LogicalRelation(relation: HadoopFsRelation) =>
          relation.paths.toSet
      }.getOrElse {
        fail("Expect an FSBasedRelation, but none could be found")
      }

      assert(actualPaths === expectedPaths)
      checkAnswer(df, partitionedTestDF.collect())
    }
  }

  // HadoopFsRelation.discoverPartitions() called by refresh(), which will ignore
  // the given partition data type.
  ignore("Partition column type casting") {
    withTempPath { file =>
      val input = partitionedTestDF.select('a, 'b, 'p1.cast(StringType).as('ps), 'p2)

      input
        .write
        .format(dataSourceName)
        .mode(SaveMode.Overwrite)
        .partitionBy("ps", "p2")
        .saveAsTable("t")

      withTempTable("t") {
        checkAnswer(sqlContext.table("t"), input.collect())
      }
    }
  }

  test("SPARK-7616: adjust column name order accordingly when saving partitioned table") {
    val df = (1 to 3).map(i => (i, s"val_$i", i * 2)).toDF("a", "b", "c")

    df.write
      .format(dataSourceName)
      .mode(SaveMode.Overwrite)
      .partitionBy("c", "a")
      .saveAsTable("t")

    withTable("t") {
      checkAnswer(sqlContext.table("t"), df.select('b, 'c, 'a).collect())
    }
  }

  // NOTE: This test suite is not super deterministic.  On nodes with only relatively few cores
  // (4 or even 1), it's hard to reproduce the data loss issue.  But on nodes with for example 8 or
  // more cores, the issue can be reproduced steadily.  Fortunately our Jenkins builder meets this
  // requirement.  We probably want to move this test case to spark-integration-tests or spark-perf
  // later.
  test("SPARK-8406: Avoids name collision while writing files") {
    withTempPath { dir =>
      val path = dir.getCanonicalPath
      sqlContext
        .range(10000)
        .repartition(250)
        .write
        .mode(SaveMode.Overwrite)
        .format(dataSourceName)
        .save(path)

      assertResult(10000) {
        sqlContext
          .read
          .format(dataSourceName)
          .option("dataSchema", StructType(StructField("id", LongType) :: Nil).json)
          .load(path)
          .count()
      }
    }
  }

  test("SPARK-8578 specified custom output committer will not be used to append data") {
    val clonedConf = new Configuration(hadoopConfiguration)
    try {
      val df = sqlContext.range(1, 10).toDF("i")
      withTempPath { dir =>
        df.write.mode("append").format(dataSourceName).save(dir.getCanonicalPath)
        hadoopConfiguration.set(
          SQLConf.OUTPUT_COMMITTER_CLASS.key,
          classOf[AlwaysFailOutputCommitter].getName)
        // Since Parquet has its own output committer setting, also set it
        // to AlwaysFailParquetOutputCommitter at here.
        hadoopConfiguration.set("spark.sql.parquet.output.committer.class",
          classOf[AlwaysFailParquetOutputCommitter].getName)
        // Because there data already exists,
        // this append should succeed because we will use the output committer associated
        // with file format and AlwaysFailOutputCommitter will not be used.
        df.write.mode("append").format(dataSourceName).save(dir.getCanonicalPath)
        checkAnswer(
          sqlContext.read
            .format(dataSourceName)
            .option("dataSchema", df.schema.json)
            .load(dir.getCanonicalPath),
          df.unionAll(df))

        // This will fail because AlwaysFailOutputCommitter is used when we do append.
        intercept[Exception] {
          df.write.mode("overwrite").format(dataSourceName).save(dir.getCanonicalPath)
        }
      }
      withTempPath { dir =>
        hadoopConfiguration.set(
          SQLConf.OUTPUT_COMMITTER_CLASS.key,
          classOf[AlwaysFailOutputCommitter].getName)
        // Since Parquet has its own output committer setting, also set it
        // to AlwaysFailParquetOutputCommitter at here.
        hadoopConfiguration.set("spark.sql.parquet.output.committer.class",
          classOf[AlwaysFailParquetOutputCommitter].getName)
        // Because there is no existing data,
        // this append will fail because AlwaysFailOutputCommitter is used when we do append
        // and there is no existing data.
        intercept[Exception] {
          df.write.mode("append").format(dataSourceName).save(dir.getCanonicalPath)
        }
      }
    } finally {
      // Hadoop 1 doesn't have `Configuration.unset`
      hadoopConfiguration.clear()
      clonedConf.asScala.foreach(entry => hadoopConfiguration.set(entry.getKey, entry.getValue))
    }
  }

  test("SPARK-8887: Explicitly define which data types can be used as dynamic partition columns") {
    val df = Seq(
      (1, "v1", Array(1, 2, 3), Map("k1" -> "v1"), Tuple2(1, "4")),
      (2, "v2", Array(4, 5, 6), Map("k2" -> "v2"), Tuple2(2, "5")),
      (3, "v3", Array(7, 8, 9), Map("k3" -> "v3"), Tuple2(3, "6"))).toDF("a", "b", "c", "d", "e")
    withTempDir { file =>
      intercept[AnalysisException] {
        df.write.format(dataSourceName).partitionBy("c", "d", "e").save(file.getCanonicalPath)
      }
    }
    intercept[AnalysisException] {
      df.write.format(dataSourceName).partitionBy("c", "d", "e").saveAsTable("t")
    }
  }

  test("SPARK-9899 Disable customized output committer when speculation is on") {
    val clonedConf = new Configuration(hadoopConfiguration)
    val speculationEnabled =
      sqlContext.sparkContext.conf.getBoolean("spark.speculation", defaultValue = false)

    try {
      withTempPath { dir =>
        // Enables task speculation
        sqlContext.sparkContext.conf.set("spark.speculation", "true")

        // Uses a customized output committer which always fails
        hadoopConfiguration.set(
          SQLConf.OUTPUT_COMMITTER_CLASS.key,
          classOf[AlwaysFailOutputCommitter].getName)

        // Code below shouldn't throw since customized output committer should be disabled.
        val df = sqlContext.range(10).coalesce(1)
        df.write.format(dataSourceName).save(dir.getCanonicalPath)
        checkAnswer(
          sqlContext
            .read
            .format(dataSourceName)
            .option("dataSchema", df.schema.json)
            .load(dir.getCanonicalPath),
          df)
      }
    } finally {
      // Hadoop 1 doesn't have `Configuration.unset`
      hadoopConfiguration.clear()
      clonedConf.asScala.foreach(entry => hadoopConfiguration.set(entry.getKey, entry.getValue))
      sqlContext.sparkContext.conf.set("spark.speculation", speculationEnabled.toString)
    }
  }
}

// This class is used to test SPARK-8578. We should not use any custom output committer when
// we actually append data to an existing dir.
class AlwaysFailOutputCommitter(
    outputPath: Path,
    context: TaskAttemptContext)
  extends FileOutputCommitter(outputPath, context) {

  override def commitJob(context: JobContext): Unit = {
    sys.error("Intentional job commitment failure for testing purpose.")
  }
}

// This class is used to test SPARK-8578. We should not use any custom output committer when
// we actually append data to an existing dir.
class AlwaysFailParquetOutputCommitter(
    outputPath: Path,
    context: TaskAttemptContext)
  extends ParquetOutputCommitter(outputPath, context) {

  override def commitJob(context: JobContext): Unit = {
    sys.error("Intentional job commitment failure for testing purpose.")
  }
}
