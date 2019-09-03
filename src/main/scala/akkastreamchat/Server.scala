package akkastreamchat

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.util.ByteString
import akkastreamchat.Protocol.ClientCommand
import akkastreamchat.Protocol.ServerCommand
import akkastreamchat.Protocol.ServerCommand.Alert
import akkastreamchat.Protocol.ServerCommand.Disconnect
import akkastreamchat.Protocol.ServerCommand.Welcome

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

object Server {

  private val clientQuit = Success(ClientCommand.SendMessage("/quit"))

  private sealed trait Response
  private final case class Broadcast(command: ServerCommand) extends Response
  private final case class DirectResponse(command: ServerCommand)
      extends Response

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("server")
    implicit val materializer = ActorMaterializer()
    import system.dispatcher

    try {
      val host = args(0)
      val port = args(1).toInt
      val binding = Await.result(run(host, port), 5.seconds)
      system.log.info("Server bound to {}:{}", binding.localAddress.getHostString, binding.localAddress.getPort)
      binding.whenUnbound.onComplete { _ =>
        system.log.info("Server unbound")
        system.terminate()
      }
    } catch {
      case th: Throwable =>
        th.printStackTrace()
        println("Usage: Server [host] [port]")
    }
  }

  def run(
      host: String,
      port: Int
  )(implicit system: ActorSystem, materializer: Materializer): Future[Tcp.ServerBinding] = {
    import system.dispatcher

    val (broadcastActorRef, broadcastSource) = Source
      .actorRef[ServerCommand](100, OverflowStrategy.dropNew)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

    // shared user registry
    val users = new ConcurrentHashMap[Username, UUID]()

    val serverBinding =
      Tcp(system)
        .bind(host, port)
        .toMat(Sink.foreach { incomingConnection =>
          val connectionId = UUID.randomUUID()
          val remote = incomingConnection.remoteAddress
          system.log.info(
            "Accepted client {} from {}:{}",
            connectionId,
            remote.getHostString,
            remote.getPort
          )

          val connectionFlow: Flow[ByteString, ByteString, NotUsed] =
            Protocol.ClientCommand.decoder
              .takeWhile(_ != clientQuit)
              .statefulMapConcat {
                () =>
                  var session: Session = new InitialState(connectionId, users)

                  {
                    clientCommand =>
                      val response = clientCommand match {
                        case Success(command) =>
                          val (newSession, response) =
                            session.handleRequest(command)
                          session = newSession
                          response
                        case Failure(parseError) =>
                          DirectResponse(
                            Alert(s"Invalid command: $parseError.getMessage")
                          )
                      }
                      response match {
                        case DirectResponse(command) =>
                          command :: Nil
                        case Broadcast(command) =>
                          broadcastActorRef ! command
                          Nil
                      }
                  }
              }
              .merge(broadcastSource)
              .watchTermination() { (_, terminationFuture) =>
                terminationFuture.onComplete { done =>
                  users
                    .entrySet()
                    .asScala
                    .find(_.getValue == connectionId)
                    .foreach { entry =>
                      broadcastActorRef ! ServerCommand
                        .Alert(s"${entry.getValue} disconnected")
                    }
                  system.log.info("Unregistered client {} because {}", connectionId, done)
                }
                NotUsed
              }
              .via(Protocol.ServerCommand.encoder)


          incomingConnection.handleWith(connectionFlow)
        })(Keep.left)
          .run()


    serverBinding
  }

  // small state machine for handling client commands
  private sealed trait Session {
    def handleRequest(command: ClientCommand): (Session, Response)
  }

  // initial session only allows identifying with a username
  private final class InitialState(
      connectionId: UUID,
      users: ConcurrentHashMap[Username, UUID]
  ) extends Session {
    def handleRequest(command: ClientCommand): (Session, Response) =
      command match {
        case ClientCommand.RequestUsername(newUsername) =>
          if (users.putIfAbsent(newUsername, connectionId) == null) {
            (
              new Running(connectionId, newUsername, users),
              DirectResponse(Welcome(newUsername, "Welcome to Akka Streams Chat!")) // FIXME also broadcast user joined
            )
          } else {
            (this, DirectResponse(Disconnect(s"${newUsername.name} already taken")))
          }
        case _ =>
          (this, DirectResponse(Alert("Specify username first")))
      }
  }

  private final class Running(
      connectionId: UUID,
      username: Username,
      users: ConcurrentHashMap[Username, UUID]
  ) extends Session {
    override def handleRequest(command: ClientCommand): (Session, Response) = {
      val response = command match {
        case ClientCommand.SendMessage("/users") =>
          DirectResponse(Alert(users.keys().asScala.map(_.name).mkString(", ")))
        case ClientCommand.SendMessage(msg) if msg.startsWith("/") =>
          DirectResponse(Alert("Unknown command"))
        case ClientCommand.SendMessage(msg) =>
          DirectResponse(Protocol.ServerCommand.Message(username, msg))
      }
      (this, response)
    }
  }
}
