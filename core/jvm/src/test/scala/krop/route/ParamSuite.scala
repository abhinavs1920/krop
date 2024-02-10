/*
 * Copyright 2023 Creative Scala
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package krop.route

import munit.FunSuite

import scala.util.Success

class ParamSuite extends FunSuite {
  def paramOneParsesValid[A](param: Param.One[A], values: Seq[(String, A)])(
      using munit.Location
  ) =
    values.foreach { case (str, a) =>
      assertEquals(param.parse(str), Right(a))
    }

  def paramOneParsesInvalid[A](param: Param.One[A], values: Seq[String])(using
      munit.Location
  ) =
    values.foreach { (str) => assert(param.parse(str).isLeft) }

  test("Param.one parses valid parameter") {
    paramOneParsesValid(
      Param.int,
      Seq(("1" -> 1), ("42" -> 42), ("-10" -> -10))
    )
    paramOneParsesValid(
      Param.string,
      Seq(
        ("a" -> "a"),
        ("42" -> "42"),
        ("baby you and me" -> "baby you and me")
      )
    )
  }

  test("Param.one fails to parse invalid parameter") {
    paramOneParsesInvalid(Param.int, Seq("a", " ", "xyz"))
  }
}
