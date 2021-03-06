package jbok.network.http.server.middleware

import cats.effect.Concurrent
import org.http4s.HttpApp
import org.http4s.server.middleware.Logger

object LoggerMiddleware {
  def apply[F[_]](logHeaders: Boolean = true, logBody: Boolean = false)(httpApp: HttpApp[F])(implicit F: Concurrent[F]): HttpApp[F] =
    Logger.httpApp(logHeaders = logHeaders, logBody = logBody)(httpApp)
}
