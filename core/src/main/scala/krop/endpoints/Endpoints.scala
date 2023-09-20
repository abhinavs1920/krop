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

package krop.endpoints

import cats.effect.IO
import cats.implicits._
import endpoints4s.Invalid
import endpoints4s.PartialInvariantFunctor
import endpoints4s.Semigroupal
import endpoints4s.Tupler
import endpoints4s.Valid
import endpoints4s.Validated
import endpoints4s.algebra
import endpoints4s.algebra.Documentation
import krop.Route.Route
import org.http4s
import org.http4s.EntityDecoder
import org.http4s.EntityEncoder
import org.http4s.Headers
import org.http4s.HttpRoutes
import org.typelevel.ci._

import scala.util.control.NonFatal

/** Interpreter for [[algebra.Endpoints]] based on http4s. It uses
  * [[algebra.BuiltInErrors]] to model client and server errors.
  *
  * Consider the following endpoint definition:
  *
  * {{{
  *   trait MyEndpoints extends algebra.Endpoints with algebra.JsonEntitiesFromSchemas {
  *     val inc = endpoint(get(path / "inc" /? qs[Int]("x")), ok(jsonResponse[Int]))
  *   }
  * }}}
  *
  * You can get a Krop route for it as follow:
  *
  * {{{
  *   object MyService
  *     extends endpoints.http4s.server.Endpoints[IO]
  *       with endpoints.http4s.server.JsonEntitiesFromSchemas
  *       with MyEndpoints {
  *
  *     val service: krop.Route = inc.handle(x => x + 1)
  *   }
  * }}}
  */
trait Endpoints
    extends algebra.Endpoints
    with EndpointsWithCustomErrors
    with BuiltInErrors {}

/** Interpreter for [[algebra.EndpointsWithCustomErrors]] based on Krop.
  */
trait EndpointsWithCustomErrors
    extends algebra.EndpointsWithCustomErrors
    with Methods
    with Urls {
  type Effect[A] = IO[A]

  // Convenient type aliases
  private[endpoints] type Http4sRequest = http4s.Request[Effect]
  private[endpoints] type Http4sResponse = http4s.Response[Effect]
  private[endpoints] type KropRoute = krop.Route.Route

  type RequestHeaders[A] = http4s.Headers => Validated[A]

  trait Request[A] {

    /** Information extracted from the URL and the headers */
    type UrlAndHeaders

    /** Checks whether the incoming request matches this request description,
      * parses its URL parameters and headers, and then parses its entity if
      * there was no previous validation errors.
      */
    final def matches(
        http4sRequest: Http4sRequest
    ): Option[Effect[Either[Http4sResponse, A]]] =
      matchAndParseHeaders(http4sRequest).map {
        case Left(response) => IO.pure(response.asLeft)
        case Right(invalid: Invalid) =>
          handleClientErrors(http4sRequest, invalid).map(_.asLeft)
        case Right(Valid(urlAndHeadersData)) =>
          parseEntity(urlAndHeadersData, http4sRequest)
      }

    /** Checks whether the incoming `http4sRequest` matches this request
      * description, and parses its URL parameters and headers.
      *
      * @return
      *   `None` if the incoming request does not match this request method and
      *   URL. Otherwise:
      *   - `Some(Left(response))` to immediately return a custom response (e.g.
      *     401),
      *   - `Some(Right(Valid(urlAndHeadersData)))` in case the URL and headers
      *     were successfully parsed,
      *   - `Some(Right(Invalid(errors)))` in case the URL and headers had
      *     validation errors
      */
    def matchAndParseHeaders(
        http4sRequest: Http4sRequest
    ): Option[Either[Http4sResponse, Validated[UrlAndHeaders]]]

    /** Parse the request entity.
      *
      * Returns either a value of type `A` containing all the information
      * extracted from the request (including URL, headers, and entity), or an
      * http4s response directly.
      */
    def parseEntity(
        urlAndHeaders: UrlAndHeaders,
        http4sRequest: Http4sRequest
    ): Effect[Either[Http4sResponse, A]]

  }

  type RequestEntity[A] =
    http4s.Request[Effect] => Effect[Either[http4s.Response[Effect], A]]

  type Response[A] = A => http4s.Response[Effect]

  override def addResponseHeaders[A, H](
      response: Response[A],
      headers: ResponseHeaders[H]
  )(implicit tupler: Tupler[A, H]): Response[tupler.Out] =
    out => {
      val (a, h) = tupler.unapply(out)
      val http4sResponse = response(a)
      http4sResponse.withHeaders(http4sResponse.headers ++ headers(h))
    }

  type ResponseEntity[A] = http4s.EntityEncoder[Effect, A]

  type ResponseHeaders[A] = A => http4s.Headers

  case class Endpoint[A, B](
      request: Request[A],
      response: Response[B],
      operationId: Option[String]
  ) {

    def handle(handler: A => B): KropRoute =
      handleIO(handler(_).pure[Effect])

    def handleIO(
        handler: A => Effect[B]
    ): KropRoute = {
      val route = { (http4sRequest: http4s.Request[Effect]) =>
        try {
          request
            .matches(http4sRequest)
            .map(_.flatMap {
              case Right(a) =>
                try {
                  handler(a)
                    .map(response)
                    .recoverWith { case NonFatal(t) =>
                      handleServerError(http4sRequest, t)
                    }
                } catch {
                  case NonFatal(t) => handleServerError(http4sRequest, t)
                }
              case Left(errorResponse) => errorResponse.pure[Effect]
            })
        } catch {
          case NonFatal(t) => Some(handleServerError(http4sRequest, t))
        }
      }
      Route(HttpRoutes.of(Function.unlift(route)))
    }
  }

  def routesFromEndpoints(endpoints: KropRoute*): KropRoute =
    endpoints.reduceLeft(_ orElse _)

  /** HEADERS
    */
  def emptyRequestHeaders: RequestHeaders[Unit] = _ => Valid(())

  def requestHeader(name: String, docs: Documentation): RequestHeaders[String] =
    headers =>
      headers.get(CIString(name)).map(_.head.value) match {
        case Some(value) => Valid(value)
        case None        => Invalid(s"Missing header $name")
      }

  def optRequestHeader(
      name: String,
      docs: Documentation
  ): RequestHeaders[Option[String]] =
    headers => Valid(headers.get(CIString(name)).map(_.head.value))

  // RESPONSES
  implicit lazy val responseInvariantFunctor
      : endpoints4s.InvariantFunctor[Response] =
    new endpoints4s.InvariantFunctor[Response] {
      def xmap[A, B](
          fa: Response[A],
          f: A => B,
          g: B => A
      ): Response[B] =
        fa compose g
    }

  implicit def responseEntityInvariantFunctor
      : endpoints4s.InvariantFunctor[ResponseEntity] =
    new endpoints4s.InvariantFunctor[ResponseEntity] {
      def xmap[A, B](
          fa: ResponseEntity[A],
          f: A => B,
          g: B => A
      ): ResponseEntity[B] =
        fa.contramap(g)
    }

  def response[A, B, R](
      statusCode: StatusCode,
      entity: ResponseEntity[A],
      docs: Documentation,
      headers: ResponseHeaders[B]
  )(implicit tupler: Tupler.Aux[A, B, R]): Response[R] =
    r => {
      val (a, b) = tupler.unapply(r)
      http4s.Response[Effect](
        status = statusCode,
        headers = headers(b) ++ entity.headers,
        entity = entity.toEntity(a)
      )
    }

  def choiceResponse[A, B](
      responseA: Response[A],
      responseB: Response[B]
  ): Response[Either[A, B]] = {
    case Left(a)  => responseA(a)
    case Right(b) => responseB(b)
  }

  def emptyResponse: ResponseEntity[Unit] =
    EntityEncoder.emptyEncoder[Unit]

  def textResponse: ResponseEntity[String] =
    EntityEncoder.stringEncoder

  implicit def responseHeadersSemigroupal: Semigroupal[ResponseHeaders] =
    new Semigroupal[ResponseHeaders] {
      def product[A, B](fa: ResponseHeaders[A], fb: ResponseHeaders[B])(implicit
          tupler: Tupler[A, B]
      ): ResponseHeaders[tupler.Out] =
        out => {
          val (a, b) = tupler.unapply(out)
          fa(a) ++ fb(b)
        }
    }

  implicit def responseHeadersInvariantFunctor
      : endpoints4s.InvariantFunctor[ResponseHeaders] =
    new endpoints4s.InvariantFunctor[ResponseHeaders] {
      def xmap[A, B](
          fa: ResponseHeaders[A],
          f: A => B,
          g: B => A
      ): ResponseHeaders[B] =
        fa compose g
    }

  def emptyResponseHeaders: ResponseHeaders[Unit] =
    _ => Headers.empty

  def responseHeader(
      name: String,
      docs: Documentation = None
  ): ResponseHeaders[String] =
    value => Headers((name, value))

  def optResponseHeader(
      name: String,
      docs: Documentation = None
  ): ResponseHeaders[Option[String]] = {
    case Some(value) => responseHeader(name, docs)(value)
    case None        => emptyResponseHeaders(())
  }

  def endpoint[A, B](
      request: Request[A],
      response: Response[B],
      docs: EndpointDocs = EndpointDocs()
  ): Endpoint[A, B] =
    Endpoint(request, response, docs.operationId)

  override def mapEndpointRequest[A, B, C](
      currentEndpoint: Endpoint[A, B],
      func: Request[A] => Request[C]
  ): Endpoint[C, B] =
    endpoint(func(currentEndpoint.request), currentEndpoint.response)

  override def mapEndpointResponse[A, B, C](
      currentEndpoint: Endpoint[A, B],
      func: Response[B] => Response[C]
  ): Endpoint[A, C] =
    endpoint(currentEndpoint.request, func(currentEndpoint.response))

  override def mapEndpointDocs[A, B](
      currentEndpoint: Endpoint[A, B],
      func: EndpointDocs => EndpointDocs
  ): Endpoint[A, B] = currentEndpoint

  // REQUESTS
  implicit def requestPartialInvariantFunctor
      : PartialInvariantFunctor[Request] =
    new PartialInvariantFunctor[Request] {
      def xmapPartial[A, B](
          fa: Request[A],
          f: A => Validated[B],
          g: B => A
      ): Request[B] =
        new Request[B] {

          type UrlAndHeaders = fa.UrlAndHeaders

          def matchAndParseHeaders(
              http4sRequest: Http4sRequest
          ): Option[Either[Http4sResponse, Validated[UrlAndHeaders]]] =
            fa.matchAndParseHeaders(http4sRequest)

          def parseEntity(
              urlAndHeaders: UrlAndHeaders,
              http4sRequest: Http4sRequest
          ): Effect[Either[Http4sResponse, B]] =
            fa.parseEntity(urlAndHeaders, http4sRequest)
              .flatMap {
                case Left(response) => IO.pure(response.asLeft)
                case Right(a) =>
                  f(a) match {
                    case Valid(b) => IO.pure(b.asRight)
                    case invalid: Invalid =>
                      handleClientErrors(http4sRequest, invalid).map(_.asLeft)
                  }
              }
        }
    }

  def emptyRequest: RequestEntity[Unit] = _ => IO.pure(Right(()))

  /* Setting `strict = true` means that this won't accept requests that are
   * missing their Content-Type header. However, if we use `strict = false`,
   * requests with incorrect specified `Content-Type` still get accepted.
   */
  def textRequest: RequestEntity[String] =
    req =>
      EntityDecoder
        .decodeBy(http4s.MediaType.text.plain) { (msg: http4s.Media[Effect]) =>
          http4s.DecodeResult.success(EntityDecoder.decodeText(msg))
        }
        .decode(req, strict = true)
        .leftWiden[Throwable]
        .rethrowT
        .map(Right(_))

  def choiceRequestEntity[A, B](
      requestEntityA: RequestEntity[A],
      requestEntityB: RequestEntity[B]
  ): RequestEntity[Either[A, B]] =
    req => {
      def decodedA = requestEntityA(req).map(_.map(Left(_): Either[A, B]))
      def decodedB = requestEntityB(req).map(_.map(Right(_): Either[A, B]))
      decodedA.orElse(decodedB)
    }

  def request[UrlP, BodyP, HeadersP, UrlAndBodyPTupled, Out](
      method: Method,
      url: Url[UrlP],
      entity: RequestEntity[BodyP] = emptyRequest,
      docs: Documentation = None,
      headers: RequestHeaders[HeadersP] = emptyRequestHeaders
  )(implicit
      tuplerUB: Tupler.Aux[UrlP, BodyP, UrlAndBodyPTupled],
      tuplerUBH: Tupler.Aux[UrlAndBodyPTupled, HeadersP, Out]
  ): Request[Out] = {
    new Request[Out] {
      type UrlAndHeaders = (UrlP, HeadersP)

      def matchAndParseHeaders(
          http4sRequest: Http4sRequest
      ): Option[Either[Http4sResponse, Validated[UrlAndHeaders]]] =
        matchAndParseHeadersAsRight(method, url, headers, http4sRequest)

      def parseEntity(
          urlAndHeaders: UrlAndHeaders,
          http4sRequest: Http4sRequest
      ): Effect[Either[Http4sResponse, Out]] =
        entity(http4sRequest)
          .map { errorResponseOrEntityData =>
            errorResponseOrEntityData.map(entityData =>
              tuplerUBH(
                tuplerUB(urlAndHeaders._1, entityData),
                urlAndHeaders._2
              )
            )
          }

    }
  }

  override def addRequestHeaders[A, H](
      request: Request[A],
      headersP: RequestHeaders[H]
  )(implicit tupler: Tupler[A, H]): Request[tupler.Out] =
    new Request[tupler.Out] {
      type UrlAndHeaders = (request.UrlAndHeaders, H)

      def matchAndParseHeaders(
          http4sRequest: Http4sRequest
      ): Option[Either[Http4sResponse, Validated[UrlAndHeaders]]] =
        request
          .matchAndParseHeaders(http4sRequest)
          .map { errorResponseOrValidatedUrlAndHeaders =>
            errorResponseOrValidatedUrlAndHeaders.map {
              validatedUrlAndHeaders =>
                validatedUrlAndHeaders.zip(headersP(http4sRequest.headers))
            }
          }

      def parseEntity(
          urlAndHeaders: UrlAndHeaders,
          http4sRequest: Http4sRequest
      ): Effect[Either[Http4sResponse, tupler.Out]] =
        request
          .parseEntity(urlAndHeaders._1, http4sRequest)
          .map { errorResponseOrA =>
            errorResponseOrA.map(a => tupler(a, urlAndHeaders._2))
          }

    }

  override def addRequestQueryString[A, Q](
      request: Request[A],
      qs: QueryString[Q]
  )(implicit tupler: Tupler[A, Q]): Request[tupler.Out] = {
    new Request[tupler.Out] {
      type UrlAndHeaders = (request.UrlAndHeaders, Q)
      def matchAndParseHeaders(
          http4sRequest: Http4sRequest
      ): Option[Either[Http4sResponse, Validated[UrlAndHeaders]]] =
        request
          .matchAndParseHeaders(http4sRequest)
          .map { errorResponseOrValidatedUrlAndHeaders =>
            errorResponseOrValidatedUrlAndHeaders.map {
              validatedUrlAndHeaders =>
                val addedQuery = qs(http4sRequest.uri.multiParams)
                validatedUrlAndHeaders.zip(addedQuery)
            }
          }
      def parseEntity(
          urlAndHeaders: UrlAndHeaders,
          http4sRequest: Http4sRequest
      ): Effect[Either[Http4sResponse, tupler.Out]] =
        request
          .parseEntity(urlAndHeaders._1, http4sRequest)
          .map { errorResponseOrA =>
            errorResponseOrA.map(a => tupler(a, urlAndHeaders._2))
          }

    }
  }

  /** Default implementation for `matchAndParseHeaders` which never returns a
    * `Left(response)`. It checks that the incoming request matches the given
    * `method` and `url`. If this is the case, it parses the request headers.
    * @return
    *   \- `None` if the incoming `http4sRequest` did not match the `method` and
    *   `url`
    *   - `Some(Right(validatedUrlAndHeaders))` if the incoming `http4sRequest`
    *     did match the `method` and `url`. The value `validatedUrlAndHeaders`
    *     contains the validation errors of both the URL and the headers.
    */
  protected final def matchAndParseHeadersAsRight[U, H](
      method: Method,
      url: Url[U],
      headers: RequestHeaders[H],
      http4sRequest: Http4sRequest
  ): Option[Either[Http4sResponse, Validated[(U, H)]]] =
    if (http4sRequest.method == method) {
      url
        .decodeUrl(http4sRequest.uri)
        .map { validatedUrl =>
          validatedUrl.zip(headers(http4sRequest.headers)).asRight
        }
    } else None

  implicit def requestEntityPartialInvariantFunctor
      : PartialInvariantFunctor[RequestEntity] =
    new PartialInvariantFunctor[RequestEntity] {
      def xmapPartial[From, To](
          f: RequestEntity[From],
          map: From => Validated[To],
          contramap: To => From
      ): RequestEntity[To] =
        body =>
          f(body).flatMap {
            case Left(response) => IO.pure(response.asLeft[To])
            case Right(from) =>
              map(from) match {
                case Valid(response) => IO.pure(response.asRight)
                case invalid: Invalid =>
                  handleClientErrors(body, invalid).map(_.asLeft)
              }
          }

    }

  implicit def requestHeadersPartialInvariantFunctor
      : PartialInvariantFunctor[RequestHeaders] =
    new PartialInvariantFunctor[RequestHeaders] {
      def xmapPartial[From, To](
          f: RequestHeaders[From],
          map: From => Validated[To],
          contramap: To => From
      ): RequestHeaders[To] =
        headers => f(headers).flatMap(map)
    }

  implicit def requestHeadersSemigroupal
      : endpoints4s.Semigroupal[RequestHeaders] =
    new Semigroupal[RequestHeaders] {
      def product[A, B](fa: RequestHeaders[A], fb: RequestHeaders[B])(implicit
          tupler: Tupler[A, B]
      ): RequestHeaders[tupler.Out] =
        headers => fa(headers).zip(fb(headers))
    }

  /** This method is called by ''endpoints'' when decoding a request failed.
    *
    * The provided implementation calls `clientErrorsResponse` to construct a
    * response containing the errors.
    *
    * This method can be overridden to customize the error reporting logic.
    */
  def handleClientErrors(
      request: http4s.Request[Effect],
      invalid: Invalid
  ): Effect[http4s.Response[Effect]] =
    IO.pure(clientErrorsResponse(invalidToClientErrors(invalid)))

  /** This method is called by ''endpoints'' when an exception is thrown during
    * request processing.
    *
    * The provided implementation calls [[serverErrorResponse]] to construct a
    * response containing the error message.
    *
    * This method can be overridden to customize the error reporting logic.
    */
  def handleServerError(
      request: http4s.Request[Effect],
      throwable: Throwable
  ): Effect[http4s.Response[Effect]] =
    IO.pure(serverErrorResponse(throwableToServerError(throwable)))

}
