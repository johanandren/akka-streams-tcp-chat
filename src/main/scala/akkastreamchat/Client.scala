package akkastreamchat

import akka.actor.ActorSystem
import akka.routing.SeveralRoutees
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akkastreamchat.Protocol.ClientCommand
import akkastreamchat.Protocol.ClientCommand.SendMessage
import akkastreamchat.Protocol.ServerCommand

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.io.StdIn
import scala.util.Failure
import scala.util.Success

object Client {

  def main(args: Array[String]): Unit ={
    implicit val system = ActorSystem("client")
    implicit val materializer = ActorMaterializer()
    try {
      val host = args(0)
      val port = args(1).toInt
      val username = args(2)
      val connection = Await.result(run(host, port, Username(username)), 15.seconds)
      // FIXME missing a Future done for connection end
    } catch {
      case th: Throwable =>
        println(th.getMessage)
        th.printStackTrace()
        println("Usage: Server [host] [port] [username]")
        System.exit(1)
    }
  }

  def run(host: String, port: Int, username: Username)(implicit system: ActorSystem, materialiser: Materializer): Future[Tcp.OutgoingConnection] = {
    import system.dispatcher

    // FIXME not showing prompt before welcome is tricky
    val in =
      Source.single(ClientCommand.RequestUsername(username))
        .concat(
          // unfoldResource deals with blocking non-streamed things
          Source.unfoldResource[SendMessage, Iterator[SendMessage]](
          () =>
            Iterator.continually {
              val line = StdIn.readLine("> ")
              ClientCommand.SendMessage(line)
            },
          iterator => Some(iterator.next()),
          iterator => ()
        ))
      .via(ClientCommand.encoder)

    val out = ServerCommand.decoder
        .takeWhile(_ != Success(ServerCommand.Disconnect), inclusive = true)
        .map {
          case Success(command) =>
            command match {
              case ServerCommand.Welcome(user, banner) =>
                s"Logged in as ${user.name} \n$banner"
              case ServerCommand.Alert(msg) => msg
              case ServerCommand.Message(user, msg) =>
                s"${user.name}: $msg"
              case ServerCommand.Disconnect(why) =>
                s"Server disconnected because: $why"
            }
          case Failure(ex) =>
            s"Error parsing server command: ${ex.getMessage}"
        }.to(Sink.foreach(println))

    val connected = in.viaMat(Tcp(system)
      .outgoingConnection(host, port))(Keep.right)
      .toMat(out)(Keep.left).run()
    connected.onComplete(t => println(s"Connected: $t"))

    connected
  }

}
