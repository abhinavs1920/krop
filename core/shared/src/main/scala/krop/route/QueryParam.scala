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

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import krop.route.Param.One

/** A [[package.QueryParam]] extracts values from a URI's query parameters. It
  * consists of a [[package.Param]], which does the necessary type conversion,
  * and the name under which the parameters should be found.
  *
  * There are two types of `QueryParam`:
  *
  * * required params, which fail if there are no values associated with the
  * name; and
  *
  * * optional parameters, that return `None` if there is no value for the name.
  */
enum QueryParam[A] {
  import QueryParseException.*

  /** Get a human-readable description of this `QueryParam`. */
  def describe: String =
    this match {
      case Required(name, param) => s"${name}=${param.describe}"
      case Optional(name, param) => s"optional(${name}=${param.describe})"
      case All                   => "all"
    }

  def parse(params: Map[String, List[String]]): Try[A] =
    this match {
      case Required(name, param) =>
        params.get(name) match {
          case Some(values) =>
            param match {
              case Param.All(_, parse, _) => parse(values)
              case Param.One(_, parse, _) =>
                if params.isEmpty then
                  Failure(NoValuesForName(name).fillInStackTrace())
                else parse(values.head)
            }
          case None => Failure(NoParameterWithName(name).fillInStackTrace())
        }

      case Optional(name, param) =>
        params.get(name) match {
          case Some(values) =>
            param match {
              case Param.All(name, parse, unparse) => parse(values).map(Some(_))
              case Param.One(name, parse, unparse) =>
                if params.isEmpty then Success(None)
                else parse(values.head).map(Some(_))
            }

          case None => Success(None)
        }

      case All => Success(params)
    }

  def unparse(a: A): Option[(String, List[String])] =
    this match {
      case Required(name, param) =>
        param match {
          case Param.All(_, _, unparse) => Some(name -> unparse(a).toList)
          case Param.One(_, _, unparse) => Some(name -> List(unparse(a)))
        }

      case Optional(name, param) =>
        a match {
          case Some(a1) =>
            param match {
              case Param.All(_, _, unparse) => Some(name -> unparse(a1).toList)
              case Param.One(_, _, unparse) => Some(name -> List(unparse(a1)))
            }
          case None => None
        }

      case All => None
    }

  case Required(name: String, param: Param[A])
  case Optional[A](name: String, param: Param[A]) extends QueryParam[Option[A]]
  case All extends QueryParam[Map[String, List[String]]]
}
object QueryParam {
  def apply[A](name: String, param: Param[A]): QueryParam[A] =
    QueryParam.Required(name, param)

  val all = QueryParam.All
}
