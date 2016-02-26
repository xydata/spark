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

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.analysis
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.catalyst.expressions.{Ascending, Explode, Literal, SortOrder}
import org.apache.spark.sql.catalyst.plans.PlanTest
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.types.StringType

class ColumnPruningSuite extends PlanTest {

  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches = Batch("Column pruning", FixedPoint(100),
      ColumnPruning) :: Nil
  }

  test("Column pruning for Generate when Generate.join = false") {
    val input = LocalRelation('a.int, 'b.array(StringType))

    val query = input.generate(Explode('b), join = false).analyze

    val optimized = Optimize.execute(query)

    val correctAnswer = input.select('b).generate(Explode('b), join = false).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Column pruning for Generate when Generate.join = true") {
    val input = LocalRelation('a.int, 'b.int, 'c.array(StringType))

    val query =
      input
        .generate(Explode('c), join = true, outputNames = "explode" :: Nil)
        .select('a, 'explode)
        .analyze

    val optimized = Optimize.execute(query)

    val correctAnswer =
      input
        .select('a, 'c)
        .generate(Explode('c), join = true, outputNames = "explode" :: Nil)
        .select('a, 'explode)
        .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Turn Generate.join to false if possible") {
    val input = LocalRelation('b.array(StringType))

    val query =
      input
        .generate(Explode('b), join = true, outputNames = "explode" :: Nil)
        .select(('explode + 1).as("result"))
        .analyze

    val optimized = Optimize.execute(query)

    val correctAnswer =
      input
        .generate(Explode('b), join = false, outputNames = "explode" :: Nil)
        .select(('explode + 1).as("result"))
        .analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Column pruning for Project on Sort") {
    val input = LocalRelation('a.int, 'b.string, 'c.double)

    val query = input.orderBy('b.asc).select('a).analyze
    val optimized = Optimize.execute(query)

    val correctAnswer = input.select('a, 'b).orderBy('b.asc).select('a).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("Column pruning for Expand") {
    val input = LocalRelation('a.int, 'b.string, 'c.double)
    val query =
      Aggregate(
        Seq('aa, 'gid),
        Seq(sum('c).as("sum")),
        Expand(
          Seq(
            Seq('a, 'b, 'c, Literal.create(null, StringType), 1),
            Seq('a, 'b, 'c, 'a, 2)),
          Seq('a, 'b, 'c, 'aa.int, 'gid.int),
          input)).analyze
    val optimized = Optimize.execute(query)

    val expected =
      Aggregate(
        Seq('aa, 'gid),
        Seq(sum('c).as("sum")),
        Expand(
          Seq(
            Seq('c, Literal.create(null, StringType), 1),
            Seq('c, 'a, 2)),
          Seq('c, 'aa.int, 'gid.int),
          Project(Seq('a, 'c),
            input))).analyze

    comparePlans(optimized, expected)
  }

  test("Column pruning on Filter") {
    val input = LocalRelation('a.int, 'b.string, 'c.double)
    val query = Project('a :: Nil, Filter('c > Literal(0.0), input)).analyze
    val expected =
      Project('a :: Nil,
        Filter('c > Literal(0.0),
          Project(Seq('a, 'c), input))).analyze
    comparePlans(Optimize.execute(query), expected)
  }

  test("Column pruning on except/intersect/distinct") {
    val input = LocalRelation('a.int, 'b.string, 'c.double)
    val query = Project('a :: Nil, Except(input, input)).analyze
    comparePlans(Optimize.execute(query), query)

    val query2 = Project('a :: Nil, Intersect(input, input)).analyze
    comparePlans(Optimize.execute(query2), query2)
    val query3 = Project('a :: Nil, Distinct(input)).analyze
    comparePlans(Optimize.execute(query3), query3)
  }

  test("Column pruning on Project") {
    val input = LocalRelation('a.int, 'b.string, 'c.double)
    val query = Project('a :: Nil, Project(Seq('a, 'b), input)).analyze
    val expected = Project(Seq('a), input).analyze
    comparePlans(Optimize.execute(query), expected)
  }

  test("column pruning for group") {
    val testRelation = LocalRelation('a.int, 'b.int, 'c.int)
    val originalQuery =
      testRelation
        .groupBy('a)('a, count('b))
        .select('a)

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer =
      testRelation
        .select('a)
        .groupBy('a)('a).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("column pruning for group with alias") {
    val testRelation = LocalRelation('a.int, 'b.int, 'c.int)

    val originalQuery =
      testRelation
        .groupBy('a)('a as 'c, count('b))
        .select('c)

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer =
      testRelation
        .select('a)
        .groupBy('a)('a as 'c).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("column pruning for Project(ne, Limit)") {
    val testRelation = LocalRelation('a.int, 'b.int, 'c.int)

    val originalQuery =
      testRelation
        .select('a, 'b)
        .limit(2)
        .select('a)

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer =
      testRelation
        .select('a)
        .limit(2).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("push down project past sort") {
    val testRelation = LocalRelation('a.int, 'b.int, 'c.int)
    val x = testRelation.subquery('x)

    // push down valid
    val originalQuery = {
      x.select('a, 'b)
        .sortBy(SortOrder('a, Ascending))
        .select('a)
    }

    val optimized = Optimize.execute(originalQuery.analyze)
    val correctAnswer =
      x.select('a)
        .sortBy(SortOrder('a, Ascending)).analyze

    comparePlans(optimized, analysis.EliminateSubqueryAliases(correctAnswer))

    // push down invalid
    val originalQuery1 = {
      x.select('a, 'b)
        .sortBy(SortOrder('a, Ascending))
        .select('b)
    }

    val optimized1 = Optimize.execute(originalQuery1.analyze)
    val correctAnswer1 =
      x.select('a, 'b)
        .sortBy(SortOrder('a, Ascending))
        .select('b).analyze

    comparePlans(optimized1, analysis.EliminateSubqueryAliases(correctAnswer1))
  }

  test("Column pruning on Union") {
    val input1 = LocalRelation('a.int, 'b.string, 'c.double)
    val input2 = LocalRelation('c.int, 'd.string, 'e.double)
    val query = Project('b :: Nil,
      Union(input1 :: input2 :: Nil)).analyze
    val expected = Project('b :: Nil,
      Union(Project('b :: Nil, input1) :: Project('d :: Nil, input2) :: Nil)).analyze
    comparePlans(Optimize.execute(query), expected)
  }

  // todo: add more tests for column pruning
}
