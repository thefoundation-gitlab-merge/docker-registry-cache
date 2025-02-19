package de.lhns.docker.cache

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.parallel._
import com.comcast.ip4s._
import org.http4s.HttpApp
import org.http4s.dsl.io.{Path => _}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.server.Server
import org.http4s.server.middleware.ErrorAction
import org.log4s.getLogger

import scala.concurrent.duration._

object Main extends IOApp {
  private val logger = getLogger

  override def run(args: List[String]): IO[ExitCode] =
    applicationResource(RegistryConfig.fromEnv).use(_ => IO.never)

  def applicationResource(registries: Seq[RegistryConfig]): Resource[IO, Unit] =
    for {
      client <- JdkHttpClient.simple[IO]
      _ <- Resource.eval(IO(logger.info("Registries:\n" + registries.map(_ + "\n").mkString)))
      _ <- Resource.eval(IO(logger.info("starting proxies")))
      registryProxies <- registries
        .zipWithIndex
        .map { case (registryConfig, index) =>
          registryConfig.registry.startProxy(
            port = 5001 + index,
            variables = registryConfig.variablesOrDefault
          )
            .map { proxyUri =>
              (registryConfig.registry, proxyUri)
            }
            .evalTap {
              case (registry, proxyUri) =>
                lazy val retryLoop: IO[String] =
                  client.expect[String](proxyUri.withPath(path"/v2/"))
                    .handleErrorWith { throwable =>
                      IO.sleep(1.second) *>
                        retryLoop
                    }

                retryLoop.timeoutTo(20.seconds, IO.raiseError(new RuntimeException(s"error starting proxy for ${registry.host}")))
            }
        }
        .parSequence
      _ <- Resource.eval(IO(logger.info("proxies started")))
      _ <- serverResource(
        host"0.0.0.0",
        port"5000",
        new RegistryProxyRoutes(client, registryProxies).toRoutes.orNotFound
      )
    } yield ()

  def serverResource(host: Host, port: Port, http: HttpApp[IO]): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(ErrorAction.log(
        http = http,
        messageFailureLogAction = (t, msg) => IO(logger.debug(t)(msg)),
        serviceErrorLogAction = (t, msg) => IO(logger.error(t)(msg))
      ))
      .build
}
