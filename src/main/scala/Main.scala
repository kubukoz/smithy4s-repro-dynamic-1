import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import cats.implicits._
import example.ExampleService
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.client.middleware.Logger
import org.http4s.implicits._
import org.typelevel.ci.CIString
import smithy4s.Document
import smithy4s.Endpoint
import smithy4s.SchemaIndex
import smithy4s.Service
import smithy4s.ShapeId
import smithy4s.api.SimpleRestJson
import smithy4s.codegen.cli.DumpModel
import smithy4s.codegen.cli.Smithy4sCommand
import smithy4s.dynamic.DynamicSchemaIndex
import smithy4s.dynamic.model.Model
import smithy4s.http.json.codecs
import smithy4s.http4s.SimpleRestJsonBuilder

object Main extends IOApp.Simple {

  val supportedSchemas = {
    SimpleRestJson.protocol.schemas ++
      SchemaIndex(
        // Normally I attach these because SimpleRestJson.protocol doesn't include them. Interestingly it also doesn't include itself indeed.
        SimpleRestJson,
        smithy.api.Error,
        smithy.api.Documentation,
        smithy.api.ExternalDocumentation,
        smithy.api.Deprecated
      )
  }

  val loadService = IO {
    DumpModel.run(
      Smithy4sCommand.DumpModelArgs(
        specs = List(os.pwd / "src" / "main" / "smithy" / "example.smithy"),
        repositories = Nil,
        dependencies = Nil,
        transformers = Nil
      )
    )
  }.flatMap { model =>
    val capi = codecs()
    capi
      .decodeFromByteArray(
        capi.compileCodec(Model.schema),
        model.getBytes()
      )
      .liftTo[IO]
  }.map(DynamicSchemaIndex.load(_, supportedSchemas))
    .flatMap(
      _.allServices
        .find(
          _.service.id == ShapeId(
            "example",
            "ExampleService"
          )
        )
        .liftTo[IO](new Throwable("service not found in DSI"))
    )

  def go[Alg[_[_, _, _, _, _]], Op[_, _, _, _, _], I, E, O, SE, SO](
      service: Service[Alg, Op],
      client: smithy4s.Monadic[Alg, IO],
      endpoint: Endpoint[Op, I, E, O, SE, SO]
  ): IO[Document] = {

    val in = Document.Decoder
      .fromSchema(endpoint.input)
      .decode(
        Document.obj(
          "greeting" -> Document.fromString("test")
        )
      )
      .toTry
      .get

    service.asTransformation(client).apply(endpoint.wrap(in)).map { o =>
      Document.Encoder.fromSchema(endpoint.output).encode(o)
    }
  }

  val mkClient = {
    import org.http4s.dsl.io._

    val c = HttpRoutes.of[IO] { case req @ (GET -> Root / "greeting") =>
      println(req)
      require(
        req.headers.headers.exists(_.name == CIString("X-GREETING")),
        "Missing required header"
      )

      Ok()
    }

    Resource
      .eval { Client.fromHttpApp(c.orNotFound).pure[IO] }
      .map(
        // fun fact: this has no effect on request logging in Client.fromHttpApp.
        Logger[IO](
          logHeaders = true,
          logBody = true,
          logAction = Some(IO.println(_: String))
        )
      )
  }

  val runDynamic = loadService.flatMap { dynamicService =>
    mkClient
      .flatMap { client =>
        SimpleRestJsonBuilder(dynamicService.service).clientResource[IO](
          client,
          uri"http://localhost:9090"
        )
      }
      .use { c =>
        val endpoint =
          dynamicService.service.endpoints
            .find(_.name == "ExampleOp")
            .getOrElse(sys.error("no endpoint found"))

        IO.println("\n\nAbout to call dynamic service\n\n") *>
          go(
            dynamicService.service,
            c,
            endpoint
          )

      }
      .attempt
      .flatMap(IO.println(_))
  }

  val runStatic = mkClient
    .flatMap { client =>
      SimpleRestJsonBuilder(ExampleService).clientResource[IO](
        client,
        uri"http://localhost:9090"
      )
    }
    .use { c =>
      IO.println("\n\nAbout to call static service\n\n") *>
        c.exampleOp("test")
    }
    .attempt
    .flatMap(IO.println(_))

  def run: IO[Unit] =
    runDynamic *>
      runStatic
}
