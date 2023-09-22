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

import org.http4s.Uri.{Path as UriPath}

import scala.util.Failure
import scala.util.Success
import scala.util.Try

final case class Path[A <: Tuple](segments: Vector[Segment | Capture[?]]) {
  def extract(path: UriPath): Option[A] = {
    def loop(
        matchSegments: Vector[Segment | Capture[?]],
        pathSegments: Vector[UriPath.Segment]
    ): Option[Tuple] =
      if matchSegments.isEmpty then {
        if pathSegments.isEmpty then Some(EmptyTuple)
        else None
      } else {
        matchSegments.head match {
          case Segment(value) =>
            if !pathSegments.isEmpty && pathSegments(0).decoded() == value then
              loop(matchSegments.tail, pathSegments.tail)
            else None

          case Capture(_, decoder) =>
            if !pathSegments.isEmpty then {
              val raw = pathSegments(0).decoded()
              val attempt = decoder(raw)
              attempt match {
                case Failure(_) => None
                case Success(value) =>
                  loop(matchSegments.tail, pathSegments.tail) match {
                    case None       => None
                    case Some(tail) => Some(value *: tail)
                  }
              }
            } else None
        }
      }

    loop(segments, path.segments).asInstanceOf[Option[A]]
  }

  def /(segment: String): Path[A] =
    Path(segments :+ Segment(segment))

  def /[B](capture: Capture[B]): Path[Tuple.Append[A, B]] =
    Path(segments :+ capture)

  override def toString(): String =
    segments
      .map {
        case Segment(value)   => value
        case Capture(name, _) => name
      }
      .mkString("/", "/", "")
}
object Path {
  val root = Path[EmptyTuple](Vector.empty)
}

final case class Segment(value: String)
final case class Capture[A](name: String, decoder: String => Try[A]) {
  def withName(name: String): Capture[A] =
    this.copy(name = name)
}
object Capture {
  val int: Capture[Int] = Capture("<int>", str => Try(str.toInt))
  val string: Capture[String] = Capture("<string>", str => Success(str))
}
